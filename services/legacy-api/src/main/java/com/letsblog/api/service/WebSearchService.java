package com.letsblog.api.service;

import com.letsblog.api.ai.BraveSearchClient;
import com.letsblog.api.ai.BraveSearchResult;
import com.letsblog.api.dto.SourceReference;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 壁打ちチャットの応答生成前に行うWeb検索。検索の失敗(APIキー未設定・タイムアウト・レート制限等)で
 * チャット機能全体を止めないよう、フェイルオープンで結果を返す。
 * プロンプトへの検索結果整形・出典一覧化・未検索/未ヒット時の注記生成は、呼び出し元(ArticlePlanService・
 * AiAssistService)で共通のため本サービスに集約する。
 */
@Service
public class WebSearchService {

    private static final int SEARCH_RESULT_COUNT = 5;

    private final BraveSearchClient braveSearchClient;
    private final SystemSettingService systemSettingService;
    private final ProjectApiKeyService projectApiKeyService;

    public WebSearchService(
            BraveSearchClient braveSearchClient,
            SystemSettingService systemSettingService,
            ProjectApiKeyService projectApiKeyService) {
        this.braveSearchClient = braveSearchClient;
        this.systemSettingService = systemSettingService;
        this.projectApiKeyService = projectApiKeyService;
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
            String apiKey = projectId != null
                    ? projectApiKeyService.resolveBraveSearchApiKey(projectId)
                    : systemSettingService.getBraveSearchApiKey();
            return WebSearchOutcome.success(braveSearchClient.search(query, SEARCH_RESULT_COUNT, apiKey));
        } catch (RuntimeException e) {
            return WebSearchOutcome.failure(e.getMessage());
        }
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
        for (BraveSearchResult result : outcome.results()) {
            sb.append(i++).append(". ").append(result.title()).append(" - ").append(result.description())
                    .append(" (").append(result.url()).append(")\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * レスポンスに含める出典一覧。検索失敗時は空リスト(searchNoteでその旨を別途伝える)。
     */
    public static List<SourceReference> toSources(WebSearchOutcome outcome) {
        if (!outcome.succeeded()) {
            return List.of();
        }
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
