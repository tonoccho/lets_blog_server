package com.letsblog.media.client;

import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.media.service.ProjectNotFoundException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 内部ブリッジ呼び出しの認証(issue #1405 QA指摘)。
 *
 * <p>画像生成ジョブは{@code @Async}スレッドで動き、バインドされたHTTPリクエストが無い。
 * 以前は{@code ProjectServiceClient}が{@code HttpServletRequest}のプロキシから
 * Authorizationを読み、その非同期スレッドで「No thread-bound request found」になった
 * (モックだけの単体テストでは見えなかった)。ここでは<b>本物の</b>クライアントを、
 * リクエストの無い素のスレッドで動かして固定する。
 */
@DisplayName("media-service: リクエストの無いスレッドでの内部ブリッジ認証(issue #1405)")
class OutboundAuthHeadersTest {

    private final ServiceTokenClient serviceTokenClient = mock(ServiceTokenClient.class);
    private HttpServer server;
    private final List<String> seenAuthorization = new CopyOnWriteArrayList<>();
    private int status = 200;
    private String responseBody = "{}";

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenAuthorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, status == 404 ? -1 : bytes.length);
            if (status != 404) {
                exchange.getResponseBody().write(bytes);
            }
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

    private ProjectServiceClient projectClient(OutboundAuthHeaders auth) {
        return new ProjectServiceClient(RestClient.builder(), baseUrl(), auth);
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
        }, "image-generation-test");
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    void リクエストの無いスレッドではサービス自身のトークンでプロジェクトを確認する() throws Exception {
        when(serviceTokenClient.getAccessToken()).thenReturn("svc-token");
        responseBody = "{\"id\":5,\"name\":\"n\",\"slug\":\"s\"}";
        ProjectServiceClient client = projectClient(new OutboundAuthHeaders(new MockHttpServletRequest(), serviceTokenClient));

        onPlainThread(() -> client.requireProjectExists(5L));

        assertEquals(List.of("Bearer svc-token"), seenAuthorization);
    }

    @Test
    void リクエストの無いスレッドでも存在しないプロジェクトはProjectNotFoundになる() throws Exception {
        when(serviceTokenClient.getAccessToken()).thenReturn("svc-token");
        status = 404;
        ProjectServiceClient client = projectClient(new OutboundAuthHeaders(new MockHttpServletRequest(), serviceTokenClient));
        AtomicReference<Throwable> seen = new AtomicReference<>();

        Thread t = new Thread(() -> {
            RequestContextHolder.resetRequestAttributes();
            try {
                client.requireProjectExists(6L);
            } catch (Throwable e) {
                seen.set(e);
            }
        });
        t.start();
        t.join();

        assertEquals(ProjectNotFoundException.class, seen.get().getClass());
    }

    @Test
    void リクエストがあればユーザーのBearerをそのまま転送する() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer user-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        responseBody = "{\"id\":5,\"name\":\"n\",\"slug\":\"s\"}";

        projectClient(new OutboundAuthHeaders(request, serviceTokenClient)).requireProjectExists(5L);

        assertEquals(List.of("Bearer user-token"), seenAuthorization);
    }

    @Test
    void AiGenerationClientもリクエストの無いスレッドでサービス自身のトークンを使う() throws Exception {
        when(serviceTokenClient.getAccessToken()).thenReturn("svc-token");
        responseBody = "{\"result\":\"ok\"}";
        AiGenerationClient client = new AiGenerationClient(
                RestClient.builder(), baseUrl(), new OutboundAuthHeaders(new MockHttpServletRequest(), serviceTokenClient));
        AtomicReference<String> result = new AtomicReference<>();

        onPlainThread(() -> result.set(client.generate(null, "p", null)));

        assertEquals("ok", result.get());
        assertEquals(List.of("Bearer svc-token"), seenAuthorization);
    }
}
