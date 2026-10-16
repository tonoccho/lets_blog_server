package com.letsblog.media.client;

import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.media.testsupport.ServiceTokenFixtures;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PlatformServiceClient#comfyUiBaseUrl(projectId)(issue #1503)。
 * 解決順はプロジェクト上書き → システム設定(platform-service。DB優先・環境変数既定)で、
 * 上書きが無い(null)ときは従来のシステム値と一致する。
 */
class PlatformServiceClientProjectUrlTest {

    private static final String SYSTEM = "http://system-comfy:8188";

    private HttpServer server;
    private PlatformServiceClient client;
    private AiServiceConnectionClient aiConnections;
    /** ai-serviceの内部API({@code /api/internal/ai/projects/{id}/...})の応答。キーは末尾のパス(例: 7/connections)。 */
    private final Map<String, String> aiBodies = new ConcurrentHashMap<>();
    /** ai-serviceへ届いたリクエストのパス。 */
    private final List<String> aiPaths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/platform/image-generation-config", this::respond);
        server.createContext("/api/internal/ai/projects/", this::respondAi);
        server.createContext(ServiceTokenFixtures.TOKEN_PATH, ServiceTokenFixtures::handleTokenRequest);
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        ServiceTokenClient tokens = ServiceTokenFixtures.client(baseUrl);
        aiConnections = new AiServiceConnectionClient(RestClient.builder(), baseUrl, tokens);
        client = new PlatformServiceClient(RestClient.builder(), baseUrl, tokens, aiConnections);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respondAi(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        aiPaths.add(path);
        String key = path.substring("/api/internal/ai/projects/".length());
        byte[] bytes = aiBodies.getOrDefault(key, "{}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void respond(HttpExchange exchange) throws IOException {
        byte[] bytes = ("{\"comfyUiBaseUrl\":\"" + SYSTEM + "\",\"chatGptBaseUrl\":\"u\"}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void 上書きがあるプロジェクトは上書きURLを使う() {
        aiBodies.put("7/connections", "{\"comfyuiBaseUrl\":\"http://gpu:8188\"}");

        assertThat(client.comfyUiBaseUrl(7L)).isEqualTo("http://gpu:8188");
    }

    @Test
    void 上書きが無いプロジェクトはシステム設定の値を使う() {
        aiBodies.put("8/connections", "{\"comfyuiBaseUrl\":null}");

        assertThat(client.comfyUiBaseUrl(8L)).isEqualTo(SYSTEM);
    }

    @Test
    void projectIdが無ければai_serviceへ問い合わせずシステム設定の値を使う() {
        assertThat(client.comfyUiBaseUrl(null)).isEqualTo(SYSTEM);

        assertThat(aiPaths).isEmpty();
    }

    @Test
    void ChatGPTのベースURLはplatformが別のURLを返しても固定のOpenAIを使う_issue1569() {
        assertThat(client.chatGptBaseUrl()).isEqualTo("https://api.openai.com/v1");
    }

    @Test
    void e2e_stubsプロファイルでだけChatGPT画像生成の接続先がスタブになる_issue1569() {
        ServiceTokenClient tokens = ServiceTokenFixtures.unreachable();
        org.springframework.mock.env.MockEnvironment stubs = new org.springframework.mock.env.MockEnvironment();
        stubs.setActiveProfiles("e2e-stubs");
        org.springframework.mock.env.MockEnvironment production = new org.springframework.mock.env.MockEnvironment();

        assertThat(new PlatformServiceClient(RestClient.builder(), "http://x", tokens, aiConnections, stubs)
                .chatGptBaseUrl()).isEqualTo("http://image-stub:8080");
        assertThat(new PlatformServiceClient(RestClient.builder(), "http://x", tokens, aiConnections, production)
                .chatGptBaseUrl()).isEqualTo("https://api.openai.com/v1");
    }

    @Test
    void ChatGPTのAPIキーはプロジェクトのキーだけを使いシステム設定へは落とさない() {
        aiBodies.put("7/openai-api-key", "{\"apiKey\":\"sk-project-7\"}");
        aiBodies.put("8/openai-api-key", "{\"apiKey\":null}");

        assertThat(client.chatGptApiKey(7L)).isEqualTo("sk-project-7");
        assertThat(client.chatGptApiKey(8L)).isNull();
    }

    @Test
    void projectIdが無ければai_serviceへ問い合わせずキーはnull() {
        assertThat(client.chatGptApiKey(null)).isNull();

        assertThat(aiPaths).isEmpty();
    }

    // ---- extensionsのツールへのトークンを付ける宛先(#1804) ----

    @Test
    void システム設定の接続先は上書きが無ければトークンを付ける() {
        aiBodies.put("8/connections", "{\"comfyuiBaseUrl\":null}");

        assertThat(client.comfyUiToolsAuthorized(8L)).isTrue();
    }

    @Test
    void projectIdが無ければai_serviceへ問い合わせずトークンを付ける() {
        assertThat(client.comfyUiToolsAuthorized(null)).isTrue();

        assertThat(aiPaths).isEmpty();
    }

    @Test
    void プロジェクトの上書き先にはトークンを付けない() {
        aiBodies.put("7/connections", "{\"comfyuiBaseUrl\":\"http://gpu:8188\"}");

        assertThat(client.comfyUiToolsAuthorized(7L)).isFalse();
    }
}
