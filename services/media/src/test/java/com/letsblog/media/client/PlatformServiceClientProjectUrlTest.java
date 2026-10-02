package com.letsblog.media.client;

import com.letsblog.common.auth.ServiceTokenClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/platform/image-generation-config", this::respond);
        server.start();
        ServiceTokenClient tokens = mock(ServiceTokenClient.class);
        when(tokens.getAccessToken()).thenReturn("svc");
        aiConnections = mock(AiServiceConnectionClient.class);
        client = new PlatformServiceClient(
                RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(), tokens, aiConnections);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
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
        when(aiConnections.comfyUiBaseUrlOverride(7L)).thenReturn("http://gpu:8188");

        assertThat(client.comfyUiBaseUrl(7L)).isEqualTo("http://gpu:8188");
    }

    @Test
    void 上書きが無いプロジェクトはシステム設定の値を使う() {
        when(aiConnections.comfyUiBaseUrlOverride(8L)).thenReturn(null);

        assertThat(client.comfyUiBaseUrl(8L)).isEqualTo(SYSTEM);
    }

    @Test
    void projectIdが無ければai_serviceへ問い合わせずシステム設定の値を使う() {
        assertThat(client.comfyUiBaseUrl(null)).isEqualTo(SYSTEM);

        verify(aiConnections, never()).comfyUiBaseUrlOverride(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void ChatGPTのベースURLはシステム設定から取得する() {
        assertThat(client.chatGptBaseUrl()).isEqualTo("u");
    }

    @Test
    void ChatGPTのAPIキーはプロジェクトのキーだけを使いシステム設定へは落とさない() {
        when(aiConnections.openAiApiKey(7L)).thenReturn("sk-project-7");
        when(aiConnections.openAiApiKey(8L)).thenReturn(null);

        assertThat(client.chatGptApiKey(7L)).isEqualTo("sk-project-7");
        assertThat(client.chatGptApiKey(8L)).isNull();
    }

    @Test
    void projectIdが無ければai_serviceへ問い合わせずキーはnull() {
        assertThat(client.chatGptApiKey(null)).isNull();

        verify(aiConnections, never()).openAiApiKey(org.mockito.ArgumentMatchers.any());
    }
}
