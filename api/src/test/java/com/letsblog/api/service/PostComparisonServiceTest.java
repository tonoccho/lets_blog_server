package com.letsblog.api.service;

import com.letsblog.api.cms.CmsAdapter;
import com.letsblog.api.cms.CmsAdapterFactory;
import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsPostSummary;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.BulkOperationLog;
import com.letsblog.api.domain.BulkOperationStatus;
import com.letsblog.api.domain.BulkOperationType;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.PostComparisonPage;
import com.letsblog.api.dto.PostEnvironmentValue;
import com.letsblog.api.repository.ProjectRepository;
import com.letsblog.api.repository.SiteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostComparisonServiceTest {

    @Mock
    private ProjectRepository projectRepository;
    @Mock
    private SiteRepository siteRepository;
    @Mock
    private SiteService siteService;
    @Mock
    private CmsAdapterFactory cmsAdapterFactory;
    @Mock
    private BulkManagementService bulkManagementService;
    @Mock
    private CmsAdapter cmsAdapter;

    private PostComparisonService service() {
        return new PostComparisonService(
                projectRepository, siteRepository, siteService, cmsAdapterFactory, bulkManagementService);
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

    private Site buildSite(Long id, String siteKey) {
        Site site = new Site();
        site.setId(id);
        site.setSiteKey(siteKey);
        site.setCmsType(CmsType.WORDPRESS);
        return site;
    }

    private CmsCredentials.WordPressCredentials creds(String baseUrl) {
        return new CmsCredentials.WordPressCredentials(baseUrl, "admin", "SSH");
    }

    @Test
    void listComparison_slugで名寄せして環境ごとの値を返す() {
        PostComparisonService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildSite(10L, "local-site");
        Site testSite = buildSite(20L, "test-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));

        CmsCredentials.WordPressCredentials localCreds = creds("https://local.test");
        CmsCredentials.WordPressCredentials testCreds = creds("https://test.test");
        when(siteService.getCredentials("local-site")).thenReturn(localCreds);
        when(siteService.getCredentials("test-site")).thenReturn(testCreds);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.listPosts(localCreds, "post")).thenReturn(
                List.of(new CmsPostSummary("101", "こんにちは", "hello", "publish", "post")));
        when(cmsAdapter.listPosts(testCreds, "post")).thenReturn(List.of());

        PostComparisonPage page = service.listComparison(1L, "post", 0, 20);

        assertEquals(1, page.items().size());
        assertEquals("hello", page.items().get(0).slug());
        PostEnvironmentValue local = page.items().get(0).local();
        assertTrue(local.available());
        assertEquals("101", local.postId());
        assertEquals("publish", local.status());
        PostEnvironmentValue test = page.items().get(0).test();
        assertTrue(test.available());
        assertEquals(null, test.postId());
        PostEnvironmentValue production = page.items().get(0).production();
        assertTrue(!production.available() && !production.error());
    }

    @Test
    void listComparison_取得失敗はエラーとして扱いログに記録する() {
        PostComparisonService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getCredentials("local-site")).thenReturn(creds("https://local.test"));
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.listPosts(any(), eq("post"))).thenThrow(new RuntimeException("接続エラー"));

        PostComparisonPage page = service.listComparison(1L, "post", 0, 20);

        assertEquals(0, page.items().size());
        org.mockito.Mockito.verify(bulkManagementService)
                .logFetchFailure(eq(1L), eq(BulkOperationType.POST_FETCH), eq("local"), eq("接続エラー"), any());
    }

    @Test
    void deleteEverywhere_見つかった環境のみ削除しログを返す() {
        PostComparisonService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildSite(10L, "local-site");
        Site testSite = buildSite(20L, "test-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteRepository.findById(20L)).thenReturn(Optional.of(testSite));
        CmsCredentials.WordPressCredentials localCreds = creds("https://local.test");
        CmsCredentials.WordPressCredentials testCreds = creds("https://test.test");
        when(siteService.getCredentials("local-site")).thenReturn(localCreds);
        when(siteService.getCredentials("test-site")).thenReturn(testCreds);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.listPosts(localCreds, "post")).thenReturn(
                List.of(new CmsPostSummary("101", "こんにちは", "hello", "publish", "post")));
        when(cmsAdapter.listPosts(testCreds, "post")).thenReturn(List.of());

        BulkOperationLog log = buildLog();
        when(bulkManagementService.deletePostAtEnvironment(1L, "local", localSite, "101", "post", "hello", 9L))
                .thenReturn(log);

        List<BulkOperationLog> results = service.deleteEverywhere(1L, "post", "hello", 9L);

        assertEquals(1, results.size());
        org.mockito.Mockito.verify(bulkManagementService, org.mockito.Mockito.never())
                .deletePostAtEnvironment(eq(1L), eq("test"), any(), any(), any(), any(), any());
    }

    @Test
    void deleteEverywhere_どの環境にも見つからなければ例外() {
        PostComparisonService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getCredentials("local-site")).thenReturn(creds("https://local.test"));
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.listPosts(any(), eq("post"))).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.deleteEverywhere(1L, "post", "missing", 9L));
    }

    @Test
    void updateStatusEverywhere_見つかった環境のみステータス変更する() {
        PostComparisonService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildSite(10L, "local-site");
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(localSite));
        CmsCredentials.WordPressCredentials localCreds = creds("https://local.test");
        when(siteService.getCredentials("local-site")).thenReturn(localCreds);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.listPosts(localCreds, "post")).thenReturn(
                List.of(new CmsPostSummary("101", "こんにちは", "hello", "draft", "post")));

        BulkOperationLog log = buildLog();
        when(bulkManagementService.updatePostStatusAtEnvironment(
                1L, "local", localSite, "101", "post", "hello", "publish", 9L)).thenReturn(log);

        List<BulkOperationLog> results = service.updateStatusEverywhere(1L, "post", "hello", "publish", 9L);

        assertEquals(1, results.size());
    }

    private BulkOperationLog buildLog() {
        BulkOperationLog log = new BulkOperationLog();
        log.setProjectId(1L);
        log.setOperationType(BulkOperationType.POST_DELETE);
        log.setEnvironment("local");
        log.setStatus(BulkOperationStatus.SUCCESS);
        log.setCreatedAt(LocalDateTime.now());
        return log;
    }
}
