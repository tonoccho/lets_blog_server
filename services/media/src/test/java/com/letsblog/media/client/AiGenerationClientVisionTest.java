package com.letsblog.media.client;

import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.media.ai.AiServiceException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1600: ai-serviceの内部ブリッジ{@code POST /api/internal/ai/generate-with-image}を呼ぶ
 * {@link AiGenerationClient#generateWithImage}。画像はbase64で本文に載せる。
 */
class AiGenerationClientVisionTest {

    private HttpServer server;
    private AiGenerationClient client;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "{\"result\":\"{\\\"tags\\\":[\\\"a\\\"]}\"}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respond);
        server.start();
        ServiceTokenClient tokens = Mockito.mock(ServiceTokenClient.class);
        Mockito.when(tokens.getAccessToken()).thenReturn("svc-token");
        client = new AiGenerationClient(
                RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(),
                new OutboundAuthHeaders(new MockHttpServletRequest(), tokens));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void respond(HttpExchange exchange) throws IOException {
        path.set(exchange.getRequestURI().getPath());
        body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void 画像をbase64で本文に載せてgenerate_with_imageを呼び結果を返す() {
        String result = client.generateWithImage(7L, "説明して", "image/jpeg", new byte[] {1, 2, 3});

        assertEquals("{\"tags\":[\"a\"]}", result);
        assertEquals("/api/internal/ai/generate-with-image", path.get());
        assertTrue(body.get().contains("\"projectId\":7"), body.get());
        assertTrue(body.get().contains("\"mimeType\":\"image/jpeg\""), body.get());
        assertTrue(body.get().contains("\"imageBase64\":\"AQID\""), body.get());
        assertTrue(body.get().contains("\"prompt\":\"説明して\""), body.get());
    }

    @Test
    void vision非対応でai_serviceがresultなしを返したらnull() {
        responseBody = "{\"result\":null}";

        assertNull(client.generateWithImage(7L, "説明して", "image/png", new byte[] {1}));
    }

    @Test
    void 空の応答はAiServiceException() {
        responseBody = "null";

        assertThrows(AiServiceException.class,
                () -> client.generateWithImage(7L, "説明して", "image/png", new byte[] {1}));
    }

    @Test
    void ai_serviceのエラーはAiServiceExceptionにする() {
        status = 502;
        responseBody = "{\"error\":\"x\"}";

        assertThrows(AiServiceException.class,
                () -> client.generateWithImage(7L, "説明して", "image/png", new byte[] {1}));
    }
}
