package com.letsblog.publishing.service;

import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.domain.Project;
import com.letsblog.publishing.domain.Site;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * publishing-serviceのArticlePreviewService(署名付きプレビューURLの発行、issue #1561)を検証する。
 * 旧プレビュー経路(fetchThemeCss/renderSkeleton/renderRealPrivatePost/deletePreviewPost)は
 * issue #1564で削除したため、そのテストも併せて削除した。記事本文レンダリング(renderHtml)は
 * content-serviceが持つ。
 */
@ExtendWith(MockitoExtension.class)
class ArticlePreviewServiceTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private SiteService siteService;

    @Mock
    private com.letsblog.publishing.cms.CmsAdapterFactory cmsAdapterFactory;

    private ArticlePreviewService service;

    @BeforeEach
    void setUp() {
        service = new ArticlePreviewService(projectService, siteService, cmsAdapterFactory);
    }

    private Project projectWithMaster(String masterEnvironment, Long testSiteId, Long productionSiteId) {
        Project project = new Project();
        project.setId(1L);
        project.setMasterEnvironment(masterEnvironment);
        project.setTestSiteId(testSiteId);
        project.setProductionSiteId(productionSiteId);
        return project;
    }

    private Site wordPressSite(Long id, String baseUrl) {
        Site site = new Site();
        site.setId(id);
        site.setCmsType(CmsType.WORDPRESS);
        site.setBaseUrl(baseUrl);
        return site;
    }

    private Site managedWordPressSite(Long id, String siteKey, String publicBaseUrl, String wpSlug) {
        Site site = wordPressSite(id, publicBaseUrl);
        site.setSiteKey(siteKey);
        site.setManagedWordpress(true);
        site.setWpSlug(wpSlug);
        return site;
    }

    private com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials agentCredentials(String wpSlug) {
        return new com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials(
                "https://localhost/sites/" + wpSlug, "admin",
                "AGENT", null, null, null, null, null, null, wpSlug);
    }

    // ---- issue #1561: 投稿を作らない署名付きプレビュー URL ----

    private com.letsblog.publishing.dto.SignedPreviewUrlRequest signedRequest(
            Long siteId, String featuredImage, Integer ttl) {
        return new com.letsblog.publishing.dto.SignedPreviewUrlRequest(
                siteId, "プレビュー題", "<p>本文</p>", java.util.List.of("news"), java.util.List.of("a", "b"),
                featuredImage, ttl);
    }

    private com.letsblog.publishing.cms.CmsAdapter givenAgentSiteForSignedPreview() {
        Project project = projectWithMaster("test", 30L, null);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        Site site = managedWordPressSite(30L, "local-site", "https://localhost/sites/local-site", "local-site");
        when(siteService.getById(30L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("local-site")).thenReturn(agentCredentials("local-site"));
        com.letsblog.publishing.cms.CmsAdapter cmsAdapter =
                org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        return cmsAdapter;
    }

    @Test
    void createSignedPreviewUrl_内容をプラグインへ渡して署名付きURLを返す() throws Exception {
        com.letsblog.publishing.cms.CmsAdapter cmsAdapter = givenAgentSiteForSignedPreview();
        when(cmsAdapter.createSignedPreview(org.mockito.ArgumentMatchers.eq(agentCredentials("local-site")),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(600)))
                .thenReturn(new com.letsblog.publishing.cms.SignedPreview("https://localhost/sites/local-site/?letsblog_preview=t", 1800000600L));

        com.letsblog.publishing.dto.SignedPreviewUrlResponse response = service.createSignedPreviewUrl(
                1L, signedRequest(30L, "data:image/png;base64,AAAA", 600));

        assertEquals("https://localhost/sites/local-site/?letsblog_preview=t", response.url());
        assertEquals(1800000600L, response.expiresAt());
        org.mockito.ArgumentCaptor<String> payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(cmsAdapter).requireLetsblogPlugin(agentCredentials("local-site"));
        verify(cmsAdapter).createSignedPreview(org.mockito.ArgumentMatchers.any(), payload.capture(),
                org.mockito.ArgumentMatchers.eq(600));
        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload.getValue());
        assertEquals("プレビュー題", node.path("title").asText());
        assertEquals("<p>本文</p>", node.path("content").asText());
        assertEquals("news", node.path("categories").get(0).asText());
        assertEquals("b", node.path("tags").get(1).asText());
        assertEquals("data:image/png;base64,AAAA", node.path("featured_image").asText());
    }

    @Test
    void createSignedPreviewUrl_カテゴリ_タグ_アイキャッチが無ければ空として渡す() throws Exception {
        com.letsblog.publishing.cms.CmsAdapter cmsAdapter = givenAgentSiteForSignedPreview();
        when(cmsAdapter.createSignedPreview(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new com.letsblog.publishing.cms.SignedPreview("https://x/?letsblog_preview=t", 1L));

        service.createSignedPreviewUrl(1L, new com.letsblog.publishing.dto.SignedPreviewUrlRequest(
                30L, "題", "<p>x</p>", null, null, " ", null));

        org.mockito.ArgumentCaptor<String> payload = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(cmsAdapter).createSignedPreview(org.mockito.ArgumentMatchers.any(), payload.capture(),
                org.mockito.ArgumentMatchers.isNull());
        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(payload.getValue());
        assertEquals(0, node.path("categories").size());
        assertEquals(0, node.path("tags").size());
        assertTrue(node.path("featured_image").isMissingNode());
    }

    @Test
    void createSignedPreviewUrl_投稿もメディアも作らない() {
        com.letsblog.publishing.cms.CmsAdapter cmsAdapter = givenAgentSiteForSignedPreview();
        when(cmsAdapter.createSignedPreview(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.letsblog.publishing.cms.SignedPreview("https://x/?letsblog_preview=t", 1L));

        service.createSignedPreviewUrl(1L, signedRequest(30L, "data:image/png;base64,AAAA", null));

        verify(cmsAdapter, org.mockito.Mockito.never()).createOrUpdatePost(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(cmsAdapter, org.mockito.Mockito.never()).uploadMedia(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(cmsAdapter, org.mockito.Mockito.never()).generateAuthCookie(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createSignedPreviewUrl_プラグインが使えなければ発行せずに拒否する() {
        com.letsblog.publishing.cms.CmsAdapter cmsAdapter = givenAgentSiteForSignedPreview();
        org.mockito.Mockito.doThrow(new com.letsblog.publishing.cms.LetsblogPluginUnavailableException(
                com.letsblog.publishing.cms.LetsblogPluginStatus.notInstalled()))
                .when(cmsAdapter).requireLetsblogPlugin(org.mockito.ArgumentMatchers.any());

        assertThrows(com.letsblog.publishing.cms.LetsblogPluginUnavailableException.class,
                () -> service.createSignedPreviewUrl(1L, signedRequest(30L, null, null)));
        verify(cmsAdapter, org.mockito.Mockito.never()).createSignedPreview(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void createSignedPreviewUrl_プロジェクトに紐づかないサイトは理由つきで拒否する() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 30L, null));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.createSignedPreviewUrl(1L, signedRequest(999L, null, null)));
        assertTrue(e.getMessage().contains("紐づいていません"));
        org.mockito.Mockito.verifyNoInteractions(cmsAdapterFactory);
    }

    @Test
    void createSignedPreviewUrl_siteId未指定ならマスター環境のサイトを対象にする() {
        com.letsblog.publishing.cms.CmsAdapter cmsAdapter = givenAgentSiteForSignedPreview();
        when(cmsAdapter.createSignedPreview(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.letsblog.publishing.cms.SignedPreview("https://x/?letsblog_preview=t", 1L));

        service.createSignedPreviewUrl(1L, signedRequest(null, null, null));

        verify(cmsAdapter).createSignedPreview(org.mockito.ArgumentMatchers.eq(agentCredentials("local-site")),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.isNull());
    }

    // ---- サイトの解決(resolveSiteForPreview)の分岐 ----

    @Test
    void createSignedPreviewUrl_siteIdのサイトが見つからなければ理由つきで拒否する() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 30L, null));
        when(siteService.getById(30L)).thenReturn(Optional.empty());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.createSignedPreviewUrl(1L, signedRequest(30L, null, null)));
        assertTrue(e.getMessage().contains("見つかりません"));
        org.mockito.Mockito.verifyNoInteractions(cmsAdapterFactory);
    }

    @Test
    void createSignedPreviewUrl_WordPress以外のCMSは理由つきで拒否する() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 30L, null));
        Site site = wordPressSite(30L, "https://example.com");
        site.setCmsType(null);
        when(siteService.getById(30L)).thenReturn(Optional.of(site));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.createSignedPreviewUrl(1L, signedRequest(30L, null, null)));
        assertTrue(e.getMessage().contains("WordPress以外"));
        org.mockito.Mockito.verifyNoInteractions(cmsAdapterFactory);
    }

    @Test
    void createSignedPreviewUrl_マスター環境が本番ならsiteId未指定で本番サイトを対象にする() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("production", null, 31L));
        Site site = managedWordPressSite(31L, "prod-site", "https://example.com", "prod-site");
        when(siteService.getById(31L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("prod-site")).thenReturn(agentCredentials("prod-site"));
        com.letsblog.publishing.cms.CmsAdapter cmsAdapter =
                org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.createSignedPreview(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.letsblog.publishing.cms.SignedPreview("https://x/?letsblog_preview=t", 1L));

        assertEquals("https://x/?letsblog_preview=t",
                service.createSignedPreviewUrl(1L, signedRequest(null, null, null)).url());
    }

    @Test
    void createSignedPreviewUrl_マスター環境がテストでもlocalでも未紐付けなら理由つきで拒否する() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("local", null, null));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.createSignedPreviewUrl(1L, signedRequest(null, null, null)));
        assertTrue(e.getMessage().contains("紐づいていません"));
    }

    @Test
    void createSignedPreviewUrl_マスター環境のサイトがproject_serviceに無ければ理由つきで拒否する() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 30L, null));
        when(siteService.getById(30L)).thenReturn(Optional.empty());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.createSignedPreviewUrl(1L, signedRequest(null, null, null)));
        assertTrue(e.getMessage().contains("紐づいていません"));
    }

    @Test
    void createSignedPreviewUrl_ローカル環境のsiteIdも対象にできる() {
        Project project = projectWithMaster("test", 30L, null);
        project.setLocalSiteId(29L);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        Site site = managedWordPressSite(29L, "local-site", "https://localhost/sites/local-site", "local-site");
        when(siteService.getById(29L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("local-site")).thenReturn(agentCredentials("local-site"));
        com.letsblog.publishing.cms.CmsAdapter cmsAdapter =
                org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.createSignedPreview(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.letsblog.publishing.cms.SignedPreview("https://x/?letsblog_preview=l", 2L));

        assertEquals(2L, service.createSignedPreviewUrl(1L, signedRequest(29L, null, null)).expiresAt());
    }
}
