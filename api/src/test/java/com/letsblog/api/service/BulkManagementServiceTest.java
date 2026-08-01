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

    private BulkManagementService service() {
        return new BulkManagementService(
                projectRepository, siteRepository, bulkOperationLogRepository, bulkManagementClient,
                bulkUploadStorageService);
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

    // ---- CATEGORY_CREATE ----

    @Test
    void execute_categoryCreate_スラッグ未指定は例外() {
        BulkManagementService service = service();

        assertThrows(IllegalArgumentException.class,
                () -> service.execute(1L, BulkOperationType.CATEGORY_CREATE, "お知らせ", null, null, null, null, 9L));
    }

    @Test
    void execute_categoryCreate_managed環境が1つもなければ何も実行せず空リストを返す() {
        BulkManagementService service = service();
        Project project = buildProject(null, null, null);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        List<BulkOperationLog> results = service.execute(
                1L, BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null, 9L);

        assertTrue(results.isEmpty());
        verify(bulkManagementClient, never()).apply(any());
    }

    @Test
    void execute_categoryCreate_managed環境のみ対象にしてログを保存する() {
        BulkManagementService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildManagedSite(10L, "local-site");
        Site externalSite = new Site();
        externalSite.setId(20L);
        externalSite.setSiteKey("external-site");
        externalSite.setCmsType(CmsType.WORDPRESS);
        externalSite.setManagedWordpress(false);

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(externalSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "category_create", "お知らせ", "oshirase", null, null, null)))
                .thenReturn(BulkApplyResult.success());
        stubSave();

        List<BulkOperationLog> results = service.execute(
                1L, BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, null, null, 9L);

        assertEquals(1, results.size());
        assertEquals("local", results.get(0).getEnvironment());
        assertEquals(BulkOperationStatus.SUCCESS, results.get(0).getStatus());
        assertEquals(BulkOperationSourceType.SLUG, results.get(0).getSourceType());
        assertEquals("oshirase", results.get(0).getCategorySlug());
        verify(bulkManagementClient, never()).apply(eq(new BulkApplyCommand(
                "external-site", "category_create", "お知らせ", "oshirase", null, null, null)));
    }

    @Test
    void execute_categoryCreate_親カテゴリと説明を渡せる() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "category_create", "サブお知らせ", "sub-oshirase", "oshirase", "説明文", null)))
                .thenReturn(BulkApplyResult.success());
        stubSave();

        List<BulkOperationLog> results = service.execute(
                1L, BulkOperationType.CATEGORY_CREATE, "サブお知らせ", "sub-oshirase", "oshirase", "説明文", null, 9L);

        assertEquals("sub-oshirase", results.get(0).getCategorySlug());
        assertEquals("oshirase", results.get(0).getCategoryParentSlug());
        assertEquals("説明文", results.get(0).getCategoryDescription());
    }

    @Test
    void execute_1環境が失敗しても他環境の実行は続行される() {
        BulkManagementService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");

        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "plugin_install", "akismet", null, null, null, null)))
                .thenReturn(BulkApplyResult.failed("接続に失敗しました"));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "test-site", "plugin_install", "akismet", null, null, null, null)))
                .thenReturn(BulkApplyResult.success());
        stubSave();

        List<BulkOperationLog> results = service.execute(
                1L, BulkOperationType.PLUGIN_INSTALL, "akismet", null, null, null, null, 9L);

        assertEquals(2, results.size());
        assertEquals(BulkOperationStatus.FAILED, results.get(0).getStatus());
        assertEquals("接続に失敗しました", results.get(0).getErrorMessage());
        assertEquals(BulkOperationStatus.SUCCESS, results.get(1).getStatus());
    }

    @Test
    void execute_SKIPPEDもそのまま記録される() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "theme_install", "twentytwentyfour", null, null, null, null)))
                .thenReturn(BulkApplyResult.skipped());
        stubSave();

        List<BulkOperationLog> results = service.execute(
                1L, BulkOperationType.THEME_INSTALL, "twentytwentyfour", null, null, null, null, 9L);

        assertEquals(BulkOperationStatus.SKIPPED, results.get(0).getStatus());
    }

    @Test
    void execute_プラグイン系はslug未指定で例外() {
        BulkManagementService service = service();

        assertThrows(IllegalArgumentException.class,
                () -> service.execute(1L, BulkOperationType.PLUGIN_ACTIVATE, "", null, null, null, null, 9L));
    }

    @Test
    void execute_プラグイン有効化_無効化_削除がそれぞれのactionで呼ばれる() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(any())).thenReturn(BulkApplyResult.success());
        stubSave();

        service.execute(1L, BulkOperationType.PLUGIN_ACTIVATE, "akismet", null, null, null, null, 9L);
        service.execute(1L, BulkOperationType.PLUGIN_DEACTIVATE, "akismet", null, null, null, null, 9L);
        service.execute(1L, BulkOperationType.PLUGIN_DELETE, "akismet", null, null, null, null, 9L);

        verify(bulkManagementClient).apply(new BulkApplyCommand(
                "local-site", "plugin_activate", "akismet", null, null, null, null));
        verify(bulkManagementClient).apply(new BulkApplyCommand(
                "local-site", "plugin_deactivate", "akismet", null, null, null, null));
        verify(bulkManagementClient).apply(new BulkApplyCommand(
                "local-site", "plugin_delete", "akismet", null, null, null, null));
    }

    @Test
    void execute_テーマ有効化_削除がそれぞれのactionで呼ばれる() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(any())).thenReturn(BulkApplyResult.success());
        stubSave();

        service.execute(1L, BulkOperationType.THEME_ACTIVATE, "twentytwentyfour", null, null, null, null, 9L);
        service.execute(1L, BulkOperationType.THEME_DELETE, "twentytwentyfour", null, null, null, null, 9L);

        verify(bulkManagementClient).apply(new BulkApplyCommand(
                "local-site", "theme_activate", "twentytwentyfour", null, null, null, null));
        verify(bulkManagementClient).apply(new BulkApplyCommand(
                "local-site", "theme_delete", "twentytwentyfour", null, null, null, null));
    }

    // ---- CATEGORY_EDIT / CATEGORY_DELETE ----

    @Test
    void execute_categoryEdit_targetSlug未指定は例外() {
        BulkManagementService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.execute(
                1L, BulkOperationType.CATEGORY_EDIT, "新名前", "new-slug", null, null, null, 9L));
    }

    @Test
    void execute_categoryEdit_正しいコマンドで呼ばれる() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "category_edit", "新お知らせ", "new-oshirase", "parent-slug", "更新後の説明", "old-oshirase")))
                .thenReturn(BulkApplyResult.success());
        stubSave();

        List<BulkOperationLog> results = service.execute(
                1L, BulkOperationType.CATEGORY_EDIT, "新お知らせ", "new-oshirase", "parent-slug", "更新後の説明",
                "old-oshirase", 9L);

        assertEquals(BulkOperationStatus.SUCCESS, results.get(0).getStatus());
        assertEquals("old-oshirase", results.get(0).getCategoryTargetSlug());
    }

    @Test
    void execute_categoryDelete_targetSlug未指定は例外() {
        BulkManagementService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.execute(
                1L, BulkOperationType.CATEGORY_DELETE, null, null, null, null, null, 9L));
    }

    @Test
    void execute_categoryDelete_valueが空でもtargetSlugで補われる() {
        BulkManagementService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.apply(new BulkApplyCommand(
                "local-site", "category_delete", "oshirase", null, null, null, "oshirase")))
                .thenReturn(BulkApplyResult.success());
        stubSave();

        List<BulkOperationLog> results = service.execute(
                1L, BulkOperationType.CATEGORY_DELETE, null, null, null, null, "oshirase", 9L);

        assertEquals("oshirase", results.get(0).getValue());
        assertEquals("oshirase", results.get(0).getCategoryTargetSlug());
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

    @Test
    void listLogs_リポジトリの結果をそのまま返す() {
        BulkManagementService service = service();
        BulkOperationLog log = buildHistoryLog(BulkOperationType.CATEGORY_CREATE, BulkOperationSourceType.SLUG, "お知らせ");
        when(bulkOperationLogRepository.findByProjectIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(log));

        List<BulkOperationLog> results = service.listLogs(1L);

        assertEquals(1, results.size());
    }

    // ---- listReferenceCategories ----

    @Test
    void listReferenceCategories_managed環境が1つもなければ空リスト() {
        BulkManagementService service = service();
        Project project = buildProject(null, null, null);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));

        List<WordPressBulkManagementClient.CategoryInfo> results = service.listReferenceCategories(1L);

        assertTrue(results.isEmpty());
        verify(bulkManagementClient, never()).listCategories(any());
    }

    @Test
    void listReferenceCategories_local優先でクライアントを呼ぶ() {
        BulkManagementService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));
        when(bulkManagementClient.listCategories("local-site"))
                .thenReturn(List.of(new WordPressBulkManagementClient.CategoryInfo("お知らせ", "oshirase", null, null)));

        List<WordPressBulkManagementClient.CategoryInfo> results = service.listReferenceCategories(1L);

        assertEquals(1, results.size());
        assertEquals("oshirase", results.get(0).slug());
        verify(bulkManagementClient, never()).listCategories("test-site");
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
