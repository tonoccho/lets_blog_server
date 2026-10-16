package com.letsblog.project.controller;

import com.letsblog.project.domain.LetsblogSyncStatus;
import com.letsblog.project.domain.Site;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.testsupport.ProjectApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * content-service が、タグ・CSS・プレフィックスの変更をサイトへの同期として依頼する内部API(issue #1558)。
 * issue #1617: 呼び出し元ユーザーの権限(プロジェクトメンバーまたはadmin、グローバルはadminのみ)を検査する。
 *
 * <p>issue #1818: LetsblogSyncService・AdminAuthorizationService・SiteService・ContentBridgeClient は実物。
 * identity-service・content-service・CMS ブリッジの代わりにフェイクHTTPサーバーが応答し、差し替えるのはDBのリポジトリだけ。
 * 同期の依頼は実行枠へためられ、{@code drainJobs()}で実行して、プラグインへ送られたことを確かめる。
 */
class LetsblogSyncInternalControllerTest {

    private static final String MEMBER = "/api/internal/identity/projects/7/members/5";

    private final ProjectApp app = new ProjectApp();

    @AfterEach
    void close() {
        app.close();
    }

    private LetsblogSyncInternalController controller() {
        return new LetsblogSyncInternalController(app.letsblogSyncService, app.world.adminAuthorizationService);
    }

    @Test
    void プロジェクトメンバーならそのプロジェクトの同期を依頼して202を返す() {
        app.world.actingAsUser(5L);
        app.world.http.json("GET", MEMBER, "true");
        Site site = app.syncableProject(7L, 3L);

        var response = controller().request(new LetsblogSyncInternalController.SyncRequest(7L));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(1, app.jobs.size(), "同期は実行枠へ依頼されただけ");
        assertEquals(0, app.pluginSyncRequests());
        app.drainJobs();
        assertEquals(1, app.pluginSyncRequests());
        assertEquals(LetsblogSyncStatus.SYNCED, site.getLetsblogSyncStatus());
        assertEquals("Bearer token-5", app.world.http.requests("GET", ".*letsblog-sync-payload").get(0).header("Authorization"));
    }

    @Test
    void adminならそのプロジェクトの同期を依頼できる() {
        app.world.actingAsAdmin(1L);
        app.syncableProject(7L, 3L);

        assertEquals(HttpStatus.ACCEPTED, controller().request(new LetsblogSyncInternalController.SyncRequest(7L)).getStatusCode());
        app.drainJobs();

        assertEquals(1, app.pluginSyncRequests());
    }

    @Test
    void プロジェクトのメンバーでなければ403で同期は依頼されない() {
        app.world.actingAsUser(5L);
        app.world.http.json("GET", MEMBER, "false");
        app.syncableProject(7L, 3L);

        assertThrows(ForbiddenException.class,
                () -> controller().request(new LetsblogSyncInternalController.SyncRequest(7L)));

        assertTrue(app.jobs.isEmpty());
        assertEquals(0, app.pluginSyncRequests());
    }

    @Test
    void プロジェクトIDが無くadminならすべてのプロジェクトの同期を依頼する() {
        app.world.actingAsAdmin(1L);
        app.syncableProject(7L, 3L);

        var response = controller().request(new LetsblogSyncInternalController.SyncRequest(null));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals(1, app.jobs.size());
        app.drainJobs();
        assertEquals(1, app.pluginSyncRequests());
    }

    @Test
    void プロジェクトIDが無くadminでなければ403で同期は依頼されない() {
        app.world.actingAsUser(5L);
        app.syncableProject(7L, 3L);

        assertThrows(ForbiddenException.class,
                () -> controller().request(new LetsblogSyncInternalController.SyncRequest(null)));

        assertTrue(app.jobs.isEmpty());
        assertEquals(0, app.pluginSyncRequests());
    }
}
