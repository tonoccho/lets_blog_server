package com.letsblog.platform.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.letsblog.common.auth.ServiceTokenUnavailableException;
import com.letsblog.common.auth.ToolsTokenProvider;
import com.letsblog.platform.testsupport.InMemoryRepositories;
import com.letsblog.platform.testsupport.PlatformWorld;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ポータルからの取り込み(JDKのHttpClientを直接使う経路)で付けるトークン(#1804)。
 * 取り込みは管理者だけが実行でき、ポータルのURLは接続先の検査(ExtensionsPortalUrlPolicy)を通ったものだけを呼ぶ。
 */
class ExtensionsConnectionImportServiceToolsTokenTest {

    /** identity-serviceはHTTPのフェイクで模し、AdminAuthorizationService・AppSettingServiceは実物を通す(issue #1825)。 */
    private final PlatformWorld world = new PlatformWorld().actingAsAdmin(1L);
    private final AdminAuthorizationService adminAuthorizationService = world.adminAuthorizationService;
    private final AppSettingService appSettingService = new AppSettingService(
            InMemoryRepositories.systemSettings(), world.credentialCipher, adminAuthorizationService,
            "gpt-4o-mini", "gpt-4o-mini,gpt-4o", "120",
            "OPENAI", "claude-3-5-haiku-20241022", "qwen2.5:7b-instruct",
            "smtp.example.com", "587", "env-user", "env-pass", "noreply@example.com",
            "http://localhost:3000", "10", "wp-admin");

    private HttpServer server;
    private String portalUrl;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/connections", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(header == null ? "(none)" : header);
            byte[] bytes = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        portalUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        world.close();
    }

    private ExtensionsConnectionImportService service(ToolsTokenProvider tools) {
        ExtensionsPortalUrlPolicy permissive = new ExtensionsPortalUrlPolicy(
                host -> new InetAddress[] {InetAddress.getByName("192.168.1.20")});
        return new ExtensionsConnectionImportService(
                appSettingService, adminAuthorizationService, permissive, Duration.ofMillis(800), tools);
    }

    @Test
    @DisplayName("有効なら、ポータルへの取得に専用クライアントのトークンが付く")
    void 有効ならトークンが付く() {
        service(ToolsTokenProvider.fromSupplier(() -> "tok-1"))
                .fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC);

        assertEquals(List.of("Bearer tok-1"), authorizations);
    }

    @Test
    @DisplayName("secret未設定(無効)なら付かない。従来と同じ")
    void 無効なら付かない() {
        service(ToolsTokenProvider.disabled()).fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC);

        assertEquals(List.of("(none)"), authorizations);
    }

    @Test
    @DisplayName("トークンが取れないときは、送らずに理由の分かるエラーにする")
    void 取れないときはエラー() {
        ExtensionsConnectionImportService failing = service(ToolsTokenProvider.fromSupplier(() -> {
            throw new ServiceTokenUnavailableException("サービストークンの取得に失敗しました(client_id=letsblog-tools-client): 401");
        }));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> failing.fetch(portalUrl, ExtensionsConnectionImportService.Route.SAME_PC));

        assertTrue(e.getMessage().contains("KEYCLOAK_TOOLS_CLIENT_SECRET"), e.getMessage());
        assertTrue(e.getMessage().contains("401"), e.getMessage());
        assertEquals(0, authorizations.size());
    }
}
