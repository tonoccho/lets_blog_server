package com.letsblog.project.controller;

import com.letsblog.project.client.HatenaApiClient;
import com.letsblog.project.config.GlobalExceptionHandler;
import com.letsblog.project.service.HatenaAuthorizationStore;
import com.letsblog.project.service.SnsHatenaService;
import com.letsblog.project.testsupport.FakeHttpServer;
import com.letsblog.project.testsupport.SnsFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * プロジェクト設定画面の はてなブックマーク 接続の API(issue #1582)。応答にトークンもクライアントの秘密も載らない。
 *
 * <p>issue #1818: SnsHatenaService・SnsXService・AdminAuthorizationService・SiteService・HatenaApiClient は実物。
 * はてなブックマークの API・identity-service・CMS ブリッジの代わりにフェイクHTTPサーバーが応答し、差し替えるのはDBのリポジトリだけ。
 */
class ProjectSnsHatenaControllerTest {

    private static final String MEMBER = "/api/internal/identity/projects/7/members/5";

    private final SnsFixture fx = new SnsFixture();
    private SnsHatenaService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        String base = fx.world.http.baseUrl();
        HatenaApiClient client = new HatenaApiClient(
                base + "/oauth/initiate", base + "/oauth/token", "https://hatena.example/authorize", base + "/profile");
        service = new SnsHatenaService(fx.snsXService, fx.world.siteService, client, new HatenaAuthorizationStore(),
                fx.world.currentActorService, fx.world.objectMapper);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProjectSnsHatenaController(service, fx.world.adminAuthorizationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void close() {
        fx.close();
    }

    private void hatenaIssuesRequestToken() {
        fx.world.http.on("POST", "/oauth/initiate", FakeHttpServer.Response.bytes(200, "application/x-www-form-urlencoded",
                "oauth_token=RT&oauth_token_secret=RS&oauth_callback_confirmed=true".getBytes(StandardCharsets.UTF_8)));
    }

    /** リクエストトークンの要求に載せたcallback URL(OAuthのAuthorizationヘッダーの oauth_callback)から、stateを取り出す。 */
    private String stateSent() {
        String header = fx.world.http.requests("POST", "/oauth/initiate").get(0).header("Authorization");
        Matcher m = Pattern.compile("oauth_callback=\"([^\"]*)\"").matcher(header);
        assertTrue(m.find(), header);
        String callback = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
        return URLDecoder.decode(callback.substring(callback.indexOf("state=") + "state=".length()), StandardCharsets.UTF_8);
    }

    private void asNonMember() {
        fx.world.actingAsUser(5L);
        fx.world.http.json("GET", MEMBER, "false");
    }

    private boolean touchedSite() {
        return !fx.world.http.requests("POST", "/api/internal/project/cms/.*").isEmpty();
    }

    @Test
    void 接続状態を返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.connectableSite();
        fx.world.letsblogSns("status", "hatena", "{\"hatena\":{\"status\":\"接続済み\",\"account_name\":\"lets_blog\"}}");
        fx.world.letsblogSns("log", "hatena",
                "[{\"sns\":\"hatena\",\"kind\":\"test\",\"post_id\":null,\"at\":\"2026-10-04T00:00:00+00:00\",\"success\":true,\"error\":null}]");

        mockMvc.perform(get("/api/projects/7/sns/hatena"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectable").value(true))
                .andExpect(jsonPath("$.status.state").value("CONNECTED"))
                .andExpect(jsonPath("$.status.accountName").value("lets_blog"))
                .andExpect(jsonPath("$.log.entries[0].kind").value("test"));
    }

    @Test
    void メンバーでもadminでもなければ状態も読めない() throws Exception {
        asNonMember();
        fx.connectableSite();

        mockMvc.perform(get("/api/projects/7/sns/hatena")).andExpect(status().isForbidden());

        assertTrue(!touchedSite());
    }

    @Test
    void 接続と切断と投稿は管理者だけで_拒否したら何も実行しない() throws Exception {
        asNonMember();
        fx.connectableSite();

        mockMvc.perform(post("/api/projects/7/sns/hatena/test")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/projects/7/sns/hatena")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/hatena/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"c\",\"clientSecret\":\"s\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/hatena/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.a\",\"oauthToken\":\"t\",\"oauthVerifier\":\"v\"}"))
                .andExpect(status().isForbidden());

        assertTrue(!touchedSite());
        assertTrue(fx.world.http.requests("POST", "/oauth/token").isEmpty());
    }

    @Test
    void 認可を始めると認可URLだけを返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.connectableSite();
        hatenaIssuesRequestToken();

        mockMvc.perform(post("/api/projects/7/sns/hatena/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"cid\",\"clientSecret\":\"csecret\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizeUrl").value(containsString("https://hatena.example/authorize?")))
                .andExpect(jsonPath("$.authorizeUrl").value(containsString("oauth_token=RT")))
                .andExpect(content().string(not(containsString("csecret"))));
    }

    @Test
    void 認可の入力が欠けていれば400() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.connectableSite();

        mockMvc.perform(post("/api/projects/7/sns/hatena/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"\",\"clientSecret\":\"s\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(!touchedSite());
    }

    @Test
    void 接続できない理由は409で返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.projectWithProductionSite(null);

        mockMvc.perform(post("/api/projects/7/sns/hatena/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"cid\",\"clientSecret\":\"csecret\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("本番サイト")));
    }

    @Test
    void コールバックはアカウント名だけを返す() throws Exception {
        fx.world.actingAsAdmin(1L).signedInAs("sub-1");
        fx.connectableSite();
        hatenaIssuesRequestToken();
        service.startAuthorization(7L, "consumer-key", "consumer-secret", "https://l/cb");
        String state = stateSent();
        fx.world.http.on("POST", "/oauth/token", FakeHttpServer.Response.bytes(200, "application/x-www-form-urlencoded",
                "oauth_token=ACCESS-TOKEN&oauth_token_secret=ACCESS-SECRET".getBytes(StandardCharsets.UTF_8)));
        fx.world.http.json("GET", "/profile", "{\"display_name\":\"lets_blog\",\"url_name\":\"lets_blog\"}");
        fx.world.letsblogSns("config-set", "hatena", "{\"sns\":\"hatena\",\"status\":\"接続済み\"}");

        mockMvc.perform(post("/api/projects/7/sns/hatena/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"" + state + "\",\"oauthToken\":\"RT\",\"oauthVerifier\":\"the-verifier\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountName").value("lets_blog"))
                .andExpect(content().string(not(containsString("token"))));
        String exchangeAuth = fx.world.http.requests("POST", "/oauth/token").get(0).header("Authorization");
        assertTrue(exchangeAuth.contains("oauth_verifier=\"the-verifier\""), exchangeAuth);
    }

    @Test
    void コールバックの入力が欠けていれば400() throws Exception {
        fx.world.actingAsAdmin(1L);

        mockMvc.perform(post("/api/projects/7/sns/hatena/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.abc\",\"oauthToken\":\"request-token\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/projects/7/sns/hatena/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.abc\",\"oauthVerifier\":\"the-verifier\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(fx.world.http.requests("POST", "/oauth/token").isEmpty());
    }

    @Test
    void テスト投稿の結果を返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.connectableSite();
        fx.world.letsblogSnsFails("test", "hatena", "はてなブックマークの投稿に失敗しました");

        mockMvc.perform(post("/api/projects/7/sns/hatena/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value(containsString("はてなブックマークの投稿に失敗しました")));
    }

    @Test
    void 切断すると本文なしで204を返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.connectableSite();
        fx.world.letsblogSns("config-clear", "hatena", "{}");

        mockMvc.perform(delete("/api/projects/7/sns/hatena")).andExpect(status().isNoContent());

        assertEquals(1, fx.world.snsRequests("config-clear").size());
        assertEquals("hatena", fx.world.snsRequests("config-clear").get(0).path("sns").asText());
    }

    @Test
    void 切断できない理由は409で返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.projectWithProductionSite(null);

        mockMvc.perform(delete("/api/projects/7/sns/hatena"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("本番サイト")));
    }
}
