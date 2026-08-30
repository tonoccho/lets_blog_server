package com.letsblog.api.client;

import com.letsblog.common.auth.ServiceTokenClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * issue #742: platform-serviceの内部ブリッジ呼び出しにAuthorizationヘッダーが付くこと。
 *
 * <p>#742以前、{@link PlatformServiceClient}はAuthorizationヘッダーを一切付与していなかった。
 * そのためplatform-service側は{@code /api/internal/platform/**}をpermitAllのまま据え置くしかなく、
 * Brave Search APIキー・LLM APIキー・ChatGPTキーを返すエンドポイントが内部ネットワークから
 * 無防備だった。本テストはヘッダー付与が失われたら落ちる。
 *
 * <p>{@link PlatformServiceClient}は注入された{@code RestClient.Builder}を{@code clone()}して
 * 独自の{@code requestFactory}を設定するため、{@code MockRestServiceServer}では差し込めない。
 * 実際に送信されるHTTPヘッダーを検証したいので、その場限りのHTTPサーバーを立てて実通信させる。
 */
@DisplayName("PlatformServiceClient: 内部ブリッジ呼び出しの認証(issue #742)")
class PlatformServiceClientTest {

    private static final String TOKEN = "service-account-access-token";

    private HttpServer server;
    private final ConcurrentMap<String, String> receivedAuthByPath = new ConcurrentHashMap<>();
    private PlatformServiceClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/platform", this::respond);
        server.start();

        ServiceTokenClient serviceTokenClient = mock(ServiceTokenClient.class);
        when(serviceTokenClient.getAccessToken()).thenReturn(TOKEN);

        client = new PlatformServiceClient(
                RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                serviceTokenClient);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        List<String> auth = exchange.getRequestHeaders().get("Authorization");
        receivedAuthByPath.put(path, auth == null ? "" : String.join(",", auth));

        String body = switch (path) {
            case "/api/internal/platform/system-settings/brave-search-api-key" -> "{\"apiKey\":\"brave-key\"}";
            case "/api/internal/platform/llm-config" ->
                    "{\"provider\":\"OPENAI\",\"baseUrl\":\"https://api.openai.test/v1\",\"apiKey\":\"llm-key\","
                            + "\"defaultModel\":\"gpt-test\",\"availableModels\":[\"gpt-test\"],"
                            + "\"requestTimeoutSeconds\":30}";
            default -> "{\"comfyUiBaseUrl\":\"http://comfyui.test\",\"chatGptApiKey\":\"chatgpt-key\","
                    + "\"chatGptBaseUrl\":\"https://api.openai.test/v1\"}";
        };
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void assertBearerSentTo(String path) {
        assertThat(receivedAuthByPath.get(path))
                .as("%s へ Authorization ヘッダーが送られていること", path)
                .isEqualTo("Bearer " + TOKEN);
    }

    @Test
    @DisplayName("Brave Search APIキー取得にBearerトークンが付く")
    void braveSearchApiKey() {
        assertThat(client.getBraveSearchApiKey()).isEqualTo("brave-key");
        assertBearerSentTo("/api/internal/platform/system-settings/brave-search-api-key");
    }

    @Test
    @DisplayName("LLM接続設定取得にBearerトークンが付く")
    void llmConfig() {
        assertThat(client.llmConfig(null).apiKey()).isEqualTo("llm-key");
        assertBearerSentTo("/api/internal/platform/llm-config");
    }

    /**
     * 画像生成設定は{@code ComfyUiClient}/{@code ChatGptImageClient}が1回の生成処理で複数回
     * 独立に呼ぶため、5秒TTLでキャッシュされる。キャッシュに載った経路でも
     * 初回リクエストにヘッダーが付いていることを確認する。
     */
    @Test
    @DisplayName("画像生成設定取得にBearerトークンが付く")
    void imageGenerationConfig() {
        assertThat(client.comfyUiBaseUrl()).isEqualTo("http://comfyui.test");
        assertThat(client.chatGptApiKey()).isEqualTo("chatgpt-key");
        assertBearerSentTo("/api/internal/platform/image-generation-config");
    }
}
