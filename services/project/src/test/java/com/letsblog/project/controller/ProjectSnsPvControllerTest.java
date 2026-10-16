package com.letsblog.project.controller;

import com.letsblog.project.config.GlobalExceptionHandler;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.ProjectPvRule;
import com.letsblog.project.domain.ProjectPvSyncState;
import com.letsblog.project.cms.LetsblogPluginStatus.State;
import com.letsblog.project.client.AnalyticsBridgeClient.GoogleAnalyticsCredentials;
import com.letsblog.project.testsupport.ProjectApp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * プロジェクト設定画面の PV 達成ルールの API(issue #1578)。読むのはメンバーかadmin、変更と再送はadminだけ。
 *
 * <p>issue #1818: PvRuleService・AdminAuthorizationService・SiteService・AnalyticsBridgeClient は実物。
 * analytics-service・identity-service・CMS ブリッジの代わりにフェイクHTTPサーバーが応答し、差し替えるのはDBのリポジトリだけ。
 */
class ProjectSnsPvControllerTest {

    private static final String MEMBER = "/api/internal/identity/projects/7/members/5";
    private static final String CMS_SNS = "/api/internal/project/cms/letsblog-sns";
    private static final GoogleAnalyticsCredentials LINKED =
            new GoogleAnalyticsCredentials(true, "987654321", "cid.apps", "csecret", "rtoken");

    private final ProjectApp app = new ProjectApp();
    private final List<ProjectPvRule> stored = new ArrayList<>();
    private ProjectPvSyncState savedState;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ProjectSnsPvController(app.pvRuleService, app.world.adminAuthorizationService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(app.pvRuleRepository.findByProjectIdOrderByIdAsc(7L)).thenAnswer(i -> List.copyOf(stored));
        when(app.pvRuleRepository.save(any(ProjectPvRule.class))).thenAnswer(i -> {
            ProjectPvRule rule = i.getArgument(0);
            rule.setId(100L + stored.size());
            stored.add(rule);
            return rule;
        });
        when(app.pvSyncStateRepository.save(any(ProjectPvSyncState.class))).thenAnswer(i -> {
            savedState = i.getArgument(0);
            return savedState;
        });
        when(app.pvSyncStateRepository.findById(7L)).thenAnswer(i -> Optional.ofNullable(savedState));
    }

    @AfterEach
    void close() {
        app.close();
    }

    /** プロジェクト7の本番サイト3が、プラグイン導入済みで GA と連携済みの状態にする。 */
    private void productionSiteReady() {
        Project project = new Project();
        project.setId(7L);
        project.setProductionSiteId(3L);
        when(app.projectRepository.findById(7L)).thenReturn(Optional.of(project));
        app.world.addSite(3L, "本番サイト");
        app.world.pluginState(State.INSTALLED);
        app.world.analytics(LINKED);
        app.world.letsblogSns("pv-config-set", null, "{}");
        app.world.letsblogSns("pv-status", null, "{\"configured\":true,\"state\":\"接続済み\",\"property_id\":\"987654321\"}");
        app.world.letsblogSns("pv-rules-set", null, "{}");
    }

    private void rule(long id, String period, int threshold) {
        ProjectPvRule rule = new ProjectPvRule();
        rule.setId(id);
        rule.setProjectId(7L);
        rule.setPeriod(period);
        rule.setThreshold(threshold);
        stored.add(rule);
    }

    private void asNonMember() {
        app.world.actingAsUser(5L);
        app.world.http.json("GET", MEMBER, "false");
    }

    private boolean sentToSite() {
        return !app.world.http.requests("POST", CMS_SNS).isEmpty();
    }

    @Test
    void ルールと送信状態を返す() throws Exception {
        app.world.actingAsAdmin(1L);
        productionSiteReady();
        rule(1L, "daily", 100);
        ProjectPvSyncState state = new ProjectPvSyncState();
        state.setProjectId(7L);
        state.setState("SENT");
        state.setSentAt(java.time.Instant.parse("2026-10-05T00:00:00Z"));
        savedState = state;

        mockMvc.perform(get("/api/projects/7/sns/pv"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addable").value(true))
                .andExpect(jsonPath("$.rules[0].id").value("r1"))
                .andExpect(jsonPath("$.rules[0].period").value("daily"))
                .andExpect(jsonPath("$.rules[0].threshold").value(100))
                .andExpect(jsonPath("$.send.state").value("SENT"))
                .andExpect(jsonPath("$.send.at").value("2026-10-05T00:00:00Z"));
    }

    @Test
    void メンバーでもadminでもなければ読めない() throws Exception {
        asNonMember();
        productionSiteReady();

        mockMvc.perform(get("/api/projects/7/sns/pv")).andExpect(status().isForbidden());

        assertTrue(app.world.http.requests("GET", ".*google-analytics/credentials").isEmpty());
    }

    @Test
    void ルールを追加する() throws Exception {
        app.world.actingAsAdmin(1L);
        productionSiteReady();

        mockMvc.perform(post("/api/projects/7/sns/pv/rules").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"period\":\"total\",\"threshold\":5000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules[0].id").value("r100"))
                .andExpect(jsonPath("$.rules[0].period").value("total"))
                .andExpect(jsonPath("$.rules[0].threshold").value(5000))
                .andExpect(jsonPath("$.send.state").value("SENT"));
        assertTrue(sentToSite(), "ルール全件を本番サイトへ送る");
    }

    @Test
    void 入力が欠けているか閾値が1未満なら400() throws Exception {
        app.world.actingAsAdmin(1L);
        productionSiteReady();

        mockMvc.perform(post("/api/projects/7/sns/pv/rules").contentType(MediaType.APPLICATION_JSON)
                .content("{\"threshold\":5}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/projects/7/sns/pv/rules").contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"daily\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/projects/7/sns/pv/rules").contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"daily\",\"threshold\":0}")).andExpect(status().isBadRequest());

        assertTrue(stored.isEmpty(), "ルールは保存されない");
        assertTrue(!sentToSite());
    }

    @Test
    void GA未連携などサービスの拒否は409で理由を返す() throws Exception {
        app.world.actingAsAdmin(1L);
        productionSiteReady();
        app.world.analytics(new GoogleAnalyticsCredentials(false, null, null, null, null));

        mockMvc.perform(post("/api/projects/7/sns/pv/rules").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"period\":\"daily\",\"threshold\":100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("GA")));
        assertTrue(stored.isEmpty());
    }

    @Test
    void ルールを削除する() throws Exception {
        app.world.actingAsAdmin(1L);
        productionSiteReady();
        rule(1L, "daily", 100);
        rule(2L, "total", 5000);
        when(app.pvRuleRepository.findByIdAndProjectId(1L, 7L)).thenReturn(Optional.of(stored.get(0)));
        org.mockito.Mockito.doAnswer(i -> stored.remove(i.getArgument(0))).when(app.pvRuleRepository)
                .delete(any(ProjectPvRule.class));

        mockMvc.perform(delete("/api/projects/7/sns/pv/rules/r1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rules.length()").value(1))
                .andExpect(jsonPath("$.rules[0].id").value("r2"));
        assertTrue(sentToSite());
    }

    @Test
    void 再送する() throws Exception {
        app.world.actingAsAdmin(1L);
        productionSiteReady();
        rule(1L, "daily", 100);

        mockMvc.perform(post("/api/projects/7/sns/pv/resend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.send.state").value("SENT"));
        assertTrue(sentToSite());
    }

    @Test
    void 変更と再送は管理者だけで_メンバーは拒否し何も実行しない() throws Exception {
        asNonMember();
        productionSiteReady();
        rule(1L, "daily", 100);

        mockMvc.perform(post("/api/projects/7/sns/pv/rules").contentType(MediaType.APPLICATION_JSON)
                .content("{\"period\":\"daily\",\"threshold\":100}")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/projects/7/sns/pv/rules/r1")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/projects/7/sns/pv/resend")).andExpect(status().isForbidden());

        assertTrue(!sentToSite());
        org.mockito.Mockito.verify(app.pvRuleRepository, org.mockito.Mockito.never()).save(any());
        org.mockito.Mockito.verify(app.pvRuleRepository, org.mockito.Mockito.never()).delete(any());
    }
}
