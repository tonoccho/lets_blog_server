package com.letsblog.identity.client;

import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.identity.service.ProjectNotFoundException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * issue #1324 レビュー指摘: 環境紐付け後の補填はRabbitリスナースレッドで動き、バインドされたHTTP
 * リクエストが無い。以前の{@code ProjectServiceClient}/{@code PublishingServiceClient}は
 * {@code HttpServletRequest}のプロキシからAuthorizationを読むため、そのスレッドでは
 * 「No thread-bound request found」になり、トークンも無く401になる。モックだけの単体テストでは
 * 見えないので、<b>本物の</b>クライアントをリクエストの無い素のスレッドで動かして固定する
 * (media-serviceのOutboundAuthHeadersTestと同じ方針)。
 */
@DisplayName("identity-service: リクエストの無いスレッドでの内部ブリッジ認証(issue #1324)")
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

    private OutboundAuthHeaders noRequestAuth() {
        return new OutboundAuthHeaders(new MockHttpServletRequest(), serviceTokenClient);
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
        }, "rabbit-listener-test");
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    void リクエストの無いスレッドではサービス自身のトークンでサイトを照会する() throws Exception {
        when(serviceTokenClient.getAccessToken()).thenReturn("svc-token");
        responseBody = "{\"id\":5,\"siteKey\":\"k\",\"name\":\"n\",\"baseUrl\":\"http://x\"}";
        ProjectServiceClient client = new ProjectServiceClient(RestClient.builder(), baseUrl(), noRequestAuth());
        AtomicReference<Optional<ProjectServiceClient.SiteBridge>> result = new AtomicReference<>();

        onPlainThread(() -> result.set(client.getSite(5L)));

        assertEquals("k", result.get().orElseThrow().siteKey());
        assertEquals(List.of("Bearer svc-token"), seenAuthorization);
    }

    @Test
    void リクエストの無いスレッドでもサイトが無ければemptyになる() throws Exception {
        when(serviceTokenClient.getAccessToken()).thenReturn("svc-token");
        status = 404;
        ProjectServiceClient client = new ProjectServiceClient(RestClient.builder(), baseUrl(), noRequestAuth());
        AtomicReference<Optional<ProjectServiceClient.SiteBridge>> result = new AtomicReference<>();

        onPlainThread(() -> result.set(client.getSite(6L)));

        assertTrue(result.get().isEmpty());
    }

    @Test
    void リクエストの無いスレッドでもプロジェクトが無ければProjectNotFoundになる() throws Exception {
        when(serviceTokenClient.getAccessToken()).thenReturn("svc-token");
        status = 404;
        ProjectServiceClient client = new ProjectServiceClient(RestClient.builder(), baseUrl(), noRequestAuth());
        AtomicReference<Throwable> seen = new AtomicReference<>();

        Thread t = new Thread(() -> {
            RequestContextHolder.resetRequestAttributes();
            try {
                client.getProject(6L);
            } catch (Throwable e) {
                seen.set(e);
            }
        });
        t.start();
        t.join();

        assertEquals(ProjectNotFoundException.class, seen.get().getClass());
    }

    @Test
    void リクエストの無いスレッドではサービス自身のトークンで著者を作る() throws Exception {
        when(serviceTokenClient.getAccessToken()).thenReturn("svc-token");
        responseBody = "{\"cmsAuthorId\":\"42\"}";
        PublishingServiceClient client = new PublishingServiceClient(RestClient.builder(), baseUrl(), noRequestAuth());
        AtomicReference<String> result = new AtomicReference<>();

        onPlainThread(() -> result.set(client.provisionAuthor("k",
                new PublishingServiceClient.AuthorProvisioningRequest(
                        "a@example.com", "author", "A", "B", "AB", null, null, "ja")).cmsAuthorId()));

        assertEquals("42", result.get());
        assertEquals(List.of("Bearer svc-token"), seenAuthorization);
    }

    @Test
    void リクエストがあればユーザーのBearerをそのまま転送する() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer user-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        responseBody = "{\"id\":5,\"siteKey\":\"k\",\"name\":\"n\",\"baseUrl\":\"http://x\"}";

        new ProjectServiceClient(RestClient.builder(), baseUrl(),
                new OutboundAuthHeaders(request, serviceTokenClient)).getSite(5L);

        assertEquals(List.of("Bearer user-token"), seenAuthorization);
    }

    @Test
    void リクエストがありAuthorizationが無ければヘッダーを付けない() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        responseBody = "{\"id\":5,\"siteKey\":\"k\",\"name\":\"n\",\"baseUrl\":\"http://x\"}";

        new ProjectServiceClient(RestClient.builder(), baseUrl(),
                new OutboundAuthHeaders(request, serviceTokenClient)).getSite(5L);

        assertEquals(List.of("null"), seenAuthorization);
    }
}
