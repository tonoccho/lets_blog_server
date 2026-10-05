package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.project.client.AnalyticsBridgeClient;
import com.letsblog.project.client.AnalyticsBridgeClient.GoogleAnalyticsCredentials;
import com.letsblog.project.cms.LetsblogPluginStatus;
import com.letsblog.project.cms.LetsblogPluginStatus.State;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.ProjectPvRule;
import com.letsblog.project.domain.ProjectPvSyncState;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.PvRulesView;
import com.letsblog.project.repository.ProjectPvRuleRepository;
import com.letsblog.project.repository.ProjectPvSyncStateRepository;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.repository.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * プロジェクトの PV 達成ルール(issue #1578)。ルールの正本はアプリ。ルールを保存したとき・GA を連携したときに、
 * 本番サイトのプラグインへ `pv config set`(GA4 の認証情報)と `pv rules set`(ルール全件・丸ごと置き換え)を
 * wp-cli で送る。GA 未連携ではルールを追加できない。送れなければ送信失敗として記録し、再送で回復できる。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PvRuleServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private static final GoogleAnalyticsCredentials LINKED =
            new GoogleAnalyticsCredentials(true, "987654321", "cid.apps", "csecret", "rtoken");
    private static final GoogleAnalyticsCredentials UNLINKED =
            new GoogleAnalyticsCredentials(false, null, null, null, null);

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private SiteRepository siteRepository;
    @Mock
    private SiteService siteService;
    @Mock
    private AnalyticsBridgeClient analyticsBridgeClient;
    @Mock
    private ProjectPvRuleRepository ruleRepository;
    @Mock
    private ProjectPvSyncStateRepository syncRepository;
    @Mock
    private CurrentActorService currentActorService;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ProjectPvRule> stored = new ArrayList<>();
    private ProjectPvSyncState savedState;
    private PvRuleService service;

    @BeforeEach
    void setUp() {
        service = new PvRuleService(projectRepository, siteRepository, siteService, analyticsBridgeClient,
                ruleRepository, syncRepository, currentActorService, mapper, Clock.fixed(NOW, ZoneOffset.UTC));
        when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer t");
        when(ruleRepository.findByProjectIdOrderByIdAsc(7L)).thenAnswer(i -> List.copyOf(stored));
        when(ruleRepository.save(any(ProjectPvRule.class))).thenAnswer(i -> {
            ProjectPvRule rule = i.getArgument(0);
            rule.setId(100L + stored.size());
            stored.add(rule);
            return rule;
        });
        when(syncRepository.save(any(ProjectPvSyncState.class))).thenAnswer(i -> {
            savedState = i.getArgument(0);
            return savedState;
        });
        when(syncRepository.findById(7L)).thenAnswer(i -> Optional.ofNullable(savedState));
    }

    private void project(Long productionSiteId) {
        Project project = new Project();
        project.setId(7L);
        project.setProductionSiteId(productionSiteId);
        when(projectRepository.findById(7L)).thenReturn(Optional.of(project));
    }

    private void productionSiteReady() {
        project(3L);
        Site site = new Site();
        site.setId(3L);
        when(siteRepository.findById(3L)).thenReturn(Optional.of(site));
        when(siteService.getLetsblogPluginStatus(3L)).thenReturn(new LetsblogPluginStatus(State.INSTALLED, "1", 1));
        when(siteService.runLetsblogSns(eq(3L), eq("pv-status"), isNull(), isNull()))
                .thenReturn("{\"configured\":true,\"state\":\"接続済み\",\"property_id\":\"987654321\"}");
    }

    private void rule(long id, String period, int threshold) {
        ProjectPvRule rule = new ProjectPvRule();
        rule.setId(id);
        rule.setProjectId(7L);
        rule.setPeriod(period);
        rule.setThreshold(threshold);
        stored.add(rule);
    }

    // ---- view ----

    @Test
    void view_未登録のプロジェクトはNotFound() {
        when(projectRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> service.view(7L));
    }

    @Test
    void view_GA連携済みならルールを追加でき_一覧とまだ送っていない状態を返す() {
        project(3L);
        rule(1L, "daily", 100);
        rule(2L, "total", 5000);
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);

        PvRulesView view = service.view(7L);

        assertTrue(view.addable());
        assertNull(view.reason());
        assertEquals(2, view.rules().size());
        assertEquals("r1", view.rules().get(0).id());
        assertEquals("daily", view.rules().get(0).period());
        assertEquals(100, view.rules().get(0).threshold());
        assertEquals("r2", view.rules().get(1).id());
        assertEquals("NONE", view.send().state());
        assertNull(view.send().error());
        assertNull(view.send().at());
    }

    @Test
    void view_GA未連携ならルールを追加できず理由を返す() {
        project(3L);
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(UNLINKED);

        PvRulesView view = service.view(7L);

        assertFalse(view.addable());
        assertTrue(view.reason().contains("GA"));
        assertTrue(view.rules().isEmpty());
    }

    @Test
    void view_GAの連携状態を取れなければ追加できず_その旨を理由に返し例外にしない() {
        project(3L);
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t"))
                .thenThrow(new IdentityServiceUnavailableException("analytics down", null));

        PvRulesView view = service.view(7L);

        assertFalse(view.addable());
        assertTrue(view.reason().contains("取得できません"));
        assertTrue(view.reason().contains("analytics down"));
    }

    @Test
    void view_記録済みの送信状態を返す() {
        project(3L);
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);
        ProjectPvSyncState state = new ProjectPvSyncState();
        state.setProjectId(7L);
        state.setState("FAILED");
        state.setError("本番サイトに届きません");
        state.setSentAt(NOW);
        savedState = state;

        PvRulesView view = service.view(7L);

        assertEquals("FAILED", view.send().state());
        assertEquals("本番サイトに届きません", view.send().error());
        assertEquals("2026-10-05T00:00:00Z", view.send().at());
    }

    @Test
    void view_送信時刻が記録されていない状態でも例外にせずatはnull() {
        project(3L);
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);
        ProjectPvSyncState state = new ProjectPvSyncState();
        state.setProjectId(7L);
        state.setState("SENT");
        savedState = state;

        PvRulesView view = service.view(7L);

        assertEquals("SENT", view.send().state());
        assertNull(view.send().at());
    }

    // ---- addRule ----

    @Test
    void addRule_保存して_GA認証情報とルール全件を本番サイトへ送り_送信済みにする() {
        productionSiteReady();
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);
        rule(1L, "daily", 100);

        PvRulesView view = service.addRule(7L, "total", 5000);

        assertEquals(2, view.rules().size());
        assertEquals("total", view.rules().get(1).period());
        assertEquals(5000, view.rules().get(1).threshold());
        assertEquals("SENT", view.send().state());
        assertNull(view.send().error());
        assertEquals("2026-10-05T00:00:00Z", view.send().at());

        ArgumentCaptor<String> config = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> rules = ArgumentCaptor.forClass(String.class);
        var order = inOrder(siteService);
        order.verify(siteService).runLetsblogSns(eq(3L), eq("pv-config-set"), isNull(), config.capture());
        order.verify(siteService).runLetsblogSns(eq(3L), eq("pv-status"), isNull(), isNull());
        order.verify(siteService).runLetsblogSns(eq(3L), eq("pv-rules-set"), isNull(), rules.capture());
        JsonNode configJson = parse(config.getValue());
        assertEquals("987654321", configJson.get("property_id").asText());
        assertEquals("cid.apps", configJson.get("client_id").asText());
        assertEquals("csecret", configJson.get("client_secret").asText());
        assertEquals("rtoken", configJson.get("refresh_token").asText());
        JsonNode rulesJson = parse(rules.getValue());
        assertEquals(2, rulesJson.size());
        assertEquals("r1", rulesJson.get(0).get("id").asText());
        assertEquals("daily", rulesJson.get(0).get("period").asText());
        assertEquals(100, rulesJson.get(0).get("threshold").asInt());
        assertEquals("total", rulesJson.get(1).get("period").asText());
        assertEquals(5000, rulesJson.get(1).get("threshold").asInt());
    }

    @Test
    void addRule_GA未連携なら保存も送信もせず理由つきで拒否する() {
        productionSiteReady();
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(UNLINKED);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.addRule(7L, "daily", 100));

        assertTrue(e.getMessage().contains("GA"));
        verify(ruleRepository, never()).save(any());
        verify(siteService, never()).runLetsblogSns(anyLong(), any(), any(), any());
    }

    @Test
    void addRule_GAの連携状態を取れなければ保存せず拒否する() {
        productionSiteReady();
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t"))
                .thenThrow(new IdentityServiceUnavailableException("analytics down", null));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.addRule(7L, "daily", 100));

        assertTrue(e.getMessage().contains("取得できません"));
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void addRule_期間が不正なら拒否する() {
        productionSiteReady();

        assertThrows(IllegalArgumentException.class, () -> service.addRule(7L, "weekly", 100));
        assertThrows(IllegalArgumentException.class, () -> service.addRule(7L, null, 100));
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void addRule_閾値が1未満なら拒否する() {
        productionSiteReady();

        assertThrows(IllegalArgumentException.class, () -> service.addRule(7L, "daily", 0));
        assertThrows(IllegalArgumentException.class, () -> service.addRule(7L, "daily", -3));
        verify(ruleRepository, never()).save(any());
    }

    @Test
    void addRule_未登録のプロジェクトはNotFound() {
        when(projectRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> service.addRule(7L, "daily", 100));
    }

    @Test
    void addRule_本番サイトへ送れなくてもルールは保存され_送信失敗として記録する() {
        productionSiteReady();
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);
        when(siteService.runLetsblogSns(eq(3L), eq("pv-config-set"), isNull(), any()))
                .thenThrow(new IllegalStateException("接続失敗"));

        PvRulesView view = service.addRule(7L, "daily", 100);

        assertEquals(1, view.rules().size());
        assertEquals("FAILED", view.send().state());
        assertTrue(view.send().error().contains("接続失敗"));
        verify(siteService, never()).runLetsblogSns(eq(3L), eq("pv-rules-set"), any(), any());
    }

    @Test
    void addRule_送信失敗の理由に認証情報は含めない() {
        productionSiteReady();
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);
        when(siteService.runLetsblogSns(eq(3L), eq("pv-config-set"), isNull(), any()))
                .thenThrow(new IllegalStateException("接続失敗"));

        PvRulesView view = service.addRule(7L, "daily", 100);

        assertFalse(view.send().error().contains("csecret"));
        assertFalse(view.send().error().contains("rtoken"));
    }

    @Test
    void addRule_プラグインが接続済みにならなければ送信失敗にしルールは送らない() {
        productionSiteReady();
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);
        when(siteService.runLetsblogSns(eq(3L), eq("pv-status"), isNull(), isNull()))
                .thenReturn("{\"configured\":true,\"state\":\"要再設定\"}");

        PvRulesView view = service.addRule(7L, "daily", 100);

        assertEquals("FAILED", view.send().state());
        assertTrue(view.send().error().contains("接続済み"));
        verify(siteService, never()).runLetsblogSns(eq(3L), eq("pv-rules-set"), any(), any());
    }

    @Test
    void addRule_プラグインが未設定と答えても送信失敗にする() {
        productionSiteReady();
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);
        when(siteService.runLetsblogSns(eq(3L), eq("pv-status"), isNull(), isNull()))
                .thenReturn("{\"configured\":false,\"state\":\"接続済み\"}");

        assertEquals("FAILED", service.addRule(7L, "daily", 100).send().state());
    }

    @Test
    void addRule_ルール送信が失敗したら送信失敗にする() {
        productionSiteReady();
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);
        when(siteService.runLetsblogSns(eq(3L), eq("pv-rules-set"), isNull(), any()))
                .thenThrow(new IllegalStateException("rules 失敗"));

        PvRulesView view = service.addRule(7L, "daily", 100);

        assertEquals("FAILED", view.send().state());
        assertTrue(view.send().error().contains("rules 失敗"));
    }

    @Test
    void addRule_プラグインの状態出力を解釈できなければ送信失敗にする() {
        productionSiteReady();
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);
        when(siteService.runLetsblogSns(eq(3L), eq("pv-status"), isNull(), isNull())).thenReturn("garbage");

        assertEquals("FAILED", service.addRule(7L, "daily", 100).send().state());
    }

    @Test
    void addRule_本番サイトが無ければ送信失敗として理由を記録する() {
        project(null);
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);

        PvRulesView view = service.addRule(7L, "daily", 100);

        assertEquals(1, view.rules().size());
        assertEquals("FAILED", view.send().state());
        assertTrue(view.send().error().contains("本番サイト"));
        verify(siteService, never()).runLetsblogSns(anyLong(), any(), any(), any());
    }

    @Test
    void addRule_本番サイトの行が無くても送信失敗として記録する() {
        project(3L);
        when(siteRepository.findById(3L)).thenReturn(Optional.empty());
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);

        assertTrue(service.addRule(7L, "daily", 100).send().error().contains("本番サイト"));
    }

    @Test
    void addRule_プラグインが未導入なら送信失敗として記録する() {
        productionSiteReady();
        when(siteService.getLetsblogPluginStatus(3L)).thenReturn(new LetsblogPluginStatus(State.NOT_INSTALLED, null, null));
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);

        PvRulesView view = service.addRule(7L, "daily", 100);

        assertEquals("FAILED", view.send().state());
        assertTrue(view.send().error().contains("未導入"));
        verify(siteService, never()).runLetsblogSns(anyLong(), any(), any(), any());
    }

    @Test
    void addRule_プラグインが要更新なら送信失敗として記録する() {
        productionSiteReady();
        when(siteService.getLetsblogPluginStatus(3L)).thenReturn(new LetsblogPluginStatus(State.NEEDS_UPDATE, "1", 0));
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);

        assertTrue(service.addRule(7L, "daily", 100).send().error().contains("要更新"));
    }

    @Test
    void addRule_プラグインの状態を取れなければ届かない旨を送信失敗として記録する() {
        productionSiteReady();
        when(siteService.getLetsblogPluginStatus(3L)).thenThrow(new IllegalStateException("接続失敗"));
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);

        PvRulesView view = service.addRule(7L, "daily", 100);

        assertEquals("FAILED", view.send().state());
        assertTrue(view.send().error().contains("届かない"));
    }

    // ---- deleteRule ----

    @Test
    void deleteRule_ルールを消して_残りのルール全件を本番サイトへ送る() {
        productionSiteReady();
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);
        rule(1L, "daily", 100);
        rule(2L, "total", 5000);
        ProjectPvRule second = stored.get(1);
        when(ruleRepository.findByIdAndProjectId(1L, 7L)).thenReturn(Optional.of(stored.get(0)));
        org.mockito.Mockito.doAnswer(i -> stored.remove(i.getArgument(0))).when(ruleRepository).delete(any(ProjectPvRule.class));

        PvRulesView view = service.deleteRule(7L, "r1");

        assertEquals(1, view.rules().size());
        assertEquals("r2", view.rules().get(0).id());
        assertEquals("SENT", view.send().state());
        ArgumentCaptor<String> rules = ArgumentCaptor.forClass(String.class);
        verify(siteService).runLetsblogSns(eq(3L), eq("pv-rules-set"), isNull(), rules.capture());
        JsonNode json = parse(rules.getValue());
        assertEquals(1, json.size());
        assertEquals("r2", json.get(0).get("id").asText());
        assertEquals(second.getThreshold(), json.get(0).get("threshold").asInt());
    }

    @Test
    void deleteRule_最後のルールを消すと空の配列を送る() {
        productionSiteReady();
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);
        rule(1L, "daily", 100);
        when(ruleRepository.findByIdAndProjectId(1L, 7L)).thenReturn(Optional.of(stored.get(0)));
        org.mockito.Mockito.doAnswer(i -> stored.remove(i.getArgument(0))).when(ruleRepository).delete(any(ProjectPvRule.class));

        service.deleteRule(7L, "r1");

        verify(siteService).runLetsblogSns(3L, "pv-rules-set", null, "[]");
    }

    @Test
    void deleteRule_別プロジェクトのルールや存在しないルールは消せない() {
        project(3L);
        when(ruleRepository.findByIdAndProjectId(9L, 7L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.deleteRule(7L, "r9"));
        verify(ruleRepository, never()).delete(any());
    }

    @Test
    void deleteRule_ルールIDの形式が不正なら拒否する() {
        project(3L);

        assertThrows(IllegalArgumentException.class, () -> service.deleteRule(7L, "../x"));
        assertThrows(IllegalArgumentException.class, () -> service.deleteRule(7L, "9"));
        assertThrows(IllegalArgumentException.class, () -> service.deleteRule(7L, null));
        verify(ruleRepository, never()).delete(any());
    }

    @Test
    void deleteRule_GAが未連携でも削除はでき_送信失敗として記録する() {
        productionSiteReady();
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(UNLINKED);
        rule(1L, "daily", 100);
        when(ruleRepository.findByIdAndProjectId(1L, 7L)).thenReturn(Optional.of(stored.get(0)));
        org.mockito.Mockito.doAnswer(i -> stored.remove(i.getArgument(0))).when(ruleRepository).delete(any(ProjectPvRule.class));

        PvRulesView view = service.deleteRule(7L, "r1");

        assertTrue(view.rules().isEmpty());
        assertEquals("FAILED", view.send().state());
        assertTrue(view.send().error().contains("GA"));
    }

    // ---- resend / onGoogleAnalyticsConnected ----

    @Test
    void resend_ルール全件とGA認証情報を送り直して送信済みにする() {
        productionSiteReady();
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);
        rule(1L, "daily", 100);
        ProjectPvSyncState failed = new ProjectPvSyncState();
        failed.setProjectId(7L);
        failed.setState("FAILED");
        failed.setError("前回の失敗");
        savedState = failed;

        PvRulesView view = service.resend(7L);

        assertEquals("SENT", view.send().state());
        assertNull(view.send().error());
        verify(siteService).runLetsblogSns(eq(3L), eq("pv-config-set"), isNull(), any());
        verify(siteService).runLetsblogSns(eq(3L), eq("pv-rules-set"), isNull(), any());
    }

    @Test
    void resend_まだ届かなければ送信失敗のまま理由を返す() {
        productionSiteReady();
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);
        when(siteService.runLetsblogSns(eq(3L), eq("pv-config-set"), isNull(), any()))
                .thenThrow(new IllegalStateException("まだ届かない"));

        PvRulesView view = service.resend(7L);

        assertEquals("FAILED", view.send().state());
        assertTrue(view.send().error().contains("まだ届かない"));
    }

    @Test
    void resend_GAの認証情報を取れなければ送信失敗として記録する() {
        productionSiteReady();
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t"))
                .thenThrow(new IdentityServiceUnavailableException("analytics down", null));

        PvRulesView view = service.resend(7L);

        assertEquals("FAILED", view.send().state());
        assertTrue(view.send().error().contains("analytics down"));
    }

    @Test
    void resend_未登録のプロジェクトはNotFound() {
        when(projectRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> service.resend(7L));
    }

    @Test
    void GA連携の完了で_ルールが無くてもGA認証情報を送り_設定済みにする() {
        productionSiteReady();
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);

        service.onGoogleAnalyticsConnected(7L);

        verify(siteService).runLetsblogSns(eq(3L), eq("pv-config-set"), isNull(), any());
        verify(siteService).runLetsblogSns(3L, "pv-rules-set", null, "[]");
        assertEquals("SENT", savedState.getState());
        assertEquals(7L, savedState.getProjectId());
    }

    @Test
    void GA連携の完了で送れなくても例外にせず_送信失敗として記録する() {
        project(null);
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);

        service.onGoogleAnalyticsConnected(7L);

        assertEquals("FAILED", savedState.getState());
    }

    @Test
    void GA連携の完了通知は未登録のプロジェクトならNotFound() {
        when(projectRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> service.onGoogleAnalyticsConnected(7L));
    }

    @Test
    void 送信結果は同じプロジェクトの既存の状態を更新して記録する() {
        productionSiteReady();
        when(analyticsBridgeClient.googleAnalyticsCredentials(7L, "Bearer t")).thenReturn(LINKED);
        ProjectPvSyncState existing = new ProjectPvSyncState();
        existing.setProjectId(7L);
        existing.setState("FAILED");
        existing.setError("old");
        savedState = existing;

        service.resend(7L);

        assertTrue(savedState == existing);
        assertEquals("SENT", existing.getState());
        assertNull(existing.getError());
        assertEquals(NOW, existing.getSentAt());
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("JSONではありません: " + json, e);
        }
    }
}
