package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.project.cms.LetsblogPluginStatus;
import com.letsblog.project.cms.LetsblogPluginStatus.State;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.ProjectSnsTemplate;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.SnsTemplatesView;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.repository.ProjectSnsTemplateRepository;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * プロジェクトの告知文テンプレート(issue #1583)。公開時と PV 達成時のテンプレートを別々に保存し、
 * 本番サイトのプラグインへ `sns templates set`(wp-cli、標準入力の JSON)で送る。空は「既定の告知文を使う」の意味で、
 * 空へ戻したときも本番サイトへ送る。送れなければ送信失敗として記録し(保存は残す)、再送で回復できる。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SnsTemplateServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private SiteRepository siteRepository;
    @Mock
    private SiteService siteService;
    @Mock
    private ProjectSnsTemplateRepository repository;

    private final ObjectMapper mapper = new ObjectMapper();
    private ProjectSnsTemplate stored;
    private SnsTemplateService service;

    @BeforeEach
    void setUp() {
        service = new SnsTemplateService(projectRepository, siteRepository, siteService, repository, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(repository.findById(7L)).thenAnswer(i -> Optional.ofNullable(stored));
        when(repository.save(any(ProjectSnsTemplate.class))).thenAnswer(i -> {
            stored = i.getArgument(0);
            return stored;
        });
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
    }

    private JsonNode sentPayload() throws Exception {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(siteService).runLetsblogSns(eq(3L), eq("templates-set"), isNull(), payload.capture());
        return mapper.readTree(payload.getValue());
    }

    // ---- view ----

    @Test
    void view_未登録のプロジェクトはNotFound() {
        when(projectRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> service.view(7L));
    }

    @Test
    void view_まだ保存していなければ空のテンプレートとまだ送っていない状態を返す() {
        project(3L);

        SnsTemplatesView view = service.view(7L);

        assertEquals("", view.publishTemplate());
        assertEquals("", view.pvTemplate());
        assertEquals("NONE", view.send().state());
        assertNull(view.send().error());
        assertNull(view.send().at());
    }

    @Test
    void view_保存済みのテンプレートと記録済みの送信状態を返す() {
        project(3L);
        stored = row("【新着】{title} {url}", "{threshold}PV", "FAILED", "本番サイトに届きません", NOW);

        SnsTemplatesView view = service.view(7L);

        assertEquals("【新着】{title} {url}", view.publishTemplate());
        assertEquals("{threshold}PV", view.pvTemplate());
        assertEquals("FAILED", view.send().state());
        assertEquals("本番サイトに届きません", view.send().error());
        assertEquals("2026-10-06T00:00:00Z", view.send().at());
    }

    @Test
    void view_送信したことがない行はNONEでatはnull() {
        project(3L);
        stored = row("a", "b", null, null, null);

        SnsTemplatesView view = service.view(7L);

        assertEquals("NONE", view.send().state());
        assertNull(view.send().at());
    }

    @Test
    void view_送信時刻が記録されていない状態でも例外にせずatはnull() {
        project(3L);
        stored = row("a", "b", "SENT", null, null);

        SnsTemplatesView view = service.view(7L);

        assertEquals("SENT", view.send().state());
        assertNull(view.send().at());
    }

    // ---- save ----

    @Test
    void save_公開時とPV達成時を別々に保存し_本番サイトへ送って送信済みにする() throws Exception {
        productionSiteReady();

        SnsTemplatesView view = service.save(7L, "【新着】{title} {url}", "{period}で{threshold}PV達成 {title} {url}");

        assertEquals("【新着】{title} {url}", view.publishTemplate());
        assertEquals("{period}で{threshold}PV達成 {title} {url}", view.pvTemplate());
        assertEquals("SENT", view.send().state());
        assertNull(view.send().error());
        assertEquals("2026-10-06T00:00:00Z", view.send().at());
        assertEquals("【新着】{title} {url}", stored.getPublishTemplate());
        assertEquals("{period}で{threshold}PV達成 {title} {url}", stored.getPvTemplate());
        assertEquals(7L, stored.getProjectId());
        JsonNode payload = sentPayload();
        assertEquals("【新着】{title} {url}", payload.get("publish").asText());
        assertEquals("{period}で{threshold}PV達成 {title} {url}", payload.get("pv").asText());
    }

    @Test
    void save_保存済みの行を更新する() {
        productionSiteReady();
        ProjectSnsTemplate existing = row("古い", "古いPV", "SENT", null, NOW);
        stored = existing;

        service.save(7L, "新しい", "新しいPV");

        assertTrue(stored == existing);
        assertEquals("新しい", existing.getPublishTemplate());
        assertEquals("新しいPV", existing.getPvTemplate());
    }

    @Test
    void save_空に戻したときも本番サイトへ送る_nullは空として扱う() throws Exception {
        productionSiteReady();
        stored = row("【新着】{title}", "{threshold}PV", "SENT", null, NOW);

        SnsTemplatesView view = service.save(7L, null, "");

        assertEquals("", view.publishTemplate());
        assertEquals("", view.pvTemplate());
        JsonNode payload = sentPayload();
        assertEquals("", payload.get("publish").asText());
        assertEquals("", payload.get("pv").asText());
    }

    @Test
    void save_上限の1000文字ちょうどは保存できる() {
        productionSiteReady();

        SnsTemplatesView view = service.save(7L, "あ".repeat(1000), "い".repeat(1000));

        assertEquals(1000, view.publishTemplate().length());
        assertEquals(1000, view.pvTemplate().length());
    }

    @Test
    void save_公開時が1000文字を超えるなら保存も送信もせず拒否する() {
        productionSiteReady();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.save(7L, "あ".repeat(1001), ""));

        assertTrue(e.getMessage().contains("1000"));
        verify(repository, never()).save(any());
        verify(siteService, never()).runLetsblogSns(anyLong(), any(), any(), any());
    }

    @Test
    void save_PV達成時が1000文字を超えるなら保存も送信もせず拒否する() {
        productionSiteReady();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.save(7L, "", "い".repeat(1001)));

        assertTrue(e.getMessage().contains("1000"));
        verify(repository, never()).save(any());
    }

    @Test
    void save_公開時のテンプレートにperiodやthresholdは使えない() {
        productionSiteReady();

        IllegalArgumentException period = assertThrows(IllegalArgumentException.class,
                () -> service.save(7L, "{title} {period}", ""));
        IllegalArgumentException threshold = assertThrows(IllegalArgumentException.class,
                () -> service.save(7L, "{threshold}PV", ""));

        assertTrue(period.getMessage().contains("{period}"));
        assertTrue(threshold.getMessage().contains("{threshold}"));
        verify(repository, never()).save(any());
        verify(siteService, never()).runLetsblogSns(anyLong(), any(), any(), any());
    }

    @Test
    void save_未登録のプロジェクトはNotFound() {
        when(projectRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> service.save(7L, "a", "b"));
    }

    @Test
    void save_本番サイトが無ければ保存は残し_送信失敗として記録する() {
        project(null);

        SnsTemplatesView view = service.save(7L, "a", "b");

        assertEquals("a", view.publishTemplate());
        assertEquals("FAILED", view.send().state());
        assertTrue(view.send().error().contains("本番サイト"));
        verify(siteService, never()).runLetsblogSns(anyLong(), any(), any(), any());
    }

    @Test
    void save_本番サイトの登録が見つからなければ送信失敗にする() {
        project(3L);
        when(siteRepository.findById(3L)).thenReturn(Optional.empty());

        SnsTemplatesView view = service.save(7L, "a", "b");

        assertEquals("FAILED", view.send().state());
        assertTrue(view.send().error().contains("本番サイト"));
    }

    @Test
    void save_本番サイトに届かなければ送信失敗にする() {
        productionSiteReady();
        when(siteService.getLetsblogPluginStatus(3L)).thenThrow(new IllegalStateException("接続失敗"));

        SnsTemplatesView view = service.save(7L, "a", "b");

        assertEquals("FAILED", view.send().state());
        assertTrue(view.send().error().contains("届かない"));
        assertTrue(view.send().error().contains("接続失敗"));
    }

    @Test
    void save_プラグインが未導入なら送信失敗にする() {
        productionSiteReady();
        when(siteService.getLetsblogPluginStatus(3L)).thenReturn(new LetsblogPluginStatus(State.NOT_INSTALLED, null, null));

        SnsTemplatesView view = service.save(7L, "a", "b");

        assertEquals("FAILED", view.send().state());
        assertTrue(view.send().error().contains("未導入"));
        verify(siteService, never()).runLetsblogSns(anyLong(), any(), any(), any());
    }

    @Test
    void save_プラグインが要更新なら送信失敗にする() {
        productionSiteReady();
        when(siteService.getLetsblogPluginStatus(3L)).thenReturn(new LetsblogPluginStatus(State.NEEDS_UPDATE, "0", 0));

        SnsTemplatesView view = service.save(7L, "a", "b");

        assertEquals("FAILED", view.send().state());
        assertTrue(view.send().error().contains("要更新"));
    }

    @Test
    void save_wp_cliの実行に失敗したら保存は残し_理由つきの送信失敗にする() {
        productionSiteReady();
        when(siteService.runLetsblogSns(eq(3L), eq("templates-set"), isNull(), any()))
                .thenThrow(new IllegalStateException("templates は未対応です"));

        SnsTemplatesView view = service.save(7L, "a", "b");

        assertEquals("a", view.publishTemplate());
        assertEquals("FAILED", view.send().state());
        assertTrue(view.send().error().contains("templates は未対応です"));
        assertEquals("a", stored.getPublishTemplate());
    }

    // ---- resend ----

    @Test
    void resend_保存済みのテンプレートを送り直し_送信済みに戻す() throws Exception {
        productionSiteReady();
        stored = row("【新着】{title}", "{threshold}PV", "FAILED", "本番サイトに届きません", NOW);

        SnsTemplatesView view = service.resend(7L);

        assertEquals("SENT", view.send().state());
        assertNull(view.send().error());
        JsonNode payload = sentPayload();
        assertEquals("【新着】{title}", payload.get("publish").asText());
        assertEquals("{threshold}PV", payload.get("pv").asText());
    }

    @Test
    void resend_まだ保存していなければ空のテンプレートを送る() throws Exception {
        productionSiteReady();

        SnsTemplatesView view = service.resend(7L);

        assertEquals("SENT", view.send().state());
        JsonNode payload = sentPayload();
        assertEquals("", payload.get("publish").asText());
        assertEquals("", payload.get("pv").asText());
    }

    @Test
    void resend_未登録のプロジェクトはNotFound() {
        when(projectRepository.findById(7L)).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> service.resend(7L));
    }

    private static ProjectSnsTemplate row(String publish, String pv, String state, String error, Instant sentAt) {
        ProjectSnsTemplate row = new ProjectSnsTemplate();
        row.setProjectId(7L);
        row.setPublishTemplate(publish);
        row.setPvTemplate(pv);
        row.setSyncState(state);
        row.setSyncError(error);
        row.setSentAt(sentAt);
        return row;
    }
}
