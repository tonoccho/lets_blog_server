package com.letsblog.project.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.Site;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.testsupport.FakeHttpServer;
import com.letsblog.project.testsupport.ProjectWorld;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * ProjectEnvironmentSyncServiceの回帰テスト。同期元が自動構築サイトならprovision-agentの/syncへ、SSH管理サイトなら
 * publishing-serviceのエクスポート(DB・メディア・テーマ)を取ってagentのインポートへ渡す。DB同期の後はidentity-serviceへ
 * ロール再整合を依頼する。
 *
 * <p>issue #1817: SiteService・各ブリッジ/agentのクライアント・IdentityBridgeClient・CurrentActorService は実物で、
 * HTTPの向こう(provision-agent・publishing-service・identity-service)をフェイクHTTPサーバーが演じる。
 */
@ExtendWith(MockitoExtension.class)
class ProjectEnvironmentSyncServiceTest {

    private static final String RECONCILE = "/api/internal/identity/project-users/1/sites/20/reconcile-roles";
    private static final String CMS = "/api/internal/project/cms/";

    @Mock
    private ProjectRepository projectRepository;

    private final ProjectWorld world = new ProjectWorld();

    @BeforeEach
    void setUp() {
        world.request.addHeader("Authorization", "Bearer token");
        world.inRequest();
        world.http.on("POST", "/sync", FakeHttpServer.Response.empty(200));
        world.http.on("POST", "/db-import", FakeHttpServer.Response.empty(200));
        world.http.on("POST", "/media-import", FakeHttpServer.Response.empty(200));
        world.http.on("POST", "/theme-import", FakeHttpServer.Response.empty(200));
        world.http.on("POST", RECONCILE, FakeHttpServer.Response.empty(200));
    }

    @AfterEach
    void close() {
        world.close();
    }

    private ProjectEnvironmentSyncService service() {
        return new ProjectEnvironmentSyncService(projectRepository, world.siteRepository, world.siteService,
                world.wordPressSyncClient, world.cmsBridgeClient, world.identityBridgeClient,
                world.currentActorService);
    }

    private Project project(Long testSiteId, Long localSiteId) {
        Project project = new Project();
        project.setId(1L);
        project.setTestSiteId(testSiteId);
        project.setLocalSiteId(localSiteId);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        return project;
    }

    @Test
    void sync_同期元と同期先が同じ環境なら例外() {
        assertThrows(IllegalArgumentException.class, () -> service().sync(1L, "test", "test", List.of("db")));
    }

    @Test
    void sync_不正な環境名は例外() {
        assertThrows(IllegalArgumentException.class, () -> service().sync(1L, "staging", "test", List.of("db")));
        assertThrows(IllegalArgumentException.class, () -> service().sync(1L, "test", "staging", List.of("db")));
    }

    @Test
    void sync_ローカル環境は同期元にできない() {
        assertThrows(IllegalArgumentException.class, () -> service().sync(1L, "local", "test", List.of("db")));
    }

    @Test
    void sync_本番環境は同期先にできない() {
        assertThrows(IllegalArgumentException.class, () -> service().sync(1L, "test", "production", List.of("db")));
    }

    @Test
    void sync_未登録のプロジェクトはNotFound() {
        when(projectRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(ProjectNotFoundException.class, () -> service().sync(1L, "test", "local", List.of("db")));
    }

    @Test
    void sync_managed同士はprovision_agentで同期しDB同期後にロール再整合を依頼する() {
        project(10L, 20L);
        world.addManagedSite(10L, "from-slug", "wp_from");
        world.addManagedSite(20L, "to-slug", "wp_to");

        service().sync(1L, "test", "local", List.of("db", "themes"));

        FakeHttpServer.Request sync = world.http.requests("POST", "/sync").get(0);
        assertEquals("agent-token", sync.header("X-Provision-Token"));
        JsonNode body = world.bodyOf(sync);
        assertEquals("from-slug", body.get("fromSlug").asText());
        assertEquals("wp_from", body.get("fromDbName").asText());
        assertEquals("to-slug", body.get("toSlug").asText());
        assertEquals("wp_to", body.get("toDbName").asText());
        assertEquals(List.of("db", "themes"), List.of(body.get("targets").get(0).asText(), body.get("targets").get(1).asText()));
        assertEquals("Bearer token", world.http.requests("POST", RECONCILE).get(0).header("Authorization"));
    }

    @Test
    void sync_本番環境を同期元にできる() {
        Project project = new Project();
        project.setId(1L);
        project.setProductionSiteId(10L);
        project.setTestSiteId(20L);
        lenient().when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        world.addManagedSite(10L, "prod-slug", "wp_prod");
        world.addManagedSite(20L, "test-slug", "wp_test");
        world.http.on("POST", "/api/internal/identity/project-users/1/sites/20/reconcile-roles",
                FakeHttpServer.Response.empty(200));

        service().sync(1L, "production", "test", List.of("db"));

        JsonNode body = world.bodyOf(world.http.requests("POST", "/sync").get(0));
        assertEquals("prod-slug", body.get("fromSlug").asText());
        assertEquals("test-slug", body.get("toSlug").asText());
    }

    @Test
    void sync_DBを含まなければロール再整合は依頼しない() {
        project(10L, 20L);
        world.addManagedSite(10L, "from-slug", "wp_from");
        world.addManagedSite(20L, "to-slug", "wp_to");

        service().sync(1L, "test", "local", List.of("media"));

        assertEquals(1, world.http.requests("POST", "/sync").size());
        assertTrue(world.http.requests("POST", RECONCILE).isEmpty());
    }

    @Test
    void sync_同期先が自動構築サイトでなければ例外() {
        project(10L, 20L);
        world.addManagedSite(10L, "from-slug", "wp_from");
        world.addSite(20L, "管理対象外");

        assertThrows(IllegalArgumentException.class, () -> service().sync(1L, "test", "local", List.of("db")));
        assertTrue(world.http.requests("POST", "/sync").isEmpty());
    }

    @Test
    void sync_環境にサイトが紐付いていなければ例外() {
        project(null, 20L);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service().sync(1L, "test", "local", List.of("db")));

        assertTrue(e.getMessage().contains("紐付けられていません"));
    }

    @Test
    void sync_紐付いたサイトが登録に無ければSiteNotFound() {
        project(10L, 20L);

        assertThrows(SiteNotFoundException.class, () -> service().sync(1L, "test", "local", List.of("db")));
    }

    // ---- 同期元がSSH管理サイト ----

    private void sshSource() {
        project(10L, 20L);
        world.addSshSite(10L, "SSH元");
        world.addManagedSite(20L, "to-slug", "wp_to");
    }

    @Test
    void sync_SSH管理サイトからはDB_メディア_テーマをエクスポートして同期先へインポートする() {
        sshSource();
        world.http.on("POST", CMS + "export-database", FakeHttpServer.Response.ok(world.toJson(Map.of(
                "tablePrefix", "wp_", "dumpBase64",
                Base64.getEncoder().encodeToString("SQL".getBytes(StandardCharsets.UTF_8))))));
        world.http.on("POST", CMS + "export-media",
                FakeHttpServer.Response.bytes(200, "application/octet-stream", new byte[] {1, 2}));
        world.http.on("POST", CMS + "export-themes",
                FakeHttpServer.Response.bytes(200, "application/octet-stream", new byte[] {3}));

        service().sync(1L, "test", "local", List.of("db", "media", "themes"));

        FakeHttpServer.Request export = world.http.requests("POST", CMS + "export-database").get(0);
        assertEquals("Bearer token", export.header("Authorization"));
        assertEquals("ssh.example.com", world.bodyOf(export).get("credentials").get("sshHost").asText());
        assertEquals(1, world.http.requests("POST", "/db-import").size());
        assertTrue(world.http.requests("POST", "/db-import").get(0).bodyText().contains("SQL"));
        assertTrue(world.http.requests("POST", "/db-import").get(0).bodyText().contains("wp_"));
        assertEquals(1, world.http.requests("POST", "/media-import").size());
        assertEquals(1, world.http.requests("POST", "/theme-import").size());
        assertTrue(world.http.requests("POST", "/sync").isEmpty());
        assertEquals(1, world.http.requests("POST", RECONCILE).size());
    }

    @Test
    void sync_SSH管理サイトからは指定した対象だけを同期する() {
        sshSource();
        world.http.on("POST", CMS + "export-database", FakeHttpServer.Response.ok(world.toJson(Map.of(
                "tablePrefix", "wp_", "dumpBase64", Base64.getEncoder().encodeToString("SQL".getBytes(StandardCharsets.UTF_8))))));
        world.http.on("POST", CMS + "export-media",
                FakeHttpServer.Response.bytes(200, "application/octet-stream", new byte[] {1}));
        world.http.on("POST", CMS + "export-themes",
                FakeHttpServer.Response.bytes(200, "application/octet-stream", new byte[] {2}));

        service().sync(1L, "test", "local", List.of("db"));
        assertEquals(1, world.http.requests("POST", "/db-import").size());
        assertTrue(world.http.requests("POST", CMS + "export-media").isEmpty());
        assertTrue(world.http.requests("POST", CMS + "export-themes").isEmpty());

        service().sync(1L, "test", "local", List.of("media"));
        assertEquals(1, world.http.requests("POST", "/media-import").size());
        assertEquals(1, world.http.requests("POST", CMS + "export-database").size(), "DBは再エクスポートしない");

        service().sync(1L, "test", "local", List.of("themes"));
        assertEquals(1, world.http.requests("POST", "/theme-import").size());
        assertEquals(1, world.http.requests("POST", CMS + "export-media").size(), "メディアは再エクスポートしない");
    }

    @Test
    void sync_SSH管理サイトの空のメディアとテーマはインポートしない() {
        sshSource();
        world.http.on("POST", CMS + "export-media", FakeHttpServer.Response.empty(200));
        world.http.on("POST", CMS + "export-themes", FakeHttpServer.Response.empty(200));

        service().sync(1L, "test", "local", List.of("media", "themes"));

        assertEquals(1, world.http.requests("POST", CMS + "export-media").size());
        assertEquals(1, world.http.requests("POST", CMS + "export-themes").size());
        assertTrue(world.http.requests("POST", "/media-import").isEmpty());
        assertTrue(world.http.requests("POST", "/theme-import").isEmpty());
        assertTrue(world.http.requests("POST", "/db-import").isEmpty());
        assertTrue(world.http.requests("POST", RECONCILE).isEmpty());
    }

    @Test
    void sync_SSH管理サイトはDB_メディア_テーマ以外を同期できない() {
        sshSource();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service().sync(1L, "test", "local", List.of("db", "plugins")));

        assertTrue(e.getMessage().contains("SSH管理サイト"));
        assertTrue(world.http.requests().stream().noneMatch(r -> r.path().startsWith(CMS)));
    }

    @Test
    void sync_SSH接続が設定されていない非managedサイトは同期元にできない() {
        project(10L, 20L);
        world.addSite(10L, "AGENT接続");
        world.addManagedSite(20L, "to-slug", "wp_to");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service().sync(1L, "test", "local", List.of("db")));

        assertTrue(e.getMessage().contains("SSH接続が設定されていない"));
    }
}
