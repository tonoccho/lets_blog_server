package com.letsblog.content.service;

import com.letsblog.content.domain.ProjectContentSettings;
import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProjectContentSettingsServiceの回帰テスト(issue #571、issue #576でcontent-serviceへ移管)。
 * project_content_settingsは初回書き込み時に遅延作成されることに加え、cssSelectorPrefix未設定時に
 * legacy-api側のプロジェクトslugへフォールバックする解決ロジック(旧ProjectService#resolveCssSelectorPrefix)
 * を検証する。
 *
 * <p>issue #1821: ProjectBridgeClient・CurrentActorService・LetsblogSyncNotifierは実物で、
 * project-serviceの代わりにHTTPフェイクを向ける。リポジトリ(DB)だけが差し替え。
 */
class ProjectContentSettingsServiceTest {

    private static final String SYNC_PATH = "/api/internal/project/letsblog-sync";

    private ContentWorld world;

    @BeforeEach
    void setUp() {
        world = new ContentWorld().actingAsAdmin(1L);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private ProjectContentSettingsService service() {
        return world.projectContentSettingsService;
    }

    @Test
    void updateCssSelectorPrefix_保存したらそのプロジェクトのサイトへの同期を依頼する() {
        service().updateCssSelectorPrefix(1L, "custom-prefix");

        var requests = world.http.requests("POST", SYNC_PATH);
        assertEquals(1, requests.size());
        assertEquals(1, world.bodyOf(requests.get(0)).get("projectId").asInt());
    }

    @Test
    void getOrCreate_だけでは同期を依頼しない() {
        service().getOrCreate(1L);

        assertTrue(world.http.requests("POST", SYNC_PATH).isEmpty());
    }

    @Test
    void getOrCreate_既存の行があればそれを返し新しい行を作らない() {
        ProjectContentSettings existing = world.settingsFor(1L, "p");

        assertSame(existing, service().getOrCreate(1L));
        assertEquals(1, world.settings.size());
    }

    @Test
    void findByProjectId_行が無ければ空() {
        assertEquals(Optional.empty(), service().findByProjectId(1L));
    }

    @Test
    void getCssSelectorPrefix_未設定なら行が無くてもnullを返す() {
        assertNull(service().getCssSelectorPrefix(1L));
    }

    @Test
    void updateCssSelectorPrefix_新規行を作成して保存する() {
        ProjectContentSettings result = service().updateCssSelectorPrefix(1L, "custom-prefix");

        assertEquals(1L, result.getProjectId());
        assertEquals("custom-prefix", result.getCssSelectorPrefix());
        assertSame(result, world.settings.get(1L));
    }

    @Test
    void updateCssSelectorPrefix_既存行を更新する() {
        ProjectContentSettings existing = world.settingsFor(1L, "old-prefix");

        service().updateCssSelectorPrefix(1L, "new-prefix");

        assertEquals("new-prefix", existing.getCssSelectorPrefix());
    }

    @Test
    void resolveCssSelectorPrefix_設定済みならproject_serviceへ問い合わせずその値を返す() {
        world.settingsFor(1L, "custom-prefix");

        assertEquals("custom-prefix", service().resolveCssSelectorPrefix(1L));
        assertTrue(world.http.requests("GET", "/api/internal/project/projects/.*").isEmpty());
    }

    @Test
    void resolveCssSelectorPrefix_未設定時はproject_service経由でプロジェクトslugへフォールバックする() {
        world.projectSlug(1L, "proj-a");

        assertEquals("proj-a", service().resolveCssSelectorPrefix(1L));
        assertEquals("Bearer token-1",
                world.http.requests("GET", "/api/internal/project/projects/1").get(0).header("Authorization"));
    }

    @Test
    void resolveCssSelectorPrefix_設定が空白ならslugへフォールバックする() {
        world.settingsFor(1L, "  ");
        world.projectSlug(1L, "proj-a");

        assertEquals("proj-a", service().resolveCssSelectorPrefix(1L));
    }
}
