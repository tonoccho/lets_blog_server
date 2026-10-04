package com.letsblog.project.service;

import com.letsblog.project.cms.CmsType;
import com.letsblog.project.cms.LetsblogPluginStatus;
import com.letsblog.project.client.BearerScope;
import com.letsblog.project.client.ContentBridgeClient;
import com.letsblog.project.client.ContentBridgeClient.SyncPayload;
import com.letsblog.project.domain.LetsblogSyncStatus;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.LetsblogSyncState;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.repository.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * カスタムタグ・CSS・プレフィックス・デザインの WordPress プラグインへの同期(issue #1558)。
 * 送信は wp-cli だけ({@link SiteService#syncLetsblogPlugin}がブリッジ経由で実行)。
 * 導入済み以外(未導入・要更新、issue #1557)のサイトへは送らず、失敗したサイトは状態に残して再同期で回復する。
 */
@ExtendWith(MockitoExtension.class)
class LetsblogSyncServiceTest {

    private static final SyncPayload PAYLOAD = new SyncPayload("{\"cssBundle\":\".a{}\"}", "h1");

    @Mock
    private SiteService siteService;
    @Mock
    private SiteRepository siteRepository;
    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private ContentBridgeClient contentBridgeClient;
    @Mock
    private CurrentActorService currentActorService;

    private final List<Runnable> queued = new ArrayList<>();
    private final Executor queueingExecutor = queued::add;
    private final Map<Long, Site> sites = new HashMap<>();
    private final List<Site> saved = new ArrayList<>();

    private LetsblogSyncService service;

    @BeforeEach
    void setUp() {
        service = new LetsblogSyncService(
                siteService, siteRepository, projectRepository, contentBridgeClient, currentActorService,
                queueingExecutor);
        lenient().when(siteRepository.findById(anyLong()))
                .thenAnswer(inv -> Optional.ofNullable(sites.get(inv.<Long>getArgument(0))));
        lenient().when(siteRepository.save(any(Site.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        lenient().when(currentActorService.getAuthorizationHeader()).thenReturn("Bearer t");
        lenient().when(contentBridgeClient.fetchSyncPayload(anyLong(), anyString())).thenReturn(PAYLOAD);
    }

    private Site site(Long id) {
        Site site = new Site();
        site.setId(id);
        site.setSiteKey("site-" + id);
        site.setCmsType(CmsType.WORDPRESS);
        sites.put(id, site);
        return site;
    }

    private Project project(Long id, Long local, Long test, Long production) {
        Project project = new Project();
        project.setId(id);
        project.setLocalSiteId(local);
        project.setTestSiteId(test);
        project.setProductionSiteId(production);
        return project;
    }

    private LetsblogPluginStatus installed() {
        return new LetsblogPluginStatus(LetsblogPluginStatus.State.INSTALLED, "1.0.0", 1);
    }

    private void drain() {
        List<Runnable> copy = new ArrayList<>(queued);
        queued.clear();
        copy.forEach(Runnable::run);
    }

    // ---- AC1: プロジェクトのすべてのサイトへ送り、ハッシュが一致する ----

    @Test
    void プロジェクトのすべてのサイトへ送り同期済みとハッシュを記録する() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, 2L, 3L)));
        site(1L); site(2L); site(3L);
        when(siteService.getLetsblogPluginStatus(anyLong())).thenReturn(installed());
        when(siteService.syncLetsblogPlugin(anyLong(), eq(PAYLOAD.payload()), eq("h1"))).thenReturn("h1");

        service.syncProject(5L, "Bearer t");

        verify(contentBridgeClient, times(1)).fetchSyncPayload(5L, "Bearer t");
        verify(siteService).syncLetsblogPlugin(1L, PAYLOAD.payload(), "h1");
        verify(siteService).syncLetsblogPlugin(2L, PAYLOAD.payload(), "h1");
        verify(siteService).syncLetsblogPlugin(3L, PAYLOAD.payload(), "h1");
        assertEquals(3, saved.size());
        for (Site s : saved) {
            assertEquals(LetsblogSyncStatus.SYNCED, s.getLetsblogSyncStatus());
            assertEquals("h1", s.getLetsblogSyncHash());
            assertNull(s.getLetsblogSyncError());
            assertNotNull(s.getLetsblogSyncedAt());
        }
    }

    @Test
    void 未設定のスロットと重複と存在しないサイトは無視する() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, 1L, 99L)));
        site(1L);
        when(siteService.getLetsblogPluginStatus(1L)).thenReturn(installed());
        when(siteService.syncLetsblogPlugin(1L, PAYLOAD.payload(), "h1")).thenReturn("h1");

        service.syncProject(5L, "Bearer t");

        verify(siteService, times(1)).syncLetsblogPlugin(anyLong(), anyString(), anyString());
        assertEquals(1, saved.size());
    }

    @Test
    void 存在しないプロジェクトは何もしない() {
        when(projectRepository.findById(5L)).thenReturn(Optional.empty());

        service.syncProject(5L, "Bearer t");

        verify(contentBridgeClient, never()).fetchSyncPayload(anyLong(), anyString());
    }

    @Test
    void サイトが無いプロジェクトでは内容を取得しない() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, null, null, null)));

        service.syncProject(5L, "Bearer t");

        verify(contentBridgeClient, never()).fetchSyncPayload(anyLong(), anyString());
    }

    @Test
    void 送信中は呼び出し元のトークンがブリッジ呼び出しに使われる() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, null, null)));
        site(1L);
        List<String> seen = new ArrayList<>();
        when(siteService.getLetsblogPluginStatus(1L)).thenAnswer(inv -> {
            seen.add(BearerScope.current());
            return installed();
        });
        when(siteService.syncLetsblogPlugin(anyLong(), anyString(), anyString())).thenAnswer(inv -> {
            seen.add(BearerScope.current());
            return "h1";
        });

        service.syncProject(5L, "Bearer scoped");

        assertEquals(List.of("Bearer scoped", "Bearer scoped"), seen);
        assertNull(BearerScope.current());
    }

    // ---- AC2: グローバルの変更はすべてのプロジェクトのサイトへ ----

    @Test
    void すべてのプロジェクトのサイトへ送る() {
        when(projectRepository.findAll()).thenReturn(List.of(project(5L, 1L, null, null), project(6L, 2L, null, null)));
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, null, null)));
        when(projectRepository.findById(6L)).thenReturn(Optional.of(project(6L, 2L, null, null)));
        site(1L); site(2L);
        when(siteService.getLetsblogPluginStatus(anyLong())).thenReturn(installed());
        when(siteService.syncLetsblogPlugin(anyLong(), anyString(), anyString())).thenReturn("h1");

        service.syncAll("Bearer t");

        verify(siteService).syncLetsblogPlugin(1L, PAYLOAD.payload(), "h1");
        verify(siteService).syncLetsblogPlugin(2L, PAYLOAD.payload(), "h1");
        verify(contentBridgeClient).fetchSyncPayload(5L, "Bearer t");
        verify(contentBridgeClient).fetchSyncPayload(6L, "Bearer t");
    }

    @Test
    void あるプロジェクトの失敗が他のプロジェクトの同期を止めない() {
        when(projectRepository.findAll()).thenReturn(List.of(project(5L, 1L, null, null), project(6L, 2L, null, null)));
        when(projectRepository.findById(6L)).thenReturn(Optional.of(project(6L, 2L, null, null)));
        when(projectRepository.findById(5L)).thenThrow(new IllegalStateException("db down"));
        site(2L);
        when(siteService.getLetsblogPluginStatus(2L)).thenReturn(installed());
        when(siteService.syncLetsblogPlugin(2L, PAYLOAD.payload(), "h1")).thenReturn("h1");

        service.syncAll("Bearer t");

        verify(siteService).syncLetsblogPlugin(2L, PAYLOAD.payload(), "h1");
    }

    // ---- AC5: 未導入・要更新のサイトへは送らない ----

    @Test
    void 未導入のサイトへは送らず見送りとして記録する() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, null, null)));
        site(1L);
        when(siteService.getLetsblogPluginStatus(1L)).thenReturn(new LetsblogPluginStatus(LetsblogPluginStatus.State.NOT_INSTALLED, null, null));

        service.syncProject(5L, "Bearer t");

        verify(siteService, never()).syncLetsblogPlugin(anyLong(), anyString(), anyString());
        assertEquals(LetsblogSyncStatus.SKIPPED, saved.get(0).getLetsblogSyncStatus());
        assertTrue(saved.get(0).getLetsblogSyncError().contains("未導入"));
        assertNull(saved.get(0).getLetsblogSyncHash());
    }

    @Test
    void 要更新のサイトへは送らず見送りとして記録する() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, null, null)));
        site(1L);
        when(siteService.getLetsblogPluginStatus(1L))
                .thenReturn(new LetsblogPluginStatus(LetsblogPluginStatus.State.NEEDS_UPDATE, "0.9.0", 0));

        service.syncProject(5L, "Bearer t");

        verify(siteService, never()).syncLetsblogPlugin(anyLong(), anyString(), anyString());
        assertEquals(LetsblogSyncStatus.SKIPPED, saved.get(0).getLetsblogSyncStatus());
        assertTrue(saved.get(0).getLetsblogSyncError().contains("要更新"));
    }

    // ---- AC3: 届かないサイトは失敗として残り、再同期で回復する ----

    @Test
    void 状態を取得できなければ失敗として記録し送らない() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, null, null)));
        site(1L);
        when(siteService.getLetsblogPluginStatus(1L)).thenThrow(new IllegalStateException("接続できません"));

        service.syncProject(5L, "Bearer t");

        verify(siteService, never()).syncLetsblogPlugin(anyLong(), anyString(), anyString());
        assertEquals(LetsblogSyncStatus.FAILED, saved.get(0).getLetsblogSyncStatus());
        assertTrue(saved.get(0).getLetsblogSyncError().contains("接続できません"));
    }

    @Test
    void 送信に失敗したら失敗として記録し他のサイトの送信は続ける() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, 2L, null)));
        site(1L); site(2L);
        when(siteService.getLetsblogPluginStatus(anyLong())).thenReturn(installed());
        when(siteService.syncLetsblogPlugin(eq(1L), anyString(), anyString()))
                .thenThrow(new IllegalStateException("wp-cliが失敗しました"));
        when(siteService.syncLetsblogPlugin(eq(2L), anyString(), anyString())).thenReturn("h1");

        service.syncProject(5L, "Bearer t");

        assertEquals(LetsblogSyncStatus.FAILED, sites.get(1L).getLetsblogSyncStatus());
        assertTrue(sites.get(1L).getLetsblogSyncError().contains("wp-cliが失敗しました"));
        assertEquals(LetsblogSyncStatus.SYNCED, sites.get(2L).getLetsblogSyncStatus());
    }

    @Test
    void 保存されたハッシュが期待と違えば失敗として記録する() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, null, null)));
        site(1L);
        when(siteService.getLetsblogPluginStatus(1L)).thenReturn(installed());
        when(siteService.syncLetsblogPlugin(1L, PAYLOAD.payload(), "h1")).thenReturn("other");

        service.syncProject(5L, "Bearer t");

        assertEquals(LetsblogSyncStatus.FAILED, saved.get(0).getLetsblogSyncStatus());
        assertTrue(saved.get(0).getLetsblogSyncError().contains("一致しません"));
    }

    @Test
    void 同期内容を取得できなければすべてのサイトを失敗として記録する() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, 2L, null)));
        site(1L); site(2L);
        when(contentBridgeClient.fetchSyncPayload(5L, "Bearer t")).thenThrow(new IllegalStateException("content down"));

        service.syncProject(5L, "Bearer t");

        verify(siteService, never()).syncLetsblogPlugin(anyLong(), anyString(), anyString());
        assertEquals(LetsblogSyncStatus.FAILED, sites.get(1L).getLetsblogSyncStatus());
        assertEquals(LetsblogSyncStatus.FAILED, sites.get(2L).getLetsblogSyncStatus());
        assertTrue(sites.get(1L).getLetsblogSyncError().contains("content down"));
    }

    @Test
    void 失敗したサイトは再同期で同期済みに戻りエラーが消える() {
        Site failed = site(1L);
        failed.setLetsblogSyncStatus(LetsblogSyncStatus.FAILED);
        failed.setLetsblogSyncError("接続できません");
        when(projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(1L, 1L, 1L))
                .thenReturn(Optional.of(project(5L, 1L, null, null)));
        when(siteService.getLetsblogPluginStatus(1L)).thenReturn(installed());
        when(siteService.syncLetsblogPlugin(1L, PAYLOAD.payload(), "h1")).thenReturn("h1");

        LetsblogSyncState state = service.syncSiteNow(1L);

        assertEquals(LetsblogSyncStatus.SYNCED, state.status());
        assertNull(state.error());
        assertEquals("h1", state.hash());
        assertNotNull(state.syncedAt());
    }

    @Test
    void 再同期はプロジェクトに紐付かないサイトを見送りにする() {
        site(1L);
        when(projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(1L, 1L, 1L))
                .thenReturn(Optional.empty());

        LetsblogSyncState state = service.syncSiteNow(1L);

        assertEquals(LetsblogSyncStatus.SKIPPED, state.status());
        verify(contentBridgeClient, never()).fetchSyncPayload(anyLong(), anyString());
    }

    @Test
    void 再同期で未登録のサイトはSiteNotFound() {
        assertThrows(SiteNotFoundException.class, () -> service.syncSiteNow(9L));
    }

    @Test
    void 再同期で内容を取得できなければ失敗の状態を返す() {
        site(1L);
        when(projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(1L, 1L, 1L))
                .thenReturn(Optional.of(project(5L, 1L, null, null)));
        when(contentBridgeClient.fetchSyncPayload(5L, "Bearer t")).thenThrow(new IllegalStateException("content down"));

        assertEquals(LetsblogSyncStatus.FAILED, service.syncSiteNow(1L).status());
    }

    // ---- 状態の参照 ----

    @Test
    void 同期したことのないサイトの状態はnull() {
        site(1L);

        assertNull(service.getState(1L));
    }

    @Test
    void 状態を返す() {
        Site s = site(1L);
        s.setLetsblogSyncStatus(LetsblogSyncStatus.FAILED);
        s.setLetsblogSyncError("x");

        assertEquals(LetsblogSyncStatus.FAILED, service.getState(1L).status());
        assertEquals("x", service.getState(1L).error());
    }

    @Test
    void 状態の参照で未登録のサイトはSiteNotFound() {
        assertThrows(SiteNotFoundException.class, () -> service.getState(9L));
    }

    // ---- 非同期の依頼: 保存の応答を待たせない ----

    @Test
    void プロジェクトの依頼はその場では実行せず呼び出し元のトークンを取り置いて後で実行する() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, null, null)));
        site(1L);
        when(siteService.getLetsblogPluginStatus(1L)).thenReturn(installed());
        when(siteService.syncLetsblogPlugin(1L, PAYLOAD.payload(), "h1")).thenReturn("h1");

        service.requestProjectSync(5L);

        verify(contentBridgeClient, never()).fetchSyncPayload(anyLong(), anyString());
        assertEquals(1, queued.size());
        drain();
        verify(contentBridgeClient).fetchSyncPayload(5L, "Bearer t");
    }

    @Test
    void すべてのプロジェクトの依頼も後で実行する() {
        when(projectRepository.findAll()).thenReturn(List.of());

        service.requestAllSync();

        verify(projectRepository, never()).findAll();
        drain();
        verify(projectRepository).findAll();
    }

    @Test
    void 依頼の実行中の予期しない例外は外へ出さない() {
        when(projectRepository.findById(5L)).thenThrow(new IllegalStateException("boom"));

        service.requestProjectSync(5L);
        drain();

        verify(contentBridgeClient, never()).fetchSyncPayload(anyLong(), anyString());
    }
}
