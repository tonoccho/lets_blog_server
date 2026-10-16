package com.letsblog.media.testsupport;

import com.letsblog.media.testsupport.FakeHttpServer.Response;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * {@code @SpringBootTest}の外部サービス(identity・publishing・ai・project・platform・Keycloakのトークン)を演じる、
 * JVMで共有するHTTPのサーバー側フェイク(issue #1820)。{@code @MockitoBean}で自前の{@code *Client}を差し替える代わりに、
 * {@code app.*-service-uri}をここへ向け、実物のクライアントがHTTPで通信する。
 *
 * <p>サーバーは1つなので、同じ設定のテストクラス間でSpringのコンテキストが再利用される。使うテストは
 * {@code @DynamicPropertySource}から{@link #register}を呼び、{@code @BeforeEach}で{@link #reset()}してから
 * 必要な応答を登録する。
 */
public final class ServiceStubs {

    public static final FakeHttpServer HTTP = new FakeHttpServer();

    private ServiceStubs() {
    }

    /** {@code @DynamicPropertySource}から呼ぶ。外部サービスの接続先をフェイクへ向ける。 */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("app.identity-service-uri", HTTP::baseUrl);
        registry.add("app.publishing-service-uri", HTTP::baseUrl);
        registry.add("app.ai-service-uri", HTTP::baseUrl);
        registry.add("app.project-service-uri", HTTP::baseUrl);
        registry.add("app.platform-service-uri", HTTP::baseUrl);
        registry.add("keycloak.admin.token-uri", () -> HTTP.baseUrl() + ServiceTokenFixtures.TOKEN_PATH);
    }

    /** 応答と記録を消し、トークンエンドポイントだけを登録し直す。 */
    public static void reset() {
        HTTP.reset();
        HTTP.on("POST", ServiceTokenFixtures.TOKEN_PATH, Response.ok(
                "{\"access_token\":\"" + ServiceTokenFixtures.TOKEN + "\",\"expires_in\":3600}"));
    }

    /** identity-serviceが、このAuthorizationの操作者を{@code userId}/{@code role}として返す。 */
    public static void profile(String authorization, long userId, String role) {
        HTTP.on("GET", "/api/identity/me", request -> authorization.equals(request.header("Authorization"))
                ? Response.ok("{\"id\":" + userId + ",\"role\":\"" + role + "\"}")
                : null);
    }

    /** identity-serviceが、このAuthorizationの操作者を解決できない(無効化ユーザー: 403)と答える。 */
    public static void profileRejected(String authorization) {
        HTTP.on("GET", "/api/identity/me", request -> authorization.equals(request.header("Authorization"))
                ? Response.json(403, "{\"error\":\"forbidden\"}")
                : null);
    }

    /** identity-serviceが、{@code userId}は{@code projectId}のメンバーかどうかを答える。 */
    public static void member(long projectId, long userId, boolean member) {
        HTTP.json("GET", "/api/internal/identity/projects/" + projectId + "/members/" + userId,
                String.valueOf(member));
    }

    /** {@code /api/identity/me}への、指定のAuthorizationでの呼び出し回数。 */
    public static int profileLookups(String authorization) {
        return (int) HTTP.requests("GET", "/api/identity/me").stream()
                .filter(request -> authorization.equals(request.header("Authorization"))).count();
    }
}
