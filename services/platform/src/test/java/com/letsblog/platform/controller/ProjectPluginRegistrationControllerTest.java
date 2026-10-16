package com.letsblog.platform.controller;

import com.letsblog.platform.config.GlobalExceptionHandler;
import com.letsblog.platform.service.PluginFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ProjectPluginRegistrationController(issue #1808)。HTTP入出力と、サービス層の認可(requireProjectMemberOrAdmin)・
 * 検査・認証情報の暗号化を通して確かめる。
 *
 * <p>issue #1825: コントローラ・ProjectPluginRegistrationService・AdminAuthorizationService・
 * IdentityBridgeClientは実物で、Springの{@link MockMvc}(スタンドアロン)を通す。差し替えるのは外部との通信
 * (identity-serviceのHTTPフェイク、登録のDB=メモリ上のリポジトリ、接続先の名前解決)だけ。
 */
class ProjectPluginRegistrationControllerTest {

    private static final String BASE = "/api/projects/10/plugin-registrations";
    private static final String BODY =
            "{\"kind\":\"LLM\",\"name\":\"社内\",\"url\":\"http://gpu-pc.example:11434/v1\",\"credential\":\"sk\"}";

    private PluginFixture fixture;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        fixture = new PluginFixture();
        mvc = MockMvcBuilders.standaloneSetup(new ProjectPluginRegistrationController(fixture.projectService))
                .setControllerAdvice(new GlobalExceptionHandler(
                        new org.springframework.boot.servlet.autoconfigure.MultipartProperties()))
                .build();
    }

    @AfterEach
    void tearDown() {
        fixture.close();
    }

    @Test
    void 一覧_追加_編集_削除_使用中の切り替え_認証情報の消去を行える() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.credentialConfigured").value(true))
                .andExpect(jsonPath("$.credential").doesNotExist());

        mvc.perform(get(BASE)).andExpect(jsonPath("$[0].name").value("社内"));

        mvc.perform(put(BASE + "/1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"社内2\",\"url\":\"http://gpu-pc.example:11434/v1\"}"))
                .andExpect(jsonPath("$.name").value("社内2"))
                .andExpect(jsonPath("$.credentialConfigured").value(true));

        mvc.perform(put(BASE + "/1/in-use")).andExpect(jsonPath("$.inUse").value(true));
        mvc.perform(delete(BASE + "/1/credential")).andExpect(jsonPath("$.credentialConfigured").value(false));
        mvc.perform(delete(BASE + "/1")).andExpect(status().isNoContent());
        assertEquals(0, fixture.projectStore.size());
    }

    @Test
    void プロジェクトのメンバーなら操作でき_メンバーでなければ403() throws Exception {
        fixture.world.actingAsUser(5L).projectMember(10L, 5L, true);
        mvc.perform(get(BASE)).andExpect(status().isOk());

        fixture.world.actingAsUser(6L).projectMember(10L, 6L, false);
        mvc.perform(get(BASE)).andExpect(status().isForbidden());
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        assertEquals(0, fixture.projectStore.size());
    }

    @Test
    void identity_serviceの障害は502() throws Exception {
        fixture.world.actingAsUser(5L);
        fixture.world.http.status("GET", "/api/internal/identity/projects/10/members/5", 503, "{}");

        mvc.perform(get(BASE)).andExpect(status().isBadGateway());
    }
}
