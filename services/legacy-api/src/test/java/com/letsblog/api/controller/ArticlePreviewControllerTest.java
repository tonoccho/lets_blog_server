package com.letsblog.api.controller;

import com.letsblog.api.dto.RenderSkeletonRequest;
import com.letsblog.api.dto.ThemeCssResponse;
import com.letsblog.api.dto.ThemeSkeletonResponse;
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

/**
 * legacy-apiのArticlePreviewControllerのうち残っているtheme-css/skeleton/preview-postのみを検証する
 * (issue #576)。記事本文レンダリング(/render)はcontent-serviceへ移設したため、その振る舞いは
 * content-service側のArticlePreviewControllerTestで検証する。
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
    void themeCss_認可後にサービスへ委譲する() {
        ArticlePreviewController controller = controller();
        when(articlePreviewService.fetchThemeCss(1L, null))
                .thenReturn(new ThemeCssResponse("body{}", true, null));

        ThemeCssResponse response = controller.themeCss(1L, null);

        assertEquals("body{}", response.css());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void themeCss_siteId指定時はそのサイトのCSSを返す() {
        ArticlePreviewController controller = controller();
        when(articlePreviewService.fetchThemeCss(1L, 20L))
                .thenReturn(new ThemeCssResponse("body{color:red}", true, null));

        ThemeCssResponse response = controller.themeCss(1L, 20L);

        assertEquals("body{color:red}", response.css());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void themeCss_認可拒否ならForbidden() {
        ArticlePreviewController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> controller.themeCss(1L, null));
    }

    @Test
    void skeleton_認可後にサービスへ委譲する() {
        ArticlePreviewController controller = controller();
        RenderSkeletonRequest request =
                new RenderSkeletonRequest(
                        "タイトル", "<p>本文</p>", "data:image/png;base64,abc", 20L, null, null, null, null);
        when(articlePreviewService.renderSkeleton(
                        1L, 20L, "タイトル", "<p>本文</p>", "data:image/png;base64,abc", null, null, null, null))
                .thenReturn(new ThemeSkeletonResponse("<article>spliced</article>", true, null, true, ""));

        ThemeSkeletonResponse response = controller.skeleton(1L, request);

        assertEquals("<article>spliced</article>", response.html());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
    }

    @Test
    void skeleton_認可拒否ならForbidden() {
        ArticlePreviewController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class,
                () -> controller.skeleton(1L,
                        new RenderSkeletonRequest("タイトル", "<p>本文</p>", null, null, null, null, null, null)));
    }

    // issue #568: 認可マトリクス整備に伴う、requireProjectMemberOrAdmin()を呼ぶ全メソッドのForbiddenパス網羅
    @Test
    void deletePreviewPost_認可後にサービスへ委譲する() {
        ArticlePreviewController controller = controller();

        controller.deletePreviewPost(1L, 20L, "123");

        verify(adminAuthorizationService).requireProjectMemberOrAdmin(1L);
        verify(articlePreviewService).deletePreviewPost(1L, 20L, "123");
    }

    @Test
    void deletePreviewPost_認可拒否ならForbidden() {
        ArticlePreviewController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(1L);

        assertThrows(ForbiddenException.class, () -> controller.deletePreviewPost(1L, 20L, "123"));
    }
}
