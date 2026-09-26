package com.letsblog.media.client;

import com.letsblog.media.ai.AiServiceException;
import com.letsblog.common.client.SyncCallProfile;
import com.letsblog.common.client.SyncServiceClient;
import com.letsblog.common.client.SyncServiceException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * ai-serviceの内部ブリッジ{@code POST /api/internal/ai/generate}を呼び出すクライアント。
 * issue #583で画像生成がmedia-serviceへ移ったのに伴い、legacy-apiの同名クラスから移設した。
 *
 * <p>media-serviceがLLMを使うのは、生成画像の検索・分類用タグをプロンプトから提案させる
 * 1箇所だけである({@code ImageGenerationService#suggestImageTagsJson}、issue #281)。
 * LLM本体の所有権はai-service(#574)にあるため、自前で呼ばずここを経由する。
 *
 * <p>issue #581(C12)で導入したlbs-commonの{@link SyncServiceClient}を使う
 * (LLM用プロファイル、リトライ無し、明確なエラー)。方針の詳細はdocs/SYNC_SERVICE_CALLS.md参照。
 *
 * <p>projectIdを渡すとai-service側でそのプロジェクトの選択中モデル/プロバイダーを解決して使う
 * (未指定時はシステム既定)。認証は{@link OutboundAuthHeaders}(リクエスト中はユーザーのBearer、
 * 非同期ジョブ(#1405)などリクエストの無いスレッドではサービス自身のトークン)。
 */
@Component
public class AiGenerationClient {

    private final SyncServiceClient client;
    private final OutboundAuthHeaders authHeaders;

    public AiGenerationClient(
            RestClient.Builder builder, @Value("${app.ai-service-uri}") String aiServiceUri,
            OutboundAuthHeaders authHeaders) {
        this.client = SyncServiceClient.builder(builder, "ai-service", aiServiceUri)
                .profile(SyncCallProfile.LLM)
                .build();
        this.authHeaders = authHeaders;
    }

    public String generate(Long projectId, String prompt, String providerOverride) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("projectId", projectId);
            body.put("prompt", prompt);
            body.put("providerOverride", providerOverride);
            GenerateResponse response = client.post(
                    "/api/internal/ai/generate", new Object[0], body, GenerateResponse.class,
                    authHeaders.current());
            if (response == null) {
                throw new AiServiceException("ai-serviceから空の応答を受け取りました", null);
            }
            return response.result();
        } catch (SyncServiceException e) {
            throw new AiServiceException("ai-serviceの/api/internal/ai/generate呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private record GenerateResponse(String result) {
    }
}
