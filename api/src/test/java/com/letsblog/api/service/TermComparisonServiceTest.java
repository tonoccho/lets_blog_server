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

    private TermComparisonService service() {
        return new TermComparisonService(projectRepository, siteRepository, bulkManagementClient, bulkManagementService);
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
    void listCategoryComparison_名前でマージし未紐付け環境はavailable_falseになる() {
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

        assertEquals(1, page.items().size());
        assertEquals("お知らせ", page.items().get(0).name());
        assertEquals("test", page.masterEnvironment());
        assertTrue(page.items().get(0).local().available());
        assertEquals("oshirase-old", page.items().get(0).local().slug());
        assertEquals("oshirase", page.items().get(0).test().slug());
        assertFalse(page.items().get(0).production().available());
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

        assertThrows(IllegalArgumentException.class, () -> service.syncCategory(1L, "お知らせ", 9L));
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
        when(bulkManagementClient.listCategories("production-site"))
                .thenReturn(List.of(new CategoryInfo("お知らせ", "oshirase-old", null, null)));
        when(bulkManagementService.applyToEnvironment(eq(1L), any(), any(), any(), any(), any(), any(), any(), eq(9L)))
                .thenReturn(new BulkOperationLog());

        service.syncCategory(1L, "お知らせ", 9L);

        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.CATEGORY_CREATE, "お知らせ", "oshirase", null, "説明", null, 9L);
        verify(bulkManagementService).applyToEnvironment(
                1L, "production", BulkOperationType.CATEGORY_EDIT, "お知らせ", "oshirase", null, "説明", "oshirase-old", 9L);
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

        List<BulkOperationLog> results = service.deleteCategoryEverywhere(1L, "お知らせ", 9L);

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

        assertThrows(IllegalArgumentException.class, () -> service.deleteCategoryEverywhere(1L, "お知らせ", 9L));
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

        service.syncTag(1L, "新着", 9L);

        verify(bulkManagementService).applyToEnvironment(
                1L, "local", BulkOperationType.TAG_CREATE, "新着", "shinchaku", null, null, null, 9L);
    }
}
