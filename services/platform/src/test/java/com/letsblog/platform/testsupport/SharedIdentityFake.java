package com.letsblog.platform.testsupport;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 統合テスト(@SpringBootTest)が共有する、identity-serviceのサーバー側フェイク(issue #1825)。
 *
 * <p>Springコンテキストの{@code app.identity-service-uri}をこの1台へ向ける({@code @DynamicPropertySource})ので、
 * コンテキストの中の{@code IdentityClient}・{@code IdentityBridgeClient}・{@code CurrentActorService}・
 * {@code AdminAuthorizationService}はすべて実物のまま、実際のHTTPでこのフェイクと話す。
 * 1台を全テストクラスで共有するので、コンテキストのキャッシュも割れない。テストごとに{@link #reset()}する。
 */
public final class SharedIdentityFake {

    private static final FakeHttpServer HTTP = new FakeHttpServer();
    private static final Map<String, Integer> REJECTED = new ConcurrentHashMap<>();
    private static final Map<String, String> PROFILES = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> MEMBERS = new ConcurrentHashMap<>();

    static {
        HTTP.on("GET", "/api/identity/me", request -> {
            String authorization = request.header("Authorization");
            if (authorization != null && REJECTED.containsKey(authorization)) {
                return FakeHttpServer.Response.json(REJECTED.get(authorization), "{\"error\":\"rejected\"}");
            }
            String profile = authorization == null ? null : PROFILES.get(authorization);
            return profile == null
                    ? FakeHttpServer.Response.json(401, "{\"error\":\"unauthorized\"}")
                    : FakeHttpServer.Response.ok(profile);
        });
        HTTP.on("GET", "/api/internal/identity/projects/\\d+/members/\\d+", request -> {
            String[] parts = request.path().split("/");
            return FakeHttpServer.Response.ok(String.valueOf(MEMBERS.getOrDefault(parts[5] + ":" + parts[7], false)));
        });
    }

    private SharedIdentityFake() {
    }

    public static String baseUrl() {
        return HTTP.baseUrl();
    }

    /** 各テストの前に、登録と記録を空にする。 */
    public static void reset() {
        REJECTED.clear();
        PROFILES.clear();
        MEMBERS.clear();
        HTTP.clearRequests();
    }

    /** {@code Authorization}ヘッダーの値に対して、操作者(id・ロール)を返すようにする。 */
    public static void profile(String authorization, long id, String role) {
        PROFILES.put(authorization, "{\"id\":" + id + ",\"role\":\"" + role + "\"}");
    }

    /** {@code Authorization}ヘッダーの値に対して、identity-serviceが401/403を返す(無効化ユーザー等)ようにする。 */
    public static void reject(String authorization, int status) {
        REJECTED.put(authorization, status);
    }

    public static void member(long projectId, long userId, boolean member) {
        MEMBERS.put(projectId + ":" + userId, member);
    }

    /** identity-serviceが受けた {@code /api/identity/me} の問い合わせの{@code Authorization}ヘッダー。 */
    public static List<String> lookups() {
        return HTTP.requests("GET", "/api/identity/me").stream().map(r -> r.header("Authorization")).toList();
    }

    public static List<FakeHttpServer.Request> requests() {
        return HTTP.requests();
    }
}
