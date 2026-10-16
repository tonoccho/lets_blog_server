package com.letsblog.content.service;

import com.letsblog.content.render.PlantUmlRenderRejectedException;
import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * legacy-apiのPlantUmlTagRenderServiceTestのうち、content-serviceへ移設したプレビュー向け
 * (renderForPreview、CMS認証情報を必要としない)の振る舞いを引き継いだテスト(issue #576)。
 *
 * <p>issue #1821: MediaRenderClientは実物で、media-serviceの代わりにHTTPフェイクを向ける。
 */
class PlantUmlTagRenderServiceTest {

    private static final String RENDER_PATH = "/api/render/plantuml";

    private ContentWorld world;
    private PlantUmlTagRenderService service;

    @BeforeEach
    void setUp() {
        world = new ContentWorld().actingAsAdmin(1L);
        service = world.plantUmlTagRenderService;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private String sentSource() {
        return world.bodyOf(world.http.requests("POST", RENDER_PATH).get(0)).get("source").asText();
    }

    @Test
    void renderForPreview_タグが無ければ何もしない() {
        assertEquals("普通の本文です。", service.renderForPreview("普通の本文です。"));
        assertTrue(world.http.requests("POST", RENDER_PATH).isEmpty());
    }

    @Test
    void renderForPreview_nullや空の本文はそのまま返す() {
        assertNull(service.renderForPreview(null));
        assertEquals("", service.renderForPreview(""));
    }

    @Test
    void renderForPreview_PNGをdata_URIとして埋め込む() {
        String result = service.renderForPreview("本文\n[plantuml]\n@startuml\nA->B\n@enduml\n[/plantuml]\n続き");

        assertTrue(result.contains("![diagram](data:image/png;base64,"));
        assertTrue(result.contains("続き"));
    }

    @Test
    void renderForPreview_startumlが無い場合は自動的に補う() {
        service.renderForPreview("[plantuml]\nA->B\n[/plantuml]");

        assertEquals("@startuml\nA->B\n@enduml", sentSource());
    }

    @Test
    void renderForPreview_既にstartumlがある場合は二重に包まない() {
        service.renderForPreview("[plantuml]\n@startuml\nA->B\n@enduml\n[/plantuml]");

        assertEquals("@startuml\nA->B\n@enduml", sentSource());
    }

    @Test
    void renderForPreview_media呼び出し失敗時はPlantUmlServerUnavailableExceptionを投げる() {
        world.http.status("POST", RENDER_PATH, 503, "{\"error\":\"down\"}");

        PlantUmlServerUnavailableException e = assertThrows(PlantUmlServerUnavailableException.class,
                () -> service.renderForPreview("[plantuml]\nA->B\n[/plantuml]"));
        assertTrue(e.getMessage().contains("PlantUMLサーバーに接続できません"));
    }

    @Test
    void renderForPreview_PlantUMLの拒否は接続不能に変えずそのまま伝える() {
        world.http.status("POST", RENDER_PATH, 400,
                "{\"error\":\"PlantUML が図を描けませんでした: Syntax Error?\"}");

        PlantUmlRenderRejectedException e = assertThrows(PlantUmlRenderRejectedException.class,
                () -> service.renderForPreview("[plantuml]\nA->B\n[/plantuml]"));

        assertTrue(e.getMessage().contains("Syntax Error?"));
    }
}
