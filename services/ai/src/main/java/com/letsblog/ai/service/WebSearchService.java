package com.letsblog.ai.service;

import com.letsblog.ai.ai.BraveSearchClient;
import com.letsblog.ai.client.PlatformServiceClient;
import com.letsblog.ai.dto.SourceReference;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 壁打ちチャットの応答生成前に行うWeb検索。検索の失敗(APIキー未設定・タイムアウト・レート制限等)で
 * チャット機能全体を止めないよう、フェイルオープンで結果を返す。
 * プロンプトへの検索結果整形・出典一覧化・未検索/未ヒット時の注記生成は、呼び出し元(ArticlePlanService・
 * AiAssistService)で共通のため本サービスに集約する。
 *
 * <p>issue #574でai-serviceへ移設。プロジェクトスコープのAPIキー(project_ai_settings、ai-service
 * 自身が所有)は自前のリポジトリから直接解決できるが、プロジェクトに紐付かない呼び出し向けの
 * システム全体既定キー(system_settings、platform-serviceがまだ未抽出のためlegacy-apiに残る)は
 * {@link PlatformServiceClient}経由で解決する(issue #583でlegacy-apiの中継を外し、システム設定を所有するplatform-serviceを直接呼ぶよう切り替えた)。
 */
@Service
public class WebSearchService {

    private static final int SEARCH_RESULT_COUNT = 5;

    private final BraveSearchClient braveSearchClient;
    private final PlatformServiceClient platformServiceClient;
    private final ProjectAiSettingsService projectAiSettingsService;
    private final CurrentActorService currentActorService;
    private final com.letsblog.common.crypto.CredentialCipher credentialCipher;

    public WebSearchService(
            BraveSearchClient braveSearchClient,
            PlatformServiceClient platformServiceClient,
            ProjectAiSettingsService projectAiSettingsService,
            CurrentActorService currentActorService,
            com.letsblog.common.crypto.CredentialCipher credentialCipher) {
        this.braveSearchClient = braveSearchClient;
        this.platformServiceClient = platformServiceClient;
        this.projectAiSettingsService = projectAiSettingsService;
        this.currentActorService = currentActorService;
        this.credentialCipher = credentialCipher;
    }

    /** プロジェクトに紐付かない呼び出し元(AiAssistService)向け。システム全体設定のキーを使う。 */
    public WebSearchOutcome searchSafely(String query) {
        return searchSafely(query, null);
    }

    /**
     * プロジェクトスコープの呼び出し元(ArticlePlanService)向け。プロジェクトにキーが
     * 設定されていればそれを優先し、未設定ならシステム全体設定へフォールバックする(issue #184)。
     */
    public WebSearchOutcome searchSafely(String query, Long projectId) {
        try {
            String apiKey = projectId != null ? resolveProjectBraveSearchApiKey(projectId) : null;
            if (apiKey == null) {
                apiKey = platformServiceClient.resolveSystemBraveSearchApiKey(currentActorService.getAuthorizationHeader());
            }
            return WebSearchOutcome.success(braveSearchClient.search(query, SEARCH_RESULT_COUNT, apiKey));
        } catch (RuntimeException e) {
            return WebSearchOutcome.failure(e.getMessage());
        }
    }

    private String resolveProjectBraveSearchApiKey(Long projectId) {
        byte[] encrypted = projectAiSettingsService.getBraveSearchApiKeyEncrypted(projectId);
        return encrypted == null || encrypted.length == 0 ? null : credentialCipher.decrypt(encrypted);
    }

    /**
     * プロンプトへ追記する検索結果ブロックを組み立てる。検索失敗/0件時は空文字列を返す。
     * WebSearchOutcomeのみに依存する純粋な整形ロジックのためstaticとし、
     * WebSearchServiceをモックしているテストでも実際の整形結果が使われるようにする。
     */
    public static String formatForPrompt(WebSearchOutcome outcome) {
        if (!outcome.succeeded() || outcome.results().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("参考のWeb検索結果:\n");
        int i = 1;
        for (var result : outcome.results()) {
            sb.append(i++).append(". ").append(result.title()).append(" - ").append(result.url()).append("\n")
                    .append("   ").append(result.description()).append("\n");
        }
        return sb.toString();
    }

    public static List<SourceReference> toSources(WebSearchOutcome outcome) {
        return outcome.results().stream()
                .map(r -> new SourceReference(r.title(), r.url()))
                .toList();
    }

    /**
     * 出典があるかのように装わないための注記。検索成功かつ結果ありの場合はnull(注記不要)。
     */
    public static String buildSearchNote(WebSearchOutcome outcome) {
        if (!outcome.succeeded()) {
            return "Web検索を利用できなかったため、出典なしで生成しています";
        }
        if (outcome.results().isEmpty()) {
            return "関連する検索結果が見つかりませんでした";
        }
        return null;
    }
}
