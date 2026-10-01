package com.letsblog.publishing.service;

import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.CmsPostSummary;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.domain.BulkOperationLog;
import com.letsblog.publishing.domain.BulkOperationStatus;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.domain.Project;
import com.letsblog.publishing.domain.Site;
import com.letsblog.publishing.dto.PostComparisonPage;
import com.letsblog.publishing.dto.PostEnvironmentValue;
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
    private SiteService siteService;
    @Mock
    private ProjectService projectService;
    @Mock
    private CmsAdapterFactory cmsAdapterFactory;
    @Mock
    private BulkManagementService bulkManagementService;
    @Mock
    private CmsAdapter cmsAdapter;

    private PostComparisonService service() {
        return new PostComparisonService(
                siteService, projectService, cmsAdapterFactory, bulkManagementService);
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
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));

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
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getCredentials("local-site")).thenReturn(creds("https://local.test"));
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.listPosts(any(), eq("post"))).thenThrow(new RuntimeException("接続エラー"));

        PostComparisonPage page = service.listComparison(1L, "post", 0, 20);

        assertEquals(0, page.items().size());
        org.mockito.Mockito.verify(bulkManagementService)
                .logFetchFailure(eq(1L), eq(BulkOperationType.POST_FETCH), eq("local"), eq("接続エラー"), any());
    }

    /** issue #1137レビュー対応: toValue()のerror()分岐(L156)のカバレッジを補う。 */
    @Test
    void listComparison_一部環境がエラーでも他環境のslugはerror値として表示される() {
        PostComparisonService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildSite(10L, "local-site");
        Site testSite = buildSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        CmsCredentials.WordPressCredentials localCreds = creds("https://local.test");
        CmsCredentials.WordPressCredentials testCreds = creds("https://test.test");
        when(siteService.getCredentials("local-site")).thenReturn(localCreds);
        when(siteService.getCredentials("test-site")).thenReturn(testCreds);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.listPosts(localCreds, "post")).thenReturn(
                List.of(new CmsPostSummary("101", "こんにちは", "hello", "publish", "post")));
        when(cmsAdapter.listPosts(testCreds, "post")).thenThrow(new RuntimeException("接続エラー"));

        PostComparisonPage page = service.listComparison(1L, "post", 0, 20);

        assertEquals(1, page.items().size());
        PostEnvironmentValue local = page.items().get(0).local();
        assertTrue(local.available());
        PostEnvironmentValue test = page.items().get(0).test();
        assertTrue(test.error());
        assertEquals("接続エラー", test.errorMessage());
    }

    @Test
    void deleteEverywhere_見つかった環境のみ削除しログを返す() {
        PostComparisonService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildSite(10L, "local-site");
        Site testSite = buildSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
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
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
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
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
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

    /** issue #1137レビュー対応: updateStatusEverywhereでslugが見つからない環境の分岐(L130)のカバレッジを補う。 */
    @Test
    void updateStatusEverywhere_一致するslugが無い環境はスキップする() {
        PostComparisonService service = service();
        Project project = buildProject(10L, 20L, null);
        Site localSite = buildSite(10L, "local-site");
        Site testSite = buildSite(20L, "test-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getById(20L)).thenReturn(Optional.of(testSite));
        CmsCredentials.WordPressCredentials localCreds = creds("https://local.test");
        CmsCredentials.WordPressCredentials testCreds = creds("https://test.test");
        when(siteService.getCredentials("local-site")).thenReturn(localCreds);
        when(siteService.getCredentials("test-site")).thenReturn(testCreds);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.listPosts(localCreds, "post")).thenReturn(
                List.of(new CmsPostSummary("101", "こんにちは", "hello", "draft", "post")));
        when(cmsAdapter.listPosts(testCreds, "post")).thenReturn(
                List.of(new CmsPostSummary("202", "別の投稿", "other-slug", "draft", "post")));

        BulkOperationLog log = buildLog();
        when(bulkManagementService.updatePostStatusAtEnvironment(
                1L, "local", localSite, "101", "post", "hello", "publish", 9L)).thenReturn(log);

        List<BulkOperationLog> results = service.updateStatusEverywhere(1L, "post", "hello", "publish", 9L);

        assertEquals(1, results.size());
        org.mockito.Mockito.verify(bulkManagementService, org.mockito.Mockito.never())
                .updatePostStatusAtEnvironment(eq(1L), eq("test"), any(), any(), any(), any(), any(), any());
    }

    /** issue #1137レビュー対応: updateStatusEverywhereで対象が1件も無い場合の分岐(L137)のカバレッジを補う。 */
    @Test
    void updateStatusEverywhere_どの環境にも見つからなければ例外() {
        PostComparisonService service = service();
        Project project = buildProject(10L, null, null);
        Site localSite = buildSite(10L, "local-site");
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(10L)).thenReturn(Optional.of(localSite));
        when(siteService.getCredentials("local-site")).thenReturn(creds("https://local.test"));
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.listPosts(any(), eq("post"))).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class,
                () -> service.updateStatusEverywhere(1L, "post", "missing", "publish", 9L));
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
