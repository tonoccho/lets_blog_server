package com.letsblog.publishing.controller;

import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.domain.Project;
import com.letsblog.publishing.domain.Site;
import com.letsblog.publishing.service.ProjectNotFoundException;
import com.letsblog.publishing.service.ProjectService;
import com.letsblog.publishing.service.SiteService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * AiExistingTaxonomyBridgeControllerの回帰テスト(issue #574でlegacy-apiの{@code AiBridgeController}
 * に新設、issue #711でpublishing-serviceへ移管、Epic #551 C6-5)。移管元のAiBridgeControllerが持っていた
 * フェイルオープン方針(サイト未紐付け・取得失敗時は空リスト)がそのまま引き継がれていることを検証する。
 */
@ExtendWith(MockitoExtension.class)
class AiExistingTaxonomyBridgeControllerTest {

    @Mock
    private ProjectService projectService;
    @Mock
    private SiteService siteService;
    @Mock
    private CmsAdapterFactory cmsAdapterFactory;
    @Mock
    private CmsAdapter cmsAdapter;

    private AiExistingTaxonomyBridgeController controller;

    @BeforeEach
    void setUp() {
        controller = new AiExistingTaxonomyBridgeController(projectService, siteService, cmsAdapterFactory);
    }

    private Project buildProject(String masterEnvironment, Long testSiteId, Long productionSiteId) {
        Project project = new Project();
        project.setId(1L);
        project.setMasterEnvironment(masterEnvironment);
        project.setTestSiteId(testSiteId);
        project.setProductionSiteId(productionSiteId);
        return project;
    }

    private Site buildSite(Long id, String siteKey) {
        Site site = new Site();
        site.setId(id);
        site.setSiteKey(siteKey);
        site.setCmsType(CmsType.WORDPRESS);
        return site;
    }

    private void stubMasterSiteAdapter(Project project, Site site) {
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        lenient().when(siteService.getById(site.getId())).thenReturn(Optional.of(site));
        CmsCredentials credentials = new CmsCredentials.WordPressCredentials("https://example.com", "admin", "SSH");
        lenient().when(siteService.getCredentials(site.getSiteKey())).thenReturn(credentials);
        lenient().when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
    }

    @Test
    void existingCategories_マスターサイトのカテゴリ名一覧を返す() {
        Project project = buildProject("test", 10L, null);
        Site site = buildSite(10L, "test-site");
        stubMasterSiteAdapter(project, site);
        when(cmsAdapter.listCategoryNames(org.mockito.ArgumentMatchers.any())).thenReturn(List.of("お知らせ", "技術"));

        List<String> categories = controller.existingCategories(1L);

        assertEquals(List.of("お知らせ", "技術"), categories);
    }

    @Test
    void existingCategories_マスター環境にサイトが未紐付けなら空リスト() {
        Project project = buildProject("test", null, null);
        when(projectService.getProjectEntity(1L)).thenReturn(project);

        List<String> categories = controller.existingCategories(1L);

        assertEquals(List.of(), categories);
    }

    @Test
    void existingCategories_プロジェクトが存在しない場合も空リスト() {
        when(projectService.getProjectEntity(99L)).thenThrow(new ProjectNotFoundException("id 99 のプロジェクトは登録されていません"));

        List<String> categories = controller.existingCategories(99L);

        assertEquals(List.of(), categories);
    }

    @Test
    void existingCategoriesWithParents_親カテゴリ名付きで返す() {
        Project project = buildProject("production", null, 20L);
        Site site = buildSite(20L, "prod-site");
        stubMasterSiteAdapter(project, site);
        List<CmsAdapter.CategoryOption> options =
                List.of(new CmsAdapter.CategoryOption("子カテゴリ", "親カテゴリ"));
        when(cmsAdapter.listCategoriesWithParents(org.mockito.ArgumentMatchers.any())).thenReturn(options);

        List<CmsAdapter.CategoryOption> result = controller.existingCategoriesWithParents(1L);

        assertEquals(options, result);
    }

    @Test
    void existingTags_マスターサイトのタグ名一覧を返す() {
        Project project = buildProject("test", 10L, null);
        Site site = buildSite(10L, "test-site");
        stubMasterSiteAdapter(project, site);
        when(cmsAdapter.listTagNames(org.mockito.ArgumentMatchers.any())).thenReturn(List.of("Java", "AWS"));

        List<String> tags = controller.existingTags(1L);

        assertEquals(List.of("Java", "AWS"), tags);
    }

    @Test
    void existingTags_取得中に例外が発生した場合も空リスト() {
        Project project = buildProject("test", 10L, null);
        Site site = buildSite(10L, "test-site");
        stubMasterSiteAdapter(project, site);
        when(cmsAdapter.listTagNames(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("CMSへの接続に失敗しました"));

        List<String> tags = controller.existingTags(1L);

        assertEquals(List.of(), tags);
    }
}
