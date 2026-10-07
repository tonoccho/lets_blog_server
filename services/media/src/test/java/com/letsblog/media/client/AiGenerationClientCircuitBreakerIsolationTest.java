package com.letsblog.media.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.letsblog.media.ai.AiServiceException;
import com.letsblog.common.auth.ServiceTokenClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

/**
 * issue #1665: テスト間でai-serviceのサーキットブレーカー状態が漏れないこと。
 * 既定ではブレーカーはJVM全体で{@code "ai-service"}をキーに共有されるため、あるテストが5xxで
 * 開かせると、別のテストが作った新しいクライアントも「circuit breaker open」で失敗する。
 * テストは専用の{@link CircuitBreakerRegistry}を渡して状態を切り離す。
 */
class AiGenerationClientCircuitBreakerIsolationTest {

    private volatile int status;
    private HttpServer server;
    private OutboundAuthHeaders auth;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        ServiceTokenClient tokens = Mockito.mock(ServiceTokenClient.class);
        Mockito.when(tokens.getAccessToken()).thenReturn("svc-token");
        auth = new OutboundAuthHeaders(new MockHttpServletRequest(), tokens);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = "{\"result\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private AiGenerationClient newClient() {
        return new AiGenerationClient(
                RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(), auth,
                CircuitBreakerRegistry.ofDefaults());
    }

    @Test
    void あるクライアントが5xxでブレーカーを開いても_新しいクライアントは影響を受けない() {
        status = 500;
        AiGenerationClient failing = newClient();
        for (int i = 0; i < 10; i++) {
            assertThrows(AiServiceException.class, () -> failing.generate(null, "p", null));
        }

        status = 200;

        assertEquals("ok", newClient().generate(null, "p", null));
    }
}
