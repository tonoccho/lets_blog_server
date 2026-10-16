package com.letsblog.project.service;

import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.project.dto.SyncEnvironmentRequest;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.testsupport.FakeHttpServer;
import com.letsblog.project.testsupport.ProjectWorld;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 環境間同期・サイト自動構築のジョブ(issue #1723)。本物のランナー・ブリッジクライアント・サイト構築/同期サービスを、
 * 「サービス自身のトークン以外は401を返す」下流につなぎ、取り置いた利用者のトークン(5分で失効)が
 * 切れていてもジョブが最後まで通ることを確かめる。監査ログの操作者・作成者メールは要求者のまま。
 *
 * <p>issue #1817: ServiceTokenClient を含め自前のクラスはすべて実物。差し替えるのはDBのリポジトリと、HTTPの向こうを
 * 演じるフェイクHTTPサーバー(トークンエンドポイントもこのサーバーが答える)だけ。
 */
@DisplayName("project-service: ジョブはサービス自身のトークンでブリッジを呼ぶ(issue #1723)")
class JobServiceTokenWiringTest {

    private static final String EXPIRED_USER_BEARER = "Bearer expired-user-token";
    private static final ActorSnapshot ACTOR =
            new ActorSnapshot(5L, "sub-5", "a@example.com", "203.0.113.9", "ua", EXPIRED_USER_BEARER, true);

    private final ProjectWorld world = new ProjectWorld();
    private final ProjectRepository projectRepository = Mockito.mock(ProjectRepository.class);
    private final List<String> calls = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        world.http.on("POST", "/sync", FakeHttpServer.Response.empty(200));
        world.http.on("POST", "/provision",
                FakeHttpServer.Response.ok("{\"url\":\"https://localhost/sites/my-site\",\"adminUser\":\"admin\"}"));
        world.http.on("POST", "/db-import", FakeHttpServer.Response.empty(200));
        world.http.on("POST", "/media-import", FakeHttpServer.Response.empty(200));
        world.http.on("POST", "/theme-import", FakeHttpServer.Response.empty(200));
        world.http.on("POST", "/api/internal/identity/project-users/3/sites/20/reconcile-roles",
                FakeHttpServer.Response.empty(200));
        world.http.on("POST", "/api/internal/project/cms/export-database", FakeHttpServer.Response.ok(
                "{\"tablePrefix\":\"wp_\",\"dumpBase64\":\"AAAA\"}"));
        world.http.on("POST", "/api/internal/project/cms/export-media",
                FakeHttpServer.Response.bytes(200, "application/octet-stream", "archive".getBytes()));
        world.http.on("POST", "/api/internal/project/cms/export-themes",
                FakeHttpServer.Response.bytes(200, "application/octet-stream", "archive".getBytes()));
        world.registrationSucceeds();
        // 内部ブリッジはサービス自身のトークン以外を401にする。最後に登録するので、他のルールより先に判定される。
        world.http.on("*", "/api/internal/.*", req -> {
            if (!("Bearer " + ProjectWorld.SERVICE_TOKEN).equals(req.header("Authorization"))) {
                return FakeHttpServer.Response.json(401, "{\"error\":\"expired\"}");
            }
            if (!req.path().startsWith("/api/internal/ai/")) {
                calls.add(req.path());
            }
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private static void onPlainThread(Runnable body) throws Exception {
        Thread thread = new Thread(body, "async-job-thread");
        thread.start();
        thread.join();
    }

    private String doneOrFailed(long jobId) {
        var all = world.jobUpdates(jobId);
        return all.get(all.size() - 1).status();
    }

    private ProjectEnvironmentSyncJobRunner syncRunner(Site fromSite, Site toSite) {
        Project project = new Project();
        project.setId(3L);
        project.setTestSiteId(fromSite.getId());
        project.setLocalSiteId(toSite.getId());
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project));
        ProjectEnvironmentSyncService service = new ProjectEnvironmentSyncService(
                projectRepository, world.siteRepository, world.siteService, world.wordPressSyncClient,
                world.cmsBridgeClient, world.identityBridgeClient, world.currentActorService);
        return new ProjectEnvironmentSyncJobRunner(service, world.currentActorService, world.generationJobClient,
                world.objectMapper, new JobHeartbeatTracker(world.generationJobClient, world.objectMapper));
    }

    @Test
    @DisplayName("環境間同期: 利用者のトークンが切れていても、DB同期後のロール再整合が通りジョブが done になる")
    void syncReconcilesRolesWithServiceToken() throws Exception {
        Site from = world.addManagedSite(10L, "slug-10", "wp_10");
        Site to = world.addManagedSite(20L, "slug-20", "wp_20");
        ProjectEnvironmentSyncJobRunner runner = syncRunner(from, to);

        onPlainThread(() -> runner.run(21L, 3L, new SyncEnvironmentRequest("test", "local", List.of("db")), ACTOR));

        assertEquals("done", doneOrFailed(21L));
        assertEquals(1, world.http.requests("POST", "/sync").size());
        assertEquals(List.of("/api/internal/identity/project-users/3/sites/20/reconcile-roles"), calls);
    }

    @Test
    @DisplayName("環境間同期: 同期元がSSH管理サイトでも、トークンが切れていてメディアとテーマのエクスポートが通る")
    void syncExportsMediaAndThemesWithServiceToken() throws Exception {
        Site from = world.addSshSite(10L, "SSH元");
        Site to = world.addManagedSite(20L, "slug-20", "wp_20");
        ProjectEnvironmentSyncJobRunner runner = syncRunner(from, to);

        onPlainThread(() -> runner.run(
                21L, 3L, new SyncEnvironmentRequest("test", "local", List.of("db", "media", "themes")), ACTOR));

        assertEquals("done", doneOrFailed(21L));
        assertTrue(calls.contains("/api/internal/project/cms/export-database"));
        assertTrue(calls.contains("/api/internal/project/cms/export-media"));
        assertTrue(calls.contains("/api/internal/project/cms/export-themes"));
        assertEquals(1, world.http.requests("POST", "/media-import").size());
        assertEquals(1, world.http.requests("POST", "/theme-import").size());
    }

    @Test
    @DisplayName("サイト自動構築: 利用者のトークンが切れていても、登録(provision と疎通確認)が通り、後始末されず done になる")
    void provisioningRegistersWithServiceToken() throws Exception {
        Site registered = new Site();
        registered.setId(7L);
        registered.setName("Name");
        registered.setSiteKey("my-site");
        when(world.siteRepository.findBySiteKey("my-site")).thenReturn(Optional.of(registered));
        WordPressSiteProvisioningService provisioningService = new WordPressSiteProvisioningService(
                world.wordPressProvisioningClient, world.wordPressSyncClient, world.siteService,
                world.siteRepository, world.domainEventPublisher);
        ManagedSiteProvisioningJobRunner runner = new ManagedSiteProvisioningJobRunner(
                provisioningService, world.currentActorService, world.generationJobClient, world.objectMapper,
                new JobHeartbeatTracker(world.generationJobClient, world.objectMapper));

        onPlainThread(() -> runner.run(
                11L, new CreateManagedWordPressSiteRequest(
                        "Name", "my-site", "Title", "admin", "admin@example.com", "pw", "ja", null),
                ACTOR));

        assertEquals("done", doneOrFailed(11L));
        assertTrue(world.jobUpdates(11L, "failed").isEmpty());
        assertEquals("a@example.com",
                world.bodiesOf("POST", "/api/internal/project/cms/provision").get(0).get("actorEmail").asText());
        assertEquals(List.of("/api/internal/project/cms/provision", "/api/internal/project/cms/test-connection"), calls);
        assertTrue(world.http.requests("POST", "/deprovision").isEmpty());
    }
}
