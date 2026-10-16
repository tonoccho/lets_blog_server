package com.letsblog.ai.client;

import com.letsblog.ai.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlatformServiceClientの分岐(issue #1824): システム全体のBrave Searchキー・LLM接続設定の解決で、
 * 認可ヘッダーの付与・空の応答・プロバイダー指定・HTTPエラーを実HTTP(サーバー側のフェイク)で通す。
 * ai-connections-config は {@link PlatformServiceClientAiConnectionsTest} が担当する。
 */
class PlatformServiceClientTest {

    private static final String KEY_PATH = "/api/internal/platform/system-settings/brave-search-api-key";
    private static final String LLM_PATH = "/api/internal/platform/llm-config";
    private static final String CONFIG = "{\"provider\":\"OLLAMA\",\"baseUrl\":\"http://o/v1\",\"apiKey\":\"\","
            + "\"defaultModel\":\"m\",\"availableModels\":[\"m\"],\"requestTimeoutSeconds\":10}";

    private FakeHttpServer server;
    private PlatformServiceClient client;

    @BeforeEach
    void setUp() {
        server = new FakeHttpServer();
        client = new PlatformServiceClient(RestClient.builder(), server.baseUrl());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    // ---- システム全体のBrave Search APIキー ----

    @Test
    void systemBraveSearchApiKey_キーを返しBearerトークンを転送する() {
        server.json("GET", KEY_PATH, "{\"apiKey\":\"brave-key\"}");

        assertEquals("brave-key", client.resolveSystemBraveSearchApiKey("Bearer t"));
        assertEquals("Bearer t", server.last().header("Authorization"));
    }

    @Test
    void systemBraveSearchApiKey_応答が空ならnull() {
        server.on("GET", KEY_PATH, FakeHttpServer.Response.empty(200));

        assertNull(client.resolveSystemBraveSearchApiKey("Bearer t"));
    }

    @Test
    void systemBraveSearchApiKey_取得に失敗してもnullを返す() {
        server.status("GET", KEY_PATH, 500, "boom");

        assertNull(client.resolveSystemBraveSearchApiKey("Bearer t"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void 認可ヘッダーはトークンがnullや空白なら付けない(String token) {
        server.json("GET", KEY_PATH, "{\"apiKey\":\"k\"}");

        assertEquals("k", client.resolveSystemBraveSearchApiKey(token));
        assertNull(server.last().header("Authorization"));
    }

    // ---- LLM接続設定 ----

    @Test
    void llmConfig_プロバイダーを指定するとクエリに付ける() {
        server.json("GET", LLM_PATH, CONFIG);

        PlatformServiceClient.LlmConfig config = client.resolveLlmConfig("OLLAMA", "Bearer t");

        assertEquals("http://o/v1", config.baseUrl());
        assertEquals(java.util.List.of("m"), config.availableModels());
        assertEquals("provider=OLLAMA", server.last().query());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void llmConfig_プロバイダーがnullや空白ならクエリを付けずシステム既定に任せる(String provider) {
        server.json("GET", LLM_PATH, CONFIG);

        client.resolveLlmConfig(provider, "Bearer t");

        assertNull(server.last().query());
    }

    @Test
    void llmConfig_応答が空なら例外にする() {
        server.on("GET", LLM_PATH, FakeHttpServer.Response.empty(200));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.resolveLlmConfig("OLLAMA", "Bearer t"));
        assertTrue(e.getMessage().contains("空の応答"), e.getMessage());
    }

    @Test
    void llmConfig_HTTPエラーは本文を例外のメッセージにする() {
        server.status("GET", LLM_PATH, 404, "no llm-config for CLAUDE");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.resolveLlmConfig("CLAUDE", "Bearer t"));
        assertEquals("no llm-config for CLAUDE", e.getMessage());
    }

    @Test
    void llmConfig_HTTPエラーで本文が空ならステータスのメッセージを使う() {
        server.on("GET", LLM_PATH, FakeHttpServer.Response.empty(503));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.resolveLlmConfig("CLAUDE", "Bearer t"));
        assertTrue(e.getMessage().contains("503"), e.getMessage());
    }

    @Test
    void llmConfig_接続できなければ原因付きの例外にする() {
        server.close();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> client.resolveLlmConfig("OLLAMA", "Bearer t"));
        assertTrue(e.getMessage().contains("llm-config"), e.getMessage());
    }
}
