package com.letsblog.api.service;

import com.letsblog.api.cms.CmsCredentials;
import com.letsblog.api.cms.CmsType;
import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.Site;
import com.letsblog.api.dto.ThemeCssResponse;
import com.letsblog.api.markdown.MarkdownRenderer;
import com.letsblog.api.repository.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
class ArticlePreviewServiceTest {

    @Mock
    private CustomTagRenderService customTagRenderService;

    @Mock
    private BlogCardTagRenderService blogCardTagRenderService;

    @Mock
    private AmazonTagRenderService amazonTagRenderService;

    @Mock
    private TocStyleRenderService tocStyleRenderService;

    @Mock
    private MarkdownRenderer markdownRenderer;

    @Mock
    private ProjectService projectService;

    @Mock
    private SiteRepository siteRepository;

    @Mock
    private SiteService siteService;

    private MockRestServiceServer server;
    private ArticlePreviewService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new ArticlePreviewService(
                customTagRenderService, blogCardTagRenderService, amazonTagRenderService, tocStyleRenderService,
                markdownRenderer, projectService, siteRepository, siteService, builder);
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

    private Site managedWordPressSite(Long id, String siteKey, String publicBaseUrl) {
        Site site = wordPressSite(id, publicBaseUrl);
        site.setSiteKey(siteKey);
        site.setManagedWordpress(true);
        return site;
    }

    @Test
    void renderHtml_カスタムタグ展開後にMarkdownをHTML変換する() {
        when(customTagRenderService.render("**bold**", 1L)).thenReturn("**bold** rendered");
        when(blogCardTagRenderService.render("**bold** rendered", 1L)).thenReturn("**bold** rendered");
        when(amazonTagRenderService.render("**bold** rendered", 1L)).thenReturn("**bold** rendered");
        when(tocStyleRenderService.render("**bold** rendered", 1L)).thenReturn("**bold** rendered");
        when(markdownRenderer.render("**bold** rendered")).thenReturn("<p><strong>bold</strong> rendered</p>");
        when(tocStyleRenderService.applyHtmlTemplate("<p><strong>bold</strong> rendered</p>", 1L))
                .thenReturn("<p><strong>bold</strong> rendered</p>");

        String html = service.renderHtml(1L, "**bold**");

        assertEquals("<p><strong>bold</strong> rendered</p>", html);
        verify(customTagRenderService).render("**bold**", 1L);
        verify(blogCardTagRenderService).render("**bold** rendered", 1L);
        verify(amazonTagRenderService).render("**bold** rendered", 1L);
        verify(tocStyleRenderService).render("**bold** rendered", 1L);
        verify(markdownRenderer).render("**bold** rendered");
        verify(tocStyleRenderService).applyHtmlTemplate("<p><strong>bold</strong> rendered</p>", 1L);
    }

    @Test
    void fetchMasterThemeCss_マスター環境にサイトが紐づいていない場合はavailableがfalse() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", null, null));

        ThemeCssResponse response = service.fetchMasterThemeCss(1L);

        assertFalse(response.available());
        assertTrue(response.reason().contains("紐づいていません"));
    }

    @Test
    void fetchMasterThemeCss_WordPress以外のCMSの場合はavailableがfalse() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        Site microCmsSite = new Site();
        microCmsSite.setId(10L);
        microCmsSite.setCmsType(CmsType.MICROCMS);
        when(siteRepository.findById(10L)).thenReturn(Optional.of(microCmsSite));

        ThemeCssResponse response = service.fetchMasterThemeCss(1L);

        assertFalse(response.available());
        assertTrue(response.reason().contains("WordPress以外"));
    }

    @Test
    void fetchMasterThemeCss_stylesheetリンクを取得し連結する() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));

        server.expect(requestTo("http://example.com"))
                .andRespond(withSuccess(
                        "<html><head>"
                        + "<link rel=\"stylesheet\" href=\"/style.css\">"
                        + "<link rel=\"preload\" href=\"/font.woff\">"
                        + "</head></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo("http://example.com/style.css"))
                .andRespond(withSuccess("body { color: red; }", MediaType.valueOf("text/css")));

        ThemeCssResponse response = service.fetchMasterThemeCss(1L);

        assertTrue(response.available());
        assertTrue(response.css().contains("body { color: red; }"));
        server.verify();
    }

    @Test
    void fetchMasterThemeCss_サイト接続失敗時はavailableがfalse() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));

        server.expect(requestTo("http://example.com")).andRespond(withServerError());

        ThemeCssResponse response = service.fetchMasterThemeCss(1L);

        assertFalse(response.available());
    }

    @Test
    void fetchMasterThemeCss_stylesheetリンクが見つからない場合はavailableがfalse() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));

        server.expect(requestTo("http://example.com"))
                .andRespond(withSuccess("<html><head></head></html>", MediaType.TEXT_HTML));

        ThemeCssResponse response = service.fetchMasterThemeCss(1L);

        assertFalse(response.available());
    }

    @Test
    void fetchThemeCss_siteId指定でそのサイトのCSSを取得する() {
        Project project = projectWithMaster("test", 10L, null);
        project.setLocalSiteId(20L);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteRepository.findById(20L)).thenReturn(Optional.of(wordPressSite(20L, "http://local.example.com")));

        server.expect(requestTo("http://local.example.com"))
                .andRespond(withSuccess(
                        "<html><head><link rel=\"stylesheet\" href=\"/local.css\"></head></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo("http://local.example.com/local.css"))
                .andRespond(withSuccess("body{color:red}", MediaType.valueOf("text/css")));

        ThemeCssResponse response = service.fetchThemeCss(1L, 20L);

        assertTrue(response.available());
        assertTrue(response.css().contains("body{color:red}"));
    }

    @Test
    void fetchThemeCss_managedサイトは内部URLでテーマCSSを取得する() {
        Project project = projectWithMaster("test", 10L, null);
        project.setLocalSiteId(30L);
        when(projectService.getProjectEntity(1L)).thenReturn(project);

        Site site = managedWordPressSite(30L, "local-site", "https://localhost/sites/local-site");
        when(siteRepository.findById(30L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("local-site")).thenReturn(
                new CmsCredentials.WordPressCredentials(
                        "http://wordpress/sites/local-site", "admin", "app-password"));

        server.expect(requestTo("http://wordpress/sites/local-site/"))
                .andRespond(withSuccess(
                        "<html><head><link rel=\"stylesheet\" "
                        + "href=\"https://localhost/sites/local-site/wp-content/style.css\"></head></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo("http://wordpress/sites/local-site/wp-content/style.css"))
                .andRespond(withSuccess("body { color: blue; }", MediaType.valueOf("text/css")));

        ThemeCssResponse response = service.fetchThemeCss(1L, 30L);

        assertTrue(response.available());
        assertTrue(response.css().contains("body { color: blue; }"));
        server.verify();
    }

    @Test
    void fetchThemeCss_managedサイトでも認証情報が取得できない場合は公開URLにフォールバックする() {
        Project project = projectWithMaster("test", 10L, null);
        project.setLocalSiteId(30L);
        when(projectService.getProjectEntity(1L)).thenReturn(project);

        Site site = managedWordPressSite(30L, "local-site", "http://public.example.com");
        when(siteRepository.findById(30L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("local-site")).thenThrow(new IllegalStateException("復号失敗"));

        server.expect(requestTo("http://public.example.com"))
                .andRespond(withSuccess(
                        "<html><head><link rel=\"stylesheet\" href=\"/style.css\"></head></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo("http://public.example.com/style.css"))
                .andRespond(withSuccess("body { color: green; }", MediaType.valueOf("text/css")));

        ThemeCssResponse response = service.fetchThemeCss(1L, 30L);

        assertTrue(response.available());
        assertTrue(response.css().contains("body { color: green; }"));
        server.verify();
    }

    @Test
    void fetchMasterThemeCss_ブラウザ相当のUser_Agentヘッダーを付与してリクエストする() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteRepository.findById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));

        server.expect(requestTo("http://example.com"))
                .andExpect(header("User-Agent", org.hamcrest.Matchers.containsString("Mozilla")))
                .andRespond(withSuccess(
                        "<html><head><link rel=\"stylesheet\" href=\"/style.css\"></head></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo("http://example.com/style.css"))
                .andExpect(header("User-Agent", org.hamcrest.Matchers.containsString("Mozilla")))
                .andRespond(withSuccess("body { color: red; }", MediaType.valueOf("text/css")));

        ThemeCssResponse response = service.fetchMasterThemeCss(1L);

        assertTrue(response.available());
        server.verify();
    }

    @Test
    void fetchThemeCss_プロジェクトに紐づかないサイトは取得を拒否する() {
        Project project = projectWithMaster("test", 10L, null);
        when(projectService.getProjectEntity(1L)).thenReturn(project);

        ThemeCssResponse response = service.fetchThemeCss(1L, 999L);

        assertFalse(response.available());
        assertTrue(response.reason().contains("このプロジェクトに紐づいていません"));
        // 紐づかないサイトはリポジトリ参照すら行わない(他プロジェクトのサイトを覗けないようにする)。
        verifyNoInteractions(siteRepository);
    }

    @Test
    void fetchThemeCss_siteIdがnullの場合はマスター環境を対象とする() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", null, null));

        ThemeCssResponse response = service.fetchThemeCss(1L, null);

        assertFalse(response.available());
        assertTrue(response.reason().contains("マスター環境"));
    }
}
