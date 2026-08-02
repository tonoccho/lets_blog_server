package com.letsblog.api.service;

import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.BulkOperationLog;
import com.letsblog.api.domain.BulkOperationType;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.TermComparisonPage;
import com.letsblog.api.provisioning.WordPressBulkManagementClient;
import com.letsblog.api.provisioning.WordPressBulkManagementClient.CategoryInfo;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.SiteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TermComparisonServiceTest {

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private WordPressBulkManagementClient bulkManagementClient;

    @Mock
    private BulkManagementService bulkManagementService;

    @Mock
    private SiteService siteService;

    @Mock
    private com.letsblog.api.cms.ssh.WordPressSshOperations sshOperations;

    @Mock
    private com.letsblog.api.cms.rest.WordPressRestBulkManagementOperations restOperations;

    private TermComparisonService service() {
        return new TermComparisonService(
                projectRepository, siteRepository, bulkManagementClient, bulkManagementService, siteService,
                sshOperations, restOperations);
    }

    private Project buildProject(Long localSiteId, Long testSiteId, Long productionSiteId, String masterEnvironment) {
        Project project = new Project();
        project.setId(1L);
        project.setName("テスト");
        project.setSlug("test");
        project.setLocalSiteId(localSiteId);
        project.setTestSiteId(testSiteId);
        project.setProductionSiteId(productionSiteId);
        project.setMasterEnvironment(masterEnvironment);
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

    // ---- listCategoryComparison ----

    @Test
    void listCategoryComparison_スラッグでマージし未紐付け環境はavailable_falseになる() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));
        when(bulkManagementClient.listCategories("local-site"))
                .thenReturn(List.of(new CategoryInfo("お知らせ(旧)", "oshirase", null, null)));
        when(bulkManagementClient.listCategories("test-site"))
                .thenReturn(List.of(new CategoryInfo("お知らせ", "oshirase", null, "説明")));

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        // 名前は環境間で異なっていても、スラッグが同じなら1行にまとまり、
        // 表示名はマスター環境(test)の値が採用される
        assertEquals("お知らせ", page.items().get(0).name());
        assertEquals("oshirase", page.items().get(0).slug());
        assertEquals("test", page.masterEnvironment());
        assertTrue(page.items().get(0).local().available());
        assertEquals("oshirase", page.items().get(0).local().slug());
        assertEquals("oshirase", page.items().get(0).test().slug());
        assertFalse(page.items().get(0).production().available());
    }

    @Test
    void listCategoryComparison_スラッグが異なれば名前が同じでも別行になる() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));
        when(bulkManagementClient.listCategories("local-site"))
                .thenReturn(List.of(new CategoryInfo("お知らせ", "oshirase-old", null, null)));
        when(bulkManagementClient.listCategories("test-site"))
                .thenReturn(List.of(new CategoryInfo("お知らせ", "oshirase", null, "説明")));

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(2, page.items().size());
    }

    @Test
    void listCategoryComparison_マスターに存在しない項目はmaster列がmissingになる() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));
        when(bulkManagementClient.listCategories("local-site"))
                .thenReturn(List.of(new CategoryInfo("ローカル限定", "local-only", null, null)));
        when(bulkManagementClient.listCategories("test-site")).thenReturn(List.of());

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertTrue(page.items().get(0).local().available());
        assertTrue(page.items().get(0).test().available());
        assertEquals(null, page.items().get(0).test().slug());
    }

    @Test
    void listCategoryComparison_20件単位でページングされる() {
        TermComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        List<CategoryInfo> categories = java.util.stream.IntStream.range(0, 25)
                .mapToObj(i -> new CategoryInfo(String.format("cat-%02d", i), "slug-" + i, null, null))
                .toList();
        when(bulkManagementClient.listCategories("local-site")).thenReturn(categories);

        TermComparisonPage firstPage = service.listCategoryComparison(1L, 0, 20);
        TermComparisonPage secondPage = service.listCategoryComparison(1L, 1, 20);

        assertEquals(20, firstPage.items().size());
        assertEquals(5, secondPage.items().size());
        assertEquals(25, firstPage.totalCount());
    }

    // ---- syncCategory ----

    @Test
    void syncCategory_マスターに存在しなければ例外() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));
        when(bulkManagementClient.listCategories(any())).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.syncCategory(1L, "oshirase", 9L));
    }

    @Test
    void syncCategory_非マスター環境に存在しなければ作成_存在すれば更新する() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, 30L, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        Site productionSite = buildManagedSite(30L, "production-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));
        when(siteRepository.findById(30L)).thenReturn(Optional.of(productionSite));

        CategoryInfo master = new CategoryInfo("お知らせ", "oshirase", null, "説明");
        when(bulkManagementClient.listCategories("test-site")).thenReturn(List.of(master));
        when(bulkManagementClient.listCategories("local-site")).thenReturn(List.of());
        // productionは同じスラッグ(名寄せキー)だが表示名・説明が異なる = 同一項目として編集対象になる
        when(bulkManagementClient.listCategories("production-site"))
                .thenReturn(List.of(new CategoryInfo("お知らせ(旧)", "oshirase", null, "旧説明")));
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        service.syncCategory(1L, "oshirase", 9L);

        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, "説明", null, 9L);
        verify(bulkManagementService).applyToEnvironment(
                1L, "production", BulkOperationType.CATEGORY_EDIT, "お知らせ", "oshirase", null, "説明", "oshirase", 9L);
        verify(bulkManagementService, never()).applyToEnvironment(
                eq(1L), eq("test"), any(), any(), any(), any(), any(), any(), eq(9L));
    }

    // ---- deleteCategoryEverywhere ----

    @Test
    void deleteCategoryEverywhere_存在する環境のみ削除される() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));
        when(bulkManagementClient.listCategories("local-site")).thenReturn(List.of());
        when(bulkManagementClient.listCategories("test-site"))
                .thenReturn(List.of(new CategoryInfo("お知らせ", "oshirase", null, null)));
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        List<BulkOperationLog> results = service.deleteCategoryEverywhere(1L, "oshirase", 9L);

        assertEquals(1, results.size());
        verify(bulkManagementService).applyToEnvironment(
                1L, "test", BulkOperationType.CATEGORY_DELETE, null, null, null, null, "oshirase", 9L);
        verify(bulkManagementService, never()).applyToEnvironment(
                eq(1L), eq("local"), any(), any(), any(), any(), any(), any(), eq(9L));
    }

    @Test
    void deleteCategoryEverywhere_どの環境にも存在しなければ例外() {
        TermComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listCategories("local-site")).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.deleteCategoryEverywhere(1L, "oshirase", 9L));
    }

    // ---- editCategoryAndSync ----

    @Test
    void editCategoryAndSync_マスターに存在しなければ例外() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));
        when(bulkManagementClient.listCategories(any())).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class,
                () -> service.editCategoryAndSync(1L, "oshirase", "新お知らせ", "new-oshirase", null, null, 9L));
    }

    @Test
    void editCategoryAndSync_マスターを新しい値で更新し他の環境にも新しい値を反映する() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, 30L, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        Site productionSite = buildManagedSite(30L, "production-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));
        when(siteRepository.findById(30L)).thenReturn(Optional.of(productionSite));

        CategoryInfo master = new CategoryInfo("お知らせ", "oshirase", null, "旧説明");
        when(bulkManagementClient.listCategories("test-site")).thenReturn(List.of(master));
        // localは同じスラッグ(名寄せキー)だが表示名は異なりうる = 同一項目として編集対象になる
        when(bulkManagementClient.listCategories("local-site"))
                .thenReturn(List.of(new CategoryInfo("お知らせ(旧)", "oshirase", null, null)));
        when(bulkManagementClient.listCategories("production-site")).thenReturn(List.of());
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        List<BulkOperationLog> results = service.editCategoryAndSync(
                1L, "oshirase", "新お知らせ", "new-oshirase", "parent-slug", "新説明", 9L);

        assertEquals(3, results.size());
        verify(bulkManagementService).applyToEnvironment(
                1L, "test", BulkOperationType.CATEGORY_EDIT, "新お知らせ", "new-oshirase", "parent-slug", "新説明",
                "oshirase", 9L);
        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_EDIT, "新お知らせ", "new-oshirase", "parent-slug", "新説明",
                "oshirase", 9L);
        verify(bulkManagementService).applyToEnvironment(
                1L, "production", BulkOperationType.CATEGORY_CREATE, "新お知らせ", "new-oshirase", "parent-slug",
                "新説明", null, 9L);
    }

    // ---- syncAllCategoriesToMaster ----

    @Test
    void syncAllCategoriesToMaster_マスターと異なる項目のみ同期し差分なしはスキップする() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));

        CategoryInfo masterDiff = new CategoryInfo("お知らせ", "oshirase", null, "説明");
        CategoryInfo masterSame = new CategoryInfo("イベント", "event", null, null);
        when(bulkManagementClient.listCategories("test-site")).thenReturn(List.of(masterDiff, masterSame));
        when(bulkManagementClient.listCategories("local-site")).thenReturn(List.of(
                new CategoryInfo("お知らせ(旧)", "oshirase", null, null),
                new CategoryInfo("イベント", "event", null, null)));
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        List<BulkOperationLog> results = service.syncAllCategoriesToMaster(1L, 9L);

        assertEquals(1, results.size());
        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_EDIT, "お知らせ", "oshirase", null, "説明", "oshirase", 9L);
    }

    @Test
    void syncAllCategoriesToMaster_差分がなければ空リストを返す() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));
        CategoryInfo same = new CategoryInfo("イベント", "event", null, null);
        when(bulkManagementClient.listCategories("test-site")).thenReturn(List.of(same));
        when(bulkManagementClient.listCategories("local-site")).thenReturn(List.of(same));

        List<BulkOperationLog> results = service.syncAllCategoriesToMaster(1L, 9L);

        assertTrue(results.isEmpty());
        verify(bulkManagementService, never()).applyToEnvironment(
                eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L));
    }

    // ---- タグ(カテゴリと同じロジックを共有していることの確認) ----

    @Test
    void syncTag_タグ用のBulkOperationTypeで呼ばれる() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));
        CategoryInfo master = new CategoryInfo("新着", "shinchaku", null, null);
        when(bulkManagementClient.listTags("test-site")).thenReturn(List.of(master));
        when(bulkManagementClient.listTags("local-site")).thenReturn(List.of());
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        service.syncTag(1L, "shinchaku", 9L);

        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.TAG_CREATE, "新着", "shinchaku", null, null, null, 9L);
    }

    // ---- 非managedサイト: REST優先・SSHホスト単位まとめ取得・エラー ----

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

    private com.letsblog.api.cms.CmsCredentials.WordPressCredentials sshCreds(String wpPath) {
        return new com.letsblog.api.cms.CmsCredentials.WordPressCredentials(
                "https://example.com", null, null, "SSH", "203.0.113.5", 22, "deploy", wpPath, "PEM", null, null);
    }

    @Test
    void listCategoryComparison_RESTが使える非managedサイトはRESTを優先して取得する() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "local");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildExternalSite(20L, "test-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));
        when(bulkManagementClient.listCategories("local-site")).thenReturn(List.of());
        when(siteService.resolveDataSource(testSite)).thenReturn(new SiteService.SiteDataSource(false, restCreds(), null));
        when(restOperations.listCategories(restCreds())).thenReturn(List.of(
                new com.letsblog.api.cms.rest.WordPressRestBulkManagementOperations.CategoryInfo(
                        "1", "News", "news", null, "")));

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertEquals(true, page.items().get(0).test().available());
        assertEquals("news", page.items().get(0).test().slug());
        verify(sshOperations, never()).fetchTermsForEnvironments(any(), any());
    }

    @Test
    void listCategoryComparison_同一ホストのSSH環境は1回のfetchTermsForEnvironmentsにまとめる() {
        TermComparisonService service = service();
        Project project = buildProject(null, 20L, 30L, "test");
        Site testSite = buildExternalSite(20L, "test-site");
        Site productionSite = buildExternalSite(30L, "production-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));
        when(siteRepository.findById(30L)).thenReturn(Optional.of(productionSite));
        when(siteService.resolveDataSource(testSite))
                .thenReturn(new SiteService.SiteDataSource(false, null, sshCreds("/var/www/html/test")));
        when(siteService.resolveDataSource(productionSite))
                .thenReturn(new SiteService.SiteDataSource(false, null, sshCreds("/var/www/html/production")));
        when(sshOperations.fetchTermsForEnvironments(eq("category"), any())).thenReturn(
                new com.letsblog.api.cms.ssh.WordPressSshOperations.EnvironmentFetchResult<>(
                        java.util.Map.of(
                                "test", List.of(new com.letsblog.api.cms.ssh.WordPressSshOperations.CategoryInfo(
                                        "1", "News", "news", null, "")),
                                "production", List.of(new com.letsblog.api.cms.ssh.WordPressSshOperations.CategoryInfo(
                                        "1", "News", "news", null, ""))),
                        java.util.Map.of()));

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertEquals("news", page.items().get(0).test().slug());
        assertEquals("news", page.items().get(0).production().slug());
        org.mockito.ArgumentCaptor<java.util.Map<String, com.letsblog.api.cms.CmsCredentials.WordPressCredentials>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(sshOperations, org.mockito.Mockito.times(1)).fetchTermsForEnvironments(eq("category"), captor.capture());
        assertEquals(2, captor.getValue().size());
    }

    @Test
    void listCategoryComparison_SSH取得失敗時はerror値になり作業ログに記録する() {
        TermComparisonService service = service();
        Project project = buildProject(null, 20L, null, "test");
        Site testSite = buildExternalSite(20L, "test-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));
        when(siteService.resolveDataSource(testSite))
                .thenReturn(new SiteService.SiteDataSource(false, null, sshCreds("/var/www/html/test")));
        when(sshOperations.fetchTermsForEnvironments(eq("category"), any())).thenReturn(
                new com.letsblog.api.cms.ssh.WordPressSshOperations.EnvironmentFetchResult<>(
                        java.util.Map.of(), java.util.Map.of("test", "Connection refused")));

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(0, page.items().size());
        verify(bulkManagementService).logFetchFailure(
                eq(1L), eq(BulkOperationType.CATEGORY_FETCH), eq("test"), eq("Connection refused"));
    }
}
