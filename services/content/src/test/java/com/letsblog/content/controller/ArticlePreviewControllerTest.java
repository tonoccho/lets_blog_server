package com.letsblog.content.controller;

import com.letsblog.content.dto.RenderPreviewRequest;
import com.letsblog.content.dto.RenderPreviewResponse;
import com.letsblog.content.service.AdminAuthorizationService;
import com.letsblog.content.service.ArticlePreviewService;
import com.letsblog.content.service.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * legacy-apiのArticlePreviewControllerTestのうち、content-serviceへ移設した記事本文レンダリング
 * (/render)の振る舞いを引き継いだテスト(issue #576)。
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

    @Test
    void render_認可後にサービスへ委譲する() {
        ArticlePreviewController controller = controller();
        when(articlePreviewService.renderHtml(1L, "# タイトル")).thenReturn("<h1>タイトル</h1>");

        RenderPreviewResponse response = controller.render(1L, new RenderPreviewRequest("# タイトル"));

        assertEquals("<h1>タイトル</h1>", response.html());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void render_認可拒否ならForbidden() {
        ArticlePreviewController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> controller.render(1L, new RenderPreviewRequest("markdown")));
    }
}
