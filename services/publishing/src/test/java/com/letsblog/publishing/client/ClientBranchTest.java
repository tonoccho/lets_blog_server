package com.letsblog.publishing.client;

import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.render.MediaRenderException;
import com.letsblog.publishing.render.PlantUmlRenderRejectedException;
import com.letsblog.publishing.service.IdentityServiceUnavailableException;
import com.letsblog.publishing.service.InvalidRechartsTagException;
import com.letsblog.publishing.service.ProjectNotFoundException;
import com.letsblog.publishing.service.SiteNotFoundException;
import com.letsblog.publishing.testsupport.FakeHttpServer;
import com.letsblog.publishing.testsupport.FakeHttpServer.Response;
import com.letsblog.publishing.testsupport.PublishingWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `client` / `render` パッケージの、外部(HTTP)の応答や呼び出し元のリクエストの状態で分かれる分岐(issue #1816)。
 *
 * <p>自前のクライアントは{@link PublishingWorld}の実物を使い、相手のサービスは{@link FakeHttpServer}
 * (サーバー側のフェイク)で、空の応答・各種のHTTPエラー・Authorizationヘッダーの有無を作り分ける。
 */
class ClientBranchTest {

    private PublishingWorld world;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private void authorization(String value) {
        world.request.removeHeader("Authorization");
        if (value != null) {
            world.request.addHeader("Authorization", value);
        }
    }

    private static String sentAuthorization(FakeHttpServer server, String method, String pathRegex) {
        var requests = server.requests(method, pathRegex);
        assertEquals(1, requests.size(), server.paths().toString());
        return requests.get(0).header("Authorization");
    }

    // ---- Authorizationヘッダーの転送(null・空白は付けない) ----

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "Bearer user-token"})
    void ContentServiceClientはリクエストのAuthorizationが有効なときだけ転送する(String header) {
        authorization(header);
        world.content.json("GET", "/api/internal/content/posts", "{\"siteId\":1,\"wpPostId\":\"9\"}");

        world.contentServiceClient.renderPreImage("本文", null, false);
        world.contentServiceClient.findPost(1L, "9");

        String expected = header == null || header.isBlank() ? null : header;
        assertEquals(expected, sentAuthorization(world.content, "POST", "/api/internal/content/render/pre-image"));
        assertEquals(expected, sentAuthorization(world.content, "GET", "/api/internal/content/posts"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "Bearer user-token"})
    void IdentityBridgeClientはリクエストのAuthorizationが有効なときだけ転送する(String header) {
        authorization(header);
        world.identity.json("GET", "/api/internal/identity/user-site-authors/1/2", "{\"cmsAuthorId\":\"7\"}");

        world.identityBridgeClient.cacheUserSiteAuthor(1L, 2L, "7");
        world.identityBridgeClient.findUserSiteAuthor(1L, 2L);

        String expected = header == null || header.isBlank() ? null : header;
        assertEquals(expected, sentAuthorization(world.identity, "POST", "/api/internal/identity/user-site-authors"));
        assertEquals(expected,
                sentAuthorization(world.identity, "GET", "/api/internal/identity/user-site-authors/1/2"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "Bearer user-token"})
    void media_serviceのクライアントはリクエストのAuthorizationが有効なときだけ転送する(String header) {
        authorization(header);
        world.media.on("GET", "/api/generated-images/1/file", Response.bytes(200, "image/png", new byte[] {1}));
        world.media.on("POST", "/api/render/plantuml", Response.bytes(200, "image/png", new byte[] {2}));

        world.mediaSettingsBridgeClient.resolveArticleImageLongEdgePx(5L);
        world.mediaGeneratedImageClient.fetchImageFile(1L);
        world.mediaRenderClient.renderPlantUml("@startuml\n@enduml");

        String expected = header == null || header.isBlank() ? null : header;
        assertEquals(expected, sentAuthorization(world.media, "GET",
                "/api/internal/media/projects/5/article-image-long-edge-px"));
        assertEquals(expected, sentAuthorization(world.media, "GET", "/api/generated-images/1/file"));
        assertEquals(expected, sentAuthorization(world.media, "POST", "/api/render/plantuml"));
    }

    // ---- ContentServiceClient ----

    @Test
    void renderPreImage_本文が空の応答なら入力のMarkdownをそのまま返す() {
        world.content.on("POST", "/api/internal/content/render/pre-image", Response.empty(200));

        assertEquals("元の本文", world.contentServiceClient.renderPreImage("元の本文", 3L, true));
    }

    @Test
    void renderPreImage_本文の無いHTTPエラーはメッセージつきのIllegalStateException() {
        world.content.on("POST", "/api/internal/content/render/pre-image", Response.empty(500));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> world.contentServiceClient.renderPreImage("本文", 3L, true));

        assertTrue(e.getMessage().contains("500"), e.getMessage());
    }

    @Test
    void renderPreImage_本文つきの400はInvalidRechartsTagExceptionに本文を載せる() {
        world.content.status("POST", "/api/internal/content/render/pre-image", 400, "不正な[recharts]");

        InvalidRechartsTagException e = assertThrows(InvalidRechartsTagException.class,
                () -> world.contentServiceClient.renderPreImage("本文", 3L, false));

        assertTrue(e.getMessage().contains("不正な[recharts]"), e.getMessage());
    }

    @Test
    void renderPreImage_本文の無い400もInvalidRechartsTagException() {
        world.content.on("POST", "/api/internal/content/render/pre-image", Response.empty(400));

        assertThrows(InvalidRechartsTagException.class,
                () -> world.contentServiceClient.renderPreImage("本文", 3L, false));
    }

    @Test
    void finalizeHtml_Markdownがnullなら空文字を送る() {
        world.contentServiceClient.finalizeHtml(null, 3L);

        assertTrue(world.content.requests("POST", "/api/internal/content/render/finalize-html").get(0)
                .bodyText().contains("\"markdown\":\"\""));
    }

    @Test
    void finalizeHtml_空の応答はIllegalStateException() {
        world.content.on("POST", "/api/internal/content/render/finalize-html", Response.empty(200));

        assertThrows(IllegalStateException.class, () -> world.contentServiceClient.finalizeHtml("本文", 3L));
    }

    @Test
    void findPost_404は空_空の本文も空_その他のHTTPエラーは例外() {
        world.content.status("GET", "/api/internal/content/posts", 404, "{}");
        assertEquals(Optional.empty(), world.contentServiceClient.findPost(1L, "9"));

        world.content.on("GET", "/api/internal/content/posts", Response.empty(200));
        assertEquals(Optional.empty(), world.contentServiceClient.findPost(1L, "9"));

        world.content.on("GET", "/api/internal/content/posts", Response.empty(500));
        assertThrows(IllegalStateException.class, () -> world.contentServiceClient.findPost(1L, "9"));
    }

    @Test
    void markTrashed_404は無視_その他のHTTPエラーは例外() {
        world.content.on("POST", "/api/internal/content/posts/mark-trashed", Response.empty(404));
        world.contentServiceClient.markTrashed(1L, "9");

        world.content.on("POST", "/api/internal/content/posts/mark-trashed", Response.empty(500));
        assertThrows(IllegalStateException.class, () -> world.contentServiceClient.markTrashed(1L, "9"));
    }

    // ---- IdentityBridgeClient ----

    @Test
    void findUserSiteAuthor_404と空の応答は空_その他のエラーは本文または状態を含む例外() {
        world.identity.status("GET", "/api/internal/identity/user-site-authors/1/2", 404, "{}");
        assertEquals(Optional.empty(), world.identityBridgeClient.findUserSiteAuthor(1L, 2L));

        world.identity.on("GET", "/api/internal/identity/user-site-authors/1/2", Response.empty(200));
        assertEquals(Optional.empty(), world.identityBridgeClient.findUserSiteAuthor(1L, 2L));

        world.identity.status("GET", "/api/internal/identity/user-site-authors/1/2", 500, "identity-boom");
        IllegalStateException withBody = assertThrows(IllegalStateException.class,
                () -> world.identityBridgeClient.findUserSiteAuthor(1L, 2L));
        assertTrue(withBody.getMessage().contains("identity-boom"), withBody.getMessage());

        world.identity.on("GET", "/api/internal/identity/user-site-authors/1/2", Response.empty(500));
        IllegalStateException withoutBody = assertThrows(IllegalStateException.class,
                () -> world.identityBridgeClient.findUserSiteAuthor(1L, 2L));
        assertTrue(withoutBody.getMessage().contains("500"), withoutBody.getMessage());
    }

    @Test
    void isProjectMember_trueだけが所属_空の応答やエラーは所属としない_エラーは例外() {
        world.identity.json("GET", "/api/internal/identity/projects/1/members/2", "true");
        assertTrue(world.identityBridgeClient.isProjectMember(1L, 2L));

        world.identity.json("GET", "/api/internal/identity/projects/1/members/2", "false");
        assertEquals(false, world.identityBridgeClient.isProjectMember(1L, 2L));

        world.identity.on("GET", "/api/internal/identity/projects/1/members/2", Response.empty(200));
        assertEquals(false, world.identityBridgeClient.isProjectMember(1L, 2L));

        world.identity.on("GET", "/api/internal/identity/projects/1/members/2", Response.empty(500));
        assertThrows(IdentityServiceUnavailableException.class,
                () -> world.identityBridgeClient.isProjectMember(1L, 2L));
    }

    // ---- IdentityClient ----

    @Test
    void fetchProfile_空の応答は障害として扱う() {
        world.identity.on("GET", "/api/identity/me", Response.empty(200));

        assertThrows(IdentityServiceUnavailableException.class, () -> world.identityClient.fetchProfile("Bearer t"));
    }

    @Test
    void lookupProfile_401と403は操作者なし_5xxと通信断は障害のまま伝える() {
        world.identity.status("GET", "/api/identity/me", 401, "{}");
        assertEquals(Optional.empty(), world.identityClient.lookupProfile("Bearer t"));

        world.identity.status("GET", "/api/identity/me", 403, "{}");
        assertEquals(Optional.empty(), world.identityClient.lookupProfile("Bearer t"));

        world.identity.status("GET", "/api/identity/me", 500, "{}");
        assertThrows(IdentityServiceUnavailableException.class, () -> world.identityClient.lookupProfile("Bearer t"));

        // 接続できない(サーバー停止)と、原因はHTTP応答ではない。
        IdentityClient unreachable = new IdentityClient(org.springframework.web.client.RestClient.builder(),
                "http://127.0.0.1:1");
        assertThrows(IdentityServiceUnavailableException.class, () -> unreachable.lookupProfile("Bearer t"));
    }

    // ---- MediaSettingsBridgeClient / MediaGeneratedImageClient / MediaRenderClient ----

    @Test
    void resolveArticleImageLongEdgePx_空の応答は既定値_エラーは例外() {
        world.media.on("GET", "/api/internal/media/projects/5/article-image-long-edge-px", Response.empty(200));
        assertEquals(1300, world.mediaSettingsBridgeClient.resolveArticleImageLongEdgePx(5L));
        assertEquals(1300, world.mediaSettingsBridgeClient.resolveArticleImageLongEdgePx(null));

        world.media.on("GET", "/api/internal/media/projects/5/article-image-long-edge-px", Response.empty(500));
        assertThrows(IllegalStateException.class, () -> world.mediaSettingsBridgeClient.resolveArticleImageLongEdgePx(5L));
    }

    @Test
    void fetchImageFile_空の応答とHTTPエラーはMediaRenderException() {
        world.media.on("GET", "/api/generated-images/1/file", Response.empty(200));
        assertThrows(MediaRenderException.class, () -> world.mediaGeneratedImageClient.fetchImageFile(1L));

        world.media.on("GET", "/api/generated-images/1/file", Response.empty(500));
        assertThrows(MediaRenderException.class, () -> world.mediaGeneratedImageClient.fetchImageFile(1L));
    }

    @Test
    void renderPlantUml_400は拒否_その他のエラーはMediaRenderException() {
        world.media.status("POST", "/api/render/plantuml", 400, "{\"error\":\"構文エラー\"}");
        assertThrows(PlantUmlRenderRejectedException.class, () -> world.mediaRenderClient.renderPlantUml("x"));

        world.media.on("POST", "/api/render/plantuml", Response.empty(500));
        assertThrows(MediaRenderException.class, () -> world.mediaRenderClient.renderPlantUml("x"));
    }

    // ---- ProjectServiceClient ----

    @Test
    void getSiteByKey_空の応答と404は未登録_その他のエラーは例外() {
        world.project.on("GET", "/api/internal/project/sites/by-key/k", Response.empty(200));
        assertThrows(SiteNotFoundException.class, () -> world.projectServiceClient.getSiteByKey("k"));

        world.project.on("GET", "/api/internal/project/sites/by-key/k", Response.empty(404));
        assertThrows(SiteNotFoundException.class, () -> world.projectServiceClient.getSiteByKey("k"));

        world.project.on("GET", "/api/internal/project/sites/by-key/k", Response.empty(500));
        assertThrows(IllegalStateException.class, () -> world.projectServiceClient.getSiteByKey("k"));
    }

    @Test
    void getSite_404と空の応答は空_その他のエラーは例外() {
        world.project.on("GET", "/api/internal/project/sites/5", Response.empty(404));
        assertEquals(Optional.empty(), world.projectServiceClient.getSite(5L));

        world.project.on("GET", "/api/internal/project/sites/5", Response.empty(200));
        assertEquals(Optional.empty(), world.projectServiceClient.getSite(5L));

        world.project.on("GET", "/api/internal/project/sites/5", Response.empty(500));
        assertThrows(IllegalStateException.class, () -> world.projectServiceClient.getSite(5L));
    }

    @Test
    void getCredentials_空の応答と404は例外_その他のエラーは本文または状態を含む例外() {
        world.project.on("GET", "/api/internal/project/sites/k/credentials", Response.empty(200));
        assertThrows(IllegalStateException.class, () -> world.projectServiceClient.getCredentials("k"));

        world.project.on("GET", "/api/internal/project/sites/k/credentials", Response.empty(404));
        assertThrows(SiteNotFoundException.class, () -> world.projectServiceClient.getCredentials("k"));

        world.project.status("GET", "/api/internal/project/sites/k/credentials", 500, "project-boom");
        IllegalStateException e =
                assertThrows(IllegalStateException.class, () -> world.projectServiceClient.getCredentials("k"));
        assertTrue(e.getMessage().contains("project-boom"), e.getMessage());
    }

    @Test
    void findProjectIdBySiteId_空の応答はnull_エラーは例外() {
        world.project.on("GET", "/api/internal/project/sites/5/project-id", Response.empty(200));
        assertNull(world.projectServiceClient.findProjectIdBySiteId(5L));

        world.project.json("GET", "/api/internal/project/sites/5/project-id", "{\"projectId\":8}");
        assertEquals(8L, world.projectServiceClient.findProjectIdBySiteId(5L));

        world.project.on("GET", "/api/internal/project/sites/5/project-id", Response.empty(500));
        assertThrows(IllegalStateException.class, () -> world.projectServiceClient.findProjectIdBySiteId(5L));
    }

    @Test
    void getProject_空の応答は例外_404は未登録_その他のエラーは例外() {
        world.project.on("GET", "/api/internal/project/projects/5", Response.empty(200));
        assertThrows(IllegalStateException.class, () -> world.projectServiceClient.getProject(5L));

        world.project.on("GET", "/api/internal/project/projects/5", Response.empty(404));
        assertThrows(ProjectNotFoundException.class, () -> world.projectServiceClient.getProject(5L));

        world.project.on("GET", "/api/internal/project/projects/5", Response.empty(500));
        assertThrows(IllegalStateException.class, () -> world.projectServiceClient.getProject(5L));
    }

    @Test
    void resolveGithubAccess_各ステータスに応じた例外にする() {
        String path = "/api/internal/project/projects/5/github-access";
        world.project.on("GET", path, Response.empty(200));
        assertThrows(IllegalStateException.class, () -> world.projectServiceClient.resolveGithubAccess(5L, 1L));

        world.project.on("GET", path, Response.empty(404));
        assertThrows(ProjectNotFoundException.class, () -> world.projectServiceClient.resolveGithubAccess(5L, 1L));

        // 409: {"error":"..."}の文面をそのまま使う。
        world.project.status("GET", path, 409, "{\"error\":\"GitHubのトークンが未設定です\"}");
        assertEquals("GitHubのトークンが未設定です", assertThrows(IllegalStateException.class,
                () -> world.projectServiceClient.resolveGithubAccess(5L, 1L)).getMessage());

        // 409でも、errorが無い・空・文字列でない・JSONでない本文は、本文そのまま。
        for (String body : new String[] {"{\"other\":1}", "{\"error\":\"\"}", "{\"error\":3}", "not json"}) {
            world.project.status("GET", path, 409, body);
            assertEquals(body, assertThrows(IllegalStateException.class,
                    () -> world.projectServiceClient.resolveGithubAccess(5L, 1L)).getMessage());
        }

        // 本文の無い409は、例外のメッセージ(状態行)を使う。
        world.project.on("GET", path, Response.empty(409));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> world.projectServiceClient.resolveGithubAccess(5L, 1L)).getMessage().contains("409"));

        world.project.on("GET", path, Response.empty(500));
        assertTrue(assertThrows(IllegalStateException.class,
                () -> world.projectServiceClient.resolveGithubAccess(5L, 1L)).getMessage().contains("github-access"));
    }

    @Test
    void SiteCredentialsBridge_sshPortは空_未指定_数値でなければnullになり_数値ならそのポート() {
        Map<String, String> base = Map.of("baseUrl", "https://e", "username", "u", "transport", "SSH");
        java.util.function.Function<Map<String, String>, Integer> port = credentials ->
                ((CmsCredentials.WordPressCredentials)
                        new ProjectServiceClient.SiteCredentialsBridge(1L, CmsType.WORDPRESS, credentials)
                                .toCmsCredentials()).sshPort();

        assertNull(port.apply(base));
        assertNull(port.apply(merged(base, "sshPort", "")));
        assertNull(port.apply(merged(base, "sshPort", "  ")));
        assertNull(port.apply(merged(base, "sshPort", "abc")));
        assertEquals(2222, port.apply(merged(base, "sshPort", "2222")));
    }

    private static Map<String, String> merged(Map<String, String> base, String key, String value) {
        Map<String, String> copy = new java.util.LinkedHashMap<>(base);
        copy.put(key, value);
        return copy;
    }
}
