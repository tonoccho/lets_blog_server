package com.letsblog.content.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.List;

/**
 * Spring Bootを起動する結合テストで、他サービス(identity-service・project-service)のHTTPを受けるサーバー側フェイク
 * (issue #1822)。{@link FakeHttpServer}をJVMで1台だけ起動して使い回す(コンテキストのキャッシュを壊さないよう、
 * ポートはテストごとに変えない)。{@code IdentityClient}・{@code IdentityBridgeClient}・{@code ProjectBridgeClient}は
 * 実物のBeanのまま、向き先({@code app.identity-service-uri} / {@code app.project-service-uri})だけをこのサーバーへ向ける。
 *
 * <p>Mockitoは使わない。テストごとに{@link #reset()}で応答と記録を初期化する。
 */
public final class ExternalServices {

    private static final FakeHttpServer HTTP = new FakeHttpServer();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(HTTP::close));
    }

    private ExternalServices() {
    }

    /** {@code @DynamicPropertySource}から呼ぶ。他サービスのベースURLを、このフェイクへ向ける。 */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("app.identity-service-uri", HTTP::baseUrl);
        registry.add("app.project-service-uri", HTTP::baseUrl);
    }

    /** 応答と記録を消し、既定の応答(認証ヘッダーの無い操作者は解決できない・メンバーではない・タグデザインとプロジェクトのslugは未設定)を入れる。 */
    public static void reset() {
        HTTP.reset();
        SharedCircuitBreakers.reset();
        HTTP.status("GET", "/api/identity/me", 401, "{\"error\":\"unauthorized\"}");
        HTTP.json("GET", "/api/internal/identity/projects/\\d+/members/\\d+", "false");
        HTTP.json("GET", "/api/internal/project/tag-design/(TOC|BLOGCARD|AMAZON)",
                "{\"backgroundColor\":null,\"textColor\":null,\"accentColor\":null,"
                        + "\"customCss\":null,\"htmlTemplate\":null}");
        // プロジェクトのslugは未設定(cssSelectorPrefixのフォールバックはnullになる)。
        HTTP.json("GET", "/api/internal/project/projects/\\d+", "{}");
    }

    /** {@code authorization}({@code Bearer ...}の全体)で来た操作者を、identity-serviceが{@code userId}・{@code role}と答える。 */
    public static void actor(String authorization, long userId, String role) {
        HTTP.on("GET", "/api/identity/me", request -> authorization.equals(request.header("Authorization"))
                ? FakeHttpServer.Response.ok("{\"id\":" + userId + ",\"role\":\"" + role + "\"}")
                : null);
    }

    /** identity-serviceが、{@code authorization}の操作者を無効化されたユーザーとして401で拒む。 */
    public static void disabledActor(String authorization) {
        HTTP.on("GET", "/api/identity/me", request -> authorization.equals(request.header("Authorization"))
                ? FakeHttpServer.Response.json(401, "{\"error\":\"disabled\"}")
                : null);
    }

    public static void projectMember(long projectId, long userId, boolean member) {
        HTTP.json("GET", "/api/internal/identity/projects/" + projectId + "/members/" + userId,
                Boolean.toString(member));
    }

    public static FakeHttpServer http() {
        return HTTP;
    }

    /** identity-serviceが受けた{@code /api/identity/me}の呼び出し。 */
    public static List<FakeHttpServer.Request> identityLookups() {
        return HTTP.requests("GET", "/api/identity/me");
    }
}
