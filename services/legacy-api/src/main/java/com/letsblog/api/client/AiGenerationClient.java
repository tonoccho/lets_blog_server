package com.letsblog.api.client;

import com.letsblog.api.ai.AiServiceException;
import com.letsblog.common.client.ServiceAuthHeaders;
import com.letsblog.common.client.SyncCallProfile;
import com.letsblog.common.client.SyncServiceClient;
import com.letsblog.common.client.SyncServiceException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * ai-serviceの内部ブリッジ{@code POST /api/ai/internal/generate}を呼び出すクライアント(issue #574)。
 * issue #581(C12)でlbs-commonの{@link SyncServiceClient}へ移行した。content-service側の同名クラス
 * ({@link com.letsblog.content.client.AiGenerationClient}相当、同じ移行)と同じ方針を使う
 * (LLM用プロファイル、リトライ無し、明確なエラー)。方針の詳細はdocs/SYNC_SERVICE_CALLS.md参照。
 *
 * <p>LlmClient/LlmConfigProvider/AiProvider(ai/パッケージ)はissue #574でai-serviceへ移管されたため、
 * legacy-apiに残るLLM呼び出し元(AiAssistService#generateImagePrompt/#suggestImageTagsJson(画像生成、
 * このIssueの移設対象外)、CustomTagGenerationService/TagDesignGenerationService/
 * StaticContentGenerationService)は、自身ではLLMを直接呼び出さず、このクライアント経由で
 * ai-serviceへ委譲する。
 *
 * <p>projectIdを渡すとai-service側でそのプロジェクトの選択中モデル/プロバイダーを解決して使う
 * (未指定時はシステム既定)。認証は呼び出し元ユーザーのBearerトークンをそのまま転送する。
 */
@Component
public class AiGenerationClient {

    private final SyncServiceClient client;
    private final HttpServletRequest request;

    public AiGenerationClient(
            RestClient.Builder builder, @Value("${app.ai-service-uri}") String aiServiceUri,
            HttpServletRequest request) {
        this.client = SyncServiceClient.builder(builder, "ai-service", aiServiceUri)
                .profile(SyncCallProfile.LLM)
                .build();
        this.request = request;
    }

    public String generate(Long projectId, String prompt, String providerOverride) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("projectId", projectId);
            body.put("prompt", prompt);
            body.put("providerOverride", providerOverride);
            GenerateResponse response = client.post(
                    "/api/ai/internal/generate", new Object[0], body, GenerateResponse.class,
                    ServiceAuthHeaders.forwardedBearer(request));
            if (response == null) {
                throw new AiServiceException("ai-serviceから空の応答を受け取りました", null);
            }
            return response.result();
        } catch (SyncServiceException e) {
            throw new AiServiceException("ai-serviceの/api/ai/internal/generate呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private record GenerateResponse(String result) {
    }
}
