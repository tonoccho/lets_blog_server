package com.letsblog.api.client;

import com.letsblog.api.ai.AiServiceException;
import jakarta.servlet.http.HttpServletRequest;
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
 * ai-serviceの{@code POST /api/generation-jobs}・{@code PATCH /api/generation-jobs/{id}}を呼び出す
 * クライアント(issue #574)。
 *
 * <p>generation_jobsテーブルの所有権はissue #574でai-serviceへ移管されたため、legacy-apiに残る
 * ジョブ作成元(AiAssistService#generateImage/#generateImagePrompt(画像生成、このIssueの移設対象外)、
 * ComfyUiModelService#startInstall/#startDelete(ComfyUIチェックポイント管理、#573時点でも移設されて
 * いない))は、自身ではgeneration_jobsへ直接書き込めなくなった(ADR-0004、クロススキーマアクセス禁止)。
 * media-service(#573)のGenerationJobClientと同じ暫定策(呼び出し元ユーザーのBearerトークンを
 * そのまま転送する)で、ai-serviceへジョブの作成・進捗更新を委譲する。
 *
 * <p>media-service側のComfyUiModelインストール/削除の非同期ジョブランナー(ModelInstallJobRunner)は、
 * このクライアントを経由せず、自身のGenerationJobClient(同じくissue #574でai-serviceへ向き先を
 * 変更した)で直接ai-serviceへ進捗を反映する。legacy-api側のこのクライアントは、あくまで
 * 「ジョブの作成」(startInstall/startDelete呼び出し元の同期リクエスト内)とAiAssistServiceの
 * 画像生成ジョブ(作成〜完了/失敗までlegacy-api自身が同期的に行う)のためにのみ使う。
 */
@Component
public class GenerationJobClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final RestClient restClient;
    private final HttpServletRequest request;

    public GenerationJobClient(
            RestClient.Builder builder, @Value("${app.ai-service-uri}") String aiServiceUri,
            HttpServletRequest request) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(aiServiceUri).requestFactory(requestFactory).build();
        this.request = request;
    }

    public GenerationJobSummary create(String type, String requestPayload) {
        try {
            GenerationJobSummary created = restClient.post()
                    .uri("/api/generation-jobs")
                    .headers(this::setAuthorization)
                    .body(Map.of("type", type, "requestPayload", requestPayload == null ? "" : requestPayload))
                    .retrieve()
                    .body(GenerationJobSummary.class);
            if (created == null) {
                throw new AiServiceException("ai-serviceから空の応答を受け取りました", null);
            }
            return created;
        } catch (RestClientException e) {
            throw new AiServiceException("ai-serviceの/api/generation-jobs作成呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    public void updateStatus(Long jobId, String status, String resultPayload) {
        try {
            restClient.patch()
                    .uri("/api/generation-jobs/{id}", jobId)
                    .headers(this::setAuthorization)
                    .body(Map.of("status", status, "resultPayload", resultPayload == null ? "" : resultPayload))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            // ジョブの進捗更新はベストエフォート: ai-serviceが一時的に到達不能でも、画像生成/チェックポイント
            // インストール自体を失敗扱いにはしない(フロントは古い進捗のまま表示され続けるのみ。
            // media-serviceのGenerationJobClientと同じ方針)。
            org.slf4j.LoggerFactory.getLogger(GenerationJobClient.class)
                    .warn("ai-serviceの/api/generation-jobs/{}呼び出しに失敗しました: {}", jobId, e.getMessage());
        }
    }

    private void setAuthorization(HttpHeaders headers) {
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }
}
