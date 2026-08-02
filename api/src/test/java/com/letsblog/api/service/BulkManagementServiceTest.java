package com.letsblog.api.service;

import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.BulkOperationLog;
import com.letsblog.api.domain.BulkOperationSourceType;
import com.letsblog.api.domain.BulkOperationStatus;
import com.letsblog.api.domain.BulkOperationType;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.provisioning.WordPressBulkManagementClient;
import com.letsblog.api.provisioning.WordPressBulkManagementClient.BulkApplyCommand;
import com.letsblog.api.provisioning.WordPressBulkManagementClient.BulkApplyResult;
import com.letsblog.api.repository.BulkOperationLogRepository;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.SiteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BulkManagementServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private BulkOperationLogRepository bulkOperationLogRepository;

    @Mock
    private WordPressBulkManagementClient bulkManagementClient;

    @Mock
    private BulkUploadStorageService bulkUploadStorageService;

    @Mock
    private SiteService siteService;

    @Mock
    private com.letsblog.api.cms.ssh.WordPressSshOperations sshOperations;

    @Mock
    private com.letsblog.api.cms.rest.WordPressRestBulkManagementOperations restOperations;

    private BulkManagementService service() {
        return new BulkManagementService(
                projectRepository, siteRepository, bulkOperationLogRepository, bulkManagementClient,
                bulkUploadStorageService, siteService, sshOperations, restOperations);
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
        site.setWpDbName("wp_" + slug);
        return site;
    }

    private void stubSave() {
        when(bulkOperationLogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
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
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

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
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(externalSite));
        when(siteService.resolveDataSource(externalSite)).thenReturn(new SiteService.SiteDataSource(false, null, null));

        assertThrows(IllegalArgumentException.class, () -> service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null, 9L));
    }

    @Test
    void applyToEnvironment_categoryCreate_指定した環境のみへ適用してログを保存する() {
        BulkManagementService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "category_create", "お知らせ", "oshirase", null, null, null)))
                .thenReturn(BulkApplyResult.success());
        stubSave();

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null, 9L);

        assertEquals("local", result.getEnvironment());
        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        assertEquals(BulkOperationSourceType.SLUG, result.getSourceType());
        assertEquals("oshirase", result.getCategorySlug());
        verify(siteRepository, never()).findById(20L);
    }

    @Test
    void applyToEnvironment_categoryCreate_親カテゴリと説明を渡せる() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "category_create", "サブお知らせ", "sub-oshirase", "oshirase", "説明文", null)))
                .thenReturn(BulkApplyResult.success());
        stubSave();

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
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "plugin_install", "akismet", null, null, null, null)))
                .thenReturn(BulkApplyResult.failed(new RuntimeException("接続に失敗しました")));
        stubSave();

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
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "theme_install", "twentytwentyfour", null, null, null, null)))
                .thenReturn(BulkApplyResult.skipped());
        stubSave();

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
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(any())).thenReturn(BulkApplyResult.success());
        stubSave();

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
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(any())).thenReturn(BulkApplyResult.success());
        stubSave();

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
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "category_edit", "新お知らせ", "new-oshirase", "parent-slug", "更新後の説明", "old-oshirase")))
                .thenReturn(BulkApplyResult.success());
        stubSave();

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
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "category_delete", "oshirase", null, null, null, "oshirase")))
                .thenReturn(BulkApplyResult.success());
        stubSave();

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
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "tag_create", "新着", "shinchaku", null, null, null)))
                .thenReturn(BulkApplyResult.success());
        stubSave();

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.TAG_CREATE, "新着", "shinchaku", null, null, null, 9L);

        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        assertEquals("shinchaku", result.getCategorySlug());
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

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));
        when(bulkUploadStorageService.store(eq(1L), any(byte[].class), eq("custom-theme.zip")))
                .thenReturn(new BulkUploadStorageService.StoredZip("1/abc.zip", "abc", "custom-theme.zip"));
        when(bulkManagementClient.applyZip(any(), eq("theme_install"), any(byte[].class), eq("custom-theme.zip")))
                .thenReturn(BulkApplyResult.success());
        stubSave();

        List<BulkOperationLog> results = service.executeFromUpload(1L, BulkOperationType.THEME_INSTALL, file, 9L);

        assertEquals(2, results.size());
        assertEquals(BulkOperationSourceType.ZIP, results.get(0).getSourceType());
        assertEquals("custom-theme.zip", results.get(0).getOriginalFilename());
        assertEquals("1/abc.zip", results.get(0).getStoragePath());
        verify(bulkManagementClient).applyZip(eq("local-site"), eq("theme_install"), any(byte[].class), eq("custom-theme.zip"));
        verify(bulkManagementClient).applyZip(eq("test-site"), eq("theme_install"), any(byte[].class), eq("custom-theme.zip"));
    }

    // ---- replay ----

    @Test
    void replay_成功ログのみを古い順に対象環境へ再適用する() {
        BulkManagementService service = service();
        Project project = buildProject(10L, 20L, null);
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));

        BulkOperationLog log1 = buildHistoryLog(BulkOperationType.CATEGORY_CREATE, BulkOperationSourceType.SLUG, "お知らせ");
        log1.setCategorySlug("oshirase");
        when(bulkOperationLogRepository.findByProjectIdAndStatusOrderByCreatedAtAsc(1L, BulkOperationStatus.SUCCESS))
                .thenReturn(List.of(log1));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "test-site", "category_create", "お知らせ", "oshirase", null, null, null)))
                .thenReturn(BulkApplyResult.success());
        stubSave();

        List<BulkOperationLog> results = service.replay(1L, "test", 9L);

        assertEquals(1, results.size());
        assertEquals("test", results.get(0).getEnvironment());
        assertTrue(results.get(0).isReplay());
        verify(bulkManagementClient).apply(new BulkApplyCommand(
                "test-site", "category_create", "お知らせ", "oshirase", null, null, null));
    }

    @Test
    void replay_カテゴリの親_対象_説明も再適用時に引き継がれる() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));

        BulkOperationLog log1 = buildHistoryLog(BulkOperationType.CATEGORY_EDIT, BulkOperationSourceType.SLUG, "新お知らせ");
        log1.setCategorySlug("new-oshirase");
        log1.setCategoryParentSlug("parent-slug");
        log1.setCategoryTargetSlug("old-oshirase");
        log1.setCategoryDescription("説明文");
        when(bulkOperationLogRepository.findByProjectIdAndStatusOrderByCreatedAtAsc(1L, BulkOperationStatus.SUCCESS))
                .thenReturn(List.of(log1));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "category_edit", "新お知らせ", "new-oshirase", "parent-slug", "説明文", "old-oshirase")))
                .thenReturn(BulkApplyResult.success());
        stubSave();

        List<BulkOperationLog> results = service.replay(1L, "local", 9L);

        assertEquals("new-oshirase", results.get(0).getCategorySlug());
        verify(bulkManagementClient).apply(new BulkApplyCommand(
                "local-site", "category_edit", "新お知らせ", "new-oshirase", "parent-slug", "説明文", "old-oshirase"));
    }

    @Test
    void replay_ZIP方式は保存済みファイルを読み出して再適用する() throws IOException {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));

        BulkOperationLog log1 = buildHistoryLog(BulkOperationType.PLUGIN_INSTALL, BulkOperationSourceType.ZIP, "custom.zip");
        log1.setStoragePath("1/abc.zip");
        log1.setOriginalFilename("custom.zip");
        when(bulkOperationLogRepository.findByProjectIdAndStatusOrderByCreatedAtAsc(1L, BulkOperationStatus.SUCCESS))
                .thenReturn(List.of(log1));
        byte[] content = new byte[]{9, 9, 9};
        when(bulkUploadStorageService.load("1/abc.zip")).thenReturn(content);
        when(bulkManagementClient.applyZip("local-site", "plugin_install", content, "custom.zip"))
                .thenReturn(BulkApplyResult.success());
        stubSave();

        List<BulkOperationLog> results = service.replay(1L, "local", 9L);

        assertEquals(BulkOperationStatus.SUCCESS, results.get(0).getStatus());
        verify(bulkManagementClient).applyZip("local-site", "plugin_install", content, "custom.zip");
    }

    @Test
    void replay_保存済みzipが見つからなければFAILEDとして記録し処理を続行する() throws IOException {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));

        BulkOperationLog log1 = buildHistoryLog(BulkOperationType.PLUGIN_INSTALL, BulkOperationSourceType.ZIP, "custom.zip");
        log1.setStoragePath("1/missing.zip");
        log1.setOriginalFilename("custom.zip");
        BulkOperationLog log2 = buildHistoryLog(BulkOperationType.CATEGORY_CREATE, BulkOperationSourceType.SLUG, "お知らせ");
        log2.setCategorySlug("oshirase");
        when(bulkOperationLogRepository.findByProjectIdAndStatusOrderByCreatedAtAsc(1L, BulkOperationStatus.SUCCESS))
                .thenReturn(List.of(log1, log2));
        when(bulkUploadStorageService.load("1/missing.zip")).thenThrow(new IOException("not found"));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "category_create", "お知らせ", "oshirase", null, null, null)))
                .thenReturn(BulkApplyResult.success());
        stubSave();

        List<BulkOperationLog> results = service.replay(1L, "local", 9L);

        assertEquals(2, results.size());
        assertEquals(BulkOperationStatus.FAILED, results.get(0).getStatus());
        assertEquals(BulkOperationStatus.SUCCESS, results.get(1).getStatus());
        verify(bulkManagementClient, never()).applyZip(any(), any(), any(), any());
    }

    @Test
    void replay_不正なenvironmentは例外() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertThrows(IllegalArgumentException.class, () -> service.replay(1L, "invalid", 9L));
    }

    @Test
    void replay_環境にサイトが紐付いていなければ例外() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        assertThrows(IllegalArgumentException.class, () -> service.replay(1L, "test", 9L));
    }

    // ---- 非managedサイト: REST/SSHの優先順位 ----

    private Site buildExternalSite(Long id, String slug) {
        Site site = new Site();
        site.setId(id);
        site.setSiteKey(slug);
        site.setCmsType(CmsType.WORDPRESS);
        site.setManagedWordpress(false);
        return site;
    }

    private com.letsblog.api.cms.CmsCredentials.WordPressCredentials restCreds() {
        return new com.letsblog.api.cms.CmsCredentials.WordPressCredentials(
                "https://example.com", "admin", "app-pass", "REST", null, null, null, null, null, null, null);
    }

    private com.letsblog.api.cms.CmsCredentials.WordPressCredentials sshCreds() {
        return new com.letsblog.api.cms.CmsCredentials.WordPressCredentials(
                "https://example.com", null, null, "SSH", "203.0.113.5", 22, "deploy",
                "/var/www/html", "PEM", null, null);
    }

    @Test
    void applyToEnvironment_RESTのみが使える非managedサイトはREST経由で適用する() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site externalSite = buildExternalSite(10L, "external-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(externalSite));
        when(siteService.resolveDataSource(externalSite))
                .thenReturn(new SiteService.SiteDataSource(false, restCreds(), null));
        when(restOperations.applyPlugin(restCreds(), BulkOperationType.PLUGIN_ACTIVATE, "akismet"))
                .thenReturn(com.letsblog.api.cms.ssh.WordPressSshOperations.SshApplyResult.success());
        stubSave();

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.PLUGIN_ACTIVATE, "akismet", null, null, null, null, 9L);

        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        verify(restOperations).applyPlugin(restCreds(), BulkOperationType.PLUGIN_ACTIVATE, "akismet");
        verify(sshOperations, never()).applyPluginTheme(any(), any(), any());
    }

    @Test
    void applyToEnvironment_RESTとSSH両方使える非managedサイトはSSHを優先する() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site externalSite = buildExternalSite(10L, "external-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(externalSite));
        when(siteService.resolveDataSource(externalSite))
                .thenReturn(new SiteService.SiteDataSource(false, restCreds(), sshCreds()));
        when(sshOperations.applyPluginTheme(sshCreds(), BulkOperationType.PLUGIN_ACTIVATE, "akismet"))
                .thenReturn(com.letsblog.api.cms.ssh.WordPressSshOperations.SshApplyResult.success());
        stubSave();

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.PLUGIN_ACTIVATE, "akismet", null, null, null, null, 9L);

        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        verify(sshOperations).applyPluginTheme(sshCreds(), BulkOperationType.PLUGIN_ACTIVATE, "akismet");
        verify(restOperations, never()).applyPlugin(any(), any(), any());
    }

    @Test
    void applyToEnvironment_RESTとSSH両方使える非managedサイトでSSHが失敗したらRESTにフォールバックする() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site externalSite = buildExternalSite(10L, "external-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(externalSite));
        when(siteService.resolveDataSource(externalSite))
                .thenReturn(new SiteService.SiteDataSource(false, restCreds(), sshCreds()));
        when(sshOperations.applyTerm(eq(sshCreds()), eq(BulkOperationType.CATEGORY_DELETE), eq("oshirase"),
                any(), any(), any(), eq("oshirase")))
                .thenReturn(com.letsblog.api.cms.ssh.WordPressSshOperations.SshApplyResult.failed(new RuntimeException("SSH接続に失敗しました")));
        when(restOperations.applyTerm(eq(restCreds()), eq(BulkOperationType.CATEGORY_DELETE), eq("oshirase"),
                any(), any(), any(), eq("oshirase")))
                .thenReturn(com.letsblog.api.cms.ssh.WordPressSshOperations.SshApplyResult.success());
        stubSave();

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_DELETE, null, null, null, null, "oshirase", 9L);

        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        verify(sshOperations).applyTerm(eq(sshCreds()), eq(BulkOperationType.CATEGORY_DELETE), eq("oshirase"),
                any(), any(), any(), eq("oshirase"));
        verify(restOperations).applyTerm(eq(restCreds()), eq(BulkOperationType.CATEGORY_DELETE), eq("oshirase"),
                any(), any(), any(), eq("oshirase"));
    }

    @Test
    void applyToEnvironment_テーマ書き込みでSSHが失敗してもRESTにフォールバックしない() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site externalSite = buildExternalSite(10L, "external-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(externalSite));
        when(siteService.resolveDataSource(externalSite))
                .thenReturn(new SiteService.SiteDataSource(false, restCreds(), sshCreds()));
        when(sshOperations.applyPluginTheme(sshCreds(), BulkOperationType.THEME_ACTIVATE, "twentytwentyfour"))
                .thenReturn(com.letsblog.api.cms.ssh.WordPressSshOperations.SshApplyResult.failed(new RuntimeException("SSH接続に失敗しました")));
        stubSave();

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.THEME_ACTIVATE, "twentytwentyfour", null, null, null, null, 9L);

        assertEquals(BulkOperationStatus.FAILED, result.getStatus());
        verify(restOperations, never()).applyPlugin(any(), any(), any());
    }

    @Test
    void applyToEnvironment_RESTが無くSSHのみの非managedサイトはSSH経由で適用する() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site externalSite = buildExternalSite(10L, "external-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(externalSite));
        when(siteService.resolveDataSource(externalSite))
                .thenReturn(new SiteService.SiteDataSource(false, null, sshCreds()));
        when(sshOperations.applyPluginTheme(sshCreds(), BulkOperationType.PLUGIN_ACTIVATE, "akismet"))
                .thenReturn(com.letsblog.api.cms.ssh.WordPressSshOperations.SshApplyResult.success());
        stubSave();

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.PLUGIN_ACTIVATE, "akismet", null, null, null, null, 9L);

        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        verify(sshOperations).applyPluginTheme(sshCreds(), BulkOperationType.PLUGIN_ACTIVATE, "akismet");
        verify(restOperations, never()).applyPlugin(any(), any(), any());
    }

    @Test
    void applyToEnvironment_テーマ書き込みはRESTがあってもSSHを使う() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site externalSite = buildExternalSite(10L, "external-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(externalSite));
        when(siteService.resolveDataSource(externalSite))
                .thenReturn(new SiteService.SiteDataSource(false, restCreds(), sshCreds()));
        when(sshOperations.applyPluginTheme(sshCreds(), BulkOperationType.THEME_ACTIVATE, "twentytwentyfour"))
                .thenReturn(com.letsblog.api.cms.ssh.WordPressSshOperations.SshApplyResult.success());
        stubSave();

        BulkOperationLog result = service.applyToEnvironment(
                1L, "local", BulkOperationType.THEME_ACTIVATE, "twentytwentyfour", null, null, null, null, 9L);

        assertEquals(BulkOperationStatus.SUCCESS, result.getStatus());
        verify(sshOperations).applyPluginTheme(sshCreds(), BulkOperationType.THEME_ACTIVATE, "twentytwentyfour");
        verify(restOperations, never()).applyPlugin(any(), any(), any());
    }

    @Test
    void applyToEnvironment_テーマ書き込みでRESTのみでSSHが無ければ例外() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site externalSite = buildExternalSite(10L, "external-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(externalSite));
        when(siteService.resolveDataSource(externalSite))
                .thenReturn(new SiteService.SiteDataSource(false, restCreds(), null));

        assertThrows(IllegalArgumentException.class, () -> service.applyToEnvironment(
                1L, "local", BulkOperationType.THEME_ACTIVATE, "twentytwentyfour", null, null, null, null, 9L));
    }

    @Test
    void logFetchFailure_FAILEDステータスでログを保存する() {
        BulkManagementService service = service();
        stubSave();

        service.logFetchFailure(1L, BulkOperationType.CATEGORY_FETCH, "test", "Connection refused", "java.io.IOException: Connection refused\n\tat ...");

        org.mockito.ArgumentCaptor<BulkOperationLog> captor = org.mockito.ArgumentCaptor.forClass(BulkOperationLog.class);
        verify(bulkOperationLogRepository).save(captor.capture());
        BulkOperationLog saved = captor.getValue();
        assertEquals(BulkOperationType.CATEGORY_FETCH, saved.getOperationType());
        assertEquals(BulkOperationStatus.FAILED, saved.getStatus());
        assertEquals("test", saved.getEnvironment());
        assertEquals("Connection refused", saved.getErrorMessage());
        assertEquals("java.io.IOException: Connection refused\n\tat ...", saved.getStackTrace());
    }

    @Test
    void listLogs_リポジトリの結果をそのまま返す() {
        BulkManagementService service = service();
        BulkOperationLog log = buildHistoryLog(BulkOperationType.CATEGORY_CREATE, BulkOperationSourceType.SLUG, "お知らせ");
        when(bulkOperationLogRepository.findByProjectIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(log));

        List<BulkOperationLog> results = service.listLogs(1L);

        assertEquals(1, results.size());
    }

    private BulkOperationLog buildHistoryLog(BulkOperationType type, BulkOperationSourceType sourceType, String value) {
        BulkOperationLog log = new BulkOperationLog();
        log.setProjectId(1L);
        log.setOperationType(type);
        log.setSourceType(sourceType);
        log.setValue(value);
        log.setEnvironment("local");
        log.setStatus(BulkOperationStatus.SUCCESS);
        log.setCreatedAt(LocalDateTime.now());
        return log;
    }
}
