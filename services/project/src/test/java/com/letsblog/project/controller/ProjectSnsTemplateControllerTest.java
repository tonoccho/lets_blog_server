package com.letsblog.project.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.project.cms.LetsblogPluginStatus.State;
import com.letsblog.project.config.GlobalExceptionHandler;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.ProjectSnsTemplate;
import com.letsblog.project.testsupport.ProjectApp;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * プロジェクト設定画面の告知文テンプレートの API(issue #1583)。読むのはメンバーかadmin、保存と再送はadminだけ。
 *
 * <p>issue #1818: SnsTemplateService・AdminAuthorizationService・SiteService は実物。identity-service・CMS ブリッジの
 * 代わりにフェイクHTTPサーバーが応答し、差し替えるのはDBのリポジトリだけ。
 */
class ProjectSnsTemplateControllerTest {

    private static final String MEMBER = "/api/internal/identity/projects/7/members/5";

    private final ProjectApp app = new ProjectApp();
    private ProjectSnsTemplate stored;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProjectSnsTemplateController(app.snsTemplateService, app.world.adminAuthorizationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(app.snsTemplateRepository.findById(7L)).thenAnswer(i -> Optional.ofNullable(stored));
        when(app.snsTemplateRepository.save(any(ProjectSnsTemplate.class))).thenAnswer(i -> {
            stored = i.getArgument(0);
            return stored;
        });
    }

    @AfterEach
    void close() {
        app.close();
    }

    private void productionSiteReady() {
        Project project = new Project();
        project.setId(7L);
        project.setProductionSiteId(3L);
        when(app.projectRepository.findById(7L)).thenReturn(Optional.of(project));
        app.world.addSite(3L, "本番サイト");
        app.world.pluginState(State.INSTALLED);
        app.world.letsblogSns("templates-set", null, "{}");
    }

    private void asNonMember() {
        app.world.actingAsUser(5L);
        app.world.http.json("GET", MEMBER, "false");
    }

    private boolean sentToSite() {
        return !app.world.snsRequests("templates-set").isEmpty();
    }

    @Test
    void テンプレートと送信状態を返す() throws Exception {
        app.world.actingAsAdmin(1L);
        productionSiteReady();
        ProjectSnsTemplate row = new ProjectSnsTemplate();
        row.setProjectId(7L);
        row.setPublishTemplate("【新着】{title} {url}");
        row.setPvTemplate("{threshold}PV");
        row.setSyncState("SENT");
        row.setSentAt(java.time.Instant.parse("2026-10-06T00:00:00Z"));
        stored = row;

        mockMvc.perform(get("/api/projects/7/sns/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishTemplate").value("【新着】{title} {url}"))
                .andExpect(jsonPath("$.pvTemplate").value("{threshold}PV"))
                .andExpect(jsonPath("$.send.state").value("SENT"))
                .andExpect(jsonPath("$.send.at").value("2026-10-06T00:00:00Z"));
    }

    @Test
    void メンバーでもadminでもなければ読めない() throws Exception {
        asNonMember();
        productionSiteReady();

        mockMvc.perform(get("/api/projects/7/sns/templates")).andExpect(status().isForbidden());

        verify(app.snsTemplateRepository, never()).findById(any());
    }

    @Test
    void 公開時とPV達成時のテンプレートを保存する() throws Exception {
        app.world.actingAsAdmin(1L);
        productionSiteReady();

        mockMvc.perform(put("/api/projects/7/sns/templates").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publishTemplate\":\"【新着】{title} {url}\",\"pvTemplate\":\"{threshold}PV\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishTemplate").value("【新着】{title} {url}"))
                .andExpect(jsonPath("$.send.state").value("SENT"));
        assertEquals("【新着】{title} {url}", stored.getPublishTemplate());
        assertEquals("{threshold}PV", stored.getPvTemplate());
        JsonNode payload = app.world.objectMapper.readTree(app.world.snsRequests("templates-set").get(0).path("stdin").asText());
        assertEquals("【新着】{title} {url}", payload.get("publish").asText());
    }

    @Test
    void 欄が欠けていても空として保存へ渡す() throws Exception {
        app.world.actingAsAdmin(1L);
        productionSiteReady();

        mockMvc.perform(put("/api/projects/7/sns/templates").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publishTemplate").value(""))
                .andExpect(jsonPath("$.pvTemplate").value(""));
        assertEquals("", stored.getPublishTemplate());
        assertEquals("", stored.getPvTemplate());
    }

    @Test
    void サービスの拒否は409で理由を返す() throws Exception {
        app.world.actingAsAdmin(1L);
        productionSiteReady();

        mockMvc.perform(put("/api/projects/7/sns/templates").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publishTemplate\":\"{period}\",\"pvTemplate\":\"\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("{period}")));
        assertTrue(!sentToSite());
    }

    @Test
    void 再送する() throws Exception {
        app.world.actingAsAdmin(1L);
        productionSiteReady();
        ProjectSnsTemplate row = new ProjectSnsTemplate();
        row.setProjectId(7L);
        row.setPublishTemplate("a");
        row.setPvTemplate("b");
        row.setSyncState("FAILED");
        stored = row;

        mockMvc.perform(post("/api/projects/7/sns/templates/resend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.send.state").value("SENT"));
        assertTrue(sentToSite());
    }

    @Test
    void 保存と再送は管理者だけで_メンバーは拒否し何も実行しない() throws Exception {
        asNonMember();
        productionSiteReady();

        mockMvc.perform(put("/api/projects/7/sns/templates").contentType(MediaType.APPLICATION_JSON)
                .content("{\"publishTemplate\":\"a\",\"pvTemplate\":\"b\"}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/templates/resend")).andExpect(status().isForbidden());

        assertTrue(!sentToSite());
        verify(app.snsTemplateRepository, never()).save(any());
    }
}
