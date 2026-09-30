package com.letsblog.publishing.service;

import com.letsblog.publishing.client.ContentServiceClient;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.cms.ReferencePost;
import com.letsblog.publishing.cms.agent.WordPressAgentOperations;
import com.letsblog.publishing.cms.ssh.WordPressSshOperations;
import com.letsblog.publishing.domain.Project;
import com.letsblog.publishing.domain.Site;
import com.letsblog.publishing.dto.ThemeCssResponse;
import com.letsblog.publishing.dto.ThemeSkeletonResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
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

/**
 * publishing-serviceのArticlePreviewService(fetchThemeCss/renderSkeleton/renderRealPrivatePost/
 * deletePreviewPost)を検証する。legacy-apiから移設したテストをそのまま引き継いだもの
 * (issue #712、Epic #551 C6-6)。記事本文レンダリング(renderHtml)はcontent-serviceが持つため、
 * その振る舞いはcontent-service側のArticlePreviewServiceTestで検証する。テーマ骨格取得
 * (旧PreviewSkeletonFetcher)はcontent-serviceへの内部ブリッジ(ContentServiceClient)経由のため、
 * モックをそちらへ差し替えている。
 */
@ExtendWith(MockitoExtension.class)
class ArticlePreviewServiceTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private SiteService siteService;

    @Mock
    private ContentServiceClient contentServiceClient;

    @Mock
    private com.letsblog.publishing.cms.CmsAdapterFactory cmsAdapterFactory;

    @Mock
    private WordPressAgentOperations wordPressAgentOperations;

    @Mock
    private WordPressSshOperations wordPressSshOperations;

    private MockRestServiceServer server;
    private ArticlePreviewService service;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        // stylesheet取得は並列のため、リクエスト到着順は不定。順序は検証せず、期待した全リクエストが来たことだけを見る
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        service = new ArticlePreviewService(
                projectService, siteService, builder, contentServiceClient,
                cmsAdapterFactory, wordPressAgentOperations, wordPressSshOperations);
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

    /**
     * fetchThemeCssは、トップページのCSSに加えて投稿ページ限定のCSS(Issue #337)も
     * 補おうとするため、参照記事の有無をwp-json/wp/v2/postsへ問い合わせる。この問い合わせ自体を
     * 検証しないテストでは、参照記事なしとして早期終了させるためにこのモックを併せて登録する。
     */
    private void expectNoReferencePostForCssFallback(String baseEndingWithSlash) {
        server.expect(requestTo(
                        baseEndingWithSlash + "wp-json/wp/v2/posts?per_page=1&orderby=date&order=desc&_fields=id,link"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
    }

    @Test
    void fetchMasterThemeCss_マスター環境にサイトが紐づいていない場合はavailableがfalse() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", null, null));

        ThemeCssResponse response = service.fetchMasterThemeCss(1L);

        assertFalse(response.available());
        assertTrue(response.reason().contains("紐づいていません"));
    }

    @Test
    void fetchMasterThemeCss_stylesheetリンクを取得し連結する() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));

        server.expect(requestTo("http://example.com"))
                .andRespond(withSuccess(
                        "<html><head>"
                        + "<link rel=\"stylesheet\" href=\"/style.css\">"
                        + "<link rel=\"preload\" href=\"/font.woff\">"
                        + "</head></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo("http://example.com/style.css"))
                .andRespond(withSuccess("body { color: red; }", MediaType.valueOf("text/css")));
        expectNoReferencePostForCssFallback("http://example.com/");

        ThemeCssResponse response = service.fetchMasterThemeCss(1L);

        assertTrue(response.available());
        assertTrue(response.css().contains("body { color: red; }"));
        server.verify();
    }

    @Test
    void fetchMasterThemeCss_サイト接続失敗時はavailableがfalse() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));

        server.expect(requestTo("http://example.com")).andRespond(withServerError());

        ThemeCssResponse response = service.fetchMasterThemeCss(1L);

        assertFalse(response.available());
    }

    @Test
    void fetchMasterThemeCss_stylesheetリンクが見つからない場合はavailableがfalse() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));

        server.expect(requestTo("http://example.com"))
                .andRespond(withSuccess("<html><head></head></html>", MediaType.TEXT_HTML));

        ThemeCssResponse response = service.fetchMasterThemeCss(1L);

        assertFalse(response.available());
    }

    @Test
    void fetchMasterThemeCss_上限を超えるstylesheetは丸ごとスキップし他のCSSを壊さない() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));

        String hugeCss = "a".repeat(3_000_100);

        server.expect(requestTo("http://example.com"))
                .andRespond(withSuccess(
                        "<html><head>"
                        + "<link rel=\"stylesheet\" href=\"/small.css\">"
                        + "<link rel=\"stylesheet\" href=\"/huge.css\">"
                        + "</head></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo("http://example.com/small.css"))
                .andRespond(withSuccess("body { color: red; }", MediaType.valueOf("text/css")));
        server.expect(requestTo("http://example.com/huge.css"))
                .andRespond(withSuccess(hugeCss, MediaType.valueOf("text/css")));
        expectNoReferencePostForCssFallback("http://example.com/");

        ThemeCssResponse response = service.fetchMasterThemeCss(1L);

        assertTrue(response.available());
        assertTrue(response.css().contains("body { color: red; }"));
        assertFalse(response.css().contains(hugeCss.substring(0, 100)));
    }

    @Test
    void fetchMasterThemeCss_preloadAsStyleのstylesheetも収集する() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));

        server.expect(requestTo("http://example.com"))
                .andRespond(withSuccess(
                        "<html><head>"
                        + "<link rel=\"preload\" as=\"style\" href=\"/optimized.css\" "
                        + "onload=\"this.rel='stylesheet'\">"
                        + "</head></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo("http://example.com/optimized.css"))
                .andRespond(withSuccess("body { color: purple; }", MediaType.valueOf("text/css")));
        expectNoReferencePostForCssFallback("http://example.com/");

        ThemeCssResponse response = service.fetchMasterThemeCss(1L);

        assertTrue(response.available());
        assertTrue(response.css().contains("body { color: purple; }"));
        server.verify();
    }

    @Test
    void fetchMasterThemeCss_インラインstyleブロックも収集する() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));

        server.expect(requestTo("http://example.com"))
                .andRespond(withSuccess(
                        "<html><head>"
                        + "<style id=\"critical-css\">.hero { color: orange; }</style>"
                        + "</head></html>",
                        MediaType.TEXT_HTML));
        expectNoReferencePostForCssFallback("http://example.com/");

        ThemeCssResponse response = service.fetchMasterThemeCss(1L);

        assertTrue(response.available());
        assertTrue(response.css().contains(".hero { color: orange; }"));
    }

    @Test
    void fetchMasterThemeCss_stylesheet内のurl相対参照を絶対URLへ書き換える() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));

        server.expect(requestTo("http://example.com"))
                .andRespond(withSuccess(
                        "<html><head><link rel=\"stylesheet\" href=\"/theme/style.css\"></head></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo("http://example.com/theme/style.css"))
                .andRespond(withSuccess(
                        "@font-face { src: url(fonts/foo.woff2); }",
                        MediaType.valueOf("text/css")));
        expectNoReferencePostForCssFallback("http://example.com/");

        ThemeCssResponse response = service.fetchMasterThemeCss(1L);

        assertTrue(response.available());
        assertTrue(response.css().contains("url(http://example.com/theme/fonts/foo.woff2)"));
    }

    @Test
    void fetchThemeCss_siteId指定でそのサイトのCSSを取得する() {
        Project project = projectWithMaster("test", 10L, null);
        project.setLocalSiteId(20L);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        when(siteService.getById(20L)).thenReturn(Optional.of(wordPressSite(20L, "http://local.example.com")));

        server.expect(requestTo("http://local.example.com"))
                .andRespond(withSuccess(
                        "<html><head><link rel=\"stylesheet\" href=\"/local.css\"></head></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo("http://local.example.com/local.css"))
                .andRespond(withSuccess("body{color:red}", MediaType.valueOf("text/css")));
        expectNoReferencePostForCssFallback("http://local.example.com/");

        ThemeCssResponse response = service.fetchThemeCss(1L, 20L);

        assertTrue(response.available());
        assertTrue(response.css().contains("body{color:red}"));
    }

    @Test
    void fetchThemeCss_managedサイトはwpSlugから組み立てた内部URLでテーマCSSを取得する() {
        Project project = projectWithMaster("test", 10L, null);
        project.setLocalSiteId(30L);
        when(projectService.getProjectEntity(1L)).thenReturn(project);

        Site site = managedWordPressSite(30L, "local-site", "https://localhost/sites/local-site", "local-site");
        when(siteService.getById(30L)).thenReturn(Optional.of(site));

        server.expect(requestTo("http://wordpress/sites/local-site/"))
                .andRespond(withSuccess(
                        "<html><head><link rel=\"stylesheet\" "
                        + "href=\"https://localhost/sites/local-site/wp-content/style.css\"></head></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo("http://wordpress/sites/local-site/wp-content/style.css"))
                .andRespond(withSuccess("body { color: blue; }", MediaType.valueOf("text/css")));
        expectNoReferencePostForCssFallback("http://wordpress/sites/local-site/");

        ThemeCssResponse response = service.fetchThemeCss(1L, 30L);

        assertTrue(response.available());
        assertTrue(response.css().contains("body { color: blue; }"));
        server.verify();
    }

    @Test
    void fetchThemeCss_managedサイトでもwpSlug未設定の場合は公開URLにフォールバックする() {
        Project project = projectWithMaster("test", 10L, null);
        project.setLocalSiteId(30L);
        when(projectService.getProjectEntity(1L)).thenReturn(project);

        Site site = managedWordPressSite(30L, "local-site", "http://public.example.com", null);
        when(siteService.getById(30L)).thenReturn(Optional.of(site));

        server.expect(requestTo("http://public.example.com"))
                .andRespond(withSuccess(
                        "<html><head><link rel=\"stylesheet\" href=\"/style.css\"></head></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo("http://public.example.com/style.css"))
                .andRespond(withSuccess("body { color: green; }", MediaType.valueOf("text/css")));
        expectNoReferencePostForCssFallback("http://public.example.com/");

        ThemeCssResponse response = service.fetchThemeCss(1L, 30L);

        assertTrue(response.available());
        assertTrue(response.css().contains("body { color: green; }"));
        server.verify();
    }

    @Test
    void fetchMasterThemeCss_ブラウザ相当のUser_Agentヘッダーを付与してリクエストする() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));

        server.expect(requestTo("http://example.com"))
                .andExpect(header("User-Agent", org.hamcrest.Matchers.containsString("Mozilla")))
                .andRespond(withSuccess(
                        "<html><head><link rel=\"stylesheet\" href=\"/style.css\"></head></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo("http://example.com/style.css"))
                .andExpect(header("User-Agent", org.hamcrest.Matchers.containsString("Mozilla")))
                .andRespond(withSuccess("body { color: red; }", MediaType.valueOf("text/css")));
        expectNoReferencePostForCssFallback("http://example.com/");

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
        // 紐づかないサイトは参照すら行わない(他プロジェクトのサイトを覗けないようにする)。
        verifyNoInteractions(siteService);
    }

    @Test
    void fetchThemeCss_siteIdがnullの場合はマスター環境を対象とする() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", null, null));

        ThemeCssResponse response = service.fetchThemeCss(1L, null);

        assertFalse(response.available());
        assertTrue(response.reason().contains("マスター環境"));
    }

    @Test
    void fetchThemeCss_is_single限定でトップページには無い投稿ページ限定のstylesheetもマージする() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));

        server.expect(requestTo("http://example.com"))
                .andRespond(withSuccess(
                        "<html><head><link rel=\"stylesheet\" href=\"/theme.css\"></head></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo("http://example.com/theme.css"))
                .andRespond(withSuccess("body { color: red; }", MediaType.valueOf("text/css")));
        server.expect(requestTo("http://example.com/wp-json/wp/v2/posts?per_page=1&orderby=date&order=desc"
                        + "&_fields=id,link"))
                .andRespond(withSuccess(
                        "[{\"id\":1,\"link\":\"http://example.com/hello-world/\"}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://example.com/hello-world/"))
                .andRespond(withSuccess(
                        "<html><head><link rel=\"stylesheet\" href=\"/custom-tags.css\"></head></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo("http://example.com/custom-tags.css"))
                .andRespond(withSuccess(".custom-tag { color: hotpink; }", MediaType.valueOf("text/css")));

        ThemeCssResponse response = service.fetchMasterThemeCss(1L);

        assertTrue(response.available());
        assertTrue(response.css().contains("body { color: red; }"));
        assertTrue(response.css().contains(".custom-tag { color: hotpink; }"));
        server.verify();
    }

    @Test
    void fetchThemeCss_参照記事が存在しない場合はトップページのCSSのみ返す() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));

        server.expect(requestTo("http://example.com"))
                .andRespond(withSuccess(
                        "<html><head><link rel=\"stylesheet\" href=\"/theme.css\"></head></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo("http://example.com/theme.css"))
                .andRespond(withSuccess("body { color: red; }", MediaType.valueOf("text/css")));
        server.expect(requestTo("http://example.com/wp-json/wp/v2/posts?per_page=1&orderby=date&order=desc"
                        + "&_fields=id,link"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        ThemeCssResponse response = service.fetchMasterThemeCss(1L);

        assertTrue(response.available());
        assertEquals("/* http://example.com/theme.css */\nbody { color: red; }\n", response.css());
        server.verify();
    }

    private com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials agentCredentials(String wpSlug) {
        return new com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials(
                "https://localhost/sites/" + wpSlug, "admin",
                "AGENT", null, null, null, null, null, null, wpSlug);
    }

    /**
     * managedサイト(agent transport)は、投稿ページ限定CSSの参照記事取得もREST(wp-json)ではなく
     * provision-agentのwp-cli経由(WordPressAgentOperations)に切り替わる(issue #519)。
     */
    @Test
    void fetchThemeCss_managedサイトは投稿ページ限定CSSの参照記事をwp_cli経由で取得する() {
        Project project = projectWithMaster("test", 10L, null);
        project.setLocalSiteId(30L);
        when(projectService.getProjectEntity(1L)).thenReturn(project);

        Site site = managedWordPressSite(30L, "local-site", "https://localhost/sites/local-site", "local-site");
        when(siteService.getById(30L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("local-site")).thenReturn(agentCredentials("local-site"));

        server.expect(requestTo("http://wordpress/sites/local-site/"))
                .andRespond(withSuccess(
                        "<html><head><link rel=\"stylesheet\" "
                        + "href=\"https://localhost/sites/local-site/wp-content/theme.css\"></head></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo("http://wordpress/sites/local-site/wp-content/theme.css"))
                .andRespond(withSuccess("body { color: red; }", MediaType.valueOf("text/css")));
        when(wordPressAgentOperations.getLatestPost(agentCredentials("local-site")))
                .thenReturn(Optional.of(new ReferencePost(
                        "1", "https://localhost/sites/local-site/hello-world/", "Hello World", "<p>Hi</p>")));
        server.expect(requestTo("http://wordpress/sites/local-site/hello-world/"))
                .andRespond(withSuccess(
                        "<html><head><link rel=\"stylesheet\" "
                        + "href=\"https://localhost/sites/local-site/wp-content/post.css\"></head></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo("http://wordpress/sites/local-site/wp-content/post.css"))
                .andRespond(withSuccess(".post { color: hotpink; }", MediaType.valueOf("text/css")));

        ThemeCssResponse response = service.fetchThemeCss(1L, 30L);

        assertTrue(response.available());
        assertTrue(response.css().contains("body { color: red; }"));
        assertTrue(response.css().contains(".post { color: hotpink; }"));
        // REST(wp-json)は一切叩かないこと(叩けばMockRestServiceServerが未登録リクエストとして失敗する)
        server.verify();
    }

    /**
     * SSH管理サイトも、投稿ページ限定CSSの参照記事取得がREST(wp-json)ではなくwp-cli経由
     * (WordPressSshOperations)に切り替わる(issue #519)。
     */
    @Test
    void fetchThemeCss_SSH管理サイトは投稿ページ限定CSSの参照記事をwp_cli経由で取得する() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        Site site = wordPressSite(10L, "http://production.example.com");
        site.setSiteKey("production-site");
        when(siteService.getById(10L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("production-site")).thenReturn(sshCredentials());

        server.expect(requestTo("http://production.example.com"))
                .andRespond(withSuccess(
                        "<html><head><link rel=\"stylesheet\" href=\"/theme.css\"></head></html>",
                        MediaType.TEXT_HTML));
        server.expect(requestTo("http://production.example.com/theme.css"))
                .andRespond(withSuccess("body { color: red; }", MediaType.valueOf("text/css")));
        when(wordPressSshOperations.getLatestPost(sshCredentials())).thenReturn(Optional.empty());

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertTrue(response.available());
        assertEquals("/* http://production.example.com/theme.css */\nbody { color: red; }\n", response.css());
        server.verify();
    }

    private ContentServiceClient.ThemeSkeletonBridgeResponse bridged(
            String html, boolean available, String reason, boolean eyecatchSpliced, String css) {
        return new ContentServiceClient.ThemeSkeletonBridgeResponse(
                html, available, reason, eyecatchSpliced, css, java.util.List.of());
    }

    private ContentServiceClient.ThemeSkeletonBridgeResponse bridgedWithUnreadable(
            String css, java.util.List<String> unreadableStylesheets) {
        return new ContentServiceClient.ThemeSkeletonBridgeResponse(
                "<article>spliced</article>", true, null, true, css, unreadableStylesheets);
    }

    /** 参照記事1件を返し、スクレイプ&amp;スプライス経路(bridge fetchAndSplice)で骨格を取得させる。 */
    private ThemeSkeletonResponse renderSkeletonScrapeWith(
            ContentServiceClient.ThemeSkeletonBridgeResponse bridgeResult) {
        return renderSkeletonScrapeWith(bridgeResult, () -> { });
    }

    /** expectStylesheetsは、参照記事のREST取得の期待より後に登録する(順序は検証しない設定だが、REST取得の期待を先に置く慣習を保つ)。 */
    private ThemeSkeletonResponse renderSkeletonScrapeWith(
            ContentServiceClient.ThemeSkeletonBridgeResponse bridgeResult, Runnable expectStylesheets) {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));
        server.expect(requestTo("http://example.com/wp-json/wp/v2/posts?per_page=1&orderby=date&order=desc"
                        + "&_fields=id,link,title,content"))
                .andRespond(withSuccess(
                        "[{\"id\":1,\"link\":\"http://example.com/hello-world/\","
                        + "\"title\":{\"rendered\":\"Hello World\"},\"content\":{\"rendered\":\"<p>Hi</p>\"}}]",
                        MediaType.APPLICATION_JSON));
        when(contentServiceClient.fetchAndSplice(
                "http://example.com/hello-world/", "Hello World", "<p>Hi</p>", "新タイトル", "<p>新本文</p>", null))
                .thenReturn(bridgeResult);
        expectStylesheets.run();
        return service.renderSkeleton(1L, null, "新タイトル", "<p>新本文</p>", null, null, null, null, null);
    }

    // ---- issue #1370: 骨格プレビューで読めなかったstylesheetの補完 ----

    @Test
    void renderSkeleton_読めなかったstylesheetをHTTPで取得して骨格のCSSへ補完する() {
        ThemeSkeletonResponse response = renderSkeletonScrapeWith(
                bridgedWithUnreadable("body{margin:0}", java.util.List.of("http://cdn.example.com/a.css")),
                () -> server.expect(requestTo("http://cdn.example.com/a.css"))
                        .andRespond(withSuccess("a{color:red}", MediaType.valueOf("text/css"))));

        assertTrue(response.available());
        assertTrue(response.css().startsWith("body{margin:0}"));
        assertTrue(response.css().contains("/* http://cdn.example.com/a.css */\na{color:red}"));
        server.verify();
    }

    @Test
    void renderSkeleton_同じhrefは一度だけ取得し二重に連結しない() {
        ThemeSkeletonResponse response = renderSkeletonScrapeWith(bridgedWithUnreadable(
                "body{margin:0}",
                java.util.List.of("http://cdn.example.com/a.css", "http://cdn.example.com/a.css")),
                () -> server.expect(requestTo("http://cdn.example.com/a.css"))
                        .andRespond(withSuccess("a{color:red}", MediaType.valueOf("text/css"))));

        assertEquals(1, response.css().split("a\\{color:red\\}", -1).length - 1);
        server.verify();
    }

    @Test
    void renderSkeleton_既にCSSに含まれるhrefは取得しない() {
        // 期待を登録しない: 取得しようとすればMockRestServiceServerが失敗する
        String base = "/* http://cdn.example.com/a.css */\na{color:red}";

        ThemeSkeletonResponse response = renderSkeletonScrapeWith(
                bridgedWithUnreadable(base, java.util.List.of("http://cdn.example.com/a.css")));

        assertEquals(base, response.css());
        server.verify();
    }

    @Test
    void renderSkeleton_補完の取得に失敗しても骨格は表示され失敗したhrefがログに残る() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ArticlePreviewService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ThemeSkeletonResponse response;
        try {
            response = renderSkeletonScrapeWith(bridgedWithUnreadable(
                    "body{margin:0}",
                    java.util.List.of("http://cdn.example.com/broken.css", "http://cdn.example.com/ok.css")),
                    () -> {
                        server.expect(requestTo("http://cdn.example.com/broken.css"))
                                .andRespond(withServerError());
                        server.expect(requestTo("http://cdn.example.com/ok.css"))
                                .andRespond(withSuccess("ok{color:blue}", MediaType.valueOf("text/css")));
                    });
        } finally {
            logger.detachAppender(appender);
        }

        assertTrue(response.available());
        assertEquals("<article>spliced</article>", response.html());
        assertTrue(response.css().contains("ok{color:blue}"));
        assertFalse(response.css().contains("broken.css"));
        assertTrue(appender.list.stream().anyMatch(e ->
                e.getLevel() == ch.qos.logback.classic.Level.WARN
                        && e.getFormattedMessage().contains("http://cdn.example.com/broken.css")));
    }

    @Test
    void renderSkeleton_空本文のstylesheetは連結しない() {
        ThemeSkeletonResponse response = renderSkeletonScrapeWith(
                bridgedWithUnreadable("body{margin:0}", java.util.List.of("http://cdn.example.com/empty.css")),
                () -> server.expect(requestTo("http://cdn.example.com/empty.css"))
                        .andRespond(withSuccess("  ", MediaType.valueOf("text/css"))));

        assertEquals("body{margin:0}", response.css());
    }

    @Test
    void renderSkeleton_補完後のCSSがMAX_CSS_LENGTHを超えるstylesheetは丸ごとスキップし超えない() {
        String base = "x".repeat(3_000_000 - 50);
        ThemeSkeletonResponse response = renderSkeletonScrapeWith(bridgedWithUnreadable(
                base, java.util.List.of("http://cdn.example.com/big.css", "http://cdn.example.com/small.css")),
                () -> {
                    server.expect(requestTo("http://cdn.example.com/big.css"))
                            .andRespond(withSuccess("b".repeat(200), MediaType.valueOf("text/css")));
                    server.expect(requestTo("http://cdn.example.com/small.css"))
                            .andRespond(withSuccess("s{}", MediaType.valueOf("text/css")));
                });

        assertTrue(response.css().length() <= 3_000_000);
        assertFalse(response.css().contains("bbbb"));
        assertTrue(response.css().contains("s{}"));
    }

    // ---- issue #1473: stylesheet取得の並列化 ----

    private static final String POSTS_URL = "http://example.com/wp-json/wp/v2/posts?per_page=1&orderby=date"
            + "&order=desc&_fields=id,link,title,content";
    private static final String POSTS_JSON = "[{\"id\":1,\"link\":\"http://example.com/hello-world/\","
            + "\"title\":{\"rendered\":\"Hello World\"},\"content\":{\"rendered\":\"<p>Hi</p>\"}}]";

    /** 指定URLごとに人為的な遅延(ms)を入れて応答するHTTP。MockRestServiceServerは応答生成を直列化するため使わない。 */
    private ArticlePreviewService serviceWithDelayedHttp(
            java.util.Map<String, Long> delayMillis, java.util.Map<String, String> bodies) {
        return serviceWithDelayedHttp(delayMillis, bodies, java.time.Duration.ofSeconds(20), null);
    }

    private final java.util.List<String> requestedUrls = new java.util.concurrent.CopyOnWriteArrayList<>();

    private ArticlePreviewService serviceWithDelayedHttp(
            java.util.Map<String, Long> delayMillis, java.util.Map<String, String> bodies,
            java.time.Duration fetchTimeout, String interruptCallerOnUrl) {
        return serviceWithDelayedHttp(delayMillis, bodies, fetchTimeout, interruptCallerOnUrl,
                com.letsblog.publishing.config.StylesheetFetchExecutorConfig.newExecutor());
    }

    private ArticlePreviewService serviceWithDelayedHttp(
            java.util.Map<String, Long> delayMillis, java.util.Map<String, String> bodies,
            java.time.Duration fetchTimeout, String interruptCallerOnUrl,
            java.util.concurrent.ExecutorService executor) {
        Thread caller = Thread.currentThread();
        org.springframework.http.client.ClientHttpRequestFactory factory = (uri, method) ->
                new org.springframework.mock.http.client.MockClientHttpRequest(method, uri) {
                    @Override
                    protected org.springframework.http.client.ClientHttpResponse executeInternal() {
                        String url = uri.toString();
                        requestedUrls.add(url);
                        if (url.equals(interruptCallerOnUrl)) {
                            caller.interrupt();
                        }
                        try {
                            Thread.sleep(delayMillis.getOrDefault(url, 0L));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        String body = url.equals(POSTS_URL) ? POSTS_JSON : bodies.get(url);
                        if (body == null) {
                            return new org.springframework.mock.http.client.MockClientHttpResponse(
                                    new byte[0], HttpStatus.INTERNAL_SERVER_ERROR);
                        }
                        org.springframework.mock.http.client.MockClientHttpResponse response =
                                new org.springframework.mock.http.client.MockClientHttpResponse(
                                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8), HttpStatus.OK);
                        response.getHeaders().setContentType(url.equals(POSTS_URL)
                                ? MediaType.APPLICATION_JSON : MediaType.valueOf("text/css;charset=UTF-8"));
                        return response;
                    }
                };
        return new ArticlePreviewService(
                projectService, siteService, RestClient.builder().requestFactory(factory), contentServiceClient,
                cmsAdapterFactory, wordPressAgentOperations, wordPressSshOperations,
                executor, fetchTimeout);
    }

    private ThemeSkeletonResponse skeletonWith(ArticlePreviewService svc, java.util.List<String> unreadable) {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));
        when(contentServiceClient.fetchAndSplice(
                "http://example.com/hello-world/", "Hello World", "<p>Hi</p>", "新タイトル", "<p>新本文</p>", null))
                .thenReturn(bridgedWithUnreadable("body{margin:0}", unreadable));
        return svc.renderSkeleton(1L, null, "新タイトル", "<p>新本文</p>", null, null, null, null, null);
    }

    @Test
    void renderSkeleton_複数のstylesheetは並列に取得され所要時間が最長1本分に近い() {
        java.util.List<String> urls = java.util.List.of("http://cdn.example.com/a.css",
                "http://cdn.example.com/b.css", "http://cdn.example.com/c.css", "http://cdn.example.com/d.css");
        java.util.Map<String, Long> delays = new java.util.HashMap<>();
        java.util.Map<String, String> bodies = new java.util.HashMap<>();
        for (String url : urls) {
            delays.put(url, 500L);
            bodies.put(url, "x{}");
        }
        ArticlePreviewService svc = serviceWithDelayedHttp(delays, bodies);

        long start = System.nanoTime();
        ThemeSkeletonResponse response = skeletonWith(svc, urls);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(response.css().contains("/* http://cdn.example.com/d.css */"));
        // 直列なら4本x500ms=2000ms以上。並列なら最長1本(500ms)+αに収まる。
        assertTrue(elapsedMillis < 1200, "elapsed=" + elapsedMillis + "ms は並列取得では説明できない");
    }

    @Test
    void renderSkeleton_取得の完了順が入力順と逆でも連結は入力順のまま() {
        java.util.Map<String, Long> delays = java.util.Map.of(
                "http://cdn.example.com/a.css", 400L,
                "http://cdn.example.com/b.css", 200L,
                "http://cdn.example.com/c.css", 0L);
        java.util.Map<String, String> bodies = java.util.Map.of(
                "http://cdn.example.com/a.css", "a{}",
                "http://cdn.example.com/b.css", "b{}",
                "http://cdn.example.com/c.css", "c{}");
        ArticlePreviewService svc = serviceWithDelayedHttp(delays, bodies);

        ThemeSkeletonResponse response = skeletonWith(svc, java.util.List.of(
                "http://cdn.example.com/a.css", "http://cdn.example.com/b.css", "http://cdn.example.com/c.css"));

        assertEquals("body{margin:0}\n"
                + "/* http://cdn.example.com/a.css */\na{}\n\n"
                + "/* http://cdn.example.com/b.css */\nb{}\n\n"
                + "/* http://cdn.example.com/c.css */\nc{}\n", response.css());
    }

    @Test
    void renderSkeleton_並列取得でも一部の失敗は他のstylesheetの連結を妨げない() {
        java.util.Map<String, String> bodies = java.util.Map.of(
                "http://cdn.example.com/a.css", "a{}",
                "http://cdn.example.com/c.css", "c{}");
        ArticlePreviewService svc = serviceWithDelayedHttp(java.util.Map.of("http://cdn.example.com/a.css", 100L), bodies);

        ThemeSkeletonResponse response = skeletonWith(svc, java.util.List.of(
                "http://cdn.example.com/a.css", "http://cdn.example.com/broken.css", "http://cdn.example.com/c.css"));

        assertEquals("body{margin:0}\n"
                + "/* http://cdn.example.com/a.css */\na{}\n\n"
                + "/* http://cdn.example.com/c.css */\nc{}\n", response.css());
    }

    @Test
    void renderSkeleton_並列取得でもMAX_CSS_LENGTH超過の打ち切りは連結順で行われ警告ログは従来と同じ() {
        String big = "b".repeat(200);
        java.util.Map<String, String> bodies = java.util.Map.of(
                "http://cdn.example.com/big.css", big,
                "http://cdn.example.com/small.css", "s{}");
        // 先頭(big)を最も遅くして、完了順と連結順を食い違わせる
        ArticlePreviewService svc = serviceWithDelayedHttp(
                java.util.Map.of("http://cdn.example.com/big.css", 300L), bodies);
        String base = "x".repeat(3_000_000 - 50);
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));
        when(contentServiceClient.fetchAndSplice(
                "http://example.com/hello-world/", "Hello World", "<p>Hi</p>", "新タイトル", "<p>新本文</p>", null))
                .thenReturn(bridgedWithUnreadable(base, java.util.List.of(
                        "http://cdn.example.com/big.css", "http://cdn.example.com/small.css")));
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ArticlePreviewService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ThemeSkeletonResponse response;
        try {
            response = svc.renderSkeleton(1L, null, "新タイトル", "<p>新本文</p>", null, null, null, null, null);
        } finally {
            logger.detachAppender(appender);
        }

        assertFalse(response.css().contains("bbbb"));
        assertTrue(response.css().endsWith("/* http://cdn.example.com/small.css */\ns{}\n"));
        assertEquals(1, appender.list.stream().filter(e ->
                e.getLevel() == ch.qos.logback.classic.Level.WARN
                        && e.getFormattedMessage().contains("MAX_CSS_LENGTH")
                        && e.getFormattedMessage().contains("http://cdn.example.com/big.css")).count());
    }

    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captureLogs() {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ArticlePreviewService.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void stopCapturing(ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> a) {
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ArticlePreviewService.class))
                .detachAppender(a);
    }

    @Test
    void renderSkeleton_応答しないstylesheetはタイムアウトで読み飛ばし他は連結され全体は待ち続けない() {
        java.util.Map<String, Long> delays = java.util.Map.of("http://cdn.example.com/hang.css", 5000L);
        java.util.Map<String, String> bodies = java.util.Map.of(
                "http://cdn.example.com/a.css", "a{}",
                "http://cdn.example.com/hang.css", "h{}",
                "http://cdn.example.com/c.css", "c{}");
        ArticlePreviewService svc = serviceWithDelayedHttp(
                delays, bodies, java.time.Duration.ofMillis(300), null);
        var appender = captureLogs();
        ThemeSkeletonResponse response;
        long start = System.nanoTime();
        try {
            response = skeletonWith(svc, java.util.List.of(
                    "http://cdn.example.com/a.css", "http://cdn.example.com/hang.css",
                    "http://cdn.example.com/c.css"));
        } finally {
            stopCapturing(appender);
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < 3000, "elapsed=" + elapsedMillis + "ms");
        assertEquals("body{margin:0}\n"
                + "/* http://cdn.example.com/a.css */\na{}\n\n"
                + "/* http://cdn.example.com/c.css */\nc{}\n", response.css());
        assertTrue(appender.list.stream().anyMatch(e ->
                e.getLevel() == ch.qos.logback.classic.Level.WARN
                        && e.getFormattedMessage().contains("http://cdn.example.com/hang.css")));
    }

    @Test
    void renderSkeleton_取得失敗のログには並列実行の包み例外ではなく元の例外が載る() {
        ArticlePreviewService svc = serviceWithDelayedHttp(java.util.Map.of(), java.util.Map.of());
        var appender = captureLogs();
        try {
            skeletonWith(svc, java.util.List.of("http://cdn.example.com/broken.css"));
        } finally {
            stopCapturing(appender);
        }

        var failure = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("http://cdn.example.com/broken.css"))
                .findFirst().orElseThrow();
        assertTrue(failure.getThrowableProxy().getClassName().contains("InternalServerError"),
                failure.getThrowableProxy().getClassName());
    }

    @Test
    void renderSkeleton_待機中に割り込まれても割り込み状態を保ち骨格は返る() {
        ArticlePreviewService svc = serviceWithDelayedHttp(
                java.util.Map.of("http://cdn.example.com/a.css", 300L),
                java.util.Map.of("http://cdn.example.com/a.css", "a{}"),
                java.time.Duration.ofSeconds(20), "http://cdn.example.com/a.css");
        boolean stillInterrupted;
        ThemeSkeletonResponse response;
        try {
            response = skeletonWith(svc, java.util.List.of("http://cdn.example.com/a.css"));
        } finally {
            stillInterrupted = Thread.interrupted();
        }

        assertTrue(stillInterrupted);
        assertEquals("body{margin:0}", response.css());
    }

    @Test
    void renderSkeleton_割り込まれたら未着手の残りの取得を取り消して待機をやめる() {
        java.util.concurrent.ExecutorService single = java.util.concurrent.Executors.newFixedThreadPool(1);
        try {
            ArticlePreviewService svc = serviceWithDelayedHttp(
                    java.util.Map.of("http://cdn.example.com/a.css", 300L),
                    java.util.Map.of("http://cdn.example.com/a.css", "a{}",
                            "http://cdn.example.com/b.css", "b{}", "http://cdn.example.com/c.css", "c{}"),
                    java.time.Duration.ofSeconds(20), "http://cdn.example.com/a.css", single);
            try {
                skeletonWith(svc, java.util.List.of("http://cdn.example.com/a.css",
                        "http://cdn.example.com/b.css", "http://cdn.example.com/c.css"));
            } finally {
                Thread.interrupted();
            }
            // 1本のワーカーがaを終えた後、取り消されたb/cのloaderは走らない
            single.submit(() -> { }).get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            single.shutdownNow();
        }

        assertFalse(requestedUrls.contains("http://cdn.example.com/b.css"), requestedUrls.toString());
        assertFalse(requestedUrls.contains("http://cdn.example.com/c.css"), requestedUrls.toString());
    }

    @Test
    void renderSkeleton_応答しない相手を読み取りタイムアウトで切り共有プールのワーカーを解放する() throws Exception {
        com.sun.net.httpserver.HttpServer hangServer =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.ExecutorService serverThreads = java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        hangServer.setExecutor(serverThreads);
        hangServer.createContext("/hang.css", exchange -> {
            try {
                Thread.sleep(15_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        hangServer.start();
        String hangUrl = "http://127.0.0.1:" + hangServer.getAddress().getPort() + "/hang.css";
        java.util.concurrent.ExecutorService single = java.util.concurrent.Executors.newFixedThreadPool(1);
        try {
            when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
            when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));
            when(contentServiceClient.fetchAndSplice(
                    "http://example.com/hello-world/", "Hello World", "<p>Hi</p>", "新タイトル", "<p>新本文</p>", null))
                    .thenReturn(bridgedWithUnreadable("body{margin:0}", java.util.List.of(hangUrl)));
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer.bindTo(builder).build()
                    .expect(requestTo(POSTS_URL))
                    .andRespond(withSuccess(POSTS_JSON, MediaType.APPLICATION_JSON));
            ArticlePreviewService svc = new ArticlePreviewService(
                    projectService, siteService, builder, contentServiceClient, cmsAdapterFactory, wordPressAgentOperations, wordPressSshOperations,
                    single, java.time.Duration.ofMillis(200),
                    ArticlePreviewService.stylesheetRequestFactory(
                            java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(600)));

            ThemeSkeletonResponse response =
                    svc.renderSkeleton(1L, null, "新タイトル", "<p>新本文</p>", null, null, null, null, null);
            assertEquals("body{margin:0}", response.css());

            // 待機は打ち切られたが、読み取りタイムアウトでワーカーが解放され、次の取得を受け付けられる
            String next = single.submit(() -> "released").get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("released", next);
        } finally {
            single.shutdownNow();
            hangServer.stop(0);
            serverThreads.shutdownNow();
        }
    }

    @Test
    void renderSkeleton_stylesheetのhttpからhttpsへのリダイレクトに追従して移動先のCSSを補完する() throws Exception {
        // 自己署名証明書のhttpsサーバ(移動先)。JDK HttpClientは既定のSSLContextを構築時に取り込むので、
        // ファクトリを作る前に差し替え、終わったら戻す。
        java.security.KeyStore keyStore = java.security.KeyStore.getInstance("PKCS12");
        try (java.io.InputStream in = getClass().getResourceAsStream("/redirect-test.p12")) {
            keyStore.load(in, "changeit".toCharArray());
        }
        javax.net.ssl.KeyManagerFactory kmf =
                javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "changeit".toCharArray());
        javax.net.ssl.TrustManagerFactory tmf =
                javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(keyStore);
        javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        javax.net.ssl.SSLContext originalContext = javax.net.ssl.SSLContext.getDefault();

        com.sun.net.httpserver.HttpsServer httpsServer =
                com.sun.net.httpserver.HttpsServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        httpsServer.setHttpsConfigurator(new com.sun.net.httpserver.HttpsConfigurator(sslContext));
        httpsServer.createContext("/new.css", exchange -> {
            byte[] body = ".moved{color:blue}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/css");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        httpsServer.start();
        String newUrl = "https://127.0.0.1:" + httpsServer.getAddress().getPort() + "/new.css";
        com.sun.net.httpserver.HttpServer httpServer =
                com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/old.css", exchange -> {
            exchange.getResponseHeaders().add("Location", newUrl);
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        httpServer.start();
        String oldUrl = "http://127.0.0.1:" + httpServer.getAddress().getPort() + "/old.css";
        java.util.concurrent.ExecutorService single = java.util.concurrent.Executors.newFixedThreadPool(1);
        try {
            javax.net.ssl.SSLContext.setDefault(sslContext);
            when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
            when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));
            when(contentServiceClient.fetchAndSplice(
                    "http://example.com/hello-world/", "Hello World", "<p>Hi</p>", "新タイトル", "<p>新本文</p>", null))
                    .thenReturn(bridgedWithUnreadable("body{margin:0}", java.util.List.of(oldUrl)));
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer.bindTo(builder).build()
                    .expect(requestTo(POSTS_URL))
                    .andRespond(withSuccess(POSTS_JSON, MediaType.APPLICATION_JSON));
            ArticlePreviewService svc = new ArticlePreviewService(
                    projectService, siteService, builder, contentServiceClient, cmsAdapterFactory, wordPressAgentOperations, wordPressSshOperations,
                    single, java.time.Duration.ofSeconds(5),
                    ArticlePreviewService.stylesheetRequestFactory(
                            java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(3)));

            ThemeSkeletonResponse response =
                    svc.renderSkeleton(1L, null, "新タイトル", "<p>新本文</p>", null, null, null, null, null);

            assertTrue(response.css().contains(".moved{color:blue}"), response.css());
        } finally {
            javax.net.ssl.SSLContext.setDefault(originalContext);
            single.shutdownNow();
            httpServer.stop(0);
            httpsServer.stop(0);
        }
    }

    @Test
    void renderSkeleton_未指定または空の補完リストは何も取得せずCSSをそのまま返す() {
        ThemeSkeletonResponse withNull = renderSkeletonScrapeWith(bridgedWithUnreadable("body{margin:0}", null));
        assertEquals("body{margin:0}", withNull.css());
    }

    private void givenSshRealPost(ContentServiceClient.ThemeSkeletonBridgeResponse bridgeResult) {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 40L, null));
        Site site = wordPressSite(40L, "http://production.example.com");
        site.setSiteKey("production-site");
        when(siteService.getById(40L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("production-site")).thenReturn(sshCredentials());
        com.letsblog.publishing.cms.CmsAdapter cmsAdapter =
                org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.createOrUpdatePost(org.mockito.ArgumentMatchers.eq(sshCredentials()),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new com.letsblog.publishing.cms.PostResult(
                        "99", "http://production.example.com/?p=99", "private"));
        when(cmsAdapter.generateAuthCookie(sshCredentials()))
                .thenReturn(new com.letsblog.publishing.cms.AuthCookie("wordpress_logged_in_x", "cookie-value"));
        when(contentServiceClient.fetchRealPost(
                "http://production.example.com/?p=99", "wordpress_logged_in_x", "cookie-value"))
                .thenReturn(bridgeResult);
    }

    @Test
    void renderRealPrivatePost_SSHサイトの読めなかった自サイトstylesheetはSFTPで読んで補完する() {
        String href = "http://production.example.com/wp-content/themes/t/style.css";
        givenSshRealPost(bridgedWithUnreadable("body{margin:0}", java.util.List.of(href)));
        when(wordPressSshOperations.fetchSiteFileLayout(sshCredentials())).thenReturn(sshLayout());
        when(wordPressSshOperations.readStylesheetFile(sshCredentials(), sshLayout(), href))
                .thenReturn(Optional.of(".theme{color:red}"));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, 40L, "新タイトル", "<p>新本文</p>", null, null, null, null, null);

        assertTrue(response.available());
        assertEquals("99", response.previewPostId());
        assertTrue(response.css().contains("/* " + href + " */\n.theme{color:red}"));
        server.verify();
    }

    @Test
    void renderRealPrivatePost_SSHのレイアウト取得に失敗したらHTTPで補完を試みる() {
        String href = "http://cdn.example.com/font.css";
        givenSshRealPost(bridgedWithUnreadable("body{margin:0}", java.util.List.of(href)));
        when(wordPressSshOperations.fetchSiteFileLayout(sshCredentials()))
                .thenThrow(new IllegalStateException("ssh down"));
        server.expect(requestTo(href)).andRespond(withSuccess("f{}", MediaType.valueOf("text/css")));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, 40L, "新タイトル", "<p>新本文</p>", null, null, null, null, null);

        assertTrue(response.available());
        assertTrue(response.css().contains("f{}"));
        server.verify();
    }

    @Test
    void renderRealPrivatePost_非SSHサイトの補完は内部オリジンで取得し公開オリジンへ戻す() {
        Project project = projectWithMaster("test", 10L, null);
        project.setLocalSiteId(30L);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        Site site = managedWordPressSite(30L, "local-site", "https://localhost/sites/local-site", "local-site");
        when(siteService.getById(30L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("local-site")).thenReturn(agentCredentials("local-site"));
        com.letsblog.publishing.cms.CmsAdapter cmsAdapter =
                org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.createOrUpdatePost(org.mockito.ArgumentMatchers.eq(agentCredentials("local-site")),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new com.letsblog.publishing.cms.PostResult(
                        "7", "https://localhost/sites/local-site/?p=7", "private"));
        when(cmsAdapter.generateAuthCookie(agentCredentials("local-site")))
                .thenReturn(new com.letsblog.publishing.cms.AuthCookie("c", "v"));
        when(contentServiceClient.fetchRealPost("http://wordpress/sites/local-site/?p=7", "c", "v"))
                .thenReturn(bridgedWithUnreadable("body{margin:0}",
                        java.util.List.of("http://wordpress/sites/local-site/wp-content/x.css")));
        server.expect(requestTo("http://wordpress/sites/local-site/wp-content/x.css"))
                .andRespond(withSuccess("x{}", MediaType.valueOf("text/css")));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, 30L, "新タイトル", "<p>新本文</p>", null, null, null, null, null);

        assertTrue(response.available());
        assertTrue(response.css().contains("/* https://localhost/sites/local-site/wp-content/x.css */\nx{}"));
        assertFalse(response.css().contains("http://wordpress/"));
        server.verify();
    }

    @Test
    void renderSkeleton_マスター環境にサイトが紐づいていない場合はavailableがfalse() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", null, null));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, null, "タイトル", "<p>本文</p>", null, null, null, null, null);

        assertFalse(response.available());
        assertTrue(response.reason().contains("紐づいていません"));
        verifyNoInteractions(contentServiceClient);
    }

    @Test
    void renderSkeleton_参照記事が存在しない場合はavailableがfalse() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));

        server.expect(requestTo("http://example.com/wp-json/wp/v2/posts?per_page=1&orderby=date&order=desc"
                        + "&_fields=id,link,title,content"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, null, "タイトル", "<p>本文</p>", null, null, null, null, null);

        assertFalse(response.available());
        assertTrue(response.reason().contains("参照記事"));
        verifyNoInteractions(contentServiceClient);
    }

    @Test
    void renderSkeleton_参照記事のタイトルと本文で差し替え位置を検索しspliceした結果を返す() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));

        server.expect(requestTo("http://example.com/wp-json/wp/v2/posts?per_page=1&orderby=date&order=desc"
                        + "&_fields=id,link,title,content"))
                .andRespond(withSuccess(
                        "[{\"id\":1,\"link\":\"http://example.com/hello-world/\","
                        + "\"title\":{\"rendered\":\"Hello World\"},\"content\":{\"rendered\":\"<p>Hi</p>\"}}]",
                        MediaType.APPLICATION_JSON));
        when(contentServiceClient.fetchAndSplice(
                "http://example.com/hello-world/", "Hello World", "<p>Hi</p>", "新タイトル", "<p>新本文</p>", null))
                .thenReturn(bridged("<article>spliced</article>", true, null, true, "body { color: red; }"));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, null, "新タイトル", "<p>新本文</p>", null, null, null, null, null);

        assertTrue(response.available());
        assertEquals("<article>spliced</article>", response.html());
        assertTrue(response.eyecatchSpliced());
        assertEquals("body { color: red; }", response.css());
    }

    /**
     * managedサイト(agent transport)は、参照記事の解決自体がREST(wp-json)ではなく
     * provision-agentのwp-cli経由(WordPressAgentOperations)になる(issue #519)。
     * 参照記事解決後のnavigateUrl組み立て(内部URLへのナビゲート・結果の公開オリジンへの巻き戻し)は
     * 従来通り。本番サイトを使うのは、非本番サイトだとusername付きのagent認証情報は
     * renderRealPrivatePost(非公開投稿の実ページ経路)へ分岐してしまい、このスクレイプ&amp;スプライス
     * 経路(参照記事解決)を検証できないため({@link #renderSkeleton}のガード参照)。
     */
    @Test
    void renderSkeleton_managedサイトは参照記事をwp_cli経由で取得し内部URLへナビゲートした結果を公開オリジンへ戻す() {
        Project project = projectWithMaster("production", null, 30L);
        when(projectService.getProjectEntity(1L)).thenReturn(project);

        Site site = managedWordPressSite(30L, "local-site", "https://localhost/sites/local-site", "local-site");
        when(siteService.getById(30L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("local-site")).thenReturn(agentCredentials("local-site"));

        when(wordPressAgentOperations.getLatestPost(agentCredentials("local-site")))
                .thenReturn(Optional.of(new ReferencePost(
                        "1", "https://localhost/sites/local-site/hello-world/", "Hello World", "<p>Hi</p>")));
        when(contentServiceClient.fetchAndSplice(
                "http://wordpress/sites/local-site/hello-world/", "Hello World", "<p>Hi</p>",
                "新タイトル", "<p>新本文</p>", null))
                .thenReturn(bridged(
                        "<article><img src=\"http://wordpress/sites/local-site/wp-content/uploads/x.png\"></article>",
                        true, null, true,
                        "body { background: url(http://wordpress/sites/local-site/wp-content/bg.png); }"));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, 30L, "新タイトル", "<p>新本文</p>", null, null, null, null, null);

        assertTrue(response.available());
        assertTrue(response.html().contains("https://localhost/sites/local-site/wp-content/uploads/x.png"));
        assertTrue(response.css().contains("https://localhost/sites/local-site/wp-content/bg.png"));
        // REST(wp-json)は一切叩かないこと(叩けばMockRestServiceServerが未登録リクエストとして失敗する)
        server.verify();
    }

    /**
     * managed/SSHいずれでもない(REST/Application Password)サイトは、wp-cliに対応しないため
     * 従来通りREST(wp-json)による参照記事解決へフォールバックする(issue #519の対象外。
     * WordPressAdapterのREST分岐と同様、#518で扱う別の課題)。
     */
    @Test
    void renderSkeleton_REST専用サイトは従来通りwp_jsonで参照記事を解決する() {
        Project project = projectWithMaster("test", 10L, null);
        project.setLocalSiteId(30L);
        when(projectService.getProjectEntity(1L)).thenReturn(project);

        Site site = managedWordPressSite(30L, "local-site", "https://localhost/sites/local-site", "local-site");
        when(siteService.getById(30L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("local-site")).thenReturn(
                new com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials(
                        "https://localhost/sites/local-site", "admin", "app-pass"));

        server.expect(requestTo("http://wordpress/sites/local-site/wp-json/wp/v2/posts?per_page=1&orderby=date"
                        + "&order=desc&_fields=id,link,title,content"))
                .andRespond(withSuccess(
                        "[{\"id\":1,\"link\":\"https://localhost/sites/local-site/hello-world/\","
                        + "\"title\":{\"rendered\":\"Hello World\"},\"content\":{\"rendered\":\"<p>Hi</p>\"}}]",
                        MediaType.APPLICATION_JSON));
        when(contentServiceClient.fetchAndSplice(
                "http://wordpress/sites/local-site/hello-world/", "Hello World", "<p>Hi</p>",
                "新タイトル", "<p>新本文</p>", null))
                .thenReturn(bridged(
                        "<article><img src=\"http://wordpress/sites/local-site/wp-content/uploads/x.png\"></article>",
                        true, null, true,
                        "body { background: url(http://wordpress/sites/local-site/wp-content/bg.png); }"));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, 30L, "新タイトル", "<p>新本文</p>", null, null, null, null, null);

        assertTrue(response.available());
        assertTrue(response.html().contains("https://localhost/sites/local-site/wp-content/uploads/x.png"));
        assertTrue(response.css().contains("https://localhost/sites/local-site/wp-content/bg.png"));
        verifyNoInteractions(wordPressAgentOperations, wordPressSshOperations);
        server.verify();
    }

    @Test
    void renderSkeleton_splice側で位置を特定できない場合はavailableがfalseの結果をそのまま返す() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));

        server.expect(requestTo("http://example.com/wp-json/wp/v2/posts?per_page=1&orderby=date&order=desc"
                        + "&_fields=id,link,title,content"))
                .andRespond(withSuccess(
                        "[{\"id\":1,\"link\":\"http://example.com/hello-world/\","
                        + "\"title\":{\"rendered\":\"Hello World\"},\"content\":{\"rendered\":\"<p>Hi</p>\"}}]",
                        MediaType.APPLICATION_JSON));
        when(contentServiceClient.fetchAndSplice(
                "http://example.com/hello-world/", "Hello World", "<p>Hi</p>", "新タイトル", "<p>新本文</p>", null))
                .thenReturn(bridged(
                        null, false, "本文の位置を特定できませんでした", false, "body { color: teal; }"));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, null, "新タイトル", "<p>新本文</p>", null, null, null, null, null);

        assertFalse(response.available());
        assertTrue(response.reason().contains("本文の位置を特定できませんでした"));
        // ナビゲーション自体には成功しているため、本文の差し替えに失敗してもCSSは活かす。
        assertEquals("body { color: teal; }", response.css());
    }

    @Test
    void renderSkeleton_ナビゲーションに成功すれば投稿ページのCSSを収集して返す() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));

        server.expect(requestTo("http://example.com/wp-json/wp/v2/posts?per_page=1&orderby=date&order=desc"
                        + "&_fields=id,link,title,content"))
                .andRespond(withSuccess(
                        "[{\"id\":1,\"link\":\"http://example.com/hello-world/\","
                        + "\"title\":{\"rendered\":\"Hello World\"},\"content\":{\"rendered\":\"<p>Hi</p>\"}}]",
                        MediaType.APPLICATION_JSON));
        when(contentServiceClient.fetchAndSplice(
                "http://example.com/hello-world/", "Hello World", "<p>Hi</p>", "新タイトル", "<p>新本文</p>", null))
                .thenReturn(bridged(
                        "<article>spliced</article>", true, null, true,
                        "/* is_single()限定のCSS */\n.custom-tag { color: hotpink; }"));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, null, "新タイトル", "<p>新本文</p>", null, null, null, null, null);

        assertTrue(response.available());
        assertTrue(response.css().contains(".custom-tag { color: hotpink; }"));
    }

    private com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials sshCredentials() {
        return new com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials(
                "http://production.example.com", "admin",
                "SSH", "ssh.example.com", 22, "deploy", "/var/www/html", "PRIVATE-KEY-PEM", null, null);
    }

    @Test
    void renderSkeleton_SSHトランスポートの非本番サイトは非公開投稿の実ページを返す() {
        Project project = projectWithMaster("test", 40L, null);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        Site site = wordPressSite(40L, "http://production.example.com");
        site.setSiteKey("production-site");
        when(siteService.getById(40L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("production-site")).thenReturn(sshCredentials());

        com.letsblog.publishing.cms.CmsAdapter cmsAdapter = org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.createOrUpdatePost(org.mockito.ArgumentMatchers.eq(sshCredentials()),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new com.letsblog.publishing.cms.PostResult(
                        "99", "http://production.example.com/?p=99", "private"));
        when(cmsAdapter.generateAuthCookie(sshCredentials()))
                .thenReturn(new com.letsblog.publishing.cms.AuthCookie("wordpress_logged_in_x", "cookie-value"));
        when(contentServiceClient.fetchRealPost(
                "http://production.example.com/?p=99", "wordpress_logged_in_x", "cookie-value"))
                .thenReturn(bridged("<article>real page</article>", true, null, false, ""));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, 40L, "新タイトル", "<p>新本文</p>", null, null, null, null, null);

        assertTrue(response.available());
        assertEquals("<article>real page</article>", response.html());
        assertEquals("99", response.previewPostId());
        verify(contentServiceClient, org.mockito.Mockito.never()).fetchAndSplice(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    /**
     * issue #1207 Requirement 4: content-serviceのテーマ骨格取得ブリッジ呼び出しが401/403で失敗した
     * 場合、他の失敗(タイムアウト・5xx・通信断)と区別できるよう、理由に「認証エラー」を含める。
     * ContentServiceClient#fetchRealPostは下流の{@code RestClientResponseException}を
     * {@code IllegalStateException}のcauseとして包んで再送出するため、そのcauseチェーンを見て
     * 判定する(ContentServiceClient側は変更しない、ArticlePreviewService側だけの変更)。
     */
    @Test
    void renderSkeleton_非公開投稿の実ページ取得が認証エラーで失敗した場合は理由に認証エラーであることを含める() {
        Project project = projectWithMaster("test", 40L, null);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        Site site = wordPressSite(40L, "http://production.example.com");
        site.setSiteKey("production-site");
        when(siteService.getById(40L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("production-site")).thenReturn(sshCredentials());

        com.letsblog.publishing.cms.CmsAdapter cmsAdapter = org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.createOrUpdatePost(org.mockito.ArgumentMatchers.eq(sshCredentials()),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new com.letsblog.publishing.cms.PostResult(
                        "99", "http://production.example.com/?p=99", "private"));
        when(cmsAdapter.generateAuthCookie(sshCredentials()))
                .thenReturn(new com.letsblog.publishing.cms.AuthCookie("wordpress_logged_in_x", "cookie-value"));
        HttpClientErrorException unauthorized = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", HttpHeaders.EMPTY, new byte[0], null);
        when(contentServiceClient.fetchRealPost(
                "http://production.example.com/?p=99", "wordpress_logged_in_x", "cookie-value"))
                .thenThrow(new IllegalStateException(
                        "content-serviceのテーマ骨格取得呼び出しに失敗しました: 401 Unauthorized: [no body]",
                        unauthorized));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, 40L, "新タイトル", "<p>新本文</p>", null, null, null, null, null);

        assertFalse(response.available());
        assertTrue(response.reason().contains("認証エラー"),
                "認証エラー(401/403)は他の失敗原因と区別できる文言にする。実際: " + response.reason());
    }

    @Test
    void renderSkeleton_非公開投稿の実ページ取得がタイムアウト等で失敗した場合は理由に認証エラーと書かない() {
        Project project = projectWithMaster("test", 40L, null);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        Site site = wordPressSite(40L, "http://production.example.com");
        site.setSiteKey("production-site");
        when(siteService.getById(40L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("production-site")).thenReturn(sshCredentials());

        com.letsblog.publishing.cms.CmsAdapter cmsAdapter = org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.createOrUpdatePost(org.mockito.ArgumentMatchers.eq(sshCredentials()),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new com.letsblog.publishing.cms.PostResult(
                        "99", "http://production.example.com/?p=99", "private"));
        when(cmsAdapter.generateAuthCookie(sshCredentials()))
                .thenReturn(new com.letsblog.publishing.cms.AuthCookie("wordpress_logged_in_x", "cookie-value"));
        when(contentServiceClient.fetchRealPost(
                "http://production.example.com/?p=99", "wordpress_logged_in_x", "cookie-value"))
                .thenThrow(new IllegalStateException(
                        "content-serviceのテーマ骨格取得呼び出しに失敗しました: Read timed out",
                        new java.net.SocketTimeoutException("Read timed out")));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, 40L, "新タイトル", "<p>新本文</p>", null, null, null, null, null);

        assertFalse(response.available());
        assertFalse(response.reason().contains("認証エラー"),
                "認証以外の失敗を認証エラーと誤表示してはいけない。実際: " + response.reason());
    }

    /** isAuthFailureの分岐カバレッジ: 403も401と同じく認証エラーとして扱う。 */
    @Test
    void renderSkeleton_非公開投稿の実ページ取得が403で失敗した場合も理由に認証エラーであることを含める() {
        Project project = projectWithMaster("test", 40L, null);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        Site site = wordPressSite(40L, "http://production.example.com");
        site.setSiteKey("production-site");
        when(siteService.getById(40L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("production-site")).thenReturn(sshCredentials());

        com.letsblog.publishing.cms.CmsAdapter cmsAdapter = org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.createOrUpdatePost(org.mockito.ArgumentMatchers.eq(sshCredentials()),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new com.letsblog.publishing.cms.PostResult(
                        "99", "http://production.example.com/?p=99", "private"));
        when(cmsAdapter.generateAuthCookie(sshCredentials()))
                .thenReturn(new com.letsblog.publishing.cms.AuthCookie("wordpress_logged_in_x", "cookie-value"));
        HttpClientErrorException forbidden = HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY, new byte[0], null);
        when(contentServiceClient.fetchRealPost(
                "http://production.example.com/?p=99", "wordpress_logged_in_x", "cookie-value"))
                .thenThrow(new IllegalStateException(
                        "content-serviceのテーマ骨格取得呼び出しに失敗しました: 403 Forbidden: [no body]",
                        forbidden));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, 40L, "新タイトル", "<p>新本文</p>", null, null, null, null, null);

        assertFalse(response.available());
        assertTrue(response.reason().contains("認証エラー"),
                "403も認証エラーとして扱う。実際: " + response.reason());
    }

    /**
     * isAuthFailureの分岐カバレッジ: 401/403以外のRestClientResponseException(5xx)は
     * 認証エラーとして扱わない(status.value()==401/==403のいずれもfalseになる経路)。
     */
    @Test
    void renderSkeleton_非公開投稿の実ページ取得が500で失敗した場合は理由に認証エラーと書かない() {
        Project project = projectWithMaster("test", 40L, null);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        Site site = wordPressSite(40L, "http://production.example.com");
        site.setSiteKey("production-site");
        when(siteService.getById(40L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("production-site")).thenReturn(sshCredentials());

        com.letsblog.publishing.cms.CmsAdapter cmsAdapter = org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.createOrUpdatePost(org.mockito.ArgumentMatchers.eq(sshCredentials()),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new com.letsblog.publishing.cms.PostResult(
                        "99", "http://production.example.com/?p=99", "private"));
        when(cmsAdapter.generateAuthCookie(sshCredentials()))
                .thenReturn(new com.letsblog.publishing.cms.AuthCookie("wordpress_logged_in_x", "cookie-value"));
        org.springframework.web.client.HttpServerErrorException serverError =
                org.springframework.web.client.HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", HttpHeaders.EMPTY,
                        new byte[0], null);
        when(contentServiceClient.fetchRealPost(
                "http://production.example.com/?p=99", "wordpress_logged_in_x", "cookie-value"))
                .thenThrow(new IllegalStateException(
                        "content-serviceのテーマ骨格取得呼び出しに失敗しました: 500 Internal Server Error: [no body]",
                        serverError));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, 40L, "新タイトル", "<p>新本文</p>", null, null, null, null, null);

        assertFalse(response.available());
        assertFalse(response.reason().contains("認証エラー"),
                "5xxは認証エラーではない。実際: " + response.reason());
    }

    /**
     * isAuthFailureの分岐カバレッジ: 本サービス自身のClient Credentialsトークン取得失敗
     * (ServiceTokenUnavailableException、issue #567・#1207)も認証エラーとして扱う。
     */
    @Test
    void renderSkeleton_自身のサービストークン取得が失敗した場合も理由に認証エラーであることを含める() {
        Project project = projectWithMaster("test", 40L, null);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        Site site = wordPressSite(40L, "http://production.example.com");
        site.setSiteKey("production-site");
        when(siteService.getById(40L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("production-site")).thenReturn(sshCredentials());

        com.letsblog.publishing.cms.CmsAdapter cmsAdapter = org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.createOrUpdatePost(org.mockito.ArgumentMatchers.eq(sshCredentials()),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new com.letsblog.publishing.cms.PostResult(
                        "99", "http://production.example.com/?p=99", "private"));
        when(cmsAdapter.generateAuthCookie(sshCredentials()))
                .thenReturn(new com.letsblog.publishing.cms.AuthCookie("wordpress_logged_in_x", "cookie-value"));
        when(contentServiceClient.fetchRealPost(
                "http://production.example.com/?p=99", "wordpress_logged_in_x", "cookie-value"))
                .thenThrow(new com.letsblog.common.auth.ServiceTokenUnavailableException(
                        "サービストークンの取得に失敗しました(client_id=letsblog-services): 401 Unauthorized"));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, 40L, "新タイトル", "<p>新本文</p>", null, null, null, null, null);

        assertFalse(response.available());
        assertTrue(response.reason().contains("認証エラー"),
                "自身のサービストークン取得失敗も認証エラーとして扱う。実際: " + response.reason());
    }

    @Test
    void renderSkeleton_本番サイトはSSH認証情報があっても非公開投稿経路を使わずwp_cli経由の参照記事取得にフォールバックする() {
        Project project = projectWithMaster("production", null, 40L);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        Site site = wordPressSite(40L, "http://production.example.com");
        site.setSiteKey("production-site");
        when(siteService.getById(40L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("production-site")).thenReturn(sshCredentials());
        // 本番サイトは(SSH認証情報があっても)非公開投稿の実ページ経路を使わないため、参照記事取得は
        // 従来のスクレイプ&スプライス経路のまま。ただしissue #519により、その参照記事取得自体は
        // wp-cli(WordPressSshOperations)経由になり、REST(wp-json)は叩かない。
        when(wordPressSshOperations.getLatestPost(sshCredentials())).thenReturn(Optional.empty());

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, 40L, "新タイトル", "<p>新本文</p>", null, null, null, null, null);

        assertFalse(response.available());
        assertTrue(response.reason().contains("参照記事が見つかりませんでした"));
        verifyNoInteractions(cmsAdapterFactory);
        verifyNoInteractions(contentServiceClient);
    }

    @Test
    void renderSkeleton_非公開投稿経路はfrontmatterのslug_categories_tagsを解決して投稿へ渡す() {
        Project project = projectWithMaster("test", 40L, null);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        Site site = wordPressSite(40L, "http://production.example.com");
        site.setSiteKey("production-site");
        when(siteService.getById(40L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("production-site")).thenReturn(sshCredentials());

        com.letsblog.publishing.cms.CmsAdapter cmsAdapter = org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.resolveCategories(sshCredentials(), java.util.List.of("お知らせ")))
                .thenReturn(java.util.List.of("5"));
        when(cmsAdapter.resolveTags(sshCredentials(), java.util.List.of("java", "spring")))
                .thenReturn(java.util.List.of("11", "12"));
        org.mockito.ArgumentCaptor<com.letsblog.publishing.cms.PostContent> contentCaptor =
                org.mockito.ArgumentCaptor.forClass(com.letsblog.publishing.cms.PostContent.class);
        when(cmsAdapter.createOrUpdatePost(org.mockito.ArgumentMatchers.eq(sshCredentials()),
                contentCaptor.capture(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new com.letsblog.publishing.cms.PostResult(
                        "99", "http://production.example.com/?p=99", "private"));
        when(cmsAdapter.generateAuthCookie(sshCredentials()))
                .thenReturn(new com.letsblog.publishing.cms.AuthCookie("wordpress_logged_in_x", "cookie-value"));
        when(contentServiceClient.fetchRealPost(
                "http://production.example.com/?p=99", "wordpress_logged_in_x", "cookie-value"))
                .thenReturn(bridged("<article>real page</article>", true, null, false, ""));

        ThemeSkeletonResponse response = service.renderSkeleton(1L, 40L, "新タイトル", "<p>新本文</p>", null, null,
                "my-slug", java.util.List.of("お知らせ"), java.util.List.of("java", "spring"));

        assertTrue(response.available());
        assertEquals("my-slug", contentCaptor.getValue().slug());
        assertEquals(java.util.List.of("5"), contentCaptor.getValue().categoryIds());
        assertEquals(java.util.List.of("11", "12"), contentCaptor.getValue().tagIds());
    }

    @Test
    void renderSkeleton_アイキャッチアップロード失敗時はavailableをtrueに保ったままwarningを返す() {
        Project project = projectWithMaster("test", 40L, null);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        Site site = wordPressSite(40L, "http://production.example.com");
        site.setSiteKey("production-site");
        when(siteService.getById(40L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("production-site")).thenReturn(sshCredentials());

        com.letsblog.publishing.cms.CmsAdapter cmsAdapter = org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.uploadMedia(org.mockito.ArgumentMatchers.eq(sshCredentials()),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("メディアのアップロードに失敗しました"));
        org.mockito.ArgumentCaptor<com.letsblog.publishing.cms.PostContent> contentCaptor =
                org.mockito.ArgumentCaptor.forClass(com.letsblog.publishing.cms.PostContent.class);
        when(cmsAdapter.createOrUpdatePost(org.mockito.ArgumentMatchers.eq(sshCredentials()),
                contentCaptor.capture(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new com.letsblog.publishing.cms.PostResult(
                        "99", "http://production.example.com/?p=99", "private"));
        when(cmsAdapter.generateAuthCookie(sshCredentials()))
                .thenReturn(new com.letsblog.publishing.cms.AuthCookie("wordpress_logged_in_x", "cookie-value"));
        when(contentServiceClient.fetchRealPost(
                "http://production.example.com/?p=99", "wordpress_logged_in_x", "cookie-value"))
                .thenReturn(bridged("<article>real page</article>", true, null, false, ""));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, 40L, "新タイトル", "<p>新本文</p>", "data:image/png;base64,AAAA", null, null, null, null);

        assertTrue(response.available());
        assertTrue(response.warning() != null && response.warning().contains("アイキャッチ"));
        assertEquals(null, contentCaptor.getValue().featuredMediaId());
    }

    /**
     * issue #1240 AC1: PNGのdata URIをアイキャッチとして渡した場合、uploadMediaへ渡す
     * ファイル名は拡張子".png"で終わる必要がある(WordPressのwp_check_filetype_and_ext()が
     * 拡張子なしファイル名からMIMEを判定できず拒否するため)。アップロードが成功すれば
     * featuredMediaIdも投稿へ設定される。
     */
    @Test
    void renderSkeleton_アイキャッチがPNGの場合は拡張子pngのファイル名でアップロードする() {
        assertPreviewUploadExtension("data:image/png;base64,AAAA", "image/png", ".png");
    }

    /** issue #1240 AC2: image/jpeg, image/gif, image/webp, image/svg+xmlそれぞれの拡張子解決。 */
    @Test
    void renderSkeleton_アイキャッチがJPEGの場合は拡張子jpgのファイル名でアップロードする() {
        assertPreviewUploadExtension("data:image/jpeg;base64,AAAA", "image/jpeg", ".jpg");
    }

    @Test
    void renderSkeleton_アイキャッチがGIFの場合は拡張子gifのファイル名でアップロードする() {
        assertPreviewUploadExtension("data:image/gif;base64,AAAA", "image/gif", ".gif");
    }

    @Test
    void renderSkeleton_アイキャッチがWEBPの場合は拡張子webpのファイル名でアップロードする() {
        assertPreviewUploadExtension("data:image/webp;base64,AAAA", "image/webp", ".webp");
    }

    @Test
    void renderSkeleton_アイキャッチがSVGの場合は拡張子svgのファイル名でアップロードする() {
        assertPreviewUploadExtension("data:image/svg+xml;base64,AAAA", "image/svg+xml", ".svg");
    }

    private void assertPreviewUploadExtension(String dataUri, String expectedContentType, String expectedExtension) {
        Project project = projectWithMaster("test", 40L, null);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        Site site = wordPressSite(40L, "http://production.example.com");
        site.setSiteKey("production-site");
        when(siteService.getById(40L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("production-site")).thenReturn(sshCredentials());

        com.letsblog.publishing.cms.CmsAdapter cmsAdapter = org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        org.mockito.ArgumentCaptor<String> filenameCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        when(cmsAdapter.uploadMedia(org.mockito.ArgumentMatchers.eq(sshCredentials()),
                filenameCaptor.capture(), org.mockito.ArgumentMatchers.eq(expectedContentType),
                org.mockito.ArgumentMatchers.any()))
                .thenReturn(new com.letsblog.publishing.cms.MediaUploadResult("77", "http://production.example.com/media/77"));
        org.mockito.ArgumentCaptor<com.letsblog.publishing.cms.PostContent> contentCaptor =
                org.mockito.ArgumentCaptor.forClass(com.letsblog.publishing.cms.PostContent.class);
        when(cmsAdapter.createOrUpdatePost(org.mockito.ArgumentMatchers.eq(sshCredentials()),
                contentCaptor.capture(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new com.letsblog.publishing.cms.PostResult(
                        "99", "http://production.example.com/?p=99", "private"));
        when(cmsAdapter.generateAuthCookie(sshCredentials()))
                .thenReturn(new com.letsblog.publishing.cms.AuthCookie("wordpress_logged_in_x", "cookie-value"));
        when(contentServiceClient.fetchRealPost(
                "http://production.example.com/?p=99", "wordpress_logged_in_x", "cookie-value"))
                .thenReturn(bridged("<article>real page</article>", true, null, false, ""));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, 40L, "新タイトル", "<p>新本文</p>", dataUri, null, null, null, null);

        assertTrue(response.available());
        assertTrue(filenameCaptor.getValue().endsWith(expectedExtension),
                "ファイル名が拡張子" + expectedExtension + "で終わっていません。実際: " + filenameCaptor.getValue());
        assertEquals("77", contentCaptor.getValue().featuredMediaId());
    }

    /**
     * issue #1240 AC3: contentTypeが既知の画像形式へ解決できない場合(decodeDataUriの
     * application/octet-stringフォールバックを含む)、uploadMedia自体を試みず、
     * available=trueのままwarningで理由を伝える。featuredMediaIdはnullのまま投稿される。
     */
    @Test
    void renderSkeleton_アイキャッチのcontentTypeが未知の場合はアップロードを試みずwarningを返す() {
        Project project = projectWithMaster("test", 40L, null);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        Site site = wordPressSite(40L, "http://production.example.com");
        site.setSiteKey("production-site");
        when(siteService.getById(40L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("production-site")).thenReturn(sshCredentials());

        com.letsblog.publishing.cms.CmsAdapter cmsAdapter = org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        org.mockito.ArgumentCaptor<com.letsblog.publishing.cms.PostContent> contentCaptor =
                org.mockito.ArgumentCaptor.forClass(com.letsblog.publishing.cms.PostContent.class);
        when(cmsAdapter.createOrUpdatePost(org.mockito.ArgumentMatchers.eq(sshCredentials()),
                contentCaptor.capture(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new com.letsblog.publishing.cms.PostResult(
                        "99", "http://production.example.com/?p=99", "private"));
        when(cmsAdapter.generateAuthCookie(sshCredentials()))
                .thenReturn(new com.letsblog.publishing.cms.AuthCookie("wordpress_logged_in_x", "cookie-value"));
        when(contentServiceClient.fetchRealPost(
                "http://production.example.com/?p=99", "wordpress_logged_in_x", "cookie-value"))
                .thenReturn(bridged("<article>real page</article>", true, null, false, ""));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, 40L, "新タイトル", "<p>新本文</p>", "data:application/octet-stream;base64,AAAA",
                null, null, null, null);

        assertTrue(response.available());
        assertTrue(response.warning() != null && response.warning().contains("アイキャッチ画像のアップロードに失敗しました"),
                "実際: " + response.warning());
        assertEquals(null, contentCaptor.getValue().featuredMediaId());
        org.mockito.Mockito.verify(cmsAdapter, org.mockito.Mockito.never()).uploadMedia(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void renderSkeleton_SSH認証情報にusernameが無い場合は投稿を作成せず従来経路にフォールバックする() {
        Project project = projectWithMaster("production", null, 40L);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        Site site = wordPressSite(40L, "http://production.example.com");
        site.setSiteKey("production-site");
        when(siteService.getById(40L)).thenReturn(Optional.of(site));
        com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials credsWithoutUsername =
                new com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials(
                        "http://production.example.com", null,
                        "SSH", "ssh.example.com", 22, "deploy", "/var/www/html", "PRIVATE-KEY-PEM", null, null);
        when(siteService.getCredentials("production-site")).thenReturn(credsWithoutUsername);
        when(wordPressSshOperations.getLatestPost(credsWithoutUsername)).thenReturn(Optional.empty());

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, 40L, "新タイトル", "<p>新本文</p>", null, null, null, null, null);

        assertFalse(response.available());
        assertTrue(response.reason().contains("参照記事が見つかりませんでした"));
        verifyNoInteractions(cmsAdapterFactory);
    }

    /**
     * SSH管理サイトのスクレイプ&amp;スプライス経路(非公開投稿の実ページ経路を使わないケース)でも、
     * 参照記事はREST(wp-json)ではなくwp-cli経由(WordPressSshOperations)で取得する(issue #519)。
     */
    @Test
    void renderSkeleton_SSH管理サイトは参照記事をwp_cli経由で取得しspliceした結果を返す() {
        Project project = projectWithMaster("production", null, 40L);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        Site site = wordPressSite(40L, "http://production.example.com");
        site.setSiteKey("production-site");
        when(siteService.getById(40L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("production-site")).thenReturn(sshCredentials());

        when(wordPressSshOperations.getLatestPost(sshCredentials())).thenReturn(Optional.of(
                new ReferencePost("1", "http://production.example.com/hello-world/", "Hello World", "<p>Hi</p>")));
        when(contentServiceClient.fetchAndSplice(
                "http://production.example.com/hello-world/", "Hello World", "<p>Hi</p>",
                "新タイトル", "<p>新本文</p>", null))
                .thenReturn(bridged("<article>spliced</article>", true, null, true, "body { color: red; }"));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, 40L, "新タイトル", "<p>新本文</p>", null, null, null, null, null);

        assertTrue(response.available());
        assertEquals("<article>spliced</article>", response.html());
        verifyNoInteractions(cmsAdapterFactory);
    }

    @Test
    void renderSkeleton_投稿作成後にCookie発行が失敗しても投稿IDは呼び出し側へ返す() {
        Project project = projectWithMaster("test", 40L, null);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        Site site = wordPressSite(40L, "http://production.example.com");
        site.setSiteKey("production-site");
        when(siteService.getById(40L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("production-site")).thenReturn(sshCredentials());

        com.letsblog.publishing.cms.CmsAdapter cmsAdapter = org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        when(cmsAdapter.createOrUpdatePost(org.mockito.ArgumentMatchers.eq(sshCredentials()),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(new com.letsblog.publishing.cms.PostResult(
                        "99", "http://production.example.com/?p=99", "private"));
        when(cmsAdapter.generateAuthCookie(sshCredentials()))
                .thenThrow(new RuntimeException("ユーザー 'null' が見つかりません"));

        ThemeSkeletonResponse response = service.renderSkeleton(
                1L, 40L, "新タイトル", "<p>新本文</p>", null, null, null, null, null);

        assertFalse(response.available());
        // 投稿自体は作成済みのため、拡張機能側が追跡・削除できるようpreviewPostIdを返す
        // (existingPreviewPostId(=null)のままだと投稿がAPI側では孤立し、削除できなくなる)。
        assertEquals("99", response.previewPostId());
    }

    @Test
    void deletePreviewPost_解決したサイトの認証情報でCMSアダプタの削除を呼ぶ() {
        Project project = projectWithMaster("test", 40L, null);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        Site site = wordPressSite(40L, "http://production.example.com");
        site.setSiteKey("production-site");
        when(siteService.getById(40L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("production-site")).thenReturn(sshCredentials());

        com.letsblog.publishing.cms.CmsAdapter cmsAdapter =
                org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);

        service.deletePreviewPost(1L, 40L, "99");

        verify(cmsAdapter).deletePost(sshCredentials(), "99");
    }

    @Test
    void deletePreviewPost_サイトを解決できない場合は何もしない() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));

        service.deletePreviewPost(1L, 999L, "99");

        verifyNoInteractions(siteService, cmsAdapterFactory);
    }

    /**
     * プレビューパネルを閉じた際のベストエフォートな後片付けであり、CMS側の削除失敗で
     * 呼び出し元(拡張機能)へ例外を投げ返さない(ログ警告のみ)。
     */
    @Test
    void deletePreviewPost_CMS側の削除が失敗しても例外を伝播させない() {
        Project project = projectWithMaster("test", 40L, null);
        when(projectService.getProjectEntity(1L)).thenReturn(project);
        Site site = wordPressSite(40L, "http://production.example.com");
        site.setSiteKey("production-site");
        when(siteService.getById(40L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("production-site")).thenReturn(sshCredentials());

        com.letsblog.publishing.cms.CmsAdapter cmsAdapter =
                org.mockito.Mockito.mock(com.letsblog.publishing.cms.CmsAdapter.class);
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(cmsAdapter);
        org.mockito.Mockito.doThrow(new RuntimeException("削除に失敗しました"))
                .when(cmsAdapter).deletePost(sshCredentials(), "99");

        service.deletePreviewPost(1L, 40L, "99");

        verify(cmsAdapter).deletePost(sshCredentials(), "99");
    }

    // ---- Issue #1368: SSH管理サイトのテーマCSSはリモートホストから取得する ----
    // 受け入れ基準はWeb UIから到達できない(ローカルスタックにSSHサーバーが無い、#1197)ため、
    // モックしたWordPressSshOperations(SshCommandExecutor相当)に対するサービスレベルテストで表明する。

    private static final String SSH_SITE_URL = "http://production.example.com";

    private com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials givenSshSite() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        Site site = wordPressSite(10L, SSH_SITE_URL);
        site.setSiteKey("production-site");
        when(siteService.getById(10L)).thenReturn(Optional.of(site));
        var creds = sshCredentials();
        when(siteService.getCredentials("production-site")).thenReturn(creds);
        return creds;
    }

    private WordPressSshOperations.SiteFileLayout sshLayout() {
        return new WordPressSshOperations.SiteFileLayout(
                SSH_SITE_URL, SSH_SITE_URL, SSH_SITE_URL + "/wp-content", "/var/www/html/",
                "/var/www/html/wp-content");
    }

    private static final String TOP_HTML = "<html><head>"
            + "<link rel=\"stylesheet\" href=\"/wp-content/themes/t/style.css\">"
            + "<style>.inline-top{color:blue}</style></head></html>";
    private static final String POST_HTML = "<html><head>"
            + "<link rel=\"stylesheet\" href=\"/wp-content/plugins/p/post.css\">"
            + "<style>.inline-post{color:green}</style></head></html>";

    private void givenSshPagesAndFiles(com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials creds) {
        when(wordPressSshOperations.fetchSiteFileLayout(creds)).thenReturn(sshLayout());
        when(wordPressSshOperations.fetchPageHtml(creds, sshLayout(), SSH_SITE_URL + "/")).thenReturn(TOP_HTML);
        when(wordPressSshOperations.readStylesheetFile(
                creds, sshLayout(), SSH_SITE_URL + "/wp-content/themes/t/style.css"))
                .thenReturn(Optional.of(".theme{color:red}"));
    }

    private void givenReferencePost(com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials creds) {
        when(wordPressSshOperations.getLatestPost(creds)).thenReturn(Optional.of(
                new ReferencePost("5", SSH_SITE_URL + "/hello/", "Hello", "<p>x</p>")));
        when(wordPressSshOperations.fetchPageHtml(creds, sshLayout(), SSH_SITE_URL + "/hello/"))
                .thenReturn(POST_HTML);
        when(wordPressSshOperations.readStylesheetFile(
                creds, sshLayout(), SSH_SITE_URL + "/wp-content/plugins/p/post.css"))
                .thenReturn(Optional.of(".plugin-post{color:pink}"));
    }

    @Test
    void fetchThemeCss_SSHサイトは公開URLへHTTP取得せずリモートから取得しavailableがtrueでsourceがSSH() {
        var creds = givenSshSite();
        givenSshPagesAndFiles(creds);
        givenReferencePost(creds);

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertTrue(response.available());
        assertEquals("SSH", response.source());
        assertTrue(response.css().contains(".theme{color:red}"));
        assertTrue(response.css().contains(".inline-top{color:blue}"));
        assertTrue(response.css().contains(".plugin-post{color:pink}"));
        assertTrue(response.css().contains(".inline-post{color:green}"));
        // MockRestServiceServerに期待を1件も登録していない: 公開URLへHTTPしていれば失敗する
        server.verify();
    }

    @Test
    void fetchThemeCss_SSHサイトで外部ホストのstylesheet取得が失敗してもサイト内CSSだけでavailableになる() {
        var creds = givenSshSite();
        when(wordPressSshOperations.fetchSiteFileLayout(creds)).thenReturn(sshLayout());
        when(wordPressSshOperations.fetchPageHtml(creds, sshLayout(), SSH_SITE_URL + "/")).thenReturn(
                "<html><head><link rel=\"stylesheet\" href=\"/wp-content/themes/t/style.css\">"
                + "<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css?family=Roboto\">"
                + "</head></html>");
        when(wordPressSshOperations.readStylesheetFile(
                creds, sshLayout(), SSH_SITE_URL + "/wp-content/themes/t/style.css"))
                .thenReturn(Optional.of(".theme{color:red}"));
        server.expect(requestTo("https://fonts.googleapis.com/css?family=Roboto")).andRespond(withServerError());
        when(wordPressSshOperations.getLatestPost(creds)).thenReturn(Optional.empty());

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertTrue(response.available());
        assertEquals("SSH", response.source());
        assertTrue(response.css().contains(".theme{color:red}"));
        assertFalse(response.css().contains("fonts.googleapis.com"));
        server.verify();
    }

    @Test
    void fetchThemeCss_SSHサイトの外部ホストstylesheetは従来どおりHTTPで取得して連結する() {
        var creds = givenSshSite();
        when(wordPressSshOperations.fetchSiteFileLayout(creds)).thenReturn(sshLayout());
        when(wordPressSshOperations.fetchPageHtml(creds, sshLayout(), SSH_SITE_URL + "/")).thenReturn(
                "<html><head><link rel=\"stylesheet\" href=\"https://cdn.example.net/font.css\"></head></html>");
        server.expect(requestTo("https://cdn.example.net/font.css"))
                .andRespond(withSuccess(".font{a:b}", MediaType.valueOf("text/css")));
        when(wordPressSshOperations.getLatestPost(creds)).thenReturn(Optional.empty());

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertEquals("SSH", response.source());
        assertTrue(response.css().contains(".font{a:b}"));
        server.verify();
    }

    @Test
    void fetchThemeCss_SSHサイトでファイルを読めないサイト内stylesheetはHTTPで補う() {
        var creds = givenSshSite();
        when(wordPressSshOperations.fetchSiteFileLayout(creds)).thenReturn(sshLayout());
        when(wordPressSshOperations.fetchPageHtml(creds, sshLayout(), SSH_SITE_URL + "/")).thenReturn(TOP_HTML);
        when(wordPressSshOperations.readStylesheetFile(
                creds, sshLayout(), SSH_SITE_URL + "/wp-content/themes/t/style.css"))
                .thenThrow(new com.letsblog.publishing.cms.ssh.SshOperationException("sftp"));
        server.expect(requestTo(SSH_SITE_URL + "/wp-content/themes/t/style.css"))
                .andRespond(withSuccess(".via-http{a:b}", MediaType.valueOf("text/css")));
        when(wordPressSshOperations.getLatestPost(creds)).thenReturn(Optional.empty());

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertEquals("SSH", response.source());
        assertTrue(response.css().contains(".via-http{a:b}"));
        server.verify();
    }

    @Test
    void fetchThemeCss_SSH経路が失敗したらHTTP経路へフォールバックしsourceがHTTP() {
        var creds = givenSshSite();
        when(wordPressSshOperations.fetchSiteFileLayout(creds))
                .thenThrow(new com.letsblog.publishing.cms.ssh.SshOperationException("ssh down"));
        // SSHが落ちているので参照記事のwp-cli取得も失敗する。RESTへ落ちて成立すること
        when(wordPressSshOperations.getLatestPost(creds))
                .thenThrow(new com.letsblog.publishing.cms.ssh.SshOperationException("ssh down"));
        server.expect(requestTo(SSH_SITE_URL)).andRespond(withSuccess(
                "<html><head><link rel=\"stylesheet\" href=\"/style.css\"></head></html>", MediaType.TEXT_HTML));
        server.expect(requestTo(SSH_SITE_URL + "/style.css"))
                .andRespond(withSuccess("body{color:red}", MediaType.valueOf("text/css")));
        expectNoReferencePostForCssFallback(SSH_SITE_URL + "/");

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertTrue(response.available());
        assertEquals("HTTP", response.source());
        assertTrue(response.css().contains("body{color:red}"));
        server.verify();
    }

    @Test
    void fetchThemeCss_SSHで取得したHTMLが空ならHTTP経路へフォールバックする() {
        var creds = givenSshSite();
        when(wordPressSshOperations.fetchSiteFileLayout(creds)).thenReturn(sshLayout());
        when(wordPressSshOperations.fetchPageHtml(creds, sshLayout(), SSH_SITE_URL + "/")).thenReturn(" ");
        when(wordPressSshOperations.getLatestPost(creds)).thenReturn(Optional.empty());
        server.expect(requestTo(SSH_SITE_URL)).andRespond(withSuccess(
                "<html><head><link rel=\"stylesheet\" href=\"/style.css\"></head></html>", MediaType.TEXT_HTML));
        server.expect(requestTo(SSH_SITE_URL + "/style.css"))
                .andRespond(withSuccess("body{color:red}", MediaType.valueOf("text/css")));

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertEquals("HTTP", response.source());
        assertTrue(response.available());
        server.verify();
    }

    @Test
    void fetchThemeCss_SSHで取得したHTMLにstylesheetが無ければHTTP経路へフォールバックする() {
        var creds = givenSshSite();
        when(wordPressSshOperations.fetchSiteFileLayout(creds)).thenReturn(sshLayout());
        when(wordPressSshOperations.fetchPageHtml(creds, sshLayout(), SSH_SITE_URL + "/"))
                .thenReturn("<html><body>challenge</body></html>");
        when(wordPressSshOperations.getLatestPost(creds)).thenReturn(Optional.empty());
        server.expect(requestTo(SSH_SITE_URL)).andRespond(withSuccess(
                "<html><head><link rel=\"stylesheet\" href=\"/style.css\"></head></html>", MediaType.TEXT_HTML));
        server.expect(requestTo(SSH_SITE_URL + "/style.css"))
                .andRespond(withSuccess("body{color:red}", MediaType.valueOf("text/css")));

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertEquals("HTTP", response.source());
        server.verify();
    }

    @Test
    void fetchThemeCss_SSHもHTTPも失敗した場合はavailableがfalseでsourceは無い() {
        var creds = givenSshSite();
        when(wordPressSshOperations.fetchSiteFileLayout(creds))
                .thenThrow(new com.letsblog.publishing.cms.ssh.SshOperationException("ssh down"));
        server.expect(requestTo(SSH_SITE_URL)).andRespond(withServerError());

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertFalse(response.available());
        assertEquals(null, response.source());
        server.verify();
    }

    @Test
    void fetchThemeCss_SSHサイトの投稿ページ取得に失敗してもトップページのCSSは返す() {
        var creds = givenSshSite();
        givenSshPagesAndFiles(creds);
        when(wordPressSshOperations.getLatestPost(creds)).thenReturn(Optional.of(
                new ReferencePost("5", SSH_SITE_URL + "/hello/", "Hello", "<p>x</p>")));
        when(wordPressSshOperations.fetchPageHtml(creds, sshLayout(), SSH_SITE_URL + "/hello/"))
                .thenThrow(new com.letsblog.publishing.cms.ssh.SshOperationException("timeout"));

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertTrue(response.available());
        assertEquals("SSH", response.source());
        assertTrue(response.css().contains(".theme{color:red}"));
        server.verify();
    }

    @Test
    void fetchThemeCss_SSHサイトの投稿ページが空または参照記事が無い場合もトップページのCSSは返す() {
        var creds = givenSshSite();
        givenSshPagesAndFiles(creds);
        when(wordPressSshOperations.getLatestPost(creds)).thenReturn(Optional.of(
                new ReferencePost("5", SSH_SITE_URL + "/hello/", "Hello", "<p>x</p>")));
        when(wordPressSshOperations.fetchPageHtml(creds, sshLayout(), SSH_SITE_URL + "/hello/")).thenReturn("");

        assertTrue(service.fetchThemeCss(1L, 10L).css().contains(".theme{color:red}"));
    }

    @Test
    void fetchThemeCss_SSHサイトの投稿ページにstylesheetもstyleも無ければ追記しない() {
        var creds = givenSshSite();
        givenSshPagesAndFiles(creds);
        when(wordPressSshOperations.getLatestPost(creds)).thenReturn(Optional.of(
                new ReferencePost("5", SSH_SITE_URL + "/hello/", "Hello", "<p>x</p>")));
        when(wordPressSshOperations.fetchPageHtml(creds, sshLayout(), SSH_SITE_URL + "/hello/"))
                .thenReturn("<html><body>plain</body></html>");

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertFalse(response.css().contains("post page"));
    }

    @Test
    void fetchThemeCss_SSHサイトのMAX_CSS_LENGTHを超えるstylesheetは丸ごとスキップする() {
        var creds = givenSshSite();
        when(wordPressSshOperations.fetchSiteFileLayout(creds)).thenReturn(sshLayout());
        when(wordPressSshOperations.fetchPageHtml(creds, sshLayout(), SSH_SITE_URL + "/")).thenReturn(TOP_HTML);
        when(wordPressSshOperations.readStylesheetFile(
                creds, sshLayout(), SSH_SITE_URL + "/wp-content/themes/t/style.css"))
                .thenReturn(Optional.of("a".repeat(3_000_001)));
        when(wordPressSshOperations.getLatestPost(creds)).thenReturn(Optional.empty());

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertTrue(response.available());
        assertFalse(response.css().contains("aaaa"));
        assertTrue(response.css().contains(".inline-top{color:blue}"));
    }

    @Test
    void fetchThemeCss_非SSHサイトはSSH経路を使わずsourceがHTTP() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        when(siteService.getById(10L)).thenReturn(Optional.of(wordPressSite(10L, "http://example.com")));
        server.expect(requestTo("http://example.com")).andRespond(withSuccess(
                "<html><head><link rel=\"stylesheet\" href=\"/style.css\"></head></html>", MediaType.TEXT_HTML));
        server.expect(requestTo("http://example.com/style.css"))
                .andRespond(withSuccess("body{}", MediaType.valueOf("text/css")));
        expectNoReferencePostForCssFallback("http://example.com/");

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertEquals("HTTP", response.source());
        verifyNoInteractions(wordPressSshOperations);
    }

    @Test
    void fetchThemeCss_SSHで取得したHTMLがnullならHTTP経路へフォールバックする() {
        var creds = givenSshSite();
        when(wordPressSshOperations.fetchSiteFileLayout(creds)).thenReturn(sshLayout());
        when(wordPressSshOperations.fetchPageHtml(creds, sshLayout(), SSH_SITE_URL + "/")).thenReturn(null);
        server.expect(requestTo(SSH_SITE_URL)).andRespond(withServerError());

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertFalse(response.available());
        server.verify();
    }

    @Test
    void fetchThemeCss_SSHサイトはインラインstyleだけのページでもavailableになる() {
        var creds = givenSshSite();
        when(wordPressSshOperations.fetchSiteFileLayout(creds)).thenReturn(sshLayout());
        when(wordPressSshOperations.fetchPageHtml(creds, sshLayout(), SSH_SITE_URL + "/"))
                .thenReturn("<html><head><style>.only-inline{a:b}</style></head></html>");
        when(wordPressSshOperations.getLatestPost(creds)).thenReturn(Optional.empty());

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertEquals("SSH", response.source());
        assertTrue(response.css().contains(".only-inline{a:b}"));
    }

    @Test
    void fetchThemeCss_SSHサイトでstylesheetがちょうど上限ならインラインstyleは追記しない() {
        var creds = givenSshSite();
        when(wordPressSshOperations.fetchSiteFileLayout(creds)).thenReturn(sshLayout());
        when(wordPressSshOperations.fetchPageHtml(creds, sshLayout(), SSH_SITE_URL + "/")).thenReturn(TOP_HTML);
        String url = SSH_SITE_URL + "/wp-content/themes/t/style.css";
        int overhead = ("/* " + url + " */\n").length() + 1;
        when(wordPressSshOperations.readStylesheetFile(creds, sshLayout(), url))
                .thenReturn(Optional.of("a".repeat(3_000_000 - overhead)));

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertEquals(3_000_000, response.css().length());
        assertFalse(response.css().contains("inline-top"));
    }

    @Test
    void fetchThemeCss_SSHサイトのインラインstyleが上限を超えたら切り詰める() {
        var creds = givenSshSite();
        when(wordPressSshOperations.fetchSiteFileLayout(creds)).thenReturn(sshLayout());
        when(wordPressSshOperations.fetchPageHtml(creds, sshLayout(), SSH_SITE_URL + "/"))
                .thenReturn("<html><head><style>" + "b".repeat(3_100_000) + "</style></head></html>");

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertEquals(3_000_000, response.css().length());
    }

    @Test
    void fetchThemeCss_SSHサイトでSFTPが空を返したサイト内stylesheetはHTTPで補う() {
        var creds = givenSshSite();
        when(wordPressSshOperations.fetchSiteFileLayout(creds)).thenReturn(sshLayout());
        when(wordPressSshOperations.fetchPageHtml(creds, sshLayout(), SSH_SITE_URL + "/")).thenReturn(TOP_HTML);
        when(wordPressSshOperations.readStylesheetFile(
                creds, sshLayout(), SSH_SITE_URL + "/wp-content/themes/t/style.css"))
                .thenReturn(Optional.empty());
        server.expect(requestTo(SSH_SITE_URL + "/wp-content/themes/t/style.css"))
                .andRespond(withSuccess(".via-http{a:b}", MediaType.valueOf("text/css")));
        when(wordPressSshOperations.getLatestPost(creds)).thenReturn(Optional.empty());

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertTrue(response.css().contains(".via-http{a:b}"));
        server.verify();
    }

    @Test
    void fetchThemeCss_SSHサイトで参照記事の取得が失敗してもトップページのCSSは返す() {
        var creds = givenSshSite();
        givenSshPagesAndFiles(creds);
        when(wordPressSshOperations.getLatestPost(creds))
                .thenThrow(new com.letsblog.publishing.cms.ssh.SshOperationException("wp eval failed"));

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertEquals("SSH", response.source());
        assertTrue(response.css().contains(".theme{color:red}"));
    }

    @Test
    void fetchThemeCss_認証情報の取得が失敗してもHTTP取得失敗は例外にせずavailableがfalse() {
        when(projectService.getProjectEntity(1L)).thenReturn(projectWithMaster("test", 10L, null));
        Site site = wordPressSite(10L, "http://example.com");
        site.setSiteKey("rest-site");
        when(siteService.getById(10L)).thenReturn(Optional.of(site));
        when(siteService.getCredentials("rest-site")).thenThrow(new IllegalStateException("credentials unavailable"));
        server.expect(requestTo("http://example.com")).andRespond(withServerError());

        ThemeCssResponse response = service.fetchThemeCss(1L, 10L);

        assertFalse(response.available());
        assertEquals(null, response.source());
        verifyNoInteractions(wordPressSshOperations);
        server.verify();
    }
}
