package com.letsblog.identity.client;

import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.identity.service.ProjectNotFoundException;
import com.letsblog.identity.testsupport.FakeHttpServer;
import java.util.List;
import java.util.Optional;
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

/**
 * issue #1324 レビュー指摘: 環境紐付け後の補填はRabbitリスナースレッドで動き、バインドされたHTTP
 * リクエストが無い。以前の{@code ProjectServiceClient}/{@code PublishingServiceClient}は
 * {@code HttpServletRequest}のプロキシからAuthorizationを読むため、そのスレッドでは
 * 「No thread-bound request found」になり、トークンも無く401になる。モックだけの単体テストでは
 * 見えないので、<b>本物の</b>クライアントをリクエストの無い素のスレッドで動かして固定する
 * (media-serviceのOutboundAuthHeadersTestと同じ方針)。サービス自身のトークンは、実物の
 * {@code ServiceTokenClient}がKeycloakのトークンエンドポイント(サーバー側フェイク)から取る。
 */
@DisplayName("identity-service: リクエストの無いスレッドでの内部ブリッジ認証(issue #1324)")
class OutboundAuthHeadersTest {

    private static final String TOKEN_PATH = "/realms/letsblog/protocol/openid-connect/token";

    private FakeHttpServer server;
    private ServiceTokenClient serviceTokenClient;

    @BeforeEach
    void startServer() {
        server = new FakeHttpServer();
        // サービス自身のトークン(Client Credentials)を返すKeycloakのトークンエンドポイント。
        server.json("POST", TOKEN_PATH, "{\"access_token\":\"svc-token\",\"expires_in\":300}");
        serviceTokenClient = new ServiceTokenClient(
                RestClient.builder(), server.baseUrl() + TOKEN_PATH, "letsblog-services", "test-secret");
    }

    @AfterEach
    void clear() {
        server.close();
        RequestContextHolder.resetRequestAttributes();
    }

    /** 内部ブリッジへ届いた要求のAuthorization(トークンエンドポイントへの要求は除く)。 */
    private List<String> seenAuthorization() {
        return server.requests().stream()
                .filter(r -> !TOKEN_PATH.equals(r.path()))
                .map(r -> String.valueOf(r.header("Authorization")))
                .toList();
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

    private static final String SITE_JSON = "{\"id\":5,\"siteKey\":\"k\",\"name\":\"n\",\"baseUrl\":\"http://x\"}";

    @Test
    void リクエストの無いスレッドではサービス自身のトークンでサイトを照会する() throws Exception {
        server.json("GET", "/api/internal/project/sites/5", SITE_JSON);
        ProjectServiceClient client = new ProjectServiceClient(RestClient.builder(), server.baseUrl(), noRequestAuth());
        AtomicReference<Optional<ProjectServiceClient.SiteBridge>> result = new AtomicReference<>();

        onPlainThread(() -> result.set(client.getSite(5L)));

        assertEquals("k", result.get().orElseThrow().siteKey());
        assertEquals(List.of("Bearer svc-token"), seenAuthorization());
    }

    @Test
    void リクエストの無いスレッドでもサイトが無ければemptyになる() throws Exception {
        server.status("GET", "/api/internal/project/sites/6", 404, "{}");
        ProjectServiceClient client = new ProjectServiceClient(RestClient.builder(), server.baseUrl(), noRequestAuth());
        AtomicReference<Optional<ProjectServiceClient.SiteBridge>> result = new AtomicReference<>();

        onPlainThread(() -> result.set(client.getSite(6L)));

        assertTrue(result.get().isEmpty());
    }

    @Test
    void リクエストの無いスレッドでもプロジェクトが無ければProjectNotFoundになる() throws Exception {
        server.status("GET", "/api/internal/project/projects/6", 404, "{}");
        ProjectServiceClient client = new ProjectServiceClient(RestClient.builder(), server.baseUrl(), noRequestAuth());
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
        server.json("POST", "/api/internal/publishing/sites/k/authors", "{\"cmsAuthorId\":\"42\"}");
        PublishingServiceClient client = new PublishingServiceClient(RestClient.builder(), server.baseUrl(), noRequestAuth());
        AtomicReference<String> result = new AtomicReference<>();

        onPlainThread(() -> result.set(client.provisionAuthor("k",
                new PublishingServiceClient.AuthorProvisioningRequest(
                        "a@example.com", "author", "A", "B", "AB", null, null, "ja")).cmsAuthorId()));

        assertEquals("42", result.get());
        assertEquals(List.of("Bearer svc-token"), seenAuthorization());
    }

    @Test
    void リクエストがあればユーザーのBearerをそのまま転送する() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer user-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        server.json("GET", "/api/internal/project/sites/5", SITE_JSON);

        new ProjectServiceClient(RestClient.builder(), server.baseUrl(),
                new OutboundAuthHeaders(request, serviceTokenClient)).getSite(5L);

        assertEquals(List.of("Bearer user-token"), seenAuthorization());
        // ユーザーのBearerを転送するので、サービス自身のトークンは取りに行かない。
        assertTrue(server.requests("POST", TOKEN_PATH).isEmpty());
    }

    @Test
    void リクエストがありAuthorizationが無ければヘッダーを付けない() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        server.json("GET", "/api/internal/project/sites/5", SITE_JSON);

        new ProjectServiceClient(RestClient.builder(), server.baseUrl(),
                new OutboundAuthHeaders(request, serviceTokenClient)).getSite(5L);

        assertEquals(List.of("null"), seenAuthorization());
    }
}
