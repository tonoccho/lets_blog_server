package com.letsblog.content.service;

import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** RenderedContentWrapperService(issue #1821)。ProjectContentSettingsServiceは実物、project-serviceはHTTPフェイク。 */
class RenderedContentWrapperServiceTest {

    private ContentWorld world;

    @BeforeEach
    void setUp() {
        world = new ContentWorld().actingAsAdmin(1L);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void htmlがnullや空なら何もラップせずそのまま返す() {
        assertNull(world.renderedContentWrapperService.wrap(null, 1L));
        assertEquals("", world.renderedContentWrapperService.wrap("", 1L));
    }

    @Test
    void projectIdが無ければ固定クラスだけのdivで囲む() {
        assertEquals("<div class=\"lets-blog-rendered\"><p>x</p></div>",
                world.renderedContentWrapperService.wrap("<p>x</p>", null));
        assertTrue(world.http.requests().isEmpty());
    }

    @Test
    void projectIdがあればプロジェクトのプレフィックスもクラスに含める() {
        world.settingsFor(2L, "acme");

        assertEquals("<div class=\"lets-blog-rendered acme\"><p>x</p></div>",
                world.renderedContentWrapperService.wrap("<p>x</p>", 2L));
    }

    @Test
    void プレフィックスが未設定ならproject_serviceのslugを使う() {
        world.projectSlug(3L, "proj-slug");

        assertEquals("<div class=\"lets-blog-rendered proj-slug\">x</div>",
                world.renderedContentWrapperService.wrap("x", 3L));
    }
}
