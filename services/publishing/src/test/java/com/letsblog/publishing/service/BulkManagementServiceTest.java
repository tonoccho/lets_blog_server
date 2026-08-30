package com.letsblog.publishing.service;

import com.letsblog.publishing.client.LegacyApiBridgeClient;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.domain.BulkOperationLog;
import com.letsblog.publishing.domain.BulkOperationSourceType;
import com.letsblog.publishing.domain.BulkOperationStatus;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.domain.Project;
import com.letsblog.publishing.domain.Site;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient.BulkApplyCommand;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient.BulkApplyResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BulkManagementServiceTest {

    @Mock
    private WordPressBulkManagementClient bulkManagementClient;

    @Mock
    private BulkUploadStorageService bulkUploadStorageService;

    @Mock
    private SiteService siteService;

    @Mock
    private com.letsblog.publishing.cms.ssh.WordPressSshOperations sshOperations;

    @Mock
    private com.letsblog.publishing.cms.CmsAdapterFactory cmsAdapterFactory;

    @Mock
    private ProjectService projectService;

    @Mock
    private ImageResizeService imageResizeService;

    @Mock
    private LegacyApiBridgeClient legacyApiBridgeClient;

    private BulkManagementService service() {
        return new BulkManagementService(
                bulkManagementClient,
                bulkUploadStorageService, siteService, sshOperations, cmsAdapterFactory,
                projectService, imageResizeService, legacyApiBridgeClient);
    }

    private Project buildProject(Long localSiteId, Long testSiteId, Long productionSiteId) {
        Project project = new Project();
        project.setId(1L);
        project.setName("テスト");
        project.setSlug("test");
        project.setLocalSiteId(localSiteId);
        project.setTestSiteId(testSiteId);
        project.setProductionSiteId(productionSiteId);
        return project;
    }

    private Site buildManagedSite(Long id, String slug) {
        Site site = new Site();
        site.setId(id);
        site.setSiteKey(slug);
        site.setCmsType(CmsType.WORDPRESS);
        site.setManagedWordpress(true);
        site.setWpSlug(slug);
        return site;
    }

    // ---- applyToEnvironment: CATEGORY_CREATE ----

    @Test
    void applyToEnvironment_categoryCreate_スラッグ未指定は例外() {
        BulkManagementService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_CREATE, "お知らせ", null, null, null, null, 9L));
    }

    @Test
    void applyToEnvironment_categoryCreate_環境にサイトが紐付いていなければ例外() {
        BulkManagementService service = service();
        Project project = buildProject(null, null, null);
        when(projectService.getProjectEntity(1L)).thenReturn(project);

        assertThrows(IllegalArgumentException.class, () -> service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null, 9L));
        verify(bulkManagementClient, never()).apply(any());
    }

    @Test
    void applyToEnvironment_categoryCreate_非managedサイトの環境は例外() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site externalSite = new Site();
        externalSite.setId(10L);
        externalSite.setSiteKey("external-site");
        externalSite.setCmsType(CmsType.WORDPRESS);
        externalSite.setManagedWordpress(false);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(externalSite));
        when(siteService.resolveDataSource(externalSite)).thenReturn(new SiteService.SiteDataSource(false, null));

        assertThrows(IllegalArgumentException.class, () -> service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null, 9L));
    }

    @Test
    void applyToEnvironment_categoryCreate_指定した環境のみへ適用してログを保存する() {
        BulkManagementService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "category_create", "お知らせ", "oshirase", null, null, null)))
                .thenReturn(BulkApplyResult.success());

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null, 9L);

        assertEquals("local", result.getEnvironment());
        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        assertEquals(BulkOperationSourceType.SLUG, result.getSourceType());
        assertEquals("oshirase", result.getCategorySlug());
        verify(siteService, never()).getById(20L);
    }

    @Test
    void applyToEnvironment_categoryCreate_親カテゴリと説明を渡せる() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "category_create", "サブお知らせ", "sub-oshirase", "oshirase", "説明文", null)))
                .thenReturn(BulkApplyResult.success());

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_CREATE, "サブお知らせ", "sub-oshirase", "oshirase", "説明文",
                null, 9L);

        assertEquals("sub-oshirase", result.getCategorySlug());
        assertEquals("oshirase", result.getCategoryParentSlug());
        assertEquals("説明文", result.getCategoryDescription());
    }

    @Test
    void applyToEnvironment_失敗結果もそのまま記録される() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "plugin_install", "akismet", null, null, null, null)))
                .thenReturn(BulkApplyResult.failed(new RuntimeException("接続に失敗しました")));

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.PLUGIN_INSTALL, "akismet", null, null, null, null, 9L);

        assertEquals(BulkOperationStatus.FAILED, result.getStatus());
        assertEquals("接続に失敗しました", result.getErrorMessage());
    }

    @Test
    void applyToEnvironment_SKIPPEDもそのまま記録される() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "theme_install", "twentytwentyfour", null, null, null, null)))
                .thenReturn(BulkApplyResult.skipped());

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.THEME_INSTALL, "twentytwentyfour", null, null, null, null, 9L);

        assertEquals(BulkOperationStatus.SKIPPED, result.getStatus());
    }

    @Test
    void applyToEnvironment_プラグイン系はslug未指定で例外() {
        BulkManagementService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.applyToEnvironment(
                1L, "local", BulkOperationType.PLUGIN_ACTIVATE, "", null, null, null, null, 9L));
    }

    @Test
    void applyToEnvironment_プラグイン有効化_無効化_削除がそれぞれのactionで呼ばれる() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(any())).thenReturn(BulkApplyResult.success());

        service.applyToEnvironment(1L, "local", BulkOperationType.PLUGIN_ACTIVATE, "akismet", null, null, null, null, 9L);
        service.applyToEnvironment(1L, "local", BulkOperationType.PLUGIN_DEACTIVATE, "akismet", null, null, null, null, 9L);
        service.applyToEnvironment(1L, "local", BulkOperationType.PLUGIN_DELETE, "akismet", null, null, null, null, 9L);

        verify(bulkManagementClient).apply(new BulkApplyCommand(
                "local-site", "plugin_activate", "akismet", null, null, null, null));
        verify(bulkManagementClient).apply(new BulkApplyCommand(
                "local-site", "plugin_deactivate", "akismet", null, null, null, null));
        verify(bulkManagementClient).apply(new BulkApplyCommand(
                "local-site", "plugin_delete", "akismet", null, null, null, null));
    }

    @Test
    void applyToEnvironment_テーマ有効化_削除がそれぞれのactionで呼ばれる() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(any())).thenReturn(BulkApplyResult.success());

        service.applyToEnvironment(1L, "local", BulkOperationType.THEME_ACTIVATE, "twentytwentyfour", null, null, null, null, 9L);
        service.applyToEnvironment(1L, "local", BulkOperationType.THEME_DELETE, "twentytwentyfour", null, null, null, null, 9L);

        verify(bulkManagementClient).apply(new BulkApplyCommand(
                "local-site", "theme_activate", "twentytwentyfour", null, null, null, null));
        verify(bulkManagementClient).apply(new BulkApplyCommand(
                "local-site", "theme_delete", "twentytwentyfour", null, null, null, null));
    }

    // ---- applyToEnvironment: CATEGORY_EDIT / CATEGORY_DELETE / TAG_* ----

    @Test
    void applyToEnvironment_categoryEdit_targetSlug未指定は例外() {
        BulkManagementService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_EDIT, "新名前", "new-slug", null, null, null, 9L));
    }

    @Test
    void applyToEnvironment_categoryEdit_正しいコマンドで呼ばれる() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "category_edit", "新お知らせ", "new-oshirase", "parent-slug", "更新後の説明", "old-oshirase")))
                .thenReturn(BulkApplyResult.success());

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_EDIT, "新お知らせ", "new-oshirase", "parent-slug", "更新後の説明",
                "old-oshirase", 9L);

        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        assertEquals("old-oshirase", result.getCategoryTargetSlug());
    }

    @Test
    void applyToEnvironment_categoryDelete_targetSlug未指定は例外() {
        BulkManagementService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_DELETE, null, null, null, null, null, 9L));
    }

    @Test
    void applyToEnvironment_categoryDelete_valueが空でもtargetSlugで補われる() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "category_delete", "oshirase", null, null, null, "oshirase")))
                .thenReturn(BulkApplyResult.success());

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_DELETE, null, null, null, null, "oshirase", 9L);

        assertEquals("oshirase", result.getValue());
        assertEquals("oshirase", result.getCategoryTargetSlug());
    }

    @Test
    void applyToEnvironment_tagCreate_正しいactionで呼ばれる() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "tag_create", "新着", "shinchaku", null, null, null)))
                .thenReturn(BulkApplyResult.success());

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.TAG_CREATE, "新着", "shinchaku", null, null, null, 9L);

        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        assertEquals("shinchaku", result.getCategorySlug());
    }

    // ---- applyToAllEnvironments (issue #393) ----

    @Test
    void applyToAllEnvironments_カテゴリ系は例外() {
        BulkManagementService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.applyToAllEnvironments(
                1L, BulkOperationType.CATEGORY_CREATE, "お知らせ", 9L));
    }

    @Test
    void applyToAllEnvironments_有効化等のアクションは例外() {
        BulkManagementService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.applyToAllEnvironments(
                1L, BulkOperationType.PLUGIN_ACTIVATE, "akismet", 9L));
    }

    @Test
    void applyToAllEnvironments_slug未指定は例外() {
        BulkManagementService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.applyToAllEnvironments(
                1L, BulkOperationType.PLUGIN_INSTALL, "", 9L));
    }

    @Test
    void applyToAllEnvironments_紐付いている全環境へインストールしログを保存する() {
        BulkManagementService service = service();
        Project project = buildProject(10L, 20L, 30L);
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        Site productionSite = buildManagedSite(30L, "production-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(siteService.getById(30L)).thenReturn(Optional.of(productionSite));
        when(bulkManagementClient.apply(any())).thenReturn(BulkApplyResult.success());

        List<BulkOperationLog> results = service.applyToAllEnvironments(
                1L, BulkOperationType.PLUGIN_INSTALL, "akismet", 9L);

        assertEquals(3, results.size());
        assertEquals("local", results.get(0).getEnvironment());
        assertEquals("test", results.get(1).getEnvironment());
        assertEquals("production", results.get(2).getEnvironment());
        results.forEach(r -> assertEquals(BulkOperationStatus.SUCCESS, r.getStatus()));
        verify(bulkManagementClient).apply(new BulkApplyCommand(
                "local-site", "plugin_install", "akismet", null, null, null, null));
        verify(bulkManagementClient).apply(new BulkApplyCommand(
                "test-site", "plugin_install", "akismet", null, null, null, null));
        verify(bulkManagementClient).apply(new BulkApplyCommand(
                "production-site", "plugin_install", "akismet", null, null, null, null));
    }

    @Test
    void applyToAllEnvironments_紐付いていない環境はスキップする() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(any())).thenReturn(BulkApplyResult.success());

        List<BulkOperationLog> results = service.applyToAllEnvironments(
                1L, BulkOperationType.PLUGIN_INSTALL, "akismet", 9L);

        assertEquals(1, results.size());
        assertEquals("local", results.get(0).getEnvironment());
    }

    @Test
    void applyToAllEnvironments_1環境が対象外でも他環境の実行を止めずFAILEDとして記録する() {
        BulkManagementService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildManagedSite(10L, "local-site");
        Site externalSite = buildExternalSite(20L, "external-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(externalSite));
        when(siteService.resolveDataSource(externalSite)).thenReturn(new SiteService.SiteDataSource(false, null));
        when(bulkManagementClient.apply(any())).thenReturn(BulkApplyResult.success());

        List<BulkOperationLog> results = service.applyToAllEnvironments(
                1L, BulkOperationType.PLUGIN_INSTALL, "akismet", 9L);

        assertEquals(2, results.size());
        assertEquals(BulkOperationStatus.SUCCESS, results.get(0).getStatus());
        assertEquals("local", results.get(0).getEnvironment());
        assertEquals(BulkOperationStatus.FAILED, results.get(1).getStatus());
        assertEquals("test", results.get(1).getEnvironment());
        verify(bulkManagementClient, never()).apply(new BulkApplyCommand(
                "external-site", "plugin_install", "akismet", null, null, null, null));
    }

    // ---- executeFromUpload ----

    @Test
    void executeFromUpload_カテゴリ系は例外() {
        BulkManagementService service = service();
        MockMultipartFile file = new MockMultipartFile("file", "theme.zip", "application/zip", new byte[]{1});

        assertThrows(IllegalArgumentException.class,
                () -> service.executeFromUpload(1L, BulkOperationType.CATEGORY_CREATE, file, 9L));
    }

    @Test
    void executeFromUpload_有効化等のアクションは例外() {
        BulkManagementService service = service();
        MockMultipartFile file = new MockMultipartFile("file", "theme.zip", "application/zip", new byte[]{1});

        assertThrows(IllegalArgumentException.class,
                () -> service.executeFromUpload(1L, BulkOperationType.PLUGIN_ACTIVATE, file, 9L));
    }

    @Test
    void executeFromUpload_拡張子がzipでなければ例外() {
        BulkManagementService service = service();
        MockMultipartFile file = new MockMultipartFile("file", "theme.txt", "text/plain", new byte[]{1});

        assertThrows(IllegalArgumentException.class,
                () -> service.executeFromUpload(1L, BulkOperationType.THEME_INSTALL, file, 9L));
    }

    @Test
    void executeFromUpload_保存してzipを各環境へ転送しログを保存する() throws IOException {
        BulkManagementService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        byte[] content = new byte[]{1, 2, 3};
        MockMultipartFile file = new MockMultipartFile("file", "custom-theme.zip", "application/zip", content);

        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(bulkUploadStorageService.store(eq(1L), any(byte[].class), eq("custom-theme.zip")))
                .thenReturn(new BulkUploadStorageService.StoredZip("1/abc.zip", "abc", "custom-theme.zip"));
        when(bulkManagementClient.applyZip(any(), eq("theme_install"), any(byte[].class), eq("custom-theme.zip")))
                .thenReturn(BulkApplyResult.success());

        List<BulkOperationLog> results = service.executeFromUpload(1L, BulkOperationType.THEME_INSTALL, file, 9L);

        assertEquals(2, results.size());
        assertEquals(BulkOperationSourceType.ZIP, results.get(0).getSourceType());
        assertEquals("custom-theme.zip", results.get(0).getOriginalFilename());
        assertEquals("1/abc.zip", results.get(0).getStoragePath());
        verify(bulkManagementClient).applyZip(eq("local-site"), eq("theme_install"), any(byte[].class), eq("custom-theme.zip"));
        verify(bulkManagementClient).applyZip(eq("test-site"), eq("theme_install"), any(byte[].class), eq("custom-theme.zip"));
    }

    @Test
    void executeFromUpload_SSH接続情報のある非managed環境にもSFTP転送で適用する() throws IOException {
        BulkManagementService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildManagedSite(10L, "local-site");
        Site externalSite = buildExternalSite(20L, "external-site");
        byte[] content = new byte[]{1, 2, 3};
        MockMultipartFile file = new MockMultipartFile("file", "custom-theme.zip", "application/zip", content);

        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(externalSite));
        when(siteService.resolveDataSource(externalSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCreds()));
        when(bulkUploadStorageService.store(eq(1L), any(byte[].class), eq("custom-theme.zip")))
                .thenReturn(new BulkUploadStorageService.StoredZip("1/abc.zip", "abc", "custom-theme.zip"));
        when(bulkManagementClient.applyZip(any(), eq("theme_install"), any(byte[].class), eq("custom-theme.zip")))
                .thenReturn(BulkApplyResult.success());
        when(sshOperations.applyZip(eq(sshCreds()), eq(BulkOperationType.THEME_INSTALL), any(byte[].class), eq("custom-theme.zip")))
                .thenReturn(new com.letsblog.publishing.cms.ssh.WordPressSshOperations.SshApplyResult("SUCCESS", null, null));

        List<BulkOperationLog> results = service.executeFromUpload(1L, BulkOperationType.THEME_INSTALL, file, 9L);

        assertEquals(2, results.size());
        verify(bulkManagementClient).applyZip(eq("local-site"), eq("theme_install"), any(byte[].class), eq("custom-theme.zip"));
        verify(sshOperations).applyZip(eq(sshCreds()), eq(BulkOperationType.THEME_INSTALL), any(byte[].class), eq("custom-theme.zip"));
    }

    @Test
    void executeFromUpload_SSHが無い非managed環境は対象外() throws IOException {
        BulkManagementService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildManagedSite(10L, "local-site");
        Site externalSite = buildExternalSite(20L, "external-site");
        byte[] content = new byte[]{1, 2, 3};
        MockMultipartFile file = new MockMultipartFile("file", "custom-theme.zip", "application/zip", content);

        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(externalSite));
        when(siteService.resolveDataSource(externalSite))
                .thenReturn(new SiteService.SiteDataSource(false, null));
        when(bulkUploadStorageService.store(eq(1L), any(byte[].class), eq("custom-theme.zip")))
                .thenReturn(new BulkUploadStorageService.StoredZip("1/abc.zip", "abc", "custom-theme.zip"));
        when(bulkManagementClient.applyZip(any(), eq("theme_install"), any(byte[].class), eq("custom-theme.zip")))
                .thenReturn(BulkApplyResult.success());

        List<BulkOperationLog> results = service.executeFromUpload(1L, BulkOperationType.THEME_INSTALL, file, 9L);

        assertEquals(1, results.size());
        assertEquals("local", results.get(0).getEnvironment());
        verify(sshOperations, never()).applyZip(any(), any(), any(), any());
    }

    // ---- uploadImageToAllEnvironments ----

    @Test
    void uploadImageToAllEnvironments_紐付いた環境ごとにアップロードしログを記録する() {
        BulkManagementService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));

        com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials localCreds =
                new com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials("https://local.test", "admin", "SSH");
        com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials testCreds =
                new com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials("https://test.test", "admin", "SSH");
        when(siteService.getCredentials("local-site")).thenReturn(localCreds);
        when(siteService.getCredentials("test-site")).thenReturn(testCreds);

        byte[] originalData = new byte[]{1, 2, 3};
        byte[] resizedData = new byte[]{9, 9, 9};
        when(legacyApiBridgeClient.resolveArticleImageLongEdgePx(1L)).thenReturn(1300);
        when(imageResizeService.resizeToLongEdge(originalData, "image/png", 1300, true))
                .thenReturn(new ImageResizeService.ResizeResult(resizedData, "image/png"));

        com.letsblog.publishing.cms.CmsAdapter adapter = org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(com.letsblog.publishing.cms.CmsType.WORDPRESS)).thenReturn(adapter);
        when(adapter.uploadMedia(eq(localCreds), eq("cat.png"), eq("image/png"), eq(resizedData)))
                .thenReturn(new com.letsblog.publishing.cms.MediaUploadResult("1", "https://local.test/cat.png"));
        when(adapter.uploadMedia(eq(testCreds), eq("cat.png"), eq("image/png"), eq(resizedData)))
                .thenThrow(new RuntimeException("接続に失敗しました"));

        List<BulkOperationLog> results = service.uploadImageToAllEnvironments(
                1L, originalData, "cat.png", "image/png", 9L);

        assertEquals(2, results.size());
        assertEquals("local", results.get(0).getEnvironment());
        assertEquals(BulkOperationStatus.SUCCESS, results.get(0).getStatus());
        assertEquals("https://local.test/cat.png", results.get(0).getValue());
        assertEquals("test", results.get(1).getEnvironment());
        assertEquals(BulkOperationStatus.FAILED, results.get(1).getStatus());
        assertEquals("接続に失敗しました", results.get(1).getErrorMessage());
        verify(adapter, never()).uploadMedia(any(), any(), any(), eq(originalData));
    }

    @Test
    void uploadImageToAllEnvironments_JPEG変換された場合はファイル名拡張子とContentTypeもjpgに揃える() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));

        com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials localCreds =
                new com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials("https://local.test", "admin", "SSH");
        when(siteService.getCredentials("local-site")).thenReturn(localCreds);

        byte[] originalData = new byte[]{1, 2, 3};
        byte[] resizedData = new byte[]{9, 9, 9};
        when(legacyApiBridgeClient.resolveArticleImageLongEdgePx(1L)).thenReturn(1300);
        when(imageResizeService.resizeToLongEdge(originalData, "image/png", 1300, true))
                .thenReturn(new ImageResizeService.ResizeResult(resizedData, "image/jpeg"));

        com.letsblog.publishing.cms.CmsAdapter adapter = org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(com.letsblog.publishing.cms.CmsType.WORDPRESS)).thenReturn(adapter);
        when(adapter.uploadMedia(eq(localCreds), eq("comfyui-5.jpg"), eq("image/jpeg"), eq(resizedData)))
                .thenReturn(new com.letsblog.publishing.cms.MediaUploadResult("1", "https://local.test/comfyui-5.jpg"));

        List<BulkOperationLog> results = service.uploadImageToAllEnvironments(
                1L, originalData, "comfyui-5.png", "image/png", 9L);

        assertEquals(1, results.size());
        assertEquals(BulkOperationStatus.SUCCESS, results.get(0).getStatus());
        assertEquals("https://local.test/comfyui-5.jpg", results.get(0).getValue());
        verify(adapter).uploadMedia(eq(localCreds), eq("comfyui-5.jpg"), eq("image/jpeg"), eq(resizedData));
    }

    @Test
    void uploadImageToAllEnvironments_サイト未紐付けの環境はスキップされる() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getCredentials("local-site")).thenReturn(
                new com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials("https://local.test", "admin", "SSH"));
        when(legacyApiBridgeClient.resolveArticleImageLongEdgePx(1L)).thenReturn(1300);
        when(imageResizeService.resizeToLongEdge(any(), any(), eq(1300), eq(true)))
                .thenReturn(new ImageResizeService.ResizeResult(new byte[]{1}, "image/png"));
        com.letsblog.publishing.cms.CmsAdapter adapter = org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(any())).thenReturn(adapter);
        when(adapter.uploadMedia(any(), any(), any(), any()))
                .thenReturn(new com.letsblog.publishing.cms.MediaUploadResult("1", "https://local.test/cat.png"));

        List<BulkOperationLog> results = service.uploadImageToAllEnvironments(
                1L, new byte[]{1}, "cat.png", "image/png", 9L);

        assertEquals(1, results.size());
        assertEquals("local", results.get(0).getEnvironment());
    }

    // ---- deletePostAtEnvironment / updatePostStatusAtEnvironment ----

    @Test
    void deletePostAtEnvironment_成功時はCmsAdapter経由で削除しSUCCESSを記録する() {
        BulkManagementService service = service();
        Site site = buildManagedSite(10L, "local-site");
        com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials creds =
                new com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials("https://local.test", "admin", "SSH");
        when(siteService.getCredentials("local-site")).thenReturn(creds);
        com.letsblog.publishing.cms.CmsAdapter adapter = org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(adapter);

        BulkOperationLog result = service.deletePostAtEnvironment(1L, "local", site, "101", "post", "hello", 9L);

        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        assertEquals(BulkOperationType.POST_DELETE, result.getOperationType());
        assertEquals("hello", result.getValue());
        verify(adapter).deletePost(creds, "101", "post");
    }

    @Test
    void deletePostAtEnvironment_失敗時はFAILEDを記録し例外を投げない() {
        BulkManagementService service = service();
        Site site = buildManagedSite(10L, "local-site");
        when(siteService.getCredentials("local-site")).thenReturn(
                new com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials("https://local.test", "admin", "SSH"));
        com.letsblog.publishing.cms.CmsAdapter adapter = org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(adapter);
        org.mockito.Mockito.doThrow(new RuntimeException("削除に失敗しました"))
                .when(adapter).deletePost(any(), any(), any());

        BulkOperationLog result = service.deletePostAtEnvironment(1L, "local", site, "101", "post", "hello", 9L);

        assertEquals(BulkOperationStatus.FAILED, result.getStatus());
        assertEquals("削除に失敗しました", result.getErrorMessage());
    }

    @Test
    void updatePostStatusAtEnvironment_成功時はステータスをpost_statusへ記録する() {
        BulkManagementService service = service();
        Site site = buildManagedSite(10L, "local-site");
        com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials creds =
                new com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials("https://local.test", "admin", "SSH");
        when(siteService.getCredentials("local-site")).thenReturn(creds);
        com.letsblog.publishing.cms.CmsAdapter adapter = org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(adapter);

        BulkOperationLog result = service.updatePostStatusAtEnvironment(
                1L, "local", site, "101", "post", "hello", "publish", 9L);

        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        assertEquals(BulkOperationType.POST_STATUS_UPDATE, result.getOperationType());
        assertEquals("publish", result.getPostStatus());
        verify(adapter).updatePostStatus(creds, "101", "post", "publish");
    }

    // ---- 非managedサイト: SSH経由での適用 ----

    private Site buildExternalSite(Long id, String slug) {
        Site site = new Site();
        site.setId(id);
        site.setSiteKey(slug);
        site.setCmsType(CmsType.WORDPRESS);
        site.setManagedWordpress(false);
        return site;
    }

    private com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials sshCreds() {
        return new com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials(
                "https://example.com", null, "SSH", "203.0.113.5", 22, "deploy",
                "/var/www/html", "PEM", null, null);
    }

    @Test
    void applyToEnvironment_非managedサイトはSSH経由で適用する() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site externalSite = buildExternalSite(10L, "external-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(externalSite));
        when(siteService.resolveDataSource(externalSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCreds()));
        when(sshOperations.applyPluginTheme(sshCreds(), BulkOperationType.PLUGIN_ACTIVATE, "akismet"))
                .thenReturn(com.letsblog.publishing.cms.ssh.WordPressSshOperations.SshApplyResult.success());

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.PLUGIN_ACTIVATE, "akismet", null, null, null, null, 9L);

        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        verify(sshOperations).applyPluginTheme(sshCreds(), BulkOperationType.PLUGIN_ACTIVATE, "akismet");
    }

    @Test
    void applyToEnvironment_非managedサイトのカテゴリ削除もSSH経由で適用する() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site externalSite = buildExternalSite(10L, "external-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(externalSite));
        when(siteService.resolveDataSource(externalSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCreds()));
        when(sshOperations.applyTerm(eq(sshCreds()), eq(BulkOperationType.CATEGORY_DELETE), eq("oshirase"),
                any(), any(), any(), eq("oshirase")))
                .thenReturn(com.letsblog.publishing.cms.ssh.WordPressSshOperations.SshApplyResult.success());

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_DELETE, null, null, null, null, "oshirase", 9L);

        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        verify(sshOperations).applyTerm(eq(sshCreds()), eq(BulkOperationType.CATEGORY_DELETE), eq("oshirase"),
                any(), any(), any(), eq("oshirase"));
    }

    @Test
    void applyToEnvironment_SSHが失敗したらFAILEDを記録する() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site externalSite = buildExternalSite(10L, "external-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(externalSite));
        when(siteService.resolveDataSource(externalSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCreds()));
        when(sshOperations.applyPluginTheme(sshCreds(), BulkOperationType.THEME_ACTIVATE, "twentytwentyfour"))
                .thenReturn(com.letsblog.publishing.cms.ssh.WordPressSshOperations.SshApplyResult.failed(new RuntimeException("SSH接続に失敗しました")));

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.THEME_ACTIVATE, "twentytwentyfour", null, null, null, null, 9L);

        assertEquals(BulkOperationStatus.FAILED, result.getStatus());
    }

    @Test
    void logFetchFailure_例外を投げずに完了する() {
        BulkManagementService service = service();

        assertDoesNotThrow(() -> service.logFetchFailure(1L, BulkOperationType.CATEGORY_FETCH, "test",
                "Connection refused", "java.io.IOException: Connection refused\n\tat ..."));
    }
}
