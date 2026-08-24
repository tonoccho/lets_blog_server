package com.letsblog.media.client;

import com.letsblog.media.service.GenerationJobBridgeException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * legacy-apiの{@code PATCH /api/generation-jobs/{id}}を呼び出し、ジョブの状態・結果を更新する
 * クライアント(#573 stage2)。
 *
 * <p>GenerationJob自体はmedia-serviceへ移設していない(generation_jobsテーブルはComfyUiModelService/
 * MediaGarbageCollectionServiceに加え、AiAssistService/ArticlePlanService(LLM機能、legacy-apiに
 * 残る)も書き込む共有インフラのため)。ジョブの作成はlegacy-api側(呼び出し元の同期リクエスト内、
 * 例: ComfyUiModelService#startInstall)が引き続き行い、media-service側の非同期ジョブランナー
 * (ModelInstallJobRunner)は進捗・完了・失敗の反映のみをこのクライアント経由で行う。
 *
 * <p>認証は、呼び出し元(legacy-api)が起動トリガーの呼び出し時に転送したBearerトークンを、
 * legacy-apiのMediaRenderClientと同じ暫定策でそのまま使う。ただしこのクライアントは
 * {@code @Async}なバックグラウンドスレッドから呼ばれ、その時点で元のHTTPリクエストは
 * 既に完了している可能性があるため、リクエストスコープ経由(HttpServletRequest注入)ではなく、
 * トリガー時点で呼び出し元が明示的に渡したトークン文字列をメソッド引数として受け取る
 * (MediaGarbageCollectionJobRunnerのactorId/actorKeycloakSubの引き回しと同じ考え方)。
 */
@Component
public class GenerationJobClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;

    public GenerationJobClient(RestClient.Builder builder, @Value("${app.legacy-api-uri}") String legacyApiUri) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(legacyApiUri).requestFactory(requestFactory).build();
    }

    /**
     * ジョブを作成する(#573 stage3、legacy-apiに残らなくなったコントローラからの起動用)。
     * ジョブID無しでは非同期処理を開始できないため、進捗更新と異なり失敗はベストエフォートでは
     * 扱わず{@link GenerationJobBridgeException}として伝播させる。
     *
     * @param bearerToken 呼び出し元の{@code Authorization}ヘッダーの値(例: {@code "Bearer xxx"}）。
     */
    public GenerationJobSummary create(String type, String requestPayload, String bearerToken) {
        try {
            GenerationJobSummary created = restClient.post()
                    .uri("/api/generation-jobs")
                    .headers(headers -> {
                        if (bearerToken != null && !bearerToken.isBlank()) {
                            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
                        }
                    })
                    .body(Map.of("type", type, "requestPayload", requestPayload == null ? "" : requestPayload))
                    .retrieve()
                    .body(GenerationJobSummary.class);
            if (created == null) {
                throw new GenerationJobBridgeException("legacy-apiから空の応答を受け取りました", null);
            }
            return created;
        } catch (RestClientException e) {
            throw new GenerationJobBridgeException("legacy-apiの/api/generation-jobs作成呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * @param bearerToken 呼び出し元の{@code Authorization}ヘッダーの値(例: {@code "Bearer xxx"}）。
     *                    nullの場合はヘッダーを付与せずに呼び出す。
     */
    public void updateStatus(Long jobId, String status, String resultPayload, String bearerToken) {
        try {
            restClient.patch()
                    .uri("/api/generation-jobs/{id}", jobId)
                    .headers(headers -> {
                        if (bearerToken != null && !bearerToken.isBlank()) {
                            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
                        }
                    })
                    .body(Map.of("status", status, "resultPayload", resultPayload == null ? "" : resultPayload))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            // ジョブの進捗更新はベストエフォート: legacy-apiが一時的に到達不能でも、チェックポイントの
            // ダウンロード/削除自体を失敗扱いにはしない(フロントは古い進捗のまま表示され続けるのみ)。
            org.slf4j.LoggerFactory.getLogger(GenerationJobClient.class)
                    .warn("legacy-apiの/api/generation-jobs/{}呼び出しに失敗しました: {}", jobId, e.getMessage());
        }
    }
}
