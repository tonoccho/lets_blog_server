package com.letsblog.media.client;

import com.letsblog.common.client.ServiceAuthHeaders;
import com.letsblog.common.client.SyncCallProfile;
import com.letsblog.common.client.SyncServiceClient;
import com.letsblog.common.client.SyncServiceException;
import com.letsblog.media.service.GenerationJobBridgeException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * ai-serviceの{@code POST /api/generation-jobs}・{@code PATCH /api/generation-jobs/{id}}を
 * 呼び出し、ジョブの作成・状態/結果を更新するクライアント(#573 stage2)。issue #581(C12)で
 * lbs-commonの{@link SyncServiceClient}(タイムアウト・リトライ・サーキットブレーカーの共通実装)へ
 * 移行した。方針の詳細はdocs/SYNC_SERVICE_CALLS.md参照。
 *
 * <p>GenerationJob自体はmedia-serviceへ移設していない(generation_jobsテーブルはComfyUiModelService/
 * MediaGarbageCollectionServiceに加え、AiAssistService/ArticlePlanService(LLM機能)も書き込む
 * 共有インフラのため)。所有権は#573時点ではlegacy-apiにあったが、issue #574でai-serviceへ移管された
 * ため、この呼び出し先も{@code app.legacy-api-uri}から{@code app.ai-service-uri}へ変更した。
 * ジョブの作成はlegacy-api側(呼び出し元の同期リクエスト内、例: ComfyUiModelService#startInstall)が
 * 引き続き行い(legacy-api側は自身のGenerationJobClientでai-serviceへ委譲する)、media-service側の
 * 非同期ジョブランナー(ModelInstallJobRunner)は進捗・完了・失敗の反映のみをこのクライアント経由で行う。
 *
 * <p>認証は、呼び出し元(legacy-api)が起動トリガーの呼び出し時に転送したBearerトークンを、
 * legacy-apiのMediaRenderClientと同じ方式でそのまま使う。ただしこのクライアントは
 * {@code @Async}なバックグラウンドスレッドから呼ばれ、その時点で元のHTTPリクエストは
 * 既に完了している可能性があるため、リクエストスコープ経由(HttpServletRequest注入)ではなく、
 * トリガー時点で呼び出し元が明示的に渡したトークン文字列をメソッド引数として受け取る
 * (MediaGarbageCollectionJobRunnerのactorId/actorKeycloakSubの引き回しと同じ考え方)。
 *
 * <p>フォールバック方針: ジョブ作成({@link #create})はジョブID無しでは非同期処理を開始できないため
 * ベストエフォートで扱わず、失敗を{@link GenerationJobBridgeException}として呼び出し元へ伝播させる
 * (明確なエラー)。進捗更新({@link #updateStatus})はai-serviceが一時的に到達不能でも、
 * チェックポイントのダウンロード/削除自体を失敗扱いにはせず、ログ警告のみで握りつぶす
 * (機能縮退。フロントは古い進捗のまま表示され続けるのみで実害が小さいため)。
 */
@Component
public class GenerationJobClient {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobClient.class);

    private final SyncServiceClient client;

    public GenerationJobClient(RestClient.Builder builder, @Value("${app.ai-service-uri}") String aiServiceUri) {
        this.client = SyncServiceClient.builder(builder, "ai-service", aiServiceUri)
                .profile(SyncCallProfile.SHORT)
                .build();
    }

    /**
     * ジョブを作成する(#573 stage3、legacy-apiに残らなくなったコントローラからの起動用)。
     *
     * @param bearerToken 呼び出し元の{@code Authorization}ヘッダーの値(例: {@code "Bearer xxx"}）。
     */
    public GenerationJobSummary create(String type, String requestPayload, String bearerToken) {
        try {
            GenerationJobSummary created = client.post(
                    "/api/internal/ai/generation-jobs", new Object[0],
                    Map.of("type", type, "requestPayload", requestPayload == null ? "" : requestPayload),
                    GenerationJobSummary.class, ServiceAuthHeaders.forwardedBearer(bearerToken));
            if (created == null) {
                throw new GenerationJobBridgeException("ai-serviceから空の応答を受け取りました", null);
            }
            return created;
        } catch (SyncServiceException e) {
            throw new GenerationJobBridgeException("ai-serviceの/api/generation-jobs作成呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * @param bearerToken 呼び出し元の{@code Authorization}ヘッダーの値(例: {@code "Bearer xxx"}）。
     *                    nullの場合はヘッダーを付与せずに呼び出す。
     */
    public void updateStatus(Long jobId, String status, String resultPayload, String bearerToken) {
        try {
            client.patch(
                    "/api/internal/ai/generation-jobs/{id}", new Object[] {jobId},
                    Map.of("status", status, "resultPayload", resultPayload == null ? "" : resultPayload),
                    ServiceAuthHeaders.forwardedBearer(bearerToken));
        } catch (SyncServiceException e) {
            // ジョブの進捗更新はベストエフォート: ai-serviceが一時的に到達不能でも、チェックポイントの
            // ダウンロード/削除自体を失敗扱いにはしない(フロントは古い進捗のまま表示され続けるのみ)。
            log.warn("ai-serviceの/api/generation-jobs/{}呼び出しに失敗しました: {}", jobId, e.getMessage());
        }
    }
}
