package com.letsblog.content.client;

import com.letsblog.common.client.SyncCallProfile;
import com.letsblog.common.client.SyncServiceClient;
import com.letsblog.common.client.SyncServiceException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * ai-serviceの内部ブリッジ{@code POST /api/internal/ai/generate}を呼び出すクライアント(issue #574)。
 * issue #581(C12)でlbs-commonの{@link SyncServiceClient}(タイムアウト・リトライ・サーキットブレーカー
 * の共通実装)へ移行した。方針の詳細はdocs/SYNC_SERVICE_CALLS.md参照。
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
 * (未指定時はシステム既定)。認証はmedia-service(#573)のGenerationJobClient等と同じ方式
 * (リクエスト中は呼び出し元ユーザーのBearerトークンをそのまま転送する。リクエストの無い非同期ジョブ(#1409)
 * ではこのサービス自身のトークン。{@link OutboundAuthHeaders}参照)。
 *
 * <p>フォールバック方針: 明確なエラー({@link AiServiceException}）。LLM生成結果はプレースホルダで
 * 代替できる性質のものではなく(記事本文/タグ提案等、ユーザーが結果を直接目にする)、機能縮退は
 * 誤解を招くため採用しない。
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
