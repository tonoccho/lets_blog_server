package com.letsblog.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.ai.LlmConfigProvider;
import com.letsblog.common.auth.ServiceTokenUnavailableException;
import com.letsblog.common.auth.ToolsTokenProvider;
import com.letsblog.common.net.GuardedTarget;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** モデル一覧の取得でextensionsのOllamaへ付けるトークン(#1804)。 */
class ProviderModelCatalogToolsTokenTest {

    private HttpServer server;
    private String root;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(header == null ? "(none)" : header);
            byte[] bytes = "{\"models\":[{\"name\":\"llama3\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        root = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private ProviderModelCatalog catalog(boolean systemTarget, ToolsTokenProvider tools) {
        LlmConfigProvider config = mock(LlmConfigProvider.class);
        when(config.targetFor(AiProvider.OLLAMA)).thenReturn(new GuardedTarget(root, null));
        when(config.toolsAuthorized(AiProvider.OLLAMA)).thenReturn(systemTarget);
        return new ProviderModelCatalog(config, Duration.ofSeconds(3), tools);
    }

    @Test
    @DisplayName("システム設定の接続先への一覧取得に、専用クライアントのトークンが付く")
    void システム設定の接続先にはトークンが付く() {
        Optional<List<String>> models = catalog(true, ToolsTokenProvider.fromSupplier(() -> "tok-1")).fetch(AiProvider.OLLAMA);

        assertThat(models).contains(List.of("llama3"));
        assertThat(authorizations).containsExactly("Bearer tok-1");
    }

    @Test
    @DisplayName("プロジェクト単位の上書き先には付かない")
    void 上書き先には付かない() {
        catalog(false, ToolsTokenProvider.fromSupplier(() -> "tok-1")).fetch(AiProvider.OLLAMA);

        assertThat(authorizations).containsExactly("(none)");
    }

    @Test
    @DisplayName("secret未設定(無効)なら付かない")
    void 無効なら付かない() {
        catalog(true, ToolsTokenProvider.disabled()).fetch(AiProvider.OLLAMA);

        assertThat(authorizations).containsExactly("(none)");
    }

    @Test
    @DisplayName("トークンが取れないときは、問い合わせず空を返す(一覧はシステム設定へ戻る。理由はログに出る)")
    void 取れないときは問い合わせない() {
        Optional<List<String>> models = catalog(true, ToolsTokenProvider.fromSupplier(() -> {
            throw new ServiceTokenUnavailableException("down");
        })).fetch(AiProvider.OLLAMA);

        assertThat(models).isEmpty();
        assertThat(authorizations).isEmpty();
    }
}
