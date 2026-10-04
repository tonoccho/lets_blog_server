package com.letsblog.publishing.controller;

import com.letsblog.publishing.service.AdminAuthorizationService;
import com.letsblog.publishing.service.ArticlePreviewService;
import com.letsblog.publishing.service.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * publishing-serviceのArticlePreviewController(署名付きプレビューURL、issue #1561)を検証する。
 * 旧プレビュー経路(theme-css/skeleton/preview-post)は issue #1564 で削除し、存在しないことを固定する。
 * 記事本文レンダリング(/render)はcontent-serviceが持つため、その振る舞いはcontent-service側の
 * ArticlePreviewControllerTestで検証する。
 */
@ExtendWith(MockitoExtension.class)
class ArticlePreviewControllerTest {

    @Mock
    private ArticlePreviewService articlePreviewService;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private ArticlePreviewController controller() {
        return new ArticlePreviewController(articlePreviewService, adminAuthorizationService);
    }

    // issue #1564: 旧プレビュー経路(テーマCSS取得・骨組み差し込み・一時投稿の削除)は存在しない。
    // 標準の MockMvc は静的リソースのハンドラを持たないので、マッピングが無ければ 404 になる。
    @Test
    void 旧プレビュー経路のthemeCss_skeleton_previewPostはマッピングが無く404() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller()).build();

        mockMvc.perform(get("/api/projects/1/preview/theme-css").param("siteId", "20"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/projects/1/preview/skeleton")
                        .contentType("application/json")
                        .content("{\"title\":\"t\",\"contentHtml\":\"<p>x</p>\",\"siteId\":20}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/projects/1/preview/preview-post")
                        .param("siteId", "20").param("postId", "123"))
                .andExpect(status().isNotFound());
        org.mockito.Mockito.verifyNoInteractions(articlePreviewService, adminAuthorizationService);
    }

    // ---- issue #1561: 署名付きプレビュー URL ----

    @Test
    void signedUrl_認可後にサービスへ委譲しURLを返す() {
        ArticlePreviewController controller = controller();
        com.letsblog.publishing.dto.SignedPreviewUrlRequest request =
                new com.letsblog.publishing.dto.SignedPreviewUrlRequest(
                        20L, "題", "<p>本文</p>", java.util.List.of("c"), java.util.List.of("t"),
                        "data:image/png;base64,AAAA", 600);
        when(articlePreviewService.createSignedPreviewUrl(1L, request))
                .thenReturn(new com.letsblog.publishing.dto.SignedPreviewUrlResponse("https://x/?letsblog_preview=t", 9L));

        com.letsblog.publishing.dto.SignedPreviewUrlResponse response = controller.signedUrl(1L, request);

        assertEquals("https://x/?letsblog_preview=t", response.url());
        assertEquals(9L, response.expiresAt());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void signedUrl_認可拒否ならサービスを呼ばずForbidden() {
        ArticlePreviewController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> controller.signedUrl(1L,
                new com.letsblog.publishing.dto.SignedPreviewUrlRequest(null, "題", "<p>x</p>", null, null, null, null)));
        org.mockito.Mockito.verifyNoInteractions(articlePreviewService);
    }
}
