package com.letsblog.ai.service;

import com.letsblog.ai.ai.BraveSearchResult;
import com.letsblog.ai.testsupport.AiWorld;
import com.letsblog.ai.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSearchServiceの回帰テスト(issue #184/#574)。projectId指定時はai-service自身が持つ
 * project_ai_settings(ProjectAiSettingsService)から直接キーを解決すること、未指定時/未設定時は
 * platform-serviceのシステム全体設定へ直接フォールバックすることを検証する。
 *
 * <p>issue #1823: BraveSearchClient・PlatformServiceClient・ProjectAiSettingsService・CurrentActorService・
 * CredentialCipherは実物で、Brave Search APIとplatform-serviceはHTTPフェイク、DBはメモリ上のリポジトリで差し替える。
 */
class WebSearchServiceTest {

    private static final String BRAVE = "/brave/res/v1/web/search";
    private static final String SYSTEM_KEY_PATH = "/api/internal/platform/system-settings/brave-search-api-key";

    private AiWorld world;
    private WebSearchService service;

    @BeforeEach
    void setUp() {
        world = new AiWorld();
        service = world.webSearchService;
        world.http.json("GET", BRAVE,
                "{\"web\":{\"results\":[{\"title\":\"title\",\"description\":\"desc\",\"url\":\"https://example.com\"}]}}");
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private String braveKeyUsed() {
        return world.http.requests("GET", BRAVE).get(0).header("X-Subscription-Token");
    }

    @Test
    void searchSafely_projectId指定時はプロジェクト自身のキー解決を使う() {
        world.projectAiSettingsService.setBraveSearchApiKeyEncrypted(1L, world.credentialCipher.encrypt("project-key"));

        WebSearchOutcome outcome = service.searchSafely("query", 1L);

        assertTrue(outcome.succeeded());
        assertEquals(List.of(new BraveSearchResult("title", "desc", "https://example.com")), outcome.results());
        assertEquals("project-key", braveKeyUsed());
        assertEquals(0, world.http.requests("GET", SYSTEM_KEY_PATH).size());
    }

    @Test
    void searchSafely_projectIdのキー未設定ならシステム全体設定へフォールバックする() {
        world.http.json("GET", SYSTEM_KEY_PATH, "{\"apiKey\":\"system-key\"}");

        WebSearchOutcome outcome = service.searchSafely("query", 1L);

        assertTrue(outcome.succeeded());
        assertEquals("system-key", braveKeyUsed());
    }

    @Test
    void searchSafely_projectIdのキーが空のバイト列ならシステム全体設定へフォールバックする() {
        world.projectAiSettingsService.setBraveSearchApiKeyEncrypted(1L, new byte[0]);
        world.http.json("GET", SYSTEM_KEY_PATH, "{\"apiKey\":\"system-key\"}");

        WebSearchOutcome outcome = service.searchSafely("query", 1L);

        assertTrue(outcome.succeeded());
        assertEquals("system-key", braveKeyUsed());
    }

    @Test
    void searchSafely_projectId未指定時はシステム全体設定をブリッジ経由で使い呼び出し元のトークンを転送する() {
        world.admin();
        world.http.json("GET", SYSTEM_KEY_PATH, "{\"apiKey\":\"system-key\"}");

        WebSearchOutcome outcome = service.searchSafely("query");

        assertTrue(outcome.succeeded());
        assertEquals("system-key", braveKeyUsed());
        assertEquals(AiWorld.BEARER, world.http.requests("GET", SYSTEM_KEY_PATH).get(0).header("Authorization"));
    }

    @Test
    void searchSafely_どこにもキーが無ければ失敗の結果を返し検索しない() {
        // platform-serviceは取得に失敗したときnullを返す(例外にしない)。
        WebSearchOutcome outcome = service.searchSafely("query");

        assertFalse(outcome.succeeded());
        assertTrue(outcome.errorMessage().contains("Brave Search APIキーが設定されていません"), outcome.errorMessage());
        assertEquals(0, world.http.requests("GET", BRAVE).size());
    }

    @Test
    void searchSafely_システム設定のキーが空の応答でも失敗の結果になる() {
        world.http.json("GET", SYSTEM_KEY_PATH, "{\"apiKey\":null}");

        WebSearchOutcome outcome = service.searchSafely("query");

        assertFalse(outcome.succeeded());
        assertEquals(0, world.http.requests("GET", BRAVE).size());
    }

    @Test
    void searchSafely_BraveSearchが失敗したら例外にせず失敗の結果を返す() {
        world.http.json("GET", SYSTEM_KEY_PATH, "{\"apiKey\":\"system-key\"}");
        world.http.status("GET", BRAVE, 429, "{\"error\":\"rate limited\"}");

        WebSearchOutcome outcome = service.searchSafely("query");

        assertFalse(outcome.succeeded());
        assertTrue(outcome.errorMessage().contains("429"), outcome.errorMessage());
    }

    @Test
    void formatForPrompt_結果があれば番号つきの参考ブロックにし_失敗や0件は空文字にする() {
        WebSearchOutcome ok = WebSearchOutcome.success(List.of(
                new BraveSearchResult("t1", "d1", "https://a"), new BraveSearchResult("t2", "d2", "https://b")));

        assertEquals("参考のWeb検索結果:\n1. t1 - https://a\n   d1\n2. t2 - https://b\n   d2\n",
                WebSearchService.formatForPrompt(ok));
        assertEquals("", WebSearchService.formatForPrompt(WebSearchOutcome.success(List.of())));
        assertEquals("", WebSearchService.formatForPrompt(WebSearchOutcome.failure("x")));
    }

    @Test
    void toSources_検索結果から出典を作る() {
        var sources = WebSearchService.toSources(
                WebSearchOutcome.success(List.of(new BraveSearchResult("t1", "d1", "https://a"))));

        assertEquals(1, sources.size());
        assertEquals("t1", sources.get(0).title());
        assertEquals("https://a", sources.get(0).url());
    }

    @Test
    void buildSearchNote_成功かつ結果ありは注記なし_失敗と0件はそれぞれの注記() {
        assertNull(WebSearchService.buildSearchNote(
                WebSearchOutcome.success(List.of(new BraveSearchResult("t", "d", "u")))));
        assertEquals("Web検索を利用できなかったため、出典なしで生成しています",
                WebSearchService.buildSearchNote(WebSearchOutcome.failure("x")));
        assertEquals("関連する検索結果が見つかりませんでした",
                WebSearchService.buildSearchNote(WebSearchOutcome.success(List.of())));
    }
}
