package com.letsblog.api.client;

import com.letsblog.api.ai.AiServiceException;
import com.letsblog.common.client.ServiceAuthHeaders;
import com.letsblog.common.client.SyncCallProfile;
import com.letsblog.common.client.SyncServiceClient;
import com.letsblog.common.client.SyncServiceException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * ai-serviceの{@code POST /api/generation-jobs}・{@code PATCH /api/generation-jobs/{id}}を呼び出す
 * クライアント(issue #574)。issue #581(C12)でlbs-commonの{@link SyncServiceClient}へ移行した。
 * media-service側の同名クラス({@link com.letsblog.media.client.GenerationJobClient}相当、
 * 同じ移行)と同じ方針を使う。方針の詳細はdocs/SYNC_SERVICE_CALLS.md参照。
 *
 * <p>generation_jobsテーブルの所有権はissue #574でai-serviceへ移管されたため、legacy-apiに残る
 * ジョブ作成元(AiAssistService#generateImage/#generateImagePrompt(画像生成、このIssueの移設対象外)、
 * ComfyUiModelService#startInstall/#startDelete(ComfyUIチェックポイント管理、#573時点でも移設されて
 * いない))は、自身ではgeneration_jobsへ直接書き込めなくなった(ADR-0004、クロススキーマアクセス禁止)。
 * media-service(#573)のGenerationJobClientと同じ方式(呼び出し元ユーザーのBearerトークンを
 * そのまま転送する)で、ai-serviceへジョブの作成・進捗更新を委譲する。
 *
 * <p>media-service側のComfyUiModelインストール/削除の非同期ジョブランナー(ModelInstallJobRunner)は、
 * このクライアントを経由せず、自身のGenerationJobClientで直接ai-serviceへ進捗を反映する。legacy-api側の
 * このクライアントは、あくまで「ジョブの作成」(startInstall/startDelete呼び出し元の同期リクエスト内)と
 * AiAssistServiceの画像生成ジョブ(作成〜完了/失敗までlegacy-api自身が同期的に行う)のためにのみ使う。
 *
 * <p>フォールバック方針: ジョブ作成({@link #create})は明確なエラー、進捗更新({@link #updateStatus}）は
 * ベストエフォート(機能縮退)。media-service版と同じ理由。
 */
@Component
public class GenerationJobClient {

    private static final Logger log = LoggerFactory.getLogger(GenerationJobClient.class);

    private final SyncServiceClient client;
    private final HttpServletRequest request;

    public GenerationJobClient(
            RestClient.Builder builder, @Value("${app.ai-service-uri}") String aiServiceUri,
            HttpServletRequest request) {
        this.client = SyncServiceClient.builder(builder, "ai-service", aiServiceUri)
                .profile(SyncCallProfile.SHORT)
                .build();
        this.request = request;
    }

    public GenerationJobSummary create(String type, String requestPayload) {
        try {
            GenerationJobSummary created = client.post(
                    "/api/generation-jobs", new Object[0],
                    Map.of("type", type, "requestPayload", requestPayload == null ? "" : requestPayload),
                    GenerationJobSummary.class, ServiceAuthHeaders.forwardedBearer(request));
            if (created == null) {
                throw new AiServiceException("ai-serviceから空の応答を受け取りました", null);
            }
            return created;
        } catch (SyncServiceException e) {
            throw new AiServiceException("ai-serviceの/api/generation-jobs作成呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    public void updateStatus(Long jobId, String status, String resultPayload) {
        try {
            client.patch(
                    "/api/generation-jobs/{id}", new Object[] {jobId},
                    Map.of("status", status, "resultPayload", resultPayload == null ? "" : resultPayload),
                    ServiceAuthHeaders.forwardedBearer(request));
        } catch (SyncServiceException e) {
            // ジョブの進捗更新はベストエフォート: ai-serviceが一時的に到達不能でも、画像生成/チェックポイント
            // インストール自体を失敗扱いにはしない(フロントは古い進捗のまま表示され続けるのみ。
            // media-serviceのGenerationJobClientと同じ方針)。
            log.warn("ai-serviceの/api/generation-jobs/{}呼び出しに失敗しました: {}", jobId, e.getMessage());
        }
    }
}
