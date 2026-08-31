package com.letsblog.api.controller;

import com.letsblog.api.dto.CustomTagPreviewRequest;
import com.letsblog.api.dto.CustomTagPreviewResponse;
import com.letsblog.api.dto.CustomTagResponse;
import com.letsblog.api.service.AdminAuthorizationService;
import com.letsblog.api.service.AmazonTagRenderService;
import com.letsblog.api.service.BlogCardTagRenderService;
import com.letsblog.api.service.CustomTagRenderService;
import com.letsblog.api.service.CustomTagService;
import com.letsblog.api.service.ForbiddenException;
import com.letsblog.api.service.RenderedContentWrapperService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectCustomTagControllerTest {

    @Mock
    private CustomTagService customTagService;

    @Mock
    private CustomTagRenderService customTagRenderService;

    @Mock
    private BlogCardTagRenderService blogCardTagRenderService;

    @Mock
    private AmazonTagRenderService amazonTagRenderService;

    @Mock
    private RenderedContentWrapperService renderedContentWrapperService;

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    private ProjectCustomTagController controller() {
        return new ProjectCustomTagController(
                customTagService, customTagRenderService, blogCardTagRenderService, amazonTagRenderService,
                renderedContentWrapperService, adminAuthorizationService);
    }

    @Test
    void list_認可後にプロジェクトスコープのみを返す() {
        ProjectCustomTagController controller = controller();
        when(customTagService.listByProject(5L)).thenReturn(List.of());

        List<CustomTagResponse> response = controller.list(5L);

        assertEquals(0, response.size());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(5L);
        verify(customTagService).listByProject(5L);
    }

    @Test
    void list_認可拒否ならForbidden() {
        ProjectCustomTagController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(5L);

        assertThrows(ForbiddenException.class, () -> controller.list(5L));
        verify(customTagService, never()).listByProject(5L);
    }

    @Test
    void cssBundle_認可後にプロジェクトスコープのCSSを返す() {
        ProjectCustomTagController controller = controller();
        when(customTagService.buildProjectCssBundle(5L)).thenReturn(".alert { color: red; }");

        var response = controller.cssBundle(5L);

        assertEquals(200, response.getStatusCode().value());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(5L);
    }

    @Test
    void cssBundle_認可拒否ならForbidden() {
        ProjectCustomTagController controller = controller();
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(5L);

        assertThrows(ForbiddenException.class, () -> controller.cssBundle(5L));
        verify(customTagService, never()).buildProjectCssBundle(5L);
    }

    @Test
    void preview_認可後に実際の投稿と同じレンダリングでプレビューを返す() {
        ProjectCustomTagController controller = controller();
        CustomTagPreviewRequest request =
                new CustomTagPreviewRequest("<div class=\"alert\">{{content}}</div>", ".alert { color: red; }", "**bold**");
        when(blogCardTagRenderService.render(request.testContent(), 5L)).thenReturn(request.testContent());
        when(amazonTagRenderService.render(request.testContent(), 5L, false)).thenReturn(request.testContent());
        when(customTagRenderService.previewTemplate(request.htmlTemplate(), request.testContent()))
                .thenReturn("<div class=\"alert\"><strong>bold</strong></div>");
        when(renderedContentWrapperService.wrap("<div class=\"alert\"><strong>bold</strong></div>", 5L))
                .thenReturn("<div class=\"lets-blog-rendered my-blog\"><div class=\"alert\"><strong>bold</strong></div></div>");
        when(customTagService.previewCss(request.cssContent(), 5L)).thenReturn(".my-blog .alert { color: red; }");

        CustomTagPreviewResponse response = controller.preview(5L, request);

        assertEquals(
                "<div class=\"lets-blog-rendered my-blog\"><div class=\"alert\"><strong>bold</strong></div></div>",
                response.html());
        assertEquals(".my-blog .alert { color: red; }", response.css());
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(5L);
    }

    @Test
    void preview_組み込みタグを実際の投稿と同じくレンダリング後に差し込む() {
        ProjectCustomTagController controller = controller();
        CustomTagPreviewRequest request = new CustomTagPreviewRequest(
                "<div>{{content}}</div>", null, "[blogcard https://example.com]\n\n[amazon https://amazon.co.jp/dp/X]");
        when(blogCardTagRenderService.render(request.testContent(), 5L))
                .thenReturn("<a class=\"lb-blogcard\">card</a>\n\n[amazon https://amazon.co.jp/dp/X]");
        when(amazonTagRenderService.render(
                        "<a class=\"lb-blogcard\">card</a>\n\n[amazon https://amazon.co.jp/dp/X]", 5L, false))
                .thenReturn("<a class=\"lb-blogcard\">card</a>\n\n<a class=\"lb-amazon-card\">product</a>");
        when(customTagRenderService.previewTemplate(
                        request.htmlTemplate(),
                        "<a class=\"lb-blogcard\">card</a>\n\n<a class=\"lb-amazon-card\">product</a>"))
                .thenReturn("<div><a class=\"lb-blogcard\">card</a><a class=\"lb-amazon-card\">product</a></div>");
        when(renderedContentWrapperService.wrap(
                        "<div><a class=\"lb-blogcard\">card</a><a class=\"lb-amazon-card\">product</a></div>", 5L))
                .thenReturn("<div class=\"lets-blog-rendered my-blog\">wrapped</div>");
        when(customTagService.previewCss(null, 5L)).thenReturn("");

        CustomTagPreviewResponse response = controller.preview(5L, request);

        assertEquals("<div class=\"lets-blog-rendered my-blog\">wrapped</div>", response.html());
    }

    @Test
    void preview_認可拒否ならForbidden() {
        ProjectCustomTagController controller = controller();
        CustomTagPreviewRequest request = new CustomTagPreviewRequest("<div>{{content}}</div>", null, "本文");
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(5L);

        assertThrows(ForbiddenException.class, () -> controller.preview(5L, request));
        verify(blogCardTagRenderService, never()).render(any(), any());
        verify(amazonTagRenderService, never()).render(any(), any(), anyBoolean());
        verify(customTagRenderService, never()).previewTemplate(any(), any());
    }
}
