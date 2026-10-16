package com.letsblog.content.controller;

import com.letsblog.content.dto.RenderPreviewRequest;
import com.letsblog.content.dto.RenderPreviewResponse;
import com.letsblog.content.service.ForbiddenException;
import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * legacy-apiのArticlePreviewControllerTestのうち、content-serviceへ移設した記事本文レンダリング
 * (/render)の振る舞いを引き継いだテスト(issue #576)。
 *
 * <p>コントローラ・{@code ArticlePreviewService}・{@code AdminAuthorizationService}は実物で、差し替えるのは
 * 外部との通信(identity-service・project-serviceのHTTP、DB)だけ({@link ContentWorld})。
 */
class ArticlePreviewControllerTest {

    private ContentWorld world;
    private ArticlePreviewController controller;

    @BeforeEach
    void setUp() {
        world = new ContentWorld();
        controller = new ArticlePreviewController(world.articlePreviewService, world.adminAuthorizationService);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void render_認可後にサービスへ委譲する() {
        world.actingAsAdmin(1L);

        RenderPreviewResponse response = controller.render(1L, new RenderPreviewRequest("# タイトル"));

        assertTrue(response.html().contains(">タイトル</h1>"), response.html());
    }

    @Test
    void render_プロジェクトメンバーなら委譲する() {
        world.actingAsUser(2L).projectMember(1L, 2L, true);

        RenderPreviewResponse response = controller.render(1L, new RenderPreviewRequest("本文"));

        assertTrue(response.html().contains("本文"), response.html());
    }

    @Test
    void render_認可拒否ならForbiddenでレンダリングしない() {
        world.actingAsUser(2L).projectMember(1L, 2L, false);

        assertThrows(ForbiddenException.class, () -> controller.render(1L, new RenderPreviewRequest("markdown")));
    }
}
