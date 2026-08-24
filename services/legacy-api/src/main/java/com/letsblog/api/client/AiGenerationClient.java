package com.letsblog.api.client;

import com.letsblog.api.ai.AiServiceException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * ai-serviceの内部ブリッジ{@code POST /api/ai/internal/generate}を呼び出すクライアント(issue #574)。
 *
 * <p>LlmClient/LlmConfigProvider/AiProvider(ai/パッケージ)はissue #574でai-serviceへ移管されたため、
 * legacy-apiに残るLLM呼び出し元(AiAssistService#generateImagePrompt/#suggestImageTagsJson(画像生成、
 * このIssueの移設対象外)、CustomTagGenerationService/TagDesignGenerationService/
 * StaticContentGenerationService(いずれもCustomTag/StaticContent等、まだ抽出されていないcontent/site
 * ドメインのテーブルへ直接書き込む、または深くSite/CMS操作へ依存しているため、このIssueでは
 * ai-serviceへ移設せずlegacy-apiに残す判断とした。PR説明参照))は、自身ではLLMを直接呼び出さず、
 * このクライアント経由でai-serviceへ委譲する。
 *
 * <p>projectIdを渡すとai-service側でそのプロジェクトの選択中モデル/プロバイダーを解決して使う
 * (未指定時はシステム既定)。認証はmedia-service(#573)のGenerationJobClient等と同じ暫定策
 * (呼び出し元ユーザーのBearerトークンをそのまま転送する)。
 */
@Component
public class AiGenerationClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    // LLM呼び出しはLLM_REQUEST_TIMEOUT_SECONDS(既定120秒)まで掛かりうるため、legacy-api→ai-service間の
    // 読み取りタイムアウトもそれを上回る値にする(gatewayのタイムアウト延長と同じ理由)。
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(180);

    private final RestClient restClient;
    private final HttpServletRequest request;

    public AiGenerationClient(
            RestClient.Builder builder, @Value("${app.ai-service-uri}") String aiServiceUri,
            HttpServletRequest request) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = builder.baseUrl(aiServiceUri).requestFactory(requestFactory).build();
        this.request = request;
    }

    public String generate(Long projectId, String prompt, String providerOverride) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("projectId", projectId);
            body.put("prompt", prompt);
            body.put("providerOverride", providerOverride);
            GenerateResponse response = restClient.post()
                    .uri("/api/ai/internal/generate")
                    .headers(this::setAuthorization)
                    .body(body)
                    .retrieve()
                    .body(GenerateResponse.class);
            if (response == null) {
                throw new AiServiceException("ai-serviceから空の応答を受け取りました", null);
            }
            return response.result();
        } catch (RestClientException e) {
            throw new AiServiceException("ai-serviceの/api/ai/internal/generate呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private record GenerateResponse(String result) {
    }

    private void setAuthorization(HttpHeaders headers) {
        String bearerToken = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, bearerToken);
        }
    }
}
