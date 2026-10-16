package com.letsblog.publishing.controller;

import com.letsblog.publishing.dto.SignedPreviewUrlRequest;
import com.letsblog.publishing.dto.SignedPreviewUrlResponse;
import com.letsblog.publishing.service.ArticlePreviewService;
import com.letsblog.publishing.service.ForbiddenException;
import com.letsblog.publishing.testsupport.PublishingWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * publishing-serviceのArticlePreviewController(署名付きプレビューURL、issue #1561)を検証する。
 * 旧プレビュー経路(theme-css/skeleton/preview-post)は issue #1564 で削除し、存在しないことを固定する。
 * 記事本文レンダリング(/render)はcontent-serviceが持つため、その振る舞いはcontent-service側の
 * ArticlePreviewControllerTestで検証する。
 *
 * <p>issue #1816で書き直し: ArticlePreviewService・AdminAuthorizationServiceは実物を使い、外部との通信
 * (identity・project-serviceへのHTTPは{@code FakeHttpServer}、WordPressへのSSHは{@code FakeWordPress})だけを
 * 差し替える。
 */
class ArticlePreviewControllerTest {

    private static final String ADMIN = "Bearer admin-token";
    private static final String MEMBER = "Bearer member-token";

    private PublishingWorld world;
    private ArticlePreviewController controller;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        controller = new ArticlePreviewController(
                new ArticlePreviewService(world.projectService, world.siteService, world.cmsAdapterFactory),
                world.adminAuthorizationService);
        world.ssh.whenOk("letsblog preview",
                "{\"url\":\"https://x/?letsblog_preview=t\",\"expires_at\":9}");
        world.bindProject(30L, 1L, null, 30L, null, "test");
        world.stubSite(30L, "test-site", true);
        world.stubCredentials("test-site", PublishingWorld.sshCredentialsMap());
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    // issue #1564: 旧プレビュー経路(テーマCSS取得・骨組み差し込み・一時投稿の削除)は存在しない。
    // 標準の MockMvc は静的リソースのハンドラを持たないので、マッピングが無ければ 404 になる。
    @Test
    void 旧プレビュー経路のthemeCss_skeleton_previewPostはマッピングが無く404() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        mockMvc.perform(get("/api/projects/1/preview/theme-css").param("siteId", "20"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/projects/1/preview/skeleton")
                        .contentType("application/json")
                        .content("{\"title\":\"t\",\"contentHtml\":\"<p>x</p>\",\"siteId\":20}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/projects/1/preview/preview-post")
                        .param("siteId", "20").param("postId", "123"))
                .andExpect(status().isNotFound());
        assertTrue(world.identity.requests().isEmpty(), "認可にも触れない");
        assertTrue(world.project.requests().isEmpty(), "project-serviceにも触れない");
        assertTrue(world.ssh.commands.isEmpty(), "WordPressにも触れない");
    }

    // ---- issue #1561: 署名付きプレビュー URL ----

    @Test
    void signedUrl_認可後にサービスへ委譲しURLを返す() {
        world.stubActor(ADMIN, 1L, "admin", "a@example.com").asUser(ADMIN);
        SignedPreviewUrlRequest request = new SignedPreviewUrlRequest(
                30L, "題", "<p>本文</p>", List.of("c"), List.of("t"), "data:image/png;base64,AAAA", 600);

        SignedPreviewUrlResponse response = controller.signedUrl(1L, request);

        assertEquals("https://x/?letsblog_preview=t", response.url());
        assertEquals(9L, response.expiresAt());
        assertTrue(world.ssh.ran("--ttl=600"), world.ssh.commands.toString());
        assertEquals(1, world.ssh.uploaded.size());
        assertTrue(new String(world.ssh.uploaded.values().iterator().next()).contains("<p>本文</p>"));
    }

    @Test
    void signedUrl_認可拒否ならサービスを呼ばずForbidden() {
        world.stubActor(MEMBER, 10L, "user", "m@example.com").stubProjectMember(1L, 10L, false).asUser(MEMBER);

        assertThrows(ForbiddenException.class, () -> controller.signedUrl(1L,
                new SignedPreviewUrlRequest(null, "題", "<p>x</p>", null, null, null, null)));

        assertTrue(world.ssh.commands.isEmpty(), "WordPressには触れない");
        assertTrue(world.project.requests().isEmpty(), "プロジェクトの解決も始まらない");
    }
}
