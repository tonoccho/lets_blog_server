package com.letsblog.api.controller;

import com.letsblog.api.dto.RenderPreviewRequest;
import com.letsblog.api.dto.RenderPreviewResponse;
import com.letsblog.api.dto.ThemeCssResponse;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.ArticlePreviewService;
import com.letsblog.api.service.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void themeCss_認可後にサービスへ委譲する() {
        ArticlePreviewController controller = controller();
        when(articlePreviewService.fetchMasterThemeCss(1L))
                .thenReturn(new ThemeCssResponse("body{}", true, null));

        ThemeCssResponse response = controller.themeCss(1L);

        assertEquals("body{}", response.css());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void themeCss_認可拒否ならForbidden() {
        ArticlePreviewController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> controller.themeCss(1L));
    }
}
