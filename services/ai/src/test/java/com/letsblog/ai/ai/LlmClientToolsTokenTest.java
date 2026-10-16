package com.letsblog.ai.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.letsblog.common.auth.ServiceTokenUnavailableException;
import com.letsblog.common.auth.ToolsTokenProvider;
import com.letsblog.common.net.GuardedTarget;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LLMの生成でextensionsのOllamaへ付けるトークン(#1804)。システム設定の接続先にだけ付け、プロジェクト単位の
 * 上書き先・secret未設定のときは付けない。secretがあるのに取れないときは、送らずに理由の分かるエラーにする。
 */
class LlmClientToolsTokenTest {

    private HttpServer server;
    private String url;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(header == null ? "(none)" : header);
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private LlmConfigProvider provider(AiProvider provider, boolean systemTarget, String apiKey) {
        LlmConfigProvider config = mock(LlmConfigProvider.class);
        when(config.provider()).thenReturn(provider);
        when(config.defaultModelFor(provider)).thenReturn("m");
        when(config.apiKeyFor(provider)).thenReturn(apiKey);
        when(config.requestTimeoutSeconds()).thenReturn(5L);
        when(config.targetFor(provider)).thenReturn(new GuardedTarget(url, null));
        when(config.toolsAuthorized(provider)).thenReturn(systemTarget);
        return config;
    }

    @Test
    @DisplayName("システム設定のOllamaへの生成に、専用クライアントのトークンが付く")
    void システム設定の接続先にはトークンが付く() {
        LlmClient client = new LlmClient(provider(AiProvider.OLLAMA, true, ""), ToolsTokenProvider.fromSupplier(() -> "tok-1"));

        assertThat(client.generate("hi")).isEqualTo("ok");
        assertThat(authorizations).containsExactly("Bearer tok-1");
    }

    @Test
    @DisplayName("プロジェクト単位の上書き先には付かない")
    void 上書き先には付かない() {
        LlmClient client = new LlmClient(provider(AiProvider.OLLAMA, false, ""), ToolsTokenProvider.fromSupplier(() -> "tok-1"));

        assertThat(client.generate("hi")).isEqualTo("ok");
        assertThat(authorizations).containsExactly("(none)");
    }

    @Test
    @DisplayName("secret未設定(無効)なら付かない。従来と同じ")
    void 無効なら付かない() {
        LlmClient client = new LlmClient(provider(AiProvider.OLLAMA, true, ""), ToolsTokenProvider.disabled());

        assertThat(client.generate("hi")).isEqualTo("ok");
        assertThat(authorizations).containsExactly("(none)");
    }

    @Test
    @DisplayName("トークンが取れないときは、ヘッダー無しで送らず、理由の分かるエラーにする")
    void 取れないときはエラー() {
        LlmClient client = new LlmClient(provider(AiProvider.OLLAMA, true, ""),
                ToolsTokenProvider.fromSupplier(() -> {
                    throw new ServiceTokenUnavailableException("サービストークンの取得に失敗しました(client_id=letsblog-tools-client): 401");
                }));

        assertThatThrownBy(() -> client.generate("hi"))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("KEYCLOAK_TOOLS_CLIENT_SECRET")
                .hasMessageContaining("401")
                .hasMessageNotContaining("タイムアウト");
        assertThat(authorizations).isEmpty();
    }

    @Test
    @DisplayName("ChatGPT(OpenAI)にはtoolsのトークンを付けず、プロジェクトのAPIキーのままである")
    void OpenAIはAPIキーのまま() {
        LlmClient client = new LlmClient(provider(AiProvider.OPENAI, false, "sk-project"), ToolsTokenProvider.fromSupplier(() -> "tok-1"));

        assertThat(client.generate("hi", null, AiProvider.OPENAI)).isEqualTo("ok");
        assertThat(authorizations).containsExactly("Bearer sk-project");
    }
}
