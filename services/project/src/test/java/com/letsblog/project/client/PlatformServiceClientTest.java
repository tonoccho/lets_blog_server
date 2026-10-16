package com.letsblog.project.client;

import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.project.service.PlatformServiceUnavailableException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** issue #1858: platform-serviceのgitlab-configを読むクライアント。 */
@DisplayName("project-service: PlatformServiceClient(issue #1858)")
class PlatformServiceClientTest {

    private HttpServer server;
    private ServiceTokenClient serviceTokenClient;
    private final List<String> seenAuthorization = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> seenPath = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String body = "{\"baseUrl\":\"https://gitlab.example.com\",\"configured\":true}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            respond(exchange, 200, "{\"access_token\":\"svc-token\",\"expires_in\":3600}");
        });
        server.createContext("/", exchange -> {
            seenAuthorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            seenPath.set(exchange.getRequestURI().getPath());
            respond(exchange, status, body);
        });
        server.start();
        serviceTokenClient = new ServiceTokenClient(RestClient.builder(), baseUrl() + "/token", "project-service", "secret");
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    @AfterEach
    void clear() {
        server.stop(0);
        RequestContextHolder.resetRequestAttributes();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private PlatformServiceClient client(String uri, MockHttpServletRequest request) {
        return new PlatformServiceClient(RestClient.builder(), uri, new OutboundAuthHeaders(request, serviceTokenClient));
    }

    private PlatformServiceClient clientInRequest(String bearer) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, bearer);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return client(baseUrl(), request);
    }

    @Test
    @DisplayName("設定済みなら値とconfigured=trueを返し、リクエスト中は呼び出し元のBearerを転送する")
    void configuredForwardsBearer() {
        PlatformServiceClient.GitLabConfig config = clientInRequest("Bearer user-token").gitLabConfig();

        assertEquals("https://gitlab.example.com", config.baseUrl());
        assertTrue(config.configured());
        assertEquals("/api/internal/platform/gitlab-config", seenPath.get());
        assertEquals(List.of("Bearer user-token"), seenAuthorization);
    }

    @Test
    @DisplayName("未設定ならbaseUrl=null・configured=falseを返す(200)")
    void notConfigured() {
        body = "{\"baseUrl\":null,\"configured\":false}";

        PlatformServiceClient.GitLabConfig config = clientInRequest("Bearer t").gitLabConfig();

        assertNull(config.baseUrl());
        assertFalse(config.configured());
    }

    @Test
    @DisplayName("リクエストが無いスレッドではサービスのトークンを付ける")
    void serviceTokenOutsideRequest() throws Exception {
        PlatformServiceClient client = client(baseUrl(), new MockHttpServletRequest());
        AtomicReference<PlatformServiceClient.GitLabConfig> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            RequestContextHolder.resetRequestAttributes();
            try {
                result.set(client.gitLabConfig());
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        thread.start();
        thread.join();

        assertNull(failure.get());
        assertTrue(result.get().configured());
        assertEquals(List.of("Bearer svc-token"), seenAuthorization);
    }

    @Test
    @DisplayName("2xx以外の応答は PlatformServiceUnavailableException")
    void non2xx() {
        status = 500;
        body = "{}";

        assertThrows(PlatformServiceUnavailableException.class, () -> clientInRequest("Bearer t").gitLabConfig());
    }

    @Test
    @DisplayName("応答が空なら PlatformServiceUnavailableException(理由つき)")
    void emptyBody() {
        body = "";

        PlatformServiceUnavailableException e =
                assertThrows(PlatformServiceUnavailableException.class, () -> clientInRequest("Bearer t").gitLabConfig());

        assertTrue(e.getMessage().contains("空の応答"));
    }

    @Test
    @DisplayName("接続できなければ PlatformServiceUnavailableException")
    void connectionRefused() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer t");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        PlatformServiceClient client = client("http://127.0.0.1:1", request);

        assertThrows(PlatformServiceUnavailableException.class, client::gitLabConfig);
    }
}
