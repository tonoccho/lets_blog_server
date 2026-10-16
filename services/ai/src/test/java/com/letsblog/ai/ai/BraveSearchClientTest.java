package com.letsblog.ai.ai;

import com.letsblog.ai.testsupport.FakeHttpServer;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** BraveSearchClient(issue #1824): キーの検査・結果の組み立て・HTTPエラー。Braveの相手はHTTPフェイクで受ける。 */
class BraveSearchClientTest {

    private static final String PATH = "/res/v1/web/search";

    private FakeHttpServer server;
    private BraveSearchClient client;

    @BeforeEach
    void setUp() {
        server = new FakeHttpServer();
        client = new BraveSearchClient(server.baseUrl());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void APIキーが無ければ検索せずに例外(String apiKey) {
        AiServiceException e = assertThrows(AiServiceException.class, () -> client.search("クエリ", 3, apiKey));

        assertTrue(e.getMessage().contains("Brave Search APIキーが設定されていません"), e.getMessage());
        assertTrue(server.requests().isEmpty());
    }

    @Test
    void 結果をタイトル_説明_URLにして返しキーとクエリを送る() {
        server.json("GET", PATH, "{\"web\":{\"results\":["
                + "{\"title\":\"t1\",\"description\":\"d1\",\"url\":\"u1\"},{\"title\":\"t2\"}]}}");

        List<BraveSearchResult> results = client.search("日本語 クエリ", 2, "key-1");

        assertEquals(List.of(new BraveSearchResult("t1", "d1", "u1"), new BraveSearchResult("t2", "", "")), results);
        assertEquals("key-1", server.last().header("X-Subscription-Token"));
        assertTrue(server.last().query().contains("count=2"), server.last().query());
    }

    @Test
    void 応答が空なら結果なし() {
        server.on("GET", PATH, FakeHttpServer.Response.empty(200));

        assertEquals(List.of(), client.search("クエリ", 3, "key-1"));
    }

    @Test
    void 結果が無い応答なら結果なし() {
        server.json("GET", PATH, "{}");

        assertEquals(List.of(), client.search("クエリ", 3, "key-1"));
    }

    @Test
    void HTTPエラーはステータスと本文を添えた例外() {
        server.status("GET", PATH, 429, "rate limited");

        AiServiceException e = assertThrows(AiServiceException.class, () -> client.search("クエリ", 3, "key-1"));
        assertTrue(e.getMessage().contains("429"), e.getMessage());
        assertTrue(e.getMessage().contains("rate limited"), e.getMessage());
    }
}
