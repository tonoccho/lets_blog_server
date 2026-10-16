package com.letsblog.publishing.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.publishing.config.AsyncJobConfig;
import com.letsblog.publishing.domain.BulkOperationStatus;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.dto.ApplyToAllEnvironmentsRequest;
import com.letsblog.publishing.dto.ApplyToEnvironmentRequest;
import com.letsblog.publishing.dto.BulkOperationLogResponse;
import com.letsblog.publishing.dto.DeleteSlugRequest;
import com.letsblog.publishing.dto.EditTermRequest;
import com.letsblog.publishing.dto.GenerationJobResponse;
import com.letsblog.publishing.dto.PostComparisonPage;
import com.letsblog.publishing.dto.ReconcileStateRequest;
import com.letsblog.publishing.dto.StatusComparisonPage;
import com.letsblog.publishing.dto.TermComparisonPage;
import com.letsblog.publishing.dto.TermNameRequest;
import com.letsblog.publishing.dto.UpdatePostStatusRequest;
import com.letsblog.publishing.render.MediaRenderException;
import com.letsblog.publishing.service.AssetImageUploadJobRunner;
import com.letsblog.publishing.service.AssetImageUploadJobStarter;
import com.letsblog.publishing.service.BulkInstallJobRunner;
import com.letsblog.publishing.service.BulkInstallJobStarter;
import com.letsblog.publishing.service.ForbiddenException;
import com.letsblog.publishing.service.PluginThemeComparisonService;
import com.letsblog.publishing.service.PostComparisonService;
import com.letsblog.publishing.service.TermComparisonService;
import com.letsblog.publishing.testsupport.AgentListings.Extension;
import com.letsblog.publishing.testsupport.AgentListings.Term;
import com.letsblog.publishing.testsupport.AsyncProxies;
import com.letsblog.publishing.testsupport.FakeHttpServer.Response;
import com.letsblog.publishing.testsupport.PublishingWorld;
import com.letsblog.publishing.testsupport.PublishingWorld.JobUpdate;
import com.letsblog.publishing.testsupport.PublishingWorld.SiteSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * legacy-apiの{@code ProjectControllerTest}のうち、publishing-serviceへ移設した一括管理サブリソース
 * (BulkManagementController)相当のテストを移設したもの(issue #708)。プロジェクトのCRUD・
 * ユーザー管理・css-selector-prefix等は移設していないため、それらのテストは含めない。
 *
 * <p>issue #1816で書き直し: ProjectService・BulkManagementService・TermComparisonService・
 * PluginThemeComparisonService・PostComparisonService・AdminAuthorizationService・CurrentActorService・
 * MediaGeneratedImageClient・BulkInstallJobStarter・AssetImageUploadJobStarter(とその先のランナー)は実物を使い、
 * 外部との通信(identity・project・media・ai-serviceとprovision-agentへのHTTPは{@code FakeHttpServer}、
 * WordPressへのSSHは{@code FakeWordPress})だけを差し替える。ジョブの{@code @Async}は、Springの実際のAOPプロキシと
 * 本番と同じ{@code AsyncJobConfig}のExecutorで動かす。
 */
class BulkManagementControllerTest {

    private static final String ADMIN = "Bearer admin-token";
    private static final String USER = "Bearer user-token";

    private PublishingWorld world;
    private ThreadPoolTaskExecutor bulkExecutor;
    private ThreadPoolTaskExecutor assetExecutor;
    private BulkManagementController controller;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        world.stubActor(ADMIN, 9L, "admin", "admin@example.com");
        world.stubActor(USER, 5L, "user", "user@example.com");
        world.agent.json("POST", "/bulk-management", "{\"status\":\"success\"}");
        world.agent.json("POST", "/bulk-management/upload", "{\"status\":\"success\"}");
        bulkExecutor = (ThreadPoolTaskExecutor) new AsyncJobConfig().bulkInstallExecutor();
        assetExecutor = (ThreadPoolTaskExecutor) new AsyncJobConfig().assetImageUploadExecutor();
        BulkInstallJobRunner bulkRunner = AsyncProxies.async(
                new BulkInstallJobRunner(world.bulkManagementService, world.bulkUploadStorageService,
                        world.generationJobClient, world.mapper),
                "bulkInstallExecutor", bulkExecutor);
        AssetImageUploadJobRunner assetRunner = AsyncProxies.async(
                new AssetImageUploadJobRunner(world.bulkManagementService, world.generationJobClient, world.mapper),
                "assetImageUploadExecutor", assetExecutor);
        controller = new BulkManagementController(
                world.projectService, world.bulkManagementService,
                new TermComparisonService(world.bulkManagementClient, world.bulkManagementService,
                        world.siteService, world.projectService, world.sshOperations),
                new PluginThemeComparisonService(world.bulkManagementClient, world.bulkManagementService,
                        world.siteService, world.projectService, world.sshOperations),
                new PostComparisonService(world.siteService, world.projectService, world.cmsAdapterFactory,
                        world.bulkManagementService),
                world.adminAuthorizationService, world.currentActorService, world.mediaGeneratedImageClient,
                new BulkInstallJobStarter(world.generationJobClient, bulkRunner, world.bulkManagementService,
                        world.bulkUploadStorageService, world.currentActorService, world.mapper),
                new AssetImageUploadJobStarter(world.generationJobClient, assetRunner,
                        world.mediaGeneratedImageClient, world.mediaSettingsBridgeClient,
                        world.currentActorService, world.mapper));
        world.asUser(ADMIN);
    }

    @AfterEach
    void tearDown() {
        bulkExecutor.shutdown();
        assetExecutor.shutdown();
        world.close();
    }

    // ---- 部品 ----

    private void bindThreeManaged() {
        world.bindEnvironments(1L, "test", SiteSpec.managed(10L, "local-site"), SiteSpec.managed(20L, "test-site"),
                SiteSpec.managed(30L, "production-site"));
    }

    private void bindSsh() {
        world.bindEnvironments(1L, "test", SiteSpec.ssh(10L, "local-site", "ssh-local", "/var/www/local"),
                SiteSpec.ssh(20L, "test-site", "ssh-test", "/var/www/test"), null);
    }

    private List<JsonNode> agentCommands() {
        return world.agent.requests("POST", "/bulk-management").stream().map(r -> {
            try {
                return world.mapper.readTree(r.body());
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    private List<String> agentActions() {
        return agentCommands().stream().map(c -> c.path("slug").asText() + ":" + c.path("action").asText()).toList();
    }

    private static void assertAllSuccess(List<BulkOperationLogResponse> logs, int expectedSize) {
        assertEquals(expectedSize, logs.size());
        for (BulkOperationLogResponse log : logs) {
            assertEquals(BulkOperationStatus.SUCCESS, log.status(), log.errorMessage());
        }
    }

    private List<JobUpdate> awaitTerminal() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<JobUpdate> updates = world.jobUpdates();
            if (updates.stream().anyMatch(u -> u.status().equals("done") || u.status().equals("failed"))) {
                return updates;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("ジョブの終端の通知が来ませんでした: " + world.jobUpdates());
    }

    private static MockMultipartFile zip() {
        return new MockMultipartFile("file", "custom-plugin.zip", "application/zip", new byte[] {1, 2, 3});
    }

    // ---- 認可 ----

    /** admin権限が無ければ、全てのエンドポイントがForbiddenで、CMS・agent・ai-service・media-serviceに触れない。 */
    @Test
    void admin権限がなければどのエンドポイントもForbiddenで外部へ触れない() {
        bindThreeManaged();
        world.asUser(USER);
        ApplyToEnvironmentRequest apply = new ApplyToEnvironmentRequest(
                "test", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null);
        ApplyToAllEnvironmentsRequest applyAll =
                new ApplyToAllEnvironmentsRequest(BulkOperationType.PLUGIN_INSTALL, "akismet");
        TermNameRequest term = new TermNameRequest("oshirase");
        EditTermRequest edit = new EditTermRequest("old-slug", "新しい名前", "new-slug", null, null);
        ReconcileStateRequest reconcile = new ReconcileStateRequest(
                "akismet", List.of(new ReconcileStateRequest.StateChangeRequest("local", "ACTIVE")));
        DeleteSlugRequest delete = new DeleteSlugRequest("akismet");
        Map<String, Executable> calls = new LinkedHashMap<>();
        calls.put("applyBulkOperation", () -> controller.applyBulkOperation(1L, apply));
        calls.put("applyBulkOperationToAllEnvironments",
                () -> controller.applyBulkOperationToAllEnvironments(1L, applyAll));
        calls.put("runBulkOperationUpload",
                () -> controller.runBulkOperationUpload(1L, BulkOperationType.PLUGIN_INSTALL, zip()));
        calls.put("applyBulkOperationToAllEnvironmentsJob",
                () -> controller.applyBulkOperationToAllEnvironmentsJob(1L, applyAll));
        calls.put("runBulkOperationUploadJob",
                () -> controller.runBulkOperationUploadJob(1L, BulkOperationType.PLUGIN_INSTALL, zip()));
        calls.put("uploadAssetImage", () -> controller.uploadAssetImage(1L, 5L));
        calls.put("uploadAssetImageJob", () -> controller.uploadAssetImageJob(1L, 5L));
        calls.put("categoryComparison", () -> controller.categoryComparison(1L, 0));
        calls.put("tagComparison", () -> controller.tagComparison(1L, 0));
        calls.put("syncCategory", () -> controller.syncCategory(1L, term));
        calls.put("deleteCategoryEverywhere", () -> controller.deleteCategoryEverywhere(1L, term));
        calls.put("editCategoryAndSync", () -> controller.editCategoryAndSync(1L, edit));
        calls.put("syncAllCategoriesToMaster", () -> controller.syncAllCategoriesToMaster(1L));
        calls.put("syncTag", () -> controller.syncTag(1L, term));
        calls.put("deleteTagEverywhere", () -> controller.deleteTagEverywhere(1L, term));
        calls.put("editTagAndSync", () -> controller.editTagAndSync(1L, edit));
        calls.put("syncAllTagsToMaster", () -> controller.syncAllTagsToMaster(1L));
        calls.put("pluginComparison", () -> controller.pluginComparison(1L, 0));
        calls.put("themeComparison", () -> controller.themeComparison(1L, 0));
        calls.put("reconcilePlugin", () -> controller.reconcilePlugin(1L, reconcile));
        calls.put("reconcileTheme", () -> controller.reconcileTheme(1L, reconcile));
        calls.put("deletePluginEverywhere", () -> controller.deletePluginEverywhere(1L, delete));
        calls.put("deleteThemeEverywhere", () -> controller.deleteThemeEverywhere(1L, delete));
        calls.put("postComparison", () -> controller.postComparison(1L, "post", 0));
        calls.put("deletePostEverywhere", () -> controller.deletePostEverywhere(1L, "post", delete));
        calls.put("updatePostStatusEverywhere", () -> controller.updatePostStatusEverywhere(
                1L, "post", new UpdatePostStatusRequest("some-post", "publish")));

        calls.forEach((name, call) -> assertThrows(ForbiddenException.class, call, name));

        assertTrue(world.agent.requests().isEmpty(), "provision-agentには触れない");
        assertTrue(world.ssh.commands.isEmpty(), "WordPressには触れない");
        assertTrue(world.ai.requests().isEmpty(), "ジョブは作られない");
        assertTrue(world.media.requests().isEmpty(), "画像は取得されない");
        assertTrue(world.project.requests().isEmpty(), "プロジェクトの解決も始まらない");
    }

    // ---- 環境ごとの実行 ----

    @Test
    void applyBulkOperation_マスター環境へのカテゴリ作成は実行できる() {
        bindThreeManaged();
        ApplyToEnvironmentRequest request = new ApplyToEnvironmentRequest(
                "test", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null);

        BulkOperationLogResponse response = controller.applyBulkOperation(1L, request);

        assertEquals(BulkOperationStatus.SUCCESS, response.status(), response.errorMessage());
        assertEquals("test", response.environment());
        assertEquals("お知らせ", response.value());
        assertEquals(List.of("test-site:category_create"), agentActions());
    }

    @Test
    void applyBulkOperation_マスター環境以外へのカテゴリ作成は例外() {
        bindThreeManaged();
        ApplyToEnvironmentRequest request = new ApplyToEnvironmentRequest(
                "production", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null);

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> controller.applyBulkOperation(1L, request));

        assertTrue(e.getMessage().contains("マスター環境(test)"), e.getMessage());
        assertTrue(world.agent.requests().isEmpty(), "どの環境にも作らない");
    }

    @Test
    void applyBulkOperation_マスター環境を要しない操作はマスター以外の環境にも実行できる() {
        bindThreeManaged();
        ApplyToEnvironmentRequest request = new ApplyToEnvironmentRequest(
                "production", BulkOperationType.PLUGIN_INSTALL, "akismet", null, null, null, null);

        BulkOperationLogResponse response = controller.applyBulkOperation(1L, request);

        assertEquals(BulkOperationStatus.SUCCESS, response.status(), response.errorMessage());
        assertEquals("production", response.environment());
        assertEquals(List.of("production-site:plugin_install"), agentActions());
    }

    @Test
    void applyBulkOperationToAllEnvironments_admin権限があれば全環境へ実行できる() {
        bindThreeManaged();

        List<BulkOperationLogResponse> response = controller.applyBulkOperationToAllEnvironments(
                1L, new ApplyToAllEnvironmentsRequest(BulkOperationType.PLUGIN_INSTALL, "akismet"));

        assertAllSuccess(response, 3);
        assertEquals(List.of("local", "test", "production"),
                response.stream().map(BulkOperationLogResponse::environment).toList());
        assertEquals(3, agentActions().size());
    }

    @Test
    void runBulkOperationUpload_admin権限があれば実行できる() throws Exception {
        bindThreeManaged();

        List<BulkOperationLogResponse> response =
                controller.runBulkOperationUpload(1L, BulkOperationType.PLUGIN_INSTALL, zip());

        assertAllSuccess(response, 3);
        assertEquals("custom-plugin.zip", response.get(0).originalFilename());
        assertEquals(3, world.agent.requests("POST", "/bulk-management/upload").size());
    }

    // ---- アセット画像 ----

    private void generatedImage(long id, byte[] data) {
        world.media.on("GET", "/api/generated-images/" + id + "/file", Response.bytes(200, "image/png", data));
    }

    private FakeUploaded uploaded() {
        var media = world.ssh.site("ssh-local", "/var/www/local").media.values();
        assertEquals(1, media.size());
        return new FakeUploaded(media.iterator().next().url);
    }

    private record FakeUploaded(String url) {
    }

    @Test
    void uploadAssetImage_admin権限があれば全環境アップロードを実行できる() {
        bindSsh();
        generatedImage(5L, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1});

        List<BulkOperationLogResponse> response = controller.uploadAssetImage(1L, 5L);

        assertAllSuccess(response, 2);
        assertTrue(uploaded().url().endsWith("comfyui-5.png"), uploaded().url());
        assertEquals(1, world.ssh.site("ssh-test", "/var/www/test").media.size());
    }

    @Test
    void uploadAssetImage_JPEGの内容なら拡張子jpgで全環境へアップロードする() {
        bindSsh();
        generatedImage(6L, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 1, 2});

        List<BulkOperationLogResponse> response = controller.uploadAssetImage(1L, 6L);

        assertAllSuccess(response, 2);
        assertTrue(uploaded().url().endsWith("comfyui-6.jpg"), uploaded().url());
    }

    @Test
    void uploadAssetImage_存在しない画像は例外() {
        bindSsh();
        world.media.status("GET", "/api/generated-images/99/file", 404, "{}");

        assertThrows(MediaRenderException.class, () -> controller.uploadAssetImage(1L, 99L));

        assertTrue(world.ssh.site("ssh-local", "/var/www/local").media.isEmpty());
    }

    // ---- 比較・同期(カテゴリ・タグ) ----

    @Test
    void categoryComparison_admin権限があれば取得できる() {
        bindThreeManaged();
        world.agentListings.categories("test-site", new Term("お知らせ", "oshirase", null, "説明"));

        TermComparisonPage page = controller.categoryComparison(1L, 0);

        assertEquals("test", page.masterEnvironment());
        assertEquals(1, page.items().size());
        assertEquals("oshirase", page.items().get(0).slug());
        assertTrue(page.items().get(0).test().available());
    }

    @Test
    void tagComparison_admin権限があれば取得できる() {
        bindThreeManaged();
        world.agentListings.tags("local-site", new Term("Java", "java", null, null));

        TermComparisonPage page = controller.tagComparison(1L, 0);

        assertEquals(1, page.items().size());
        assertEquals("java", page.items().get(0).slug());
        assertTrue(page.items().get(0).local().available());
    }

    @Test
    void syncCategory_admin権限があれば実行できる() {
        bindThreeManaged();
        world.agentListings.categories("test-site", new Term("お知らせ", "oshirase", null, null));

        List<BulkOperationLogResponse> response = controller.syncCategory(1L, new TermNameRequest("oshirase"));

        assertAllSuccess(response, 2);
        assertEquals(List.of("local-site:category_create", "production-site:category_create"), agentActions());
    }

    @Test
    void deleteCategoryEverywhere_admin権限があれば実行できる() {
        bindThreeManaged();
        world.agentListings.categories("local-site", new Term("お知らせ", "oshirase", null, null));

        List<BulkOperationLogResponse> response =
                controller.deleteCategoryEverywhere(1L, new TermNameRequest("oshirase"));

        assertAllSuccess(response, 1);
        assertEquals(List.of("local-site:category_delete"), agentActions());
    }

    @Test
    void editCategoryAndSync_admin権限があれば実行できる() {
        bindThreeManaged();
        world.agentListings.categories("test-site", new Term("旧", "old-slug", null, null));

        List<BulkOperationLogResponse> response = controller.editCategoryAndSync(
                1L, new EditTermRequest("old-slug", "新しい名前", "new-slug", null, null));

        assertFalse(response.isEmpty());
        assertTrue(agentCommands().stream().anyMatch(c -> c.path("action").asText().equals("category_edit")
                && c.path("slug").asText().equals("test-site")), agentCommands().toString());
    }

    @Test
    void syncAllCategoriesToMaster_admin権限があれば実行できる() {
        bindThreeManaged();
        world.agentListings.categories("test-site", new Term("お知らせ", "oshirase", null, null));

        List<BulkOperationLogResponse> response = controller.syncAllCategoriesToMaster(1L);

        assertAllSuccess(response, 2);
        assertEquals(List.of("local-site:category_create", "production-site:category_create"), agentActions());
    }

    @Test
    void syncTag_admin権限があれば実行できる() {
        bindThreeManaged();
        world.agentListings.tags("test-site", new Term("Java", "java", null, null));

        List<BulkOperationLogResponse> response = controller.syncTag(1L, new TermNameRequest("java"));

        assertAllSuccess(response, 2);
        assertEquals(List.of("local-site:tag_create", "production-site:tag_create"), agentActions());
    }

    @Test
    void deleteTagEverywhere_admin権限があれば実行できる() {
        bindThreeManaged();
        world.agentListings.tags("production-site", new Term("Java", "java", null, null));

        List<BulkOperationLogResponse> response = controller.deleteTagEverywhere(1L, new TermNameRequest("java"));

        assertAllSuccess(response, 1);
        assertEquals(List.of("production-site:tag_delete"), agentActions());
    }

    @Test
    void editTagAndSync_admin権限があれば実行できる() {
        bindThreeManaged();
        world.agentListings.tags("test-site", new Term("旧", "old-slug", null, null));

        List<BulkOperationLogResponse> response = controller.editTagAndSync(
                1L, new EditTermRequest("old-slug", "新しい名前", "new-slug", null, null));

        assertFalse(response.isEmpty());
        assertTrue(agentCommands().stream().anyMatch(c -> c.path("action").asText().equals("tag_edit")
                && c.path("slug").asText().equals("test-site")), agentCommands().toString());
    }

    @Test
    void syncAllTagsToMaster_admin権限があれば実行できる() {
        bindThreeManaged();
        world.agentListings.tags("test-site", new Term("Java", "java", null, null));

        List<BulkOperationLogResponse> response = controller.syncAllTagsToMaster(1L);

        assertAllSuccess(response, 2);
        assertEquals(List.of("local-site:tag_create", "production-site:tag_create"), agentActions());
    }

    // ---- 比較・同期(プラグイン・テーマ) ----

    @Test
    void pluginComparison_admin権限があれば取得できる() {
        bindThreeManaged();
        world.agentListings.plugins("test-site", new Extension("akismet", "active"));

        StatusComparisonPage page = controller.pluginComparison(1L, 0);

        assertEquals(1, page.items().size());
        assertEquals("akismet", page.items().get(0).slug());
        assertEquals("test", page.masterEnvironment());
    }

    @Test
    void themeComparison_admin権限があれば取得できる() {
        bindThreeManaged();
        world.agentListings.themes("local-site", new Extension("twentytwentyfour", "active"));

        StatusComparisonPage page = controller.themeComparison(1L, 0);

        assertEquals(1, page.items().size());
        assertEquals("twentytwentyfour", page.items().get(0).slug());
    }

    @Test
    void reconcilePlugin_admin権限があれば実行できる() {
        bindThreeManaged();

        List<BulkOperationLogResponse> response = controller.reconcilePlugin(1L, new ReconcileStateRequest(
                "akismet", List.of(new ReconcileStateRequest.StateChangeRequest("local", "ACTIVE"))));

        assertFalse(response.isEmpty());
        assertEquals(List.of("local-site:plugin_install", "local-site:plugin_activate"), agentActions());
    }

    @Test
    void reconcileTheme_admin権限があれば実行できる() {
        bindThreeManaged();

        List<BulkOperationLogResponse> response = controller.reconcileTheme(1L, new ReconcileStateRequest(
                "twentytwentyfour", List.of(new ReconcileStateRequest.StateChangeRequest("local", "ACTIVE"))));

        assertFalse(response.isEmpty());
        assertEquals(List.of("local-site:theme_install", "local-site:theme_activate"), agentActions());
    }

    @Test
    void deletePluginEverywhere_admin権限があれば実行できる() {
        bindThreeManaged();
        world.agentListings.plugins("test-site", new Extension("akismet", "inactive"));

        List<BulkOperationLogResponse> response = controller.deletePluginEverywhere(1L, new DeleteSlugRequest("akismet"));

        assertAllSuccess(response, 1);
        assertEquals(List.of("test-site:plugin_delete"), agentActions());
    }

    @Test
    void deleteThemeEverywhere_admin権限があれば実行できる() {
        bindThreeManaged();
        world.agentListings.themes("production-site", new Extension("twentytwentyfour", "inactive"));

        List<BulkOperationLogResponse> response =
                controller.deleteThemeEverywhere(1L, new DeleteSlugRequest("twentytwentyfour"));

        assertAllSuccess(response, 1);
        assertEquals(List.of("production-site:theme_delete"), agentActions());
    }

    // ---- 投稿 ----

    @Test
    void postComparison_admin権限があれば取得できる() {
        bindSsh();
        world.ssh.site("ssh-local", "/var/www/local").addPost("101", "post", "こんにちは", "hello", "publish");

        PostComparisonPage page = controller.postComparison(1L, "post", 0);

        assertEquals(1, page.items().size());
        assertEquals("hello", page.items().get(0).slug());
        assertEquals("101", page.items().get(0).local().postId());
    }

    @Test
    void deletePostEverywhere_admin権限があれば実行できる() {
        bindSsh();
        world.ssh.site("ssh-local", "/var/www/local").addPost("101", "post", "こんにちは", "hello", "publish");

        List<BulkOperationLogResponse> response = controller.deletePostEverywhere(1L, "post", new DeleteSlugRequest("hello"));

        assertAllSuccess(response, 1);
        assertEquals("trash", world.ssh.site("ssh-local", "/var/www/local").posts.get("101").status);
    }

    @Test
    void updatePostStatusEverywhere_admin権限があれば実行できる() {
        bindSsh();
        world.ssh.site("ssh-local", "/var/www/local").addPost("101", "post", "こんにちは", "hello", "draft");

        List<BulkOperationLogResponse> response = controller.updatePostStatusEverywhere(
                1L, "post", new UpdatePostStatusRequest("hello", "publish"));

        assertAllSuccess(response, 1);
        assertEquals("publish", world.ssh.site("ssh-local", "/var/www/local").posts.get("101").status);
    }

    // ---- issue #1702: 全環境インストールをジョブとして受理する口 ----

    @Test
    void applyBulkOperationToAllEnvironmentsJob_adminなら202とジョブを返す() throws Exception {
        bindThreeManaged();

        ResponseEntity<GenerationJobResponse> response = controller.applyBulkOperationToAllEnvironmentsJob(
                1L, new ApplyToAllEnvironmentsRequest(BulkOperationType.PLUGIN_INSTALL, "akismet"));

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("bulk_slug_install", response.getBody().type());
        assertEquals("running", response.getBody().status());
        List<JobUpdate> updates = awaitTerminal();
        assertEquals("done", updates.get(updates.size() - 1).status());
        assertEquals(response.getBody().id(), updates.get(updates.size() - 1).id());
        assertEquals(3, agentActions().size());
    }

    @Test
    void runBulkOperationUploadJob_adminなら202とジョブを返す() throws Exception {
        bindThreeManaged();

        ResponseEntity<GenerationJobResponse> response =
                controller.runBulkOperationUploadJob(1L, BulkOperationType.PLUGIN_INSTALL, zip());

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("bulk_zip_install", response.getBody().type());
        List<JobUpdate> updates = awaitTerminal();
        assertEquals("done", updates.get(updates.size() - 1).status());
        assertEquals(3, world.agent.requests("POST", "/bulk-management/upload").size());
    }

    @Test
    void uploadAssetImageJob_adminなら202とジョブを返す() throws Exception {
        bindSsh();
        generatedImage(5L, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1});

        ResponseEntity<GenerationJobResponse> response = controller.uploadAssetImageJob(1L, 5L);

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("asset_image_upload", response.getBody().type());
        assertEquals("running", response.getBody().status());
        List<JobUpdate> updates = awaitTerminal();
        assertEquals("done", updates.get(updates.size() - 1).status());
        assertTrue(uploaded().url().endsWith("comfyui-5.png"), uploaded().url());
    }
}
