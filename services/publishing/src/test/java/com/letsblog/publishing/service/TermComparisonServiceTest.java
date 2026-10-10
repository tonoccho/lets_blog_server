package com.letsblog.publishing.service;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.slf4j.MDC;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.domain.BulkOperationLog;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.domain.Project;
import com.letsblog.publishing.domain.Site;
import com.letsblog.publishing.dto.TermComparisonPage;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient.CategoryInfo;
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
    private WordPressBulkManagementClient bulkManagementClient;

    @Mock
    private BulkManagementService bulkManagementService;

    @Mock
    private SiteService siteService;

    @Mock
    private ProjectService projectService;

    @Mock
    private com.letsblog.publishing.cms.ssh.WordPressSshOperations sshOperations;

    private TermComparisonService service() {
        return new TermComparisonService(
                bulkManagementClient, bulkManagementService, siteService, projectService,
                sshOperations);
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
        return site;
    }

    // ---- listCategoryComparison ----

    @Test
    void listCategoryComparison_スラッグでマージし未紐付け環境はavailable_falseになる() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
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

    // ---- issue #1682: agent経由の一覧取得失敗はerror値になり作業ログに記録される ----

    private void bindLocalAndTestManaged() {
        Project project = buildProject(10L, 20L, null, "test");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(buildManagedSite(10L, "local-site")));
        when(siteService.getById(20L)).thenReturn(Optional.of(buildManagedSite(20L, "test-site")));
    }

    @Test
    void listCategoryComparison_agentの読み取りタイムアウトはerror値になりCATEGORY_FETCHで記録する() {
        TermComparisonService service = service();
        bindLocalAndTestManaged();
        when(bulkManagementClient.listCategories("local-site"))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Read timed out"));
        when(bulkManagementClient.listCategories("test-site"))
                .thenReturn(List.of(new CategoryInfo("お知らせ", "oshirase", null, null)));

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertFalse(page.items().get(0).local().available());
        assertEquals("Read timed out", page.items().get(0).local().errorMessage());
        assertTrue(page.items().get(0).test().available());
        verify(bulkManagementService).logFetchFailure(
                eq(1L), eq(BulkOperationType.CATEGORY_FETCH), eq("local"), eq("Read timed out"), any());
    }

    @Test
    void listTagComparison_agentの読み取りタイムアウトはerror値になりTAG_FETCHで記録する() {
        TermComparisonService service = service();
        bindLocalAndTestManaged();
        when(bulkManagementClient.listTags("local-site"))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Read timed out"));
        when(bulkManagementClient.listTags("test-site"))
                .thenReturn(List.of(new CategoryInfo("タグ", "tag", null, null)));

        TermComparisonPage page = service.listTagComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertFalse(page.items().get(0).local().available());
        verify(bulkManagementService).logFetchFailure(
                eq(1L), eq(BulkOperationType.TAG_FETCH), eq("local"), eq("Read timed out"), any());
    }

    @Test
    void listCategoryComparison_agentが5xxを返した環境もerror値になる() {
        TermComparisonService service = service();
        bindLocalAndTestManaged();
        when(bulkManagementClient.listCategories("local-site"))
                .thenReturn(List.of(new CategoryInfo("お知らせ", "oshirase", null, null)));
        when(bulkManagementClient.listCategories("test-site")).thenThrow(
                new org.springframework.web.client.HttpServerErrorException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY));

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertFalse(page.items().get(0).test().available());
        verify(bulkManagementService).logFetchFailure(
                eq(1L), eq(BulkOperationType.CATEGORY_FETCH), eq("test"), any(), any());
    }

    @Test
    void listTagComparison_agentが4xxやその他のRestClientExceptionでもerror値になる() {
        TermComparisonService service = service();
        bindLocalAndTestManaged();
        when(bulkManagementClient.listTags("local-site")).thenThrow(
                new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.FORBIDDEN));
        when(bulkManagementClient.listTags("test-site"))
                .thenThrow(new org.springframework.web.client.RestClientException("unexpected body"));

        TermComparisonPage page = service.listTagComparison(1L, 0, 20);

        assertEquals(0, page.items().size());
        verify(bulkManagementService).logFetchFailure(
                eq(1L), eq(BulkOperationType.TAG_FETCH), eq("local"), any(), any());
        verify(bulkManagementService).logFetchFailure(
                eq(1L), eq(BulkOperationType.TAG_FETCH), eq("test"), eq("unexpected body"), any());
    }

    @Test
    void listCategoryComparison_agentが正常に0件を返した環境はエラーにならない() {
        TermComparisonService service = service();
        bindLocalAndTestManaged();
        when(bulkManagementClient.listCategories("local-site")).thenReturn(List.of());
        when(bulkManagementClient.listCategories("test-site"))
                .thenReturn(List.of(new CategoryInfo("お知らせ", "oshirase", null, null)));

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertTrue(page.items().get(0).local().available());
        assertFalse(page.items().get(0).local().error());
        assertEquals(null, page.items().get(0).local().slug());
        verify(bulkManagementService, never()).logFetchFailure(any(), any(), any(), any(), any());
    }

    @Test
    void listCategoryComparison_スラッグが異なれば名前が同じでも別行になる() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
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
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
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
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
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
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
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
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(siteService.getById(30L)).thenReturn(Optional.of(productionSite));

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

    /** issue #1137レビュー対応: toValue()のerror()分岐(L179)のカバレッジを補う。 */
    @Test
    void listCategoryComparison_一部環境がエラーでも他環境のslugはerror値として表示される() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildExternalSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(bulkManagementClient.listCategories("local-site"))
                .thenReturn(List.of(new CategoryInfo("お知らせ", "oshirase", null, null)));
        when(siteService.resolveDataSource(testSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCreds("/var/www/html/test")));
        when(sshOperations.fetchTermsForEnvironments(eq("category"), any())).thenReturn(
                new com.letsblog.publishing.cms.ssh.WordPressSshOperations.EnvironmentFetchResult<>(
                        java.util.Map.of(), java.util.Map.of("test", "Connection refused"),
                        java.util.Map.of("test", "stacktrace")));

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertTrue(page.items().get(0).local().available());
        assertFalse(page.items().get(0).test().available());
        assertEquals("Connection refused", page.items().get(0).test().errorMessage());
    }

    // ---- deleteCategoryEverywhere ----

    @Test
    void deleteCategoryEverywhere_存在する環境のみ削除される() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
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
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listCategories("local-site")).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.deleteCategoryEverywhere(1L, "oshirase", 9L));
    }

    /** issue #1137レビュー対応: deleteTagEverywhereでのdeleteType三項演算子(L317)のカバレッジを補う。 */
    @Test
    void deleteTagEverywhere_存在する環境のみ削除される() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(bulkManagementClient.listTags("local-site")).thenReturn(List.of());
        when(bulkManagementClient.listTags("test-site"))
                .thenReturn(List.of(new CategoryInfo("新着", "shinchaku", null, null)));
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        List<BulkOperationLog> results = service.deleteTagEverywhere(1L, "shinchaku", 9L);

        assertEquals(1, results.size());
        verify(bulkManagementService).applyToEnvironment(
                1L, "test", BulkOperationType.TAG_DELETE, null, null, null, null, "shinchaku", 9L);
    }

    // ---- editCategoryAndSync ----

    @Test
    void editCategoryAndSync_マスターに存在しなければ新規作成する() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(bulkManagementClient.listCategories(any())).thenReturn(List.of());
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        List<BulkOperationLog> results = service.editCategoryAndSync(
                1L, "oshirase", "新お知らせ", "new-oshirase", null, null, 9L);

        assertEquals(2, results.size());
        verify(bulkManagementService).applyToEnvironment(
                1L, "test", BulkOperationType.CATEGORY_CREATE, "新お知らせ", "new-oshirase", null, null, null, 9L);
        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_CREATE, "新お知らせ", "new-oshirase", null, null, null, 9L);
    }

    @Test
    void editCategoryAndSync_マスターを新しい値で更新し他の環境にも新しい値を反映する() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, 30L, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        Site productionSite = buildManagedSite(30L, "production-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(siteService.getById(30L)).thenReturn(Optional.of(productionSite));

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

    /** issue #1137レビュー対応: editTagAndSyncのBulkOperationType三項演算子(L242/L243)のカバレッジを補う。 */
    @Test
    void editTagAndSync_タグ用のBulkOperationTypeで呼ばれる() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(bulkManagementClient.listTags(any())).thenReturn(List.of());
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        List<BulkOperationLog> results = service.editTagAndSync(
                1L, "shinchaku", "新着", "shinchaku-new", null, null, 9L);

        assertEquals(2, results.size());
        verify(bulkManagementService).applyToEnvironment(
                1L, "test", BulkOperationType.TAG_CREATE, "新着", "shinchaku-new", null, null, null, 9L);
    }

    // ---- syncAllCategoriesToMaster ----

    @Test
    void syncAllCategoriesToMaster_マスターと異なる項目のみ同期し差分なしはスキップする() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));

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
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        CategoryInfo same = new CategoryInfo("イベント", "event", null, null);
        when(bulkManagementClient.listCategories("test-site")).thenReturn(List.of(same));
        when(bulkManagementClient.listCategories("local-site")).thenReturn(List.of(same));

        List<BulkOperationLog> results = service.syncAllCategoriesToMaster(1L, 9L);

        assertTrue(results.isEmpty());
        verify(bulkManagementService, never()).applyToEnvironment(
                eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L));
    }

    /**
     * issue #1137レビュー対応: マスター環境に項目自体が存在しない(missing()、available=true・slug=null)
     * 行は同期対象から除外される分岐(L285のmasterValue.slug()==null側)のカバレッジを補う。
     */
    @Test
    void syncAllCategoriesToMaster_マスターに存在しない項目は同期対象外() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(bulkManagementClient.listCategories("local-site"))
                .thenReturn(List.of(new CategoryInfo("ローカル限定", "local-only", null, null)));
        when(bulkManagementClient.listCategories("test-site")).thenReturn(List.of());

        List<BulkOperationLog> results = service.syncAllCategoriesToMaster(1L, 9L);

        assertTrue(results.isEmpty());
        verify(bulkManagementService, never()).applyToEnvironment(
                eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L));
    }

    /**
     * issue #1137レビュー対応: マスター環境自体が未紐付け(unavailable()、available=false)の場合は
     * 全行が同期対象外になる分岐(L285のmasterValue.available()==false側)のカバレッジを補う。
     */
    @Test
    void syncAllCategoriesToMaster_マスター環境が未紐付けなら全行が対象外() {
        TermComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(bulkManagementClient.listCategories("local-site"))
                .thenReturn(List.of(new CategoryInfo("ローカル限定", "local-only", null, null)));

        List<BulkOperationLog> results = service.syncAllCategoriesToMaster(1L, 9L);

        assertTrue(results.isEmpty());
        verify(bulkManagementService, never()).applyToEnvironment(
                eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L));
    }

    /** issue #1137レビュー対応: needsSyncのslug差分分岐(L305)のカバレッジを補う(大文字小文字違い)。 */
    @Test
    void syncAllCategoriesToMaster_slugの大文字小文字が異なれば同期対象になる() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(bulkManagementClient.listCategories("test-site"))
                .thenReturn(List.of(new CategoryInfo("お知らせ", "oshirase", null, null)));
        when(bulkManagementClient.listCategories("local-site"))
                .thenReturn(List.of(new CategoryInfo("お知らせ", "Oshirase", null, null)));
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        List<BulkOperationLog> results = service.syncAllCategoriesToMaster(1L, 9L);

        assertEquals(1, results.size());
        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_EDIT, "お知らせ", "oshirase", null, null, "Oshirase", 9L);
    }

    /** issue #1137レビュー対応: needsSyncのparentSlug差分分岐(L306)のカバレッジを補う。 */
    @Test
    void syncAllCategoriesToMaster_parentSlugのみ異なれば同期対象になる() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(bulkManagementClient.listCategories("test-site"))
                .thenReturn(List.of(new CategoryInfo("お知らせ", "oshirase", "oya", null)));
        when(bulkManagementClient.listCategories("local-site"))
                .thenReturn(List.of(new CategoryInfo("お知らせ", "oshirase", "chigau-oya", null)));
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        List<BulkOperationLog> results = service.syncAllCategoriesToMaster(1L, 9L);

        assertEquals(1, results.size());
        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_EDIT, "お知らせ", "oshirase", "oya", null, "oshirase", 9L);
    }

    // ---- タグ(カテゴリと同じロジックを共有していることの確認) ----

    @Test
    void syncTag_タグ用のBulkOperationTypeで呼ばれる() {
        TermComparisonService service = service();
        Project project = buildProject(10L, 20L, null, "test");
        Site localSite = buildManagedSite(10L, "local-site");
        Site testSite = buildManagedSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        CategoryInfo master = new CategoryInfo("新着", "shinchaku", null, null);
        when(bulkManagementClient.listTags("test-site")).thenReturn(List.of(master));
        when(bulkManagementClient.listTags("local-site")).thenReturn(List.of());
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        service.syncTag(1L, "shinchaku", 9L);

        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.TAG_CREATE, "新着", "shinchaku", null, null, null, 9L);
    }

    // ---- 非managedサイト: SSHホスト単位まとめ取得・エラー ----

    private Site buildExternalSite(Long id, String slug) {
        Site site = new Site();
        site.setId(id);
        site.setSiteKey(slug);
        site.setCmsType(CmsType.WORDPRESS);
        site.setManagedWordpress(false);
        return site;
    }

    private com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials sshCreds(String wpPath) {
        return new com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials(
                "https://example.com", null, "SSH", "203.0.113.5", 22, "deploy", wpPath, "PEM", null, null);
    }

    @Test
    void listCategoryComparison_同一ホストのSSH環境は1回のfetchTermsForEnvironmentsにまとめる() {
        TermComparisonService service = service();
        Project project = buildProject(null, 20L, 30L, "test");
        Site testSite = buildExternalSite(20L, "test-site");
        Site productionSite = buildExternalSite(30L, "production-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(siteService.getById(30L)).thenReturn(Optional.of(productionSite));
        when(siteService.resolveDataSource(testSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCreds("/var/www/html/test")));
        when(siteService.resolveDataSource(productionSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCreds("/var/www/html/production")));
        when(sshOperations.fetchTermsForEnvironments(eq("category"), any())).thenReturn(
                new com.letsblog.publishing.cms.ssh.WordPressSshOperations.EnvironmentFetchResult<>(
                        java.util.Map.of(
                                "test", List.of(new com.letsblog.publishing.cms.ssh.WordPressSshOperations.CategoryInfo(
                                        "1", "News", "news", null, "")),
                                "production", List.of(new com.letsblog.publishing.cms.ssh.WordPressSshOperations.CategoryInfo(
                                        "1", "News", "news", null, ""))),
                        java.util.Map.of(), java.util.Map.of()));

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(1, page.items().size());
        assertEquals("news", page.items().get(0).test().slug());
        assertEquals("news", page.items().get(0).production().slug());
        org.mockito.ArgumentCaptor<java.util.Map<String, com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(sshOperations, org.mockito.Mockito.times(1)).fetchTermsForEnvironments(eq("category"), captor.capture());
        assertEquals(2, captor.getValue().size());
    }

    @Test
    void listCategoryComparison_SSH取得失敗時はerror値になり作業ログに記録する() {
        TermComparisonService service = service();
        Project project = buildProject(null, 20L, null, "test");
        Site testSite = buildExternalSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(siteService.resolveDataSource(testSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCreds("/var/www/html/test")));
        when(sshOperations.fetchTermsForEnvironments(eq("category"), any())).thenReturn(
                new com.letsblog.publishing.cms.ssh.WordPressSshOperations.EnvironmentFetchResult<>(
                        java.util.Map.of(), java.util.Map.of("test", "Connection refused"),
                        java.util.Map.of("test", "java.io.IOException: Connection refused\n\tat ...")));

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(0, page.items().size());
        verify(bulkManagementService).logFetchFailure(
                eq(1L), eq(BulkOperationType.CATEGORY_FETCH), eq("test"), eq("Connection refused"),
                eq("java.io.IOException: Connection refused\n\tat ..."));
    }

    /**
     * issue #1137レビュー対応: タグ側のSSH取得(L390の"post_tag"分岐/L424/L426のタグ側ログ)の
     * カバレッジを補う。
     */
    @Test
    void listTagComparison_SSH取得失敗時はerror値になり作業ログに記録する() {
        TermComparisonService service = service();
        Project project = buildProject(null, 20L, null, "test");
        Site testSite = buildExternalSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(siteService.resolveDataSource(testSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCreds("/var/www/html/test")));
        when(sshOperations.fetchTermsForEnvironments(eq("post_tag"), any())).thenReturn(
                new com.letsblog.publishing.cms.ssh.WordPressSshOperations.EnvironmentFetchResult<>(
                        java.util.Map.of(), java.util.Map.of("test", "Connection refused"),
                        java.util.Map.of("test", "stacktrace")));

        TermComparisonPage page = service.listTagComparison(1L, 0, 20);

        assertEquals(0, page.items().size());
        verify(sshOperations).fetchTermsForEnvironments(eq("post_tag"), any());
        verify(bulkManagementService).logFetchFailure(
                eq(1L), eq(BulkOperationType.TAG_FETCH), eq("test"), eq("Connection refused"), eq("stacktrace"));
    }

    /**
     * issue #1137レビュー対応: 非managedかつSSHも使えないサイトはunavailableになる分岐(L379)の
     * カバレッジを補う。
     */
    @Test
    void listCategoryComparison_SSHも使えない非managedサイトはunavailableになる() {
        TermComparisonService service = service();
        Project project = buildProject(10L, null, null, "test");
        Site externalSite = buildExternalSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(externalSite));
        when(siteService.resolveDataSource(externalSite))
                .thenReturn(new SiteService.SiteDataSource(false, null));

        TermComparisonPage page = service.listCategoryComparison(1L, 0, 20);

        assertEquals(0, page.items().size());
        verify(sshOperations, never()).fetchTermsForEnvironments(any(), any());
    }

    /** issue #1137レビュー対応: sshPort未設定時は既定の22番ポートでホストキーをまとめる分岐(L418)のカバレッジを補う。 */
    @Test
    void listCategoryComparison_sshPort未設定なら既定の22番でホストをまとめる() {
        TermComparisonService service = service();
        Project project = buildProject(null, 20L, 30L, "test");
        Site testSite = buildExternalSite(20L, "test-site");
        Site productionSite = buildExternalSite(30L, "production-site");
        com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials credsWithoutPort =
                new com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials(
                        "https://example.com", null, "SSH", "203.0.113.5", null, "deploy",
                        "/var/www/html/test", "PEM", null, null);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(siteService.getById(30L)).thenReturn(Optional.of(productionSite));
        when(siteService.resolveDataSource(testSite))
                .thenReturn(new SiteService.SiteDataSource(false, credsWithoutPort));
        when(siteService.resolveDataSource(productionSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCreds("/var/www/html/production")));
        when(sshOperations.fetchTermsForEnvironments(eq("category"), any())).thenReturn(
                new com.letsblog.publishing.cms.ssh.WordPressSshOperations.EnvironmentFetchResult<>(
                        java.util.Map.of(), java.util.Map.of(), java.util.Map.of()));

        service.listCategoryComparison(1L, 0, 20);

        // 双方とも同一ホスト(203.0.113.5)かつポート未設定=既定22番なので、production側の
        // 明示的な22番指定と同一ホストキーになり1回のfetchにまとまる。
        verify(sshOperations, org.mockito.Mockito.times(1)).fetchTermsForEnvironments(eq("category"), any());
    }

    // ---- issue #1474: agent経路の環境別取得の並列化 ----

    private Project threeManagedEnvironments() {
        Project project = buildProject(10L, 20L, 30L, "test");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(buildManagedSite(10L, "local-site")));
        when(siteService.getById(20L)).thenReturn(Optional.of(buildManagedSite(20L, "test-site")));
        when(siteService.getById(30L)).thenReturn(Optional.of(buildManagedSite(30L, "production-site")));
        return project;
    }

    @Test
    void listCategoryComparison_agent経由の3環境の取得は並列に走る() {
        threeManagedEnvironments();
        // 3環境の取得が同時に走っていなければ、全員が揃う前にlatchの待機がタイムアウトする(直列実行では揃わない)
        java.util.concurrent.CountDownLatch allStarted = new java.util.concurrent.CountDownLatch(3);
        java.util.concurrent.atomic.AtomicInteger timedOut = new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.stubbing.Answer<List<CategoryInfo>> rendezvous = invocation -> {
            allStarted.countDown();
            if (!allStarted.await(3, java.util.concurrent.TimeUnit.SECONDS)) {
                timedOut.incrementAndGet();
            }
            return List.of(new CategoryInfo("お知らせ", "oshirase", null, null));
        };
        when(bulkManagementClient.listCategories(any())).thenAnswer(rendezvous);

        TermComparisonPage page = service().listCategoryComparison(1L, 0, 20);

        assertEquals(0, timedOut.get());
        assertEquals(1, page.items().size());
        assertTrue(page.items().get(0).local().available());
        assertTrue(page.items().get(0).test().available());
        assertTrue(page.items().get(0).production().available());
    }

    @Test
    void listCategoryComparison_完了順が逆でも結果は環境ごとの正しい列に入る() throws Exception {
        threeManagedEnvironments();
        // localが最後に、productionが最初に終わる
        when(bulkManagementClient.listCategories("local-site")).thenAnswer(invocation -> {
            Thread.sleep(300);
            return List.of(new CategoryInfo("L", "only-local", null, null));
        });
        when(bulkManagementClient.listCategories("test-site")).thenAnswer(invocation -> {
            Thread.sleep(150);
            return List.of(new CategoryInfo("T", "only-test", null, null));
        });
        when(bulkManagementClient.listCategories("production-site"))
                .thenReturn(List.of(new CategoryInfo("P", "only-production", null, null)));

        TermComparisonPage page = service().listCategoryComparison(1L, 0, 20);

        assertEquals(3, page.items().size());
        java.util.Map<String, com.letsblog.publishing.dto.TermComparisonRow> bySlug = new java.util.HashMap<>();
        page.items().forEach(row -> bySlug.put(row.slug(), row));
        assertTrue(bySlug.get("only-local").local().available());
        assertEquals("only-local", bySlug.get("only-local").local().slug());
        assertEquals("only-test", bySlug.get("only-test").test().slug());
        assertEquals("only-production", bySlug.get("only-production").production().slug());
    }

    @Test
    void listCategoryComparison_agentの取得が例外なら並列化前と同じ例外がそのまま伝わる() {
        threeManagedEnvironments();
        when(bulkManagementClient.listCategories("local-site")).thenReturn(List.of());
        when(bulkManagementClient.listCategories("test-site")).thenReturn(List.of());
        when(bulkManagementClient.listCategories("production-site"))
                .thenThrow(new IllegalStateException("agent down"));

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> service().listCategoryComparison(1L, 0, 20));

        assertEquals("agent down", thrown.getMessage());
    }

    @Test
    void listCategoryComparison_managedとSSHが混在してもSSHは1回にまとめagentは並列に取得する() {
        Project project = buildProject(10L, 20L, 30L, "test");
        Site testSite = buildExternalSite(20L, "test-site");
        Site productionSite = buildExternalSite(30L, "production-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(buildManagedSite(10L, "local-site")));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        when(siteService.getById(30L)).thenReturn(Optional.of(productionSite));
        when(siteService.resolveDataSource(testSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCreds("/var/www/html/test")));
        when(siteService.resolveDataSource(productionSite))
                .thenReturn(new SiteService.SiteDataSource(false, sshCreds("/var/www/html/production")));
        when(bulkManagementClient.listCategories("local-site"))
                .thenReturn(List.of(new CategoryInfo("News", "news", null, null)));
        when(sshOperations.fetchTermsForEnvironments(eq("category"), any())).thenReturn(
                new com.letsblog.publishing.cms.ssh.WordPressSshOperations.EnvironmentFetchResult<>(
                        java.util.Map.of(), java.util.Map.of("test", "refused", "production", "refused"),
                        java.util.Map.of()));

        TermComparisonPage page = service().listCategoryComparison(1L, 0, 20);

        verify(sshOperations, org.mockito.Mockito.times(1)).fetchTermsForEnvironments(eq("category"), any());
        assertEquals("news", page.items().get(0).local().slug());
        assertEquals("refused", page.items().get(0).test().errorMessage());
    }

    @Test
    void listCategoryComparison_agentの取得がErrorで落ちたときはCompletionExceptionとして伝わる() {
        threeManagedEnvironments();
        when(bulkManagementClient.listCategories("local-site")).thenReturn(List.of());
        when(bulkManagementClient.listCategories("test-site")).thenReturn(List.of());
        when(bulkManagementClient.listCategories("production-site"))
                .thenThrow(new OutOfMemoryError("oom"));

        java.util.concurrent.CompletionException thrown = assertThrows(
                java.util.concurrent.CompletionException.class, () -> service().listCategoryComparison(1L, 0, 20));

        assertTrue(thrown.getCause() instanceof OutOfMemoryError);
    }

    // ---- issue #1732: 並列取得のワーカーも、リクエストの処理IDを引き継ぐ ----

    @org.junit.jupiter.api.AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void listCategoryComparison_並列取得のワーカーは投入元の処理IDを引き継ぎ_終了後は持ち越さない() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            TermComparisonService service = new TermComparisonService(
                    bulkManagementClient, bulkManagementService, siteService, projectService, sshOperations, executor);
            bindLocalAndTestManaged();
            List<String> seen = new CopyOnWriteArrayList<>();
            when(bulkManagementClient.listCategories(any())).thenAnswer(invocation -> {
                seen.add(String.valueOf(MDC.get("correlationId")));
                return List.of();
            });
            MDC.put("correlationId", "cid-terms");

            service.listCategoryComparison(1L, 0, 20);

            assertEquals(List.of("cid-terms", "cid-terms"), seen);
            assertEquals(null, executor.submit(() -> MDC.get("correlationId")).get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }
}
