package com.letsblog.publishing.client;

import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.publishing.testsupport.FakeHttpServer;
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

/**
 * 一括インストールジョブ(issue #1702)の{@code @Async}スレッドにはバインドされたHTTPリクエストが無く、
 * {@code HttpServletRequest}のプロキシを読むと「No thread-bound request found」になる。リクエストの無い
 * スレッドではこのサービス自身のClient Credentialsで内部ブリッジを呼び、リクエスト中は従来どおり利用者の
 * Bearerを転送する(project-serviceの{@code OutboundAuthHeaders}、#1409/#1723と同じ)。
 */
@DisplayName("publishing-service: リクエストの無いスレッドでのProjectServiceClient認証(issue #1702)")
class ProjectServiceClientJobAuthTest {

    private static final String PROJECT_JSON =
            "{\"id\":7,\"localSiteId\":1,\"testSiteId\":2,\"productionSiteId\":3,\"masterEnvironment\":\"test\"}";
    private static final String SITE_JSON =
            "{\"id\":10,\"siteKey\":\"k\",\"name\":\"n\",\"baseUrl\":\"https://e.example\","
                    + "\"cmsType\":\"WORDPRESS\",\"managedWordpress\":false,\"wpSlug\":null}";

    /** サービス間トークンのエンドポイント(外部との通信)をサーバー側のフェイクで模し、ServiceTokenClientは実物を使う。 */
    private final FakeHttpServer tokenServer = new FakeHttpServer();
    private ServiceTokenClient serviceTokenClient;
    private final List<String> seenAuthorization = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        tokenServer.json("POST", ".*", "{\"access_token\":\"svc-token\",\"expires_in\":300}");
        serviceTokenClient = new ServiceTokenClient(RestClient.builder(), tokenServer.baseUrl(), "publishing", "secret");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenAuthorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            String body = exchange.getRequestURI().getPath().contains("/sites/") ? SITE_JSON : PROJECT_JSON;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        tokenServer.close();
        server.stop(0);
        RequestContextHolder.resetRequestAttributes();
    }

    private ProjectServiceClient client(MockHttpServletRequest request) {
        return new ProjectServiceClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(),
                new OutboundAuthHeaders(request, serviceTokenClient));
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
        }, "bulk-install-test");
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    @Test
    @DisplayName("リクエストの無いスレッドでは、サービス自身のトークンでプロジェクトとサイトを引く")
    void usesServiceTokenOutsideRequest() throws Exception {
        ProjectServiceClient client = client(new MockHttpServletRequest());
        AtomicReference<Long> siteId = new AtomicReference<>();

        onPlainThread(() -> {
            assertEquals(3L, client.getProject(7L).productionSiteId());
            siteId.set(client.getSite(10L).orElseThrow().id());
        });

        assertEquals(10L, siteId.get());
        assertEquals(List.of("Bearer svc-token", "Bearer svc-token"), seenAuthorization);
        assertEquals(1, tokenServer.requests("POST", ".*").size(), "トークンは取得して使い回す");
    }

    @Test
    @DisplayName("リクエスト中は、利用者のBearerをそのまま転送する(同期APIの挙動は変えない)")
    void forwardsUserBearerInsideRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer user-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        client(request).getProject(7L);

        assertEquals(List.of("Bearer user-token"), seenAuthorization);
    }

    @Test
    @DisplayName("リクエストにAuthorizationが無ければヘッダーを付けない")
    void noHeaderWhenRequestHasNone() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        client(request).getProject(7L);

        assertEquals(List.of("null"), seenAuthorization);
    }
}
