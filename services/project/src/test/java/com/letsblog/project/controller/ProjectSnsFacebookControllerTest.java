package com.letsblog.project.controller;

import com.letsblog.project.client.FacebookApiClient;
import com.letsblog.project.config.GlobalExceptionHandler;
import com.letsblog.project.service.FacebookPageSelectionStore;
import com.letsblog.project.service.SnsFacebookService;
import com.letsblog.project.testsupport.FakeHttpServer;
import com.letsblog.project.testsupport.SnsFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * プロジェクト設定画面の Facebook ページ接続の API(issue #1580)。応答にトークンもクライアントの秘密も載らない。
 *
 * <p>issue #1818: SnsFacebookService・SnsXService・AdminAuthorizationService・SiteService・FacebookApiClient・
 * FacebookPageSelectionStore は実物。Facebook の API・identity-service・CMS ブリッジの代わりにフェイクHTTPサーバーが応答し、
 * 差し替えるのはDBのリポジトリだけ。
 */
class ProjectSnsFacebookControllerTest {

    private static final String MEMBER = "/api/internal/identity/projects/7/members/5";
    private static final String CALLBACK = "https://l/cb";
    private static final String PAGES_JSON = "{\"data\":[{\"id\":\"100\",\"name\":\"ページA\",\"access_token\":\"PAGE-TOKEN-100\"}]}";

    private final SnsFixture fx = new SnsFixture();
    private SnsFacebookService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        FacebookApiClient client = new FacebookApiClient(fx.world.http.baseUrl(), "https://facebook.example/dialog/oauth");
        service = new SnsFacebookService(fx.snsXService, fx.world.siteService, client, fx.store,
                new FacebookPageSelectionStore(), fx.world.currentActorService, fx.world.objectMapper);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProjectSnsFacebookController(service, fx.world.adminAuthorizationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void close() {
        fx.close();
    }

    private void asNonMember() {
        fx.world.actingAsUser(5L);
        fx.world.http.json("GET", MEMBER, "false");
    }

    private boolean touchedSite() {
        return !fx.world.http.requests("POST", "/api/internal/project/cms/.*").isEmpty();
    }

    private void facebookAnswers() {
        fx.world.http.on("GET", "/oauth/access_token", req -> req.query().contains("fb_exchange_token")
                ? FakeHttpServer.Response.ok("{\"access_token\":\"USER-LONG\"}")
                : FakeHttpServer.Response.ok("{\"access_token\":\"USER-SHORT\"}"));
        fx.world.http.json("GET", "/me/accounts", PAGES_JSON);
    }

    /** 認可を始めて(本人は sub-1)、stateを返す。 */
    private String started() {
        fx.world.actingAsAdmin(1L).signedInAs("sub-1");
        fx.connectableSite();
        return SnsFixture.queryParam(service.startAuthorization(7L, "cid", "csecret", CALLBACK), "state");
    }

    /** 認可を終えてページを選べる状態にし、そのstateを返す。 */
    private String authorizedWithPages() {
        String state = started();
        facebookAnswers();
        service.completeAuthorization(7L, state, "the-code");
        return state;
    }

    @Test
    void 接続状態を返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.connectableSite();
        fx.world.letsblogSns("status", "facebook", "{\"facebook\":{\"status\":\"接続済み\",\"account_name\":\"公式ページ\"}}");
        fx.world.letsblogSns("log", "facebook",
                "[{\"sns\":\"facebook\",\"kind\":\"test\",\"post_id\":null,\"at\":\"2026-10-06T00:00:00+00:00\",\"success\":true,\"error\":null}]");

        mockMvc.perform(get("/api/projects/7/sns/facebook"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status.state").value("CONNECTED"))
                .andExpect(jsonPath("$.status.accountName").value("公式ページ"))
                .andExpect(jsonPath("$.log.entries[0].kind").value("test"));
    }

    @Test
    void メンバーでもadminでもなければ状態も読めない() throws Exception {
        asNonMember();
        fx.connectableSite();

        mockMvc.perform(get("/api/projects/7/sns/facebook")).andExpect(status().isForbidden());

        assertTrue(!touchedSite());
    }

    @Test
    void 接続と選択と切断と投稿は管理者だけで_拒否したら何も実行しない() throws Exception {
        asNonMember();
        fx.connectableSite();

        mockMvc.perform(post("/api/projects/7/sns/facebook/test")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/projects/7/sns/facebook")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/projects/7/sns/facebook/pages").param("state", "7.a")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/facebook/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"c\",\"clientSecret\":\"s\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/facebook/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.a\",\"code\":\"c\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/facebook/page").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.a\",\"pageId\":\"100\"}"))
                .andExpect(status().isForbidden());

        assertTrue(!touchedSite());
        assertTrue(fx.world.http.requests("GET", "/oauth/access_token").isEmpty());
    }

    @Test
    void 認可を始めると認可URLだけを返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.connectableSite();

        mockMvc.perform(post("/api/projects/7/sns/facebook/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"cid\",\"clientSecret\":\"csecret\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizeUrl").value(containsString("https://facebook.example/dialog/oauth?")))
                .andExpect(jsonPath("$.authorizeUrl").value(containsString("client_id=cid")))
                .andExpect(content().string(not(containsString("csecret"))));
    }

    @Test
    void 認可の入力が欠けていれば400() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.connectableSite();

        mockMvc.perform(post("/api/projects/7/sns/facebook/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"\",\"clientSecret\":\"s\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(!touchedSite());
    }

    @Test
    void 接続できない理由は409で返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.projectWithProductionSite(null);

        mockMvc.perform(post("/api/projects/7/sns/facebook/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"cid\",\"clientSecret\":\"csecret\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("本番サイト")));
    }

    @Test
    void コールバックは選べるページの一覧だけを返す() throws Exception {
        String state = started();
        facebookAnswers();

        mockMvc.perform(post("/api/projects/7/sns/facebook/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"" + state + "\",\"code\":\"the-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages[0].id").value("100"))
                .andExpect(jsonPath("$.pages[0].name").value("ページA"))
                .andExpect(content().string(not(containsString("token"))));
        assertTrue(fx.world.snsRequests("config-set").isEmpty(), "ページを選ぶまで本番サイトへは送らない");
    }

    @Test
    void コールバックの入力が欠けていれば400() throws Exception {
        fx.world.actingAsAdmin(1L);

        mockMvc.perform(post("/api/projects/7/sns/facebook/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.abc\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(fx.world.http.requests("GET", "/oauth/access_token").isEmpty());
    }

    @Test
    void 選べるページの一覧を返す() throws Exception {
        String state = authorizedWithPages();

        mockMvc.perform(get("/api/projects/7/sns/facebook/pages").param("state", state))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pages[0].name").value("ページA"))
                .andExpect(content().string(not(containsString("token"))));
    }

    @Test
    void ページを選ぶとページ名だけを返す() throws Exception {
        String state = authorizedWithPages();
        fx.world.letsblogSns("config-set", "facebook", "{\"sns\":\"facebook\",\"status\":\"接続済み\"}");

        mockMvc.perform(post("/api/projects/7/sns/facebook/page").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"" + state + "\",\"pageId\":\"100\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountName").value("ページA"))
                .andExpect(content().string(not(containsString("token"))));
        assertEquals("PAGE-TOKEN-100", fx.world.objectMapper
                .readTree(fx.world.snsRequests("config-set").get(0).path("stdin").asText()).path("access_token").asText());
    }

    @Test
    void ページの選択の入力が欠けていれば400() throws Exception {
        fx.world.actingAsAdmin(1L);

        mockMvc.perform(post("/api/projects/7/sns/facebook/page").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.abc\",\"pageId\":\"\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(!touchedSite());
    }

    @Test
    void テスト投稿の結果を返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.connectableSite();
        fx.world.letsblogSnsFails("test", "facebook", "Facebook の投稿に失敗しました");

        mockMvc.perform(post("/api/projects/7/sns/facebook/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value(containsString("Facebook の投稿に失敗しました")));
    }

    @Test
    void 切断すると本文なしで204を返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.connectableSite();
        fx.world.letsblogSns("config-clear", "facebook", "{}");

        mockMvc.perform(delete("/api/projects/7/sns/facebook")).andExpect(status().isNoContent());

        assertEquals(1, fx.world.snsRequests("config-clear").size());
    }

    @Test
    void 切断できない理由は409で返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.projectWithProductionSite(null);

        mockMvc.perform(delete("/api/projects/7/sns/facebook"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("本番サイト")));
    }
}
