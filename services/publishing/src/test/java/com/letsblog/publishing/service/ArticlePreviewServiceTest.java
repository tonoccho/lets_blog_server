package com.letsblog.publishing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.publishing.cms.LetsblogPluginUnavailableException;
import com.letsblog.publishing.dto.SignedPreviewUrlRequest;
import com.letsblog.publishing.dto.SignedPreviewUrlResponse;
import com.letsblog.publishing.testsupport.PublishingWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * publishing-serviceのArticlePreviewService(署名付きプレビューURLの発行、issue #1561)を検証する。
 * 旧プレビュー経路(fetchThemeCss/renderSkeleton/renderRealPrivatePost/deletePreviewPost)は
 * issue #1564で削除したため、そのテストも併せて削除した。記事本文レンダリング(renderHtml)は
 * content-serviceが持つ。
 *
 * <p>issue #1815で書き直し: ProjectService・SiteService・ProjectServiceClient・CmsAdapterFactory・WordPressAdapterは
 * 実物を使い、project-serviceへのHTTP({@code FakeHttpServer})とWordPressへのSSH({@code FakeWordPress})だけを差し替える。
 */
class ArticlePreviewServiceTest {

    private static final String PREVIEW_OUTPUT =
            "{\"url\":\"https://localhost/sites/local-site/?letsblog_preview=t\",\"expires_at\":1800000600}";

    private PublishingWorld world;
    private ArticlePreviewService service;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        world.asUser("Bearer user-token");
        service = new ArticlePreviewService(world.projectService, world.siteService, world.cmsAdapterFactory);
        world.ssh.whenOk("letsblog preview", PREVIEW_OUTPUT);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    /** プロジェクト1に、テスト(30)・本番(31)・ローカル(29)のサイトを置く。 */
    private void projectWithSites(String masterEnvironment, Long localSiteId, Long testSiteId, Long productionSiteId) {
        world.bindProject(30L, 1L, localSiteId, testSiteId, productionSiteId, masterEnvironment);
        world.stubSite(29L, "local-site", true);
        world.stubSite(30L, "test-site", true);
        world.stubSite(31L, "prod-site", true);
        world.stubCredentials("local-site", PublishingWorld.sshCredentialsMap());
        world.stubCredentials("test-site", PublishingWorld.sshCredentialsMap());
        world.stubCredentials("prod-site", PublishingWorld.sshCredentialsMap());
    }

    private SignedPreviewUrlRequest signedRequest(Long siteId, String featuredImage, Integer ttl) {
        return new SignedPreviewUrlRequest(
                siteId, "プレビュー題", "<p>本文</p>", List.of("news"), List.of("a", "b"), featuredImage, ttl);
    }

    private JsonNode sentPayload() throws Exception {
        assertEquals(1, world.ssh.uploaded.size(), "プレビューの内容が1つだけ送られる");
        return world.mapper.readTree(world.ssh.uploaded.values().iterator().next());
    }

    private boolean previewCommandRan() {
        return world.ssh.ran("letsblog preview");
    }

    // ---- issue #1561: 投稿を作らない署名付きプレビュー URL ----

    @Test
    void createSignedPreviewUrl_内容をプラグインへ渡して署名付きURLを返す() throws Exception {
        projectWithSites("test", null, 30L, null);

        SignedPreviewUrlResponse response = service.createSignedPreviewUrl(
                1L, signedRequest(30L, "data:image/png;base64,AAAA", 600));

        assertEquals("https://localhost/sites/local-site/?letsblog_preview=t", response.url());
        assertEquals(1800000600L, response.expiresAt());
        assertTrue(world.ssh.ran("--ttl=600"));
        assertTrue(world.ssh.indexOf("letsblog status") < world.ssh.indexOf("letsblog preview"),
                "プラグインの状態確認が先");
        JsonNode node = sentPayload();
        assertEquals("プレビュー題", node.path("title").asText());
        assertEquals("<p>本文</p>", node.path("content").asText());
        assertEquals("news", node.path("categories").get(0).asText());
        assertEquals("b", node.path("tags").get(1).asText());
        assertEquals("data:image/png;base64,AAAA", node.path("featured_image").asText());
    }

    @Test
    void createSignedPreviewUrl_カテゴリ_タグ_アイキャッチが無ければ空として渡す() throws Exception {
        projectWithSites("test", null, 30L, null);

        service.createSignedPreviewUrl(1L, new SignedPreviewUrlRequest(
                30L, "題", "<p>x</p>", null, null, " ", null));

        JsonNode node = sentPayload();
        assertEquals(0, node.path("categories").size());
        assertEquals(0, node.path("tags").size());
        assertTrue(node.path("featured_image").isMissingNode());
        assertFalse(world.ssh.ran("--ttl"), "有効期限を指定しなければプラグインの規定値");
    }

    @Test
    void createSignedPreviewUrl_投稿もメディアも作らない() {
        projectWithSites("test", null, 30L, null);

        service.createSignedPreviewUrl(1L, signedRequest(30L, "data:image/png;base64,AAAA", null));

        assertTrue(world.ssh.posts.isEmpty());
        assertTrue(world.ssh.media.isEmpty());
        assertFalse(world.ssh.ran("post create"));
        assertFalse(world.ssh.ran("media import"));
        assertFalse(world.ssh.ran("eval"), "認証Cookieの発行もしない");
    }

    @Test
    void createSignedPreviewUrl_プラグインが使えなければ発行せずに拒否する() {
        projectWithSites("test", null, 30L, null);
        world.ssh.site().letsblogStatusOutput = null;

        assertThrows(LetsblogPluginUnavailableException.class,
                () -> service.createSignedPreviewUrl(1L, signedRequest(30L, null, null)));
        assertFalse(previewCommandRan());
        assertTrue(world.ssh.uploaded.isEmpty(), "何も渡さない");
    }

    @Test
    void createSignedPreviewUrl_プロジェクトに紐づかないサイトは理由つきで拒否する() {
        projectWithSites("test", null, 30L, null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.createSignedPreviewUrl(1L, signedRequest(999L, null, null)));
        assertTrue(e.getMessage().contains("紐づいていません"));
        assertTrue(world.ssh.commands.isEmpty());
    }

    @Test
    void createSignedPreviewUrl_siteId未指定ならマスター環境のサイトを対象にする() {
        projectWithSites("test", null, 30L, null);

        service.createSignedPreviewUrl(1L, signedRequest(null, null, null));

        assertEquals(1, world.project.requests("GET", "/api/internal/project/sites/test-site/credentials").size(),
                "マスター環境(テスト)のサイトの認証情報で接続する");
        assertTrue(previewCommandRan());
    }

    // ---- サイトの解決(resolveSiteForPreview)の分岐 ----

    @Test
    void createSignedPreviewUrl_siteIdのサイトが見つからなければ理由つきで拒否する() {
        world.bindProject(30L, 1L, null, 30L, null, "test");
        // サイト30はproject-serviceに無い(404)。

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.createSignedPreviewUrl(1L, signedRequest(30L, null, null)));
        assertTrue(e.getMessage().contains("見つかりません"));
        assertTrue(world.ssh.commands.isEmpty());
    }

    @Test
    void createSignedPreviewUrl_WordPress以外のCMSは理由つきで拒否する() {
        world.bindProject(30L, 1L, null, 30L, null, "test");
        world.project.json("GET", "/api/internal/project/sites/30",
                "{\"id\":30,\"siteKey\":\"test-site\",\"name\":\"n\",\"baseUrl\":\"https://example.com\","
                        + "\"cmsType\":null,\"managedWordpress\":false,\"wpSlug\":null}");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.createSignedPreviewUrl(1L, signedRequest(30L, null, null)));
        assertTrue(e.getMessage().contains("WordPress以外"));
        assertTrue(world.ssh.commands.isEmpty());
    }

    @Test
    void createSignedPreviewUrl_マスター環境が本番ならsiteId未指定で本番サイトを対象にする() {
        projectWithSites("production", null, null, 31L);

        SignedPreviewUrlResponse response = service.createSignedPreviewUrl(1L, signedRequest(null, null, null));

        assertEquals("https://localhost/sites/local-site/?letsblog_preview=t", response.url());
        assertEquals(1, world.project.requests("GET", "/api/internal/project/sites/prod-site/credentials").size());
    }

    @Test
    void createSignedPreviewUrl_マスター環境がテストでもlocalでも未紐付けなら理由つきで拒否する() {
        projectWithSites("local", null, null, null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.createSignedPreviewUrl(1L, signedRequest(null, null, null)));
        assertTrue(e.getMessage().contains("紐づいていません"));
    }

    @Test
    void createSignedPreviewUrl_マスター環境のサイトがproject_serviceに無ければ理由つきで拒否する() {
        world.bindProject(30L, 1L, null, 30L, null, "test");
        // サイト30はproject-serviceに無い(404)。

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.createSignedPreviewUrl(1L, signedRequest(null, null, null)));
        assertTrue(e.getMessage().contains("紐づいていません"));
    }

    @Test
    void createSignedPreviewUrl_ローカル環境のsiteIdも対象にできる() {
        projectWithSites("test", 29L, 30L, null);

        SignedPreviewUrlResponse response = service.createSignedPreviewUrl(1L, signedRequest(29L, null, null));

        assertEquals(1800000600L, response.expiresAt());
        assertEquals(1, world.project.requests("GET", "/api/internal/project/sites/local-site/credentials").size());
    }

    @Test
    void createSignedPreviewUrl_本番環境のsiteIdも対象にできる() {
        projectWithSites("test", null, 30L, 31L);

        SignedPreviewUrlResponse response = service.createSignedPreviewUrl(1L, signedRequest(31L, null, null));

        assertEquals(1800000600L, response.expiresAt());
        assertEquals(1, world.project.requests("GET", "/api/internal/project/sites/prod-site/credentials").size());
    }
}
