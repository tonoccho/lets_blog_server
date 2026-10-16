package com.letsblog.project.service;

import com.letsblog.project.cms.LetsblogPluginStatus;
import com.letsblog.project.cms.LetsblogSyncResult;
import com.letsblog.project.client.BearerScope;
import com.letsblog.project.client.ContentBridgeClient.SyncPayload;
import com.letsblog.project.domain.LetsblogSyncStatus;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.LetsblogSyncState;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.testsupport.FakeHttpServer;
import com.letsblog.project.testsupport.ProjectWorld;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * カスタムタグ・CSS・プレフィックス・デザインの WordPress プラグインへの同期(issue #1558)。
 * 送信は wp-cli だけ({@link SiteService#syncLetsblogPlugin}がブリッジ経由で実行)。
 * 導入済み以外(未導入・要更新、issue #1557)のサイトへは送らず、失敗したサイトは状態に残して再同期で回復する。
 */
@ExtendWith(MockitoExtension.class)
class LetsblogSyncServiceTest {

    private static final SyncPayload PAYLOAD = new SyncPayload("{\"cssBundle\":\".a{}\"}", "h1");
    private static final String CMS = "/api/internal/project/cms/";
    private static final String CONTENT = "/api/internal/content/projects/\\d+/letsblog-sync-payload";

    @Mock
    private ProjectRepository projectRepository;

    // 自前のクラス(SiteService・ContentBridgeClient・CurrentActorService)は実物で、HTTPはフェイクが答える(issue #1817)。
    private final ProjectWorld world = new ProjectWorld();
    private final List<Runnable> queued = new ArrayList<>();
    private final Executor queueingExecutor = queued::add;
    private final Map<Long, Site> sites = new HashMap<>();
    private final List<Site> saved = new ArrayList<>();

    private LetsblogSyncService service;

    @BeforeEach
    void setUp() {
        service = new LetsblogSyncService(
                world.siteService, world.siteRepository, projectRepository, world.contentBridgeClient,
                world.currentActorService, queueingExecutor);
        lenient().when(world.siteRepository.save(any(Site.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        world.request.addHeader("Authorization", "Bearer t");
        world.respond("GET", CONTENT, PAYLOAD);
        world.respond("POST", CMS + "sync-letsblog-plugin", new LetsblogSyncResult("h1"));
        world.pluginState(LetsblogPluginStatus.State.INSTALLED);
    }

    @AfterEach
    void close() {
        BearerScope.call(null, () -> null);
        world.close();
    }

    private Site site(Long id) {
        Site site = world.addSite(id, "site-" + id);
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

    private void drain() {
        List<Runnable> copy = new ArrayList<>(queued);
        queued.clear();
        copy.forEach(Runnable::run);
    }

    private int syncRequests() {
        return world.http.requests("POST", CMS + "sync-letsblog-plugin").size();
    }

    private int contentFetches() {
        return world.http.requests("GET", CONTENT).size();
    }

    // ---- AC1: プロジェクトのすべてのサイトへ送り、ハッシュが一致する ----

    @Test
    void プロジェクトのすべてのサイトへ送り同期済みとハッシュを記録する() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, 2L, 3L)));
        site(1L); site(2L); site(3L);

        service.syncProject(5L, "Bearer t");

        assertEquals(1, contentFetches());
        assertEquals("Bearer t", world.http.requests("GET", CONTENT).get(0).header("Authorization"));
        var sent = world.bodiesOf("POST", CMS + "sync-letsblog-plugin");
        assertEquals(3, sent.size());
        for (var body : sent) {
            assertEquals(PAYLOAD.payload(), body.get("payload").asText());
            assertEquals("h1", body.get("hash").asText());
        }
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

        service.syncProject(5L, "Bearer t");

        assertEquals(1, syncRequests());
        assertEquals(1, saved.size());
    }

    @Test
    void 存在しないプロジェクトは何もしない() {
        when(projectRepository.findById(5L)).thenReturn(Optional.empty());

        service.syncProject(5L, "Bearer t");

        assertEquals(0, contentFetches());
    }

    @Test
    void サイトが無いプロジェクトでは内容を取得しない() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, null, null, null)));

        service.syncProject(5L, "Bearer t");

        assertEquals(0, contentFetches());
    }

    @Test
    void 送信中は呼び出し元のトークンがブリッジ呼び出しに使われる() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, null, null)));
        site(1L);

        service.syncProject(5L, "Bearer scoped");

        // 状態の取得と送信、どちらのブリッジ呼び出しにも、取り置いたトークンが使われる。
        assertEquals("Bearer scoped",
                world.http.requests("POST", CMS + "letsblog-plugin-status").get(0).header("Authorization"));
        assertEquals("Bearer scoped",
                world.http.requests("POST", CMS + "sync-letsblog-plugin").get(0).header("Authorization"));
        assertNull(BearerScope.current());
    }

    // ---- AC2: グローバルの変更はすべてのプロジェクトのサイトへ ----

    @Test
    void すべてのプロジェクトのサイトへ送る() {
        when(projectRepository.findAll()).thenReturn(List.of(project(5L, 1L, null, null), project(6L, 2L, null, null)));
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, null, null)));
        when(projectRepository.findById(6L)).thenReturn(Optional.of(project(6L, 2L, null, null)));
        site(1L); site(2L);

        service.syncAll("Bearer t");

        assertEquals(2, syncRequests());
        assertEquals(1, world.http.requests("GET", "/api/internal/content/projects/5/letsblog-sync-payload").size());
        assertEquals(1, world.http.requests("GET", "/api/internal/content/projects/6/letsblog-sync-payload").size());
        assertEquals(LetsblogSyncStatus.SYNCED, sites.get(1L).getLetsblogSyncStatus());
        assertEquals(LetsblogSyncStatus.SYNCED, sites.get(2L).getLetsblogSyncStatus());
    }

    @Test
    void あるプロジェクトの失敗が他のプロジェクトの同期を止めない() {
        when(projectRepository.findAll()).thenReturn(List.of(project(5L, 1L, null, null), project(6L, 2L, null, null)));
        when(projectRepository.findById(6L)).thenReturn(Optional.of(project(6L, 2L, null, null)));
        when(projectRepository.findById(5L)).thenThrow(new IllegalStateException("db down"));
        site(2L);

        service.syncAll("Bearer t");

        assertEquals(LetsblogSyncStatus.SYNCED, sites.get(2L).getLetsblogSyncStatus());
    }

    // ---- AC5: 未導入・要更新のサイトへは送らない ----

    @Test
    void 未導入のサイトへは送らず見送りとして記録する() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, null, null)));
        site(1L);
        world.pluginState(LetsblogPluginStatus.State.NOT_INSTALLED);

        service.syncProject(5L, "Bearer t");

        assertEquals(0, syncRequests());
        assertEquals(LetsblogSyncStatus.SKIPPED, saved.get(0).getLetsblogSyncStatus());
        assertTrue(saved.get(0).getLetsblogSyncError().contains("未導入"));
        assertNull(saved.get(0).getLetsblogSyncHash());
    }

    @Test
    void 要更新のサイトへは送らず見送りとして記録する() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, null, null)));
        site(1L);
        world.pluginState(LetsblogPluginStatus.State.NEEDS_UPDATE);

        service.syncProject(5L, "Bearer t");

        assertEquals(0, syncRequests());
        assertEquals(LetsblogSyncStatus.SKIPPED, saved.get(0).getLetsblogSyncStatus());
        assertTrue(saved.get(0).getLetsblogSyncError().contains("要更新"));
    }

    // ---- AC3: 届かないサイトは失敗として残り、再同期で回復する ----

    @Test
    void 状態を取得できなければ失敗として記録し送らない() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, null, null)));
        site(1L);
        world.http.status("POST", CMS + "letsblog-plugin-status", 500, "{\"error\":\"接続できません\"}");

        service.syncProject(5L, "Bearer t");

        assertEquals(0, syncRequests());
        assertEquals(LetsblogSyncStatus.FAILED, saved.get(0).getLetsblogSyncStatus());
        assertTrue(saved.get(0).getLetsblogSyncError().contains("接続できません"));
    }

    @Test
    void 送信に失敗したら失敗として記録し他のサイトの送信は続ける() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, 2L, null)));
        site(1L); site(2L);
        // サイト1のブリッジ呼び出し(認証情報のbaseUrlで見分ける)だけが失敗する。
        world.http.on("POST", CMS + "sync-letsblog-plugin", req -> world.bodyOf(req).path("credentials")
                .path("baseUrl").asText().contains("site1.")
                ? FakeHttpServer.Response.json(500, "{\"error\":\"wp-cliが失敗しました\"}") : null);

        service.syncProject(5L, "Bearer t");

        assertEquals(LetsblogSyncStatus.FAILED, sites.get(1L).getLetsblogSyncStatus());
        assertTrue(sites.get(1L).getLetsblogSyncError().contains("wp-cliが失敗しました"));
        assertEquals(LetsblogSyncStatus.SYNCED, sites.get(2L).getLetsblogSyncStatus());
    }

    @Test
    void 保存されたハッシュが期待と違えば失敗として記録する() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, null, null)));
        site(1L);
        world.respond("POST", CMS + "sync-letsblog-plugin", new LetsblogSyncResult("other"));

        service.syncProject(5L, "Bearer t");

        assertEquals(LetsblogSyncStatus.FAILED, saved.get(0).getLetsblogSyncStatus());
        assertTrue(saved.get(0).getLetsblogSyncError().contains("一致しません"));
    }

    @Test
    void 同期内容を取得できなければすべてのサイトを失敗として記録する() {
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, 2L, null)));
        site(1L); site(2L);
        world.http.status("GET", CONTENT, 500, "content down");

        service.syncProject(5L, "Bearer t");

        assertEquals(0, syncRequests());
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

        LetsblogSyncState state = service.syncSiteNow(1L);

        assertEquals(LetsblogSyncStatus.SYNCED, state.status());
        assertNull(state.error());
        assertEquals("h1", state.hash());
        assertNotNull(state.syncedAt());
        assertEquals("Bearer t", world.http.requests("GET", CONTENT).get(0).header("Authorization"));
    }

    @Test
    void 再同期はプロジェクトに紐付かないサイトを見送りにする() {
        site(1L);
        when(projectRepository.findByLocalSiteIdOrTestSiteIdOrProductionSiteId(1L, 1L, 1L))
                .thenReturn(Optional.empty());

        LetsblogSyncState state = service.syncSiteNow(1L);

        assertEquals(LetsblogSyncStatus.SKIPPED, state.status());
        assertEquals(0, contentFetches());
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
        world.http.status("GET", CONTENT, 500, "content down");

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

        service.requestProjectSync(5L);

        assertEquals(0, contentFetches());
        assertEquals(1, queued.size());
        drain();
        assertEquals(1, contentFetches());
        assertEquals("Bearer t", world.http.requests("GET", CONTENT).get(0).header("Authorization"));
    }

    @Test
    void すべてのプロジェクトの依頼も後で実行する() {
        when(projectRepository.findAll()).thenReturn(List.of(project(5L, 1L, null, null)));
        when(projectRepository.findById(5L)).thenReturn(Optional.of(project(5L, 1L, null, null)));
        site(1L);

        service.requestAllSync();

        assertEquals(0, contentFetches());
        drain();
        assertEquals(1, contentFetches());
        assertEquals(LetsblogSyncStatus.SYNCED, sites.get(1L).getLetsblogSyncStatus());
    }

    @Test
    void 依頼の実行中の予期しない例外は外へ出さない() {
        when(projectRepository.findById(5L)).thenThrow(new IllegalStateException("boom"));

        service.requestProjectSync(5L);
        drain();

        assertEquals(0, contentFetches());
    }
}
