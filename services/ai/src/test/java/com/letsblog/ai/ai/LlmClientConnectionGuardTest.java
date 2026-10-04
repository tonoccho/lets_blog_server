package com.letsblog.ai.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.letsblog.common.net.ForbiddenDestinationException;
import com.letsblog.common.net.GuardedTarget;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 接続時検査で拒否されたOllama接続先へ、LlmClientは接続せずに生成を失敗させる(issue #1547)。 */
class LlmClientConnectionGuardTest {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            hits.incrementAndGet();
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private LlmConfigProvider provider(java.util.function.Supplier<GuardedTarget> target) {
        LlmConfigProvider provider = mock(LlmConfigProvider.class);
        when(provider.provider()).thenReturn(AiProvider.OLLAMA);
        when(provider.defaultModelFor(AiProvider.OLLAMA)).thenReturn("m");
        when(provider.requestTimeoutSeconds()).thenReturn(5L);
        when(provider.targetFor(AiProvider.OLLAMA)).thenAnswer(inv -> target.get());
        return provider;
    }

    @Test
    void forbiddenDestinationFailsGenerationWithoutConnecting() throws Exception {
        String url = startServer();
        LlmClient client = new LlmClient(provider(() -> {
            throw new ForbiddenDestinationException(
                    "プロジェクト設定の「Ollama の接続先」がサーバ内部のネットワーク(または禁止された宛先)のため接続しませんでした");
        }));
        assertThatThrownBy(() -> client.generate("hi"))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("Ollama")
                .hasMessageContaining("サーバ内部のネットワーク");
        assertThat(hits.get()).isZero();
        assertThat(url).isNotBlank();
    }

    @Test
    void allowedTargetIsConnectedTo() throws Exception {
        String url = startServer();
        LlmClient client = new LlmClient(provider(() -> new GuardedTarget(url, null)));
        assertThat(client.generate("hi")).isEqualTo("ok");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void defaultTargetForFallsBackToBaseUrlFor() throws Exception {
        String url = startServer();
        LlmConfigProvider plain = new LlmConfigProvider() {
            public String baseUrl() { return url; }
            public String apiKey() { return ""; }
            public String defaultModel() { return "m"; }
            public long requestTimeoutSeconds() { return 5; }
            public AiProvider provider() { return AiProvider.OLLAMA; }
            public String apiKeyFor(AiProvider p) { return ""; }
            public String defaultModelFor(AiProvider p) { return "m"; }
            public String baseUrlFor(AiProvider p) { return url; }
            public java.util.List<String> availableModels() { return java.util.List.of(); }
            public java.util.List<String> availableModelsFor(AiProvider p) { return java.util.List.of(); }
        };
        GuardedTarget target = plain.targetFor(AiProvider.OLLAMA);
        assertThat(target.baseUrl()).isEqualTo(url);
        assertThat(target.sniHost()).isNull();
        assertThat(new LlmClient(plain).generate("hi")).isEqualTo("ok");
    }
}
