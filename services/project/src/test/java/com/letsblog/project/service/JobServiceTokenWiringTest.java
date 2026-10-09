package com.letsblog.project.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.project.client.CmsProvisioningBridgeClient;
import com.letsblog.project.client.IdentityBridgeClient;
import com.letsblog.project.client.IdentityClient;
import com.letsblog.project.cms.ConnectionCheckResult;
import com.letsblog.project.domain.Project;
import com.letsblog.project.domain.Site;
import com.letsblog.project.dto.CreateManagedWordPressSiteRequest;
import com.letsblog.project.dto.SiteResponse;
import com.letsblog.project.dto.SyncEnvironmentRequest;
import com.letsblog.project.provisioning.WordPressSyncClient;
import com.letsblog.project.repository.ProjectRepository;
import com.letsblog.project.repository.SiteRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 環境間同期・サイト自動構築のジョブ(issue #1723)。本物のランナー・ブリッジクライアントを、
 * 「サービス自身のトークン以外は401を返す」下流につなぎ、取り置いた利用者のトークン(5分で失効)が
 * 切れていてもジョブが最後まで通ることを確かめる。監査ログの操作者・作成者メールは要求者のまま。
 */
@DisplayName("project-service: ジョブはサービス自身のトークンでブリッジを呼ぶ(issue #1723)")
class JobServiceTokenWiringTest {

    private static final String EXPIRED_USER_BEARER = "Bearer expired-user-token";
    private static final ActorSnapshot ACTOR =
            new ActorSnapshot(5L, "sub-5", "a@example.com", "203.0.113.9", "ua", EXPIRED_USER_BEARER, true);

    private final ServiceTokenClient serviceTokenClient = mock(ServiceTokenClient.class);
    private final GenerationJobClient generationJobClient = mock(GenerationJobClient.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CurrentActorService currentActorService =
            new CurrentActorService(new MockHttpServletRequest(), mock(IdentityClient.class));
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private IdentityBridgeClient identityBridgeClient;
    private CmsProvisioningBridgeClient cmsClient;

    @BeforeEach
    void setUp() throws Exception {
        when(serviceTokenClient.getAccessToken()).thenReturn("svc-token");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            exchange.getRequestBody().readAllBytes();
            if (!"Bearer svc-token".equals(auth)) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
            calls.add(path);
            String json = "{}";
            String type = "application/json";
            if (path.endsWith("/export-database")) {
                json = "{\"tablePrefix\":\"wp_\",\"dumpBase64\":\"AAAA\"}";
            } else if (path.endsWith("/export-media") || path.endsWith("/export-themes")) {
                json = "archive";
                type = "application/octet-stream";
            } else if (path.endsWith("/provision")) {
                json = "{\"authorId\":\"9\"}";
            } else if (path.endsWith("/test-connection")) {
                json = "{\"ok\":true}";
            }
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", type);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        identityBridgeClient = new IdentityBridgeClient(RestClient.builder(), base, serviceTokenClient);
        cmsClient = new CmsProvisioningBridgeClient(
                RestClient.builder(), base, new MockHttpServletRequest(), serviceTokenClient);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        RequestContextHolder.resetRequestAttributes();
    }

    private static void onPlainThread(Runnable body) throws Exception {
        Thread thread = new Thread(body, "async-job-thread");
        thread.start();
        thread.join();
    }

    private String doneOrFailed() {
        org.mockito.ArgumentCaptor<String> status = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(generationJobClient, org.mockito.Mockito.atLeastOnce()).updateStatus(any(), status.capture(), any());
        List<String> all = new ArrayList<>(status.getAllValues());
        return all.get(all.size() - 1);
    }

    private ProjectEnvironmentSyncJobRunner syncRunner(
            SiteService siteService, WordPressSyncClient syncClient, Site fromSite, Site toSite) {
        ProjectRepository projectRepository = mock(ProjectRepository.class);
        SiteRepository siteRepository = mock(SiteRepository.class);
        Project project = new Project();
        project.setId(3L);
        project.setTestSiteId(10L);
        project.setLocalSiteId(20L);
        when(projectRepository.findById(3L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(fromSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(toSite));
        ProjectEnvironmentSyncService service = new ProjectEnvironmentSyncService(
                projectRepository, siteRepository, siteService, syncClient, cmsClient, identityBridgeClient,
                currentActorService);
        return new ProjectEnvironmentSyncJobRunner(service, currentActorService, generationJobClient, objectMapper);
    }

    private static Site site(long id, boolean managed) {
        Site site = new Site();
        site.setId(id);
        site.setManagedWordpress(managed);
        site.setWpSlug("slug-" + id);
        site.setWpDbName("wp_" + id);
        return site;
    }

    @Test
    @DisplayName("環境間同期: 利用者のトークンが切れていても、DB同期後のロール再整合が通りジョブが done になる")
    void syncReconcilesRolesWithServiceToken() throws Exception {
        WordPressSyncClient syncClient = mock(WordPressSyncClient.class);
        ProjectEnvironmentSyncJobRunner runner =
                syncRunner(mock(SiteService.class), syncClient, site(10L, true), site(20L, true));

        onPlainThread(() -> runner.run(21L, 3L, new SyncEnvironmentRequest("test", "local", List.of("db")), ACTOR));

        assertEquals("done", doneOrFailed());
        verify(syncClient).sync(any());
        assertEquals(List.of("/api/internal/identity/project-users/3/sites/20/reconcile-roles"), calls);
    }

    @Test
    @DisplayName("環境間同期: 同期元がSSH管理サイトでも、トークンが切れていてメディアとテーマのエクスポートが通る")
    void syncExportsMediaAndThemesWithServiceToken() throws Exception {
        WordPressSyncClient syncClient = mock(WordPressSyncClient.class);
        SiteService siteService = mock(SiteService.class);
        Site from = site(10L, false);
        when(siteService.resolveDataSource(from))
                .thenReturn(new SiteService.SiteDataSource(false, Map.of("wpSlug", "ssh")));
        ProjectEnvironmentSyncJobRunner runner = syncRunner(siteService, syncClient, from, site(20L, true));

        onPlainThread(() -> runner.run(
                21L, 3L, new SyncEnvironmentRequest("test", "local", List.of("db", "media", "themes")), ACTOR));

        assertEquals("done", doneOrFailed());
        assertTrue(calls.contains("/api/internal/project/cms/export-database"));
        assertTrue(calls.contains("/api/internal/project/cms/export-media"));
        assertTrue(calls.contains("/api/internal/project/cms/export-themes"));
        verify(syncClient).importMedia(eq("slug-20"), any());
        verify(syncClient).importThemes(eq("slug-20"), any());
    }

    @Test
    @DisplayName("サイト自動構築: 利用者のトークンが切れていても、登録(provision と疎通確認)が通り、後始末されず done になる")
    void provisioningRegistersWithServiceToken() throws Exception {
        WordPressSiteProvisioningService provisioningService = mock(WordPressSiteProvisioningService.class);
        ProvisioningService bridge = new ProvisioningService(cmsClient);
        AtomicReference<String> actorEmail = new AtomicReference<>();
        AtomicReference<ConnectionCheckResult> check = new AtomicReference<>();
        when(provisioningService.createManagedSiteForJob(any(), any())).thenAnswer(invocation -> {
            actorEmail.set(currentActorService.getCurrentActorEmail());
            bridge.provisionSite("WORDPRESS", Map.of("wpSlug", "my-site"), actorEmail.get());
            check.set(cmsClient.testConnection("WORDPRESS", Map.of("wpSlug", "my-site")));
            return new SiteResponse(7L, "Name", "my-site", null, "https://localhost/sites/my-site",
                    Instant.now(), Instant.now(), "SUCCESS", true, false, null);
        });
        ManagedSiteProvisioningJobRunner runner = new ManagedSiteProvisioningJobRunner(
                provisioningService, currentActorService, generationJobClient, objectMapper);

        onPlainThread(() -> runner.run(
                11L, new CreateManagedWordPressSiteRequest(
                        "Name", "my-site", "Title", "admin", "admin@example.com", "pw", "ja", null),
                ACTOR));

        assertEquals("done", doneOrFailed());
        verify(generationJobClient, never()).updateStatus(eq(11L), eq("failed"), any());
        assertEquals("a@example.com", actorEmail.get());
        assertTrue(check.get().ok());
        assertEquals(List.of("/api/internal/project/cms/provision", "/api/internal/project/cms/test-connection"), calls);
    }
}
