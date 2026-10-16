package com.letsblog.ai.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code @SpringBootTest}の統合テストが使う、他サービスのHTTPフェイク(issue #1824)。
 *
 * <p>identity-service・platform-service・project-service・publishing-service・Brave Search・GitHubの
 * ベースURLをすべて、JVMで1台だけ起動する{@link FakeHttpServer}へ向ける({@link #register})。
 * {@code IdentityClient}・{@code IdentityBridgeClient}・{@code PlatformServiceClient}などはアプリケーションの
 * 実物のBeanで、{@code @MockitoBean}にはしない。モックのままなのは、Keycloakの鍵取得を伴う{@code JwtDecoder}だけ。
 *
 * <p>ベースURLは全クラスで同じ値なので、{@code @DynamicPropertySource}を使っても、同じ構成の
 * テストクラスはSpringのコンテキストキャッシュを共有できる。テストごとに{@link #reset()}で登録と記録を初期化する。
 */
public final class IntegrationBackends {

    private static final FakeHttpServer SERVER = new FakeHttpServer();
    private static final Map<String, String> PROFILES = new ConcurrentHashMap<>();

    private IntegrationBackends() {
    }

    public static FakeHttpServer server() {
        return SERVER;
    }

    /** {@code @DynamicPropertySource}から呼ぶ。 */
    public static void register(DynamicPropertyRegistry registry) {
        String base = SERVER.baseUrl();
        registry.add("app.identity-service-uri", () -> base);
        registry.add("app.platform-service-uri", () -> base);
        registry.add("app.project-service-uri", () -> base);
        registry.add("app.publishing-service-uri", () -> base);
        registry.add("app.brave-search-base-url", () -> base + "/brave");
        registry.add("app.github-api-base-url", () -> base + "/github");
    }

    /** 登録した応答と記録を消し、identity-serviceの利用者解決だけを張り直す(未知のトークンは401)。 */
    public static void reset() {
        SERVER.reset();
        PROFILES.clear();
        // 登録の無いプロジェクトメンバー判定は「メンバーではない」(後から登録した個別の応答が優先される)。
        SERVER.json("GET", "/api/internal/identity/projects/\\d+/members/\\d+", "false");
        SERVER.on("GET", "/api/identity/me", request -> {
            String profile = PROFILES.get(request.header("Authorization"));
            return profile == null
                    ? FakeHttpServer.Response.json(401, "{\"error\":\"unauthorized\"}")
                    : FakeHttpServer.Response.ok(profile);
        });
    }

    /** {@code Authorization: Bearer <token>}の利用者をID・ロールで解決させる。 */
    public static void actor(String token, long id, String role) {
        PROFILES.put("Bearer " + token, "{\"id\":" + id + ",\"role\":\"" + role + "\"}");
    }

    /** プロジェクトのメンバー判定(identity-serviceの内部ブリッジ)の結果。 */
    public static void membership(long projectId, long userId, boolean member) {
        SERVER.json("GET", "/api/internal/identity/projects/" + projectId + "/members/" + userId, String.valueOf(member));
    }

    /** platform-serviceの{@code ai-connections-config}(4プロバイダーぶん)。 */
    public static void aiConnectionsConfig(
            String ollamaUrl, String ollamaSource, String comfyuiUrl, String comfyuiSource) {
        SERVER.json("GET", "/api/internal/platform/ai-connections-config",
                "{\"ollama\":{\"baseUrl\":" + quote(ollamaUrl) + ",\"source\":\"" + ollamaSource + "\",\"configured\":true},"
                        + "\"comfyui\":{\"baseUrl\":" + quote(comfyuiUrl) + ",\"source\":\"" + comfyuiSource + "\",\"configured\":true},"
                        + "\"openai\":{\"baseUrl\":null,\"source\":\"DATABASE\",\"configured\":true},"
                        + "\"claude\":{\"baseUrl\":null,\"source\":\"NONE\",\"configured\":false}}");
    }

    /**
     * platform-serviceの{@code llm-config}。どのプロバイダーを問い合わせても、フェイク自身を向くOpenAI互換・Ollamaの
     * 設定を返す(本物のAPI・外部のホストへは出ない)。Ollamaのモデル一覧({@code /api/tags})も返す。
     */
    public static void llmConfig(String defaultModel) {
        SERVER.on("GET", "/api/internal/platform/llm-config", request -> {
            String query = request.query();
            String provider = query != null && query.startsWith("provider=") ? query.substring("provider=".length()) : "OLLAMA";
            return FakeHttpServer.Response.ok("{\"provider\":\"" + provider + "\",\"baseUrl\":\"" + SERVER.baseUrl()
                    + "/v1\",\"apiKey\":\"test-api-key\",\"defaultModel\":\"" + defaultModel
                    + "\",\"availableModels\":[\"" + defaultModel + "\"],\"requestTimeoutSeconds\":30}");
        });
        SERVER.json("GET", "/api/tags", "{\"models\":[{\"name\":\"" + defaultModel + "\"}]}");
    }

    private static String quote(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
