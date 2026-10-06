package com.letsblog.content.client;

import com.letsblog.common.auth.ServiceTokenClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 内部ブリッジ呼び出しの認証(issue #1409)。カスタムタグ生成ジョブは{@code @Async}スレッドで動き、
 * バインドされたHTTPリクエストが無い。{@code HttpServletRequest}のプロキシを読むと
 * 「No thread-bound request found」になる(media-serviceが#1405で踏んだ)ので、
 * リクエストの無いスレッドではこのサービス自身のClient Credentialsトークンを使う。
 * 本物のクライアントをリクエストの無い素のスレッドで動かして固定する。
 */
@DisplayName("content-service: リクエストの無いスレッドでの内部ブリッジ認証(issue #1409)")
class OutboundAuthHeadersTest {

    private final ServiceTokenClient serviceTokenClient = mock(ServiceTokenClient.class);
    private HttpServer server;
    private final List<String> seenAuthorization = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenAuthorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] bytes = "{\"result\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void clear() {
        server.stop(0);
        RequestContextHolder.resetRequestAttributes();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void onPlainThread(Runnable body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            RequestContextHolder.resetRequestAttributes();
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "custom-tag-generation-test");
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    @DisplayName("リクエストの無いスレッドではサービス自身のトークンでai-serviceを呼ぶ")
    void usesServiceTokenOutsideRequest() throws Exception {
        when(serviceTokenClient.getAccessToken()).thenReturn("svc-token");
        AiGenerationClient client = new AiGenerationClient(
                RestClient.builder(), baseUrl(), new OutboundAuthHeaders(new MockHttpServletRequest(), serviceTokenClient));
        AtomicReference<String> result = new AtomicReference<>();

        onPlainThread(() -> result.set(client.generate(null, "p", null)));

        assertEquals("ok", result.get());
        assertEquals(List.of("Bearer svc-token"), seenAuthorization);
    }

    @Test
    @DisplayName("リクエストがあればユーザーのBearerをそのまま転送する(同期APIの挙動は変えない)")
    void forwardsUserBearerInsideRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer user-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        AiGenerationClient client = new AiGenerationClient(
                RestClient.builder(), baseUrl(), new OutboundAuthHeaders(request, serviceTokenClient));

        assertEquals("ok", client.generate(null, "p", null));

        assertEquals(List.of("Bearer user-token"), seenAuthorization);
    }
}
