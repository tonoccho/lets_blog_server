package com.letsblog.project.client;

import com.letsblog.common.auth.ServiceTokenClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ジョブのスレッド(HTTPリクエスト無し)から呼ぶサービス間ブリッジの認証(issue #1723)。
 * 取り置いた利用者のBearerは5分で切れるので、リクエストの無いスレッドではこのサービス自身の
 * Client Credentialsを使い、リクエスト中は従来どおり利用者のBearerを転送する(#1083, #1409と同じ)。
 */
@DisplayName("project-service: ジョブのスレッドでのブリッジ認証(issue #1723)")
class BridgeClientJobAuthTest {

    private final ServiceTokenClient serviceTokenClient = mock(ServiceTokenClient.class);
    private final List<String> seenAuthorization = new CopyOnWriteArrayList<>();
    private HttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        when(serviceTokenClient.getAccessToken()).thenReturn("svc-token");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenAuthorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] bytes = "[1,2]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        RequestContextHolder.resetRequestAttributes();
        BearerScope.call(null, () -> null);
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private IdentityBridgeClient identity() {
        return new IdentityBridgeClient(RestClient.builder(), baseUrl(), serviceTokenClient);
    }

    private CmsProvisioningBridgeClient cms(MockHttpServletRequest request) {
        return new CmsProvisioningBridgeClient(RestClient.builder(), baseUrl(), request, serviceTokenClient);
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
        }, "job-thread-test");
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private static MockHttpServletRequest requestWith(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    @Test
    @DisplayName("identity: リクエストの無いスレッドで利用者のBearerが無ければ、サービス自身のトークンでロール再整合を呼ぶ")
    void identityUsesServiceTokenOnJobThread() throws Exception {
        onPlainThread(() -> identity().reconcileRolesForSite(1L, 2L, null));

        assertEquals(List.of("Bearer svc-token"), seenAuthorization);
    }

    @Test
    @DisplayName("identity: 空白のBearerもサービス自身のトークンに置き換える")
    void identityBlankBearerOnJobThread() throws Exception {
        onPlainThread(() -> identity().reconcileRolesForSite(1L, 2L, " "));

        assertEquals(List.of("Bearer svc-token"), seenAuthorization);
    }

    @Test
    @DisplayName("identity: リクエスト中は、渡された利用者のBearerをそのまま転送する(同期APIの挙動は変えない)")
    void identityForwardsUserBearerInsideRequest() {
        requestWith("Bearer user");

        identity().reconcileRolesForSite(1L, 2L, "Bearer user");

        assertEquals(List.of("Bearer user"), seenAuthorization);
    }

    @Test
    @DisplayName("identity: リクエスト中にBearerが無ければ、サービス自身のトークンへは置き換えずヘッダーを付けない")
    void identityNoSubstitutionInsideRequest() {
        requestWith(null);

        identity().reconcileRolesForSite(1L, 2L, null);

        assertEquals(List.of("null"), seenAuthorization);
    }

    @Test
    @DisplayName("identity: ロール再整合以外(メンバー判定)は、リクエスト外でもBearerが無ければヘッダーを付けない")
    void identityOtherCallsUnchanged() throws Exception {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            seenAuthorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] bytes = "true".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        onPlainThread(() -> identity().isProjectMember(1L, 2L, null));

        assertEquals(List.of("null"), seenAuthorization);
    }

    @Test
    @DisplayName("cms: リクエストの無いスレッドでBearerScopeも無ければ、サービス自身のトークンでエクスポートを呼ぶ")
    void cmsUsesServiceTokenOnJobThread() throws Exception {
        CmsProvisioningBridgeClient client = cms(new MockHttpServletRequest());

        onPlainThread(() -> {
            client.exportMedia(Map.of("wpSlug", "s"));
            client.exportThemes(Map.of("wpSlug", "s"));
        });

        assertEquals(List.of("Bearer svc-token", "Bearer svc-token"), seenAuthorization);
    }

    @Test
    @DisplayName("cms: BearerScopeの中なら取り置いたBearerを優先する(#1558の挙動は変えない)")
    void cmsPrefersBearerScope() throws Exception {
        CmsProvisioningBridgeClient client = cms(new MockHttpServletRequest());

        onPlainThread(() -> BearerScope.call("Bearer scoped", () -> client.exportMedia(Map.of("wpSlug", "s"))));

        assertEquals(List.of("Bearer scoped"), seenAuthorization);
    }

    @Test
    @DisplayName("cms: リクエスト中は、リクエストの利用者のBearerを転送する(同期API)")
    void cmsForwardsRequestBearer() {
        CmsProvisioningBridgeClient client = cms(requestWith("Bearer user"));

        assertArrayEquals("[1,2]".getBytes(StandardCharsets.UTF_8), client.exportMedia(Map.of("wpSlug", "s")));

        assertEquals(List.of("Bearer user"), seenAuthorization);
    }

    @Test
    @DisplayName("cms: リクエスト中にAuthorizationが無ければヘッダーを付けない")
    void cmsNoHeaderInsideRequestWithoutBearer() {
        CmsProvisioningBridgeClient client = cms(requestWith(null));

        client.exportMedia(Map.of("wpSlug", "s"));

        assertEquals(List.of("null"), seenAuthorization);
    }
}
