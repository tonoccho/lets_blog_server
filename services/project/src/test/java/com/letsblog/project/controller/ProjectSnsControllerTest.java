package com.letsblog.project.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.project.client.XApiClient;
import com.letsblog.project.config.GlobalExceptionHandler;
import com.letsblog.project.dto.XConnectResult;
import com.letsblog.project.service.SnsXService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * プロジェクト設定画面の X 接続の API(issue #1574)。応答にトークンもクライアントの秘密も載らない。
 *
 * <p>issue #1818: SnsXService・AdminAuthorizationService・SiteService・XApiClient は実物。X の API・identity-service・
 * CMS ブリッジの代わりにフェイクHTTPサーバーが応答し、差し替えるのはDBのリポジトリだけ。
 */
class ProjectSnsControllerTest {

    private static final String MEMBER = "/api/internal/identity/projects/7/members/5";

    private final SnsFixture fx = new SnsFixture();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProjectSnsController(fx.snsXService, fx.world.adminAuthorizationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void close() {
        fx.close();
    }

    /** プロジェクト7のメンバーではない一般ユーザーとして操作する。 */
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
        fx.world.letsblogSns("status", "x", "{\"x\":{\"status\":\"接続済み\",\"account_name\":\"lets_blog\"}}");
        fx.world.letsblogSns("log", "x",
                "[{\"sns\":\"x\",\"kind\":\"test\",\"post_id\":null,\"at\":\"2026-10-04T00:00:00+00:00\",\"success\":true,\"error\":null}]");

        mockMvc.perform(get("/api/projects/7/sns/x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectable").value(true))
                .andExpect(jsonPath("$.siteName").value("本番サイト"))
                .andExpect(jsonPath("$.status.state").value("CONNECTED"))
                .andExpect(jsonPath("$.status.accountName").value("lets_blog"))
                .andExpect(jsonPath("$.log.entries[0].kind").value("test"));
    }

    @Test
    void プロジェクトのメンバーなら状態を読める() throws Exception {
        fx.world.actingAsUser(5L);
        fx.world.http.json("GET", MEMBER, "true");
        fx.projectWithProductionSite(null);

        mockMvc.perform(get("/api/projects/7/sns/x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connectable").value(false));
        assertEquals("Bearer token-5", fx.world.http.requests("GET", MEMBER).get(0).header("Authorization"));
    }

    @Test
    void メンバーでもadminでもなければ状態も読めない() throws Exception {
        asNonMember();
        fx.connectableSite();

        mockMvc.perform(get("/api/projects/7/sns/x")).andExpect(status().isForbidden());

        assertTrue(!touchedSite(), "本番サイトへは問い合わせない");
    }

    @Test
    void 接続と投稿は管理者だけで_メンバーは拒否し何も実行しない() throws Exception {
        asNonMember();
        fx.connectableSite();

        mockMvc.perform(post("/api/projects/7/sns/x/test")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/x/authorize").contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"c\",\"clientSecret\":\"s\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/x/callback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"state\":\"7.a\",\"code\":\"c\"}"))
                .andExpect(status().isForbidden());

        assertTrue(!touchedSite());
        assertTrue(fx.world.http.requests("POST", "/2/oauth2/token").isEmpty());
    }

    @Test
    void 認可を始めるとXの認可URLだけを返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.connectableSite();

        mockMvc.perform(post("/api/projects/7/sns/x/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"cid\",\"clientSecret\":\"csecret\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizeUrl").value(containsString("https://x.example/authorize?")))
                .andExpect(jsonPath("$.authorizeUrl").value(containsString("client_id=cid")))
                .andExpect(content().string(not(containsString("csecret"))));
    }

    @Test
    void 認可の入力が欠けていれば400() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.connectableSite();

        mockMvc.perform(post("/api/projects/7/sns/x/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"\",\"clientSecret\":\"s\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(!touchedSite());
    }

    @Test
    void 接続できない理由は409で返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.projectWithProductionSite(null);

        mockMvc.perform(post("/api/projects/7/sns/x/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"cid\",\"clientSecret\":\"csecret\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("本番サイト")));
    }

    @Test
    void コールバックはアカウント名だけを返す() throws Exception {
        fx.world.actingAsAdmin(1L).signedInAs("sub-1");
        fx.connectableSite();
        String authorizeUrl = fx.snsXService.startAuthorization(7L, "cid", "csecret", "https://l/cb");
        String state = SnsFixture.queryParam(authorizeUrl, "state");
        fx.world.http.json("POST", "/2/oauth2/token",
                "{\"access_token\":\"ACCESS-TOKEN\",\"refresh_token\":\"REFRESH-TOKEN\",\"expires_in\":7200}");
        fx.world.http.json("GET", "/2/users/me", "{\"data\":{\"username\":\"lets_blog\"}}");
        fx.world.letsblogSns("config-set", "x", "{\"sns\":\"x\",\"status\":\"接続済み\"}");

        mockMvc.perform(post("/api/projects/7/sns/x/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"" + state + "\",\"code\":\"the-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountName").value("lets_blog"))
                .andExpect(content().string(not(containsString("token"))));
        assertTrue(fx.world.http.requests("POST", "/2/oauth2/token").get(0).bodyText().contains("code=the-code"));
    }

    @Test
    void コールバックの入力が欠けていれば400() throws Exception {
        fx.world.actingAsAdmin(1L);

        mockMvc.perform(post("/api/projects/7/sns/x/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.abc\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(fx.world.http.requests("POST", "/2/oauth2/token").isEmpty());
    }

    @Test
    void テスト投稿の結果を返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.connectableSite();
        fx.world.letsblogSnsFails("test", "x", "X の投稿に失敗しました");

        mockMvc.perform(post("/api/projects/7/sns/x/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value(containsString("X の投稿に失敗しました")));
    }

    @Test
    void テスト投稿が成功すれば成功を返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.connectableSite();
        fx.world.letsblogSns("test", "x", "{\"sns\":\"x\",\"kind\":\"test\",\"success\":true}");

        mockMvc.perform(post("/api/projects/7/sns/x/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void 応答を直列化してもトークンのフィールドは存在しない() throws Exception {
        String json = new ObjectMapper().writeValueAsString(new XConnectResult(7L, "lets_blog"));

        org.junit.jupiter.api.Assertions.assertEquals("{\"projectId\":7,\"accountName\":\"lets_blog\"}", json);
    }
}
