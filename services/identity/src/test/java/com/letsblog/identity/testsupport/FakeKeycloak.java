package com.letsblog.identity.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.identity.keycloak.KeycloakAdminClient;
import com.letsblog.identity.keycloak.KeycloakAdminProperties;
import org.springframework.web.client.RestClient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Keycloak(トークンエンドポイントとAdmin REST API)のサーバー側フェイク(issue #1826)。
 * {@link FakeHttpServer}の上に、ユーザー作成・更新・削除・パスワード・realmロールのマッピングを
 * 実際のHTTPで応答する。{@link KeycloakAdminClient}の<b>実物</b>をこのサーバーへ向けて使うための部品で、
 * Mockitoは使わない(外部との通信だけを差し替える方針)。
 *
 * <p>受けた操作は{@link #ops()}に「操作名:引数...」の文字列で記録する(例: {@code grant:sub-1:admin}、
 * {@code setEnabled:sub-1:false})。失敗させたい呼び出しは{@link #fail}で登録する(後から登録した方が優先)。
 */
public final class FakeKeycloak implements AutoCloseable {

    public static final String REALM_PATH = "/admin/realms/letsblog";
    public static final String TOKEN_PATH = "/realms/letsblog/protocol/openid-connect/token";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FakeHttpServer http = new FakeHttpServer();
    private final List<String> ops = new CopyOnWriteArrayList<>();
    private final Deque<String> queuedSubs = new ArrayDeque<>();
    private final AtomicInteger subSequence = new AtomicInteger();
    /** 実行ごとに変わる接頭辞。テスト用DBに前回の実行で作られた行が残っていても、subが衝突しない。 */
    private final String subPrefix = "fake-kc-" + java.util.UUID.randomUUID().toString().substring(0, 8) + "-";
    private final Map<String, Set<String>> assigned = new LinkedHashMap<>();
    private final Set<String> realmRoles = new LinkedHashSet<>(List.of("admin"));
    private final Set<String> missingUsers = new LinkedHashSet<>();
    private final List<String> createdEmails = new CopyOnWriteArrayList<>();
    private volatile long createDelayMillis;

    private final int baseRoutes;

    public FakeKeycloak() {
        http.on("POST", TOKEN_PATH, request -> {
            ops.add("token");
            return FakeHttpServer.Response.ok("{\"access_token\":\"fake-access-token\",\"expires_in\":300}");
        });
        http.on("POST", REALM_PATH + "/users", request -> {
            JsonNode body = json(request);
            String email = body.path("email").asText();
            boolean requirePasswordReset = body.path("requiredActions").size() > 0;
            if (createDelayMillis > 0) {
                try {
                    Thread.sleep(createDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            ops.add("createUser:" + email + ":" + requirePasswordReset);
            createdEmails.add(email);
            String sub;
            synchronized (queuedSubs) {
                sub = queuedSubs.isEmpty() ? subPrefix + subSequence.incrementAndGet() : queuedSubs.poll();
            }
            return FakeHttpServer.Response.created(http.baseUrl() + REALM_PATH + "/users/" + sub);
        });
        http.on("PUT", REALM_PATH + "/users/[^/]+", request -> {
            String sub = lastSegment(request.path());
            JsonNode body = json(request);
            if (body.has("enabled")) {
                ops.add("setEnabled:" + sub + ":" + body.get("enabled").asBoolean());
            } else if (body.has("firstName") || body.has("lastName")) {
                ops.add("updateProfile:" + sub + ":" + body.path("firstName").asText() + ":"
                        + body.path("lastName").asText());
            } else {
                ops.add("updateEmail:" + sub + ":" + body.path("email").asText());
            }
            return FakeHttpServer.Response.empty(204);
        });
        http.on("GET", REALM_PATH + "/users/[^/]+", request -> {
            String sub = lastSegment(request.path());
            ops.add("exists:" + sub);
            return missingUsers.contains(sub)
                    ? FakeHttpServer.Response.json(404, "{\"error\":\"User not found\"}")
                    : FakeHttpServer.Response.ok("{\"id\":\"" + sub + "\"}");
        });
        http.on("DELETE", REALM_PATH + "/users/[^/]+", request -> {
            String sub = lastSegment(request.path());
            ops.add("delete:" + sub);
            return missingUsers.contains(sub)
                    ? FakeHttpServer.Response.json(404, "{\"error\":\"User not found\"}")
                    : FakeHttpServer.Response.empty(204);
        });
        http.on("PUT", REALM_PATH + "/users/[^/]+/execute-actions-email", request -> {
            ops.add("sendPasswordResetEmail:" + segmentBeforeLast(request.path()));
            return FakeHttpServer.Response.empty(204);
        });
        http.on("PUT", REALM_PATH + "/users/[^/]+/reset-password", request -> {
            ops.add("setPassword:" + segmentBeforeLast(request.path()) + ":" + json(request).path("value").asText());
            return FakeHttpServer.Response.empty(204);
        });
        http.on("GET", REALM_PATH + "/users/[^/]+/role-mappings/realm", request ->
                FakeHttpServer.Response.ok(rolesJson(assignedTo(subOfRoleMappings(request.path())))));
        http.on("GET", REALM_PATH + "/users/[^/]+/role-mappings/realm/available", request -> {
            Set<String> available = new LinkedHashSet<>(realmRoles);
            available.removeAll(assignedTo(subOfRoleMappings(request.path())));
            return FakeHttpServer.Response.ok(rolesJson(available));
        });
        http.on("POST", REALM_PATH + "/users/[^/]+/role-mappings/realm", request -> {
            String sub = subOfRoleMappings(request.path());
            for (JsonNode role : json(request)) {
                String name = role.path("name").asText();
                ops.add("grant:" + sub + ":" + name);
                synchronized (assigned) {
                    assigned.computeIfAbsent(sub, k -> new LinkedHashSet<>()).add(name);
                }
            }
            return FakeHttpServer.Response.empty(204);
        });
        http.on("DELETE", REALM_PATH + "/users/[^/]+/role-mappings/realm", request -> {
            String sub = subOfRoleMappings(request.path());
            for (JsonNode role : json(request)) {
                String name = role.path("name").asText();
                ops.add("revoke:" + sub + ":" + name);
                synchronized (assigned) {
                    assigned.computeIfAbsent(sub, k -> new LinkedHashSet<>()).remove(name);
                }
            }
            return FakeHttpServer.Response.empty(204);
        });
        baseRoutes = http.routeCount();
    }

    // ------------------------------------------------------------------ 実物のクライアントを作る

    public KeycloakAdminProperties properties() {
        KeycloakAdminProperties properties = new KeycloakAdminProperties();
        properties.setTokenUri(http.baseUrl() + TOKEN_PATH);
        properties.setAdminBaseUri(http.baseUrl() + REALM_PATH);
        properties.setClientId("letsblog-services");
        properties.setClientSecret("test-secret");
        return properties;
    }

    /** このフェイクへ向けた{@link KeycloakAdminClient}の実物。 */
    public KeycloakAdminClient client() {
        return new KeycloakAdminClient(RestClient.builder(), properties());
    }

    public String baseUrl() {
        return http.baseUrl();
    }

    // ------------------------------------------------------------------ 振る舞いの設定

    /** 次に作られるユーザーのsubを指定する(複数回呼ぶと順に使う)。 */
    public FakeKeycloak nextSub(String... subs) {
        synchronized (queuedSubs) {
            queuedSubs.addAll(List.of(subs));
        }
        return this;
    }

    /** ユーザー作成の応答を遅らせる(同時実行の競合を再現するとき)。 */
    public FakeKeycloak delayCreateUser(long millis) {
        this.createDelayMillis = millis;
        return this;
    }

    /** Keycloak側に存在しないユーザーにする(GET/DELETEが404)。 */
    public FakeKeycloak userMissing(String sub) {
        missingUsers.add(sub);
        return this;
    }

    /** realmに「admin」ロールの定義が無い状態にする。 */
    public FakeKeycloak withoutRealmRoles() {
        realmRoles.clear();
        return this;
    }

    /** ユーザーに既にrealmロールが付与されている状態にする。 */
    public FakeKeycloak alreadyAssigned(String sub, String role) {
        realmRoles.add(role);
        synchronized (assigned) {
            assigned.computeIfAbsent(sub, k -> new LinkedHashSet<>()).add(role);
        }
        return this;
    }

    /** メソッドとパス(Admin APIのベースからの相対の正規表現、全体一致)に、指定ステータスで失敗させる。 */
    public FakeKeycloak fail(String method, String relativePathRegex, int status) {
        http.on(method, REALM_PATH + relativePathRegex, request -> {
            ops.add("failed:" + request.method() + ":" + request.path().substring(REALM_PATH.length()));
            return FakeHttpServer.Response.json(status, "{\"error\":\"injected failure\"}");
        });
        return this;
    }

    /** 同じ要求を、最初の{@code times}回だけ失敗させ、その後は通常どおり応答する。 */
    public FakeKeycloak failTimes(String method, String relativePathRegex, int status, int times) {
        AtomicInteger remaining = new AtomicInteger(times);
        http.on(method, REALM_PATH + relativePathRegex, request -> {
            if (remaining.getAndDecrement() > 0) {
                ops.add("failed:" + request.method() + ":" + request.path().substring(REALM_PATH.length()));
                return FakeHttpServer.Response.json(status, "{\"error\":\"injected failure\"}");
            }
            return null;
        });
        return this;
    }

    /** トークンエンドポイントを失敗させる(Keycloak停止の再現)。 */
    public FakeKeycloak failToken(int status) {
        http.on("POST", TOKEN_PATH, FakeHttpServer.Response.json(status, "{\"error\":\"down\"}"));
        return this;
    }

    // ------------------------------------------------------------------ 観測

    /** 記録された操作(トークン取得を除く)。 */
    public List<String> ops() {
        List<String> result = new ArrayList<>(ops);
        result.removeIf("token"::equals);
        return result;
    }

    public List<String> ops(String prefix) {
        return ops().stream().filter(op -> op.startsWith(prefix)).toList();
    }

    public List<String> createdEmails() {
        return List.copyOf(createdEmails);
    }

    public Set<String> assignedRoles(String sub) {
        return Set.copyOf(assignedTo(sub));
    }

    public FakeHttpServer http() {
        return http;
    }

    /** 操作の記録と、割り当て済みロール・遅延・注入した失敗をすべて初期状態に戻す(共有インスタンス用)。 */
    public void reset() {
        ops.clear();
        createdEmails.clear();
        createDelayMillis = 0;
        missingUsers.clear();
        realmRoles.clear();
        realmRoles.add("admin");
        synchronized (queuedSubs) {
            queuedSubs.clear();
        }
        http.clearRoutesAfter(baseRoutes);
        synchronized (assigned) {
            assigned.clear();
        }
    }

    @Override
    public void close() {
        http.close();
    }

    // ------------------------------------------------------------------ 内部

    private Set<String> assignedTo(String sub) {
        synchronized (assigned) {
            return new LinkedHashSet<>(assigned.getOrDefault(sub, Set.of()));
        }
    }

    private static String rolesJson(Set<String> names) {
        List<String> items = new ArrayList<>();
        for (String name : names) {
            items.add("{\"id\":\"role-id-" + name + "\",\"name\":\"" + name + "\"}");
        }
        return "[" + String.join(",", items) + "]";
    }

    private static JsonNode json(FakeHttpServer.Request request) {
        try {
            return MAPPER.readTree(request.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String lastSegment(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String segmentBeforeLast(String path) {
        String parent = path.substring(0, path.lastIndexOf('/'));
        return lastSegment(parent);
    }

    /** {@code /users/{sub}/role-mappings/realm[/available]}から{sub}を取り出す。 */
    private static String subOfRoleMappings(String path) {
        String afterUsers = path.substring(path.indexOf("/users/") + "/users/".length());
        return afterUsers.substring(0, afterUsers.indexOf('/'));
    }
}
