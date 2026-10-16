package com.letsblog.project.controller;

import com.letsblog.project.client.ThreadsApiClient;
import com.letsblog.project.config.GlobalExceptionHandler;
import com.letsblog.project.service.SnsThreadsService;
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
 * プロジェクト設定画面の Threads 接続の API(issue #1579)。応答にトークンもクライアントの秘密も載らない。
 *
 * <p>issue #1818: SnsThreadsService・SnsXService・AdminAuthorizationService・SiteService・ThreadsApiClient は実物。
 * Threads の API・identity-service・CMS ブリッジの代わりにフェイクHTTPサーバーが応答し、差し替えるのはDBのリポジトリだけ。
 */
class ProjectSnsThreadsControllerTest {

    private static final String MEMBER = "/api/internal/identity/projects/7/members/5";

    private final SnsFixture fx = new SnsFixture();
    private SnsThreadsService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ThreadsApiClient client = new ThreadsApiClient(fx.world.http.baseUrl(), "https://threads.example/authorize");
        service = new SnsThreadsService(fx.snsXService, fx.world.siteService, client, fx.store,
                fx.world.currentActorService, fx.world.objectMapper);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProjectSnsThreadsController(service, fx.world.adminAuthorizationService))
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

    @Test
    void 接続状態を返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.connectableSite();
        fx.world.letsblogSns("status", "threads", "{\"threads\":{\"status\":\"接続済み\",\"account_name\":\"lets_blog\"}}");
        fx.world.letsblogSns("log", "threads",
                "[{\"sns\":\"threads\",\"kind\":\"test\",\"post_id\":null,\"at\":\"2026-10-04T00:00:00+00:00\",\"success\":true,\"error\":null}]");

        mockMvc.perform(get("/api/projects/7/sns/threads"))
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

        mockMvc.perform(get("/api/projects/7/sns/threads")).andExpect(status().isForbidden());

        assertTrue(!touchedSite());
    }

    @Test
    void 接続と切断と投稿は管理者だけで_拒否したら何も実行しない() throws Exception {
        asNonMember();
        fx.connectableSite();

        mockMvc.perform(post("/api/projects/7/sns/threads/test")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/projects/7/sns/threads")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/threads/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"c\",\"clientSecret\":\"s\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/threads/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.a\",\"code\":\"c\"}"))
                .andExpect(status().isForbidden());

        assertTrue(!touchedSite());
        assertTrue(fx.world.http.requests("POST", "/oauth/access_token").isEmpty());
    }

    @Test
    void 認可を始めると認可URLだけを返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.connectableSite();

        mockMvc.perform(post("/api/projects/7/sns/threads/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"cid\",\"clientSecret\":\"csecret\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizeUrl").value(containsString("https://threads.example/authorize?")))
                .andExpect(jsonPath("$.authorizeUrl").value(containsString("client_id=cid")))
                .andExpect(content().string(not(containsString("csecret"))));
    }

    @Test
    void 認可の入力が欠けていれば400() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.connectableSite();

        mockMvc.perform(post("/api/projects/7/sns/threads/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"\",\"clientSecret\":\"s\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(!touchedSite());
    }

    @Test
    void 接続できない理由は409で返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.projectWithProductionSite(null);

        mockMvc.perform(post("/api/projects/7/sns/threads/authorize").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientId\":\"cid\",\"clientSecret\":\"csecret\",\"redirectUri\":\"https://l/cb\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("本番サイト")));
    }

    @Test
    void コールバックはアカウント名だけを返す() throws Exception {
        fx.world.actingAsAdmin(1L).signedInAs("sub-1");
        fx.connectableSite();
        String state = SnsFixture.queryParam(service.startAuthorization(7L, "cid", "csecret", "https://l/cb"), "state");
        fx.world.http.json("POST", "/oauth/access_token", "{\"access_token\":\"SHORT-TOKEN\",\"user_id\":\"17841400000000001\"}");
        fx.world.http.json("GET", "/access_token", "{\"access_token\":\"LONG-TOKEN\",\"expires_in\":5184000}");
        fx.world.http.json("GET", "/v1.0/me", "{\"id\":\"1\",\"username\":\"lets_blog\"}");
        fx.world.letsblogSns("config-set", "threads", "{\"sns\":\"threads\",\"status\":\"接続済み\"}");

        mockMvc.perform(post("/api/projects/7/sns/threads/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"" + state + "\",\"code\":\"the-code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountName").value("lets_blog"))
                .andExpect(content().string(not(containsString("token"))));
        assertTrue(fx.world.http.requests("POST", "/oauth/access_token").get(0).bodyText().contains("code=the-code"));
    }

    @Test
    void コールバックの入力が欠けていれば400() throws Exception {
        fx.world.actingAsAdmin(1L);

        mockMvc.perform(post("/api/projects/7/sns/threads/callback").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":\"7.abc\"}"))
                .andExpect(status().isBadRequest());
        assertTrue(fx.world.http.requests("POST", "/oauth/access_token").isEmpty());
    }

    @Test
    void テスト投稿の結果を返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.connectableSite();
        fx.world.letsblogSnsFails("test", "threads", "Threads の投稿に失敗しました");

        mockMvc.perform(post("/api/projects/7/sns/threads/test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value(containsString("Threads の投稿に失敗しました")));
    }

    @Test
    void 切断すると本文なしで204を返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.connectableSite();
        fx.world.letsblogSns("config-clear", "threads", "{}");

        mockMvc.perform(delete("/api/projects/7/sns/threads")).andExpect(status().isNoContent());

        assertEquals(1, fx.world.snsRequests("config-clear").size());
        assertEquals("threads", fx.world.snsRequests("config-clear").get(0).path("sns").asText());
    }

    @Test
    void 切断できない理由は409で返す() throws Exception {
        fx.world.actingAsAdmin(1L);
        fx.projectWithProductionSite(null);

        mockMvc.perform(delete("/api/projects/7/sns/threads"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("本番サイト")));
    }
}
