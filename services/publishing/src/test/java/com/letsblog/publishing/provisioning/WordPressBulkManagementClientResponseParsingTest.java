package com.letsblog.publishing.provisioning;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1123の受け入れ基準4(変更ファイルのC1/C2分岐カバレッジ90%以上)を満たすための補完テスト。
 * 本Issueが変更したのはタイムアウト設定とタイムアウト/エラー応答の判別(catch節)であり、
 * 正常応答のJSONボディ解析(listTerms/listPluginsOrThemesのnull・形式チェック、resultOf、
 * asString)自体はこのIssueで変更していない既存ロジックだが、
 * scripts/check-changed-coverage.pyのカバレッジゲートはファイル単位でBRANCHカウンタを見るため、
 * このIssue以前にテストが一切無かった当該ファイルの分岐(このテストが追加されるまで0%)も
 * あわせて満たす必要がある。したがって本ファイルのテストは新しい振る舞いのRED/GREENではなく、
 * 既存の(変更していない)正常系の分岐を網羅するための回帰テストであり、production側の変更を
 * 伴わずに最初からGREENである(CLAUDE.mdのTest-First Implementation
 * → Coverageが要求する「変更ファイルのカバレッジ」を満たすための、振る舞いを変えない検証)。
 */
class WordPressBulkManagementClientResponseParsingTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private HttpServer httpServer;

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    @Test
    void listCategories_成功時はカテゴリ一覧を返す() throws IOException {
        respondWith("{\"categories\":["
                + "{\"name\":\"News\",\"slug\":\"news\",\"parentSlug\":null,\"description\":\"desc\"},"
                + "{\"name\":\"Sub\",\"slug\":\"sub\",\"parentSlug\":\"news\",\"description\":null}]}");
        WordPressBulkManagementClient client =
                new WordPressBulkManagementClient(baseUrl(), "token", TIMEOUT, TIMEOUT);

        List<WordPressBulkManagementClient.CategoryInfo> result = client.listCategories("site-a");

        assertEquals(2, result.size());
        assertEquals("News", result.get(0).name());
        assertEquals("news", result.get(0).slug());
        assertNull(result.get(0).parentSlug());
        assertEquals("desc", result.get(0).description());
        assertEquals("news", result.get(1).parentSlug());
        assertNull(result.get(1).description());
    }

    @Test
    void listCategories_応答本文がnullなら空リストで戻る() throws IOException {
        respondWith("null");
        WordPressBulkManagementClient client =
                new WordPressBulkManagementClient(baseUrl(), "token", TIMEOUT, TIMEOUT);

        assertTrue(client.listCategories("site-a").isEmpty());
    }

    @Test
    void listCategories_想定外の形式の応答なら空リストで戻る() throws IOException {
        respondWith("{\"unexpected\":\"value\"}");
        WordPressBulkManagementClient client =
                new WordPressBulkManagementClient(baseUrl(), "token", TIMEOUT, TIMEOUT);

        assertTrue(client.listCategories("site-a").isEmpty());
    }

    @Test
    void listPlugins_成功時はプラグイン一覧を返す() throws IOException {
        respondWith("{\"plugins\":["
                + "{\"name\":\"seo-plugin\",\"status\":\"active\"},"
                + "{\"name\":\"cache-plugin\",\"status\":\"inactive\"}]}");
        WordPressBulkManagementClient client =
                new WordPressBulkManagementClient(baseUrl(), "token", TIMEOUT, TIMEOUT);

        List<WordPressBulkManagementClient.PluginThemeInfo> result = client.listPlugins("site-a");

        assertEquals(2, result.size());
        assertEquals("seo-plugin", result.get(0).name());
        assertEquals("active", result.get(0).status());
    }

    @Test
    void listPlugins_応答本文がnullなら空リストで戻る() throws IOException {
        respondWith("null");
        WordPressBulkManagementClient client =
                new WordPressBulkManagementClient(baseUrl(), "token", TIMEOUT, TIMEOUT);

        assertTrue(client.listPlugins("site-a").isEmpty());
    }

    @Test
    void listPlugins_想定外の形式の応答なら空リストで戻る() throws IOException {
        respondWith("{\"unexpected\":\"value\"}");
        WordPressBulkManagementClient client =
                new WordPressBulkManagementClient(baseUrl(), "token", TIMEOUT, TIMEOUT);

        assertTrue(client.listPlugins("site-a").isEmpty());
    }

    @Test
    void apply_成功時はSUCCESSで戻る() throws IOException {
        respondWith("{\"status\":\"success\"}");
        WordPressBulkManagementClient client =
                new WordPressBulkManagementClient(baseUrl(), "token", TIMEOUT, TIMEOUT);

        WordPressBulkManagementClient.BulkApplyResult result = client.apply(
                new WordPressBulkManagementClient.BulkApplyCommand(
                        "site-a", "activate-plugin", "some-plugin", null, null, null, null));

        assertEquals("SUCCESS", result.status());
    }

    @Test
    void apply_skippedのときはSKIPPEDで戻る() throws IOException {
        respondWith("{\"status\":\"skipped\"}");
        WordPressBulkManagementClient client =
                new WordPressBulkManagementClient(baseUrl(), "token", TIMEOUT, TIMEOUT);

        WordPressBulkManagementClient.BulkApplyResult result = client.apply(
                new WordPressBulkManagementClient.BulkApplyCommand(
                        "site-a", "activate-plugin", "some-plugin", null, null, null, null));

        assertEquals("SKIPPED", result.status());
    }

    @Test
    void apply_応答本文がnullならSUCCESSとして戻る() throws IOException {
        respondWith("null");
        WordPressBulkManagementClient client =
                new WordPressBulkManagementClient(baseUrl(), "token", TIMEOUT, TIMEOUT);

        WordPressBulkManagementClient.BulkApplyResult result = client.apply(
                new WordPressBulkManagementClient.BulkApplyCommand(
                        "site-a", "activate-plugin", "some-plugin", null, null, null, null));

        assertEquals("SUCCESS", result.status());
    }

    private void respondWith(String jsonBody) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/", exchange -> {
            byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        httpServer.start();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + httpServer.getAddress().getPort();
    }
}
