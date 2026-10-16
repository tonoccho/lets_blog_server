package com.letsblog.content.service;

import com.letsblog.content.client.AiServiceException;
import com.letsblog.content.render.PlantUmlRenderRejectedException;
import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * legacy-apiのPlantUmlEmbedServiceTestのうち、content-serviceへ移設したプレビュー向け
 * (embedDiagramsForPreview、CMS認証情報を必要としない)の振る舞いを引き継いだテスト(issue #576)。
 *
 * <p>issue #1821: MediaRenderClientは実物で、media-serviceの代わりにHTTPフェイクを向ける。
 */
class PlantUmlEmbedServiceTest {

    private static final String RENDER_PATH = "/api/render/plantuml";

    private ContentWorld world;
    private PlantUmlEmbedService service;

    @BeforeEach
    void setUp() {
        world = new ContentWorld().actingAsAdmin(1L);
        service = world.plantUmlEmbedService;
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void embedDiagramsForPreview_data_URIとして埋め込みアップロードしない() {
        String result = service.embedDiagramsForPreview("```plantuml\nA->B\n```");

        String expected = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
        assertTrue(result.contains("![diagram](data:image/png;base64," + expected + ")"));
        // 呼び出し元のBearerトークンを転送し、@startuml/@endumlで包んで送る
        var request = world.http.requests("POST", RENDER_PATH).get(0);
        assertEquals("Bearer token-1", request.header("Authorization"));
        assertEquals("@startuml\nA->B\n@enduml", world.bodyOf(request).get("source").asText());
    }

    @Test
    void embedDiagramsForPreview_startumlがあれば二重に包まない() {
        service.embedDiagramsForPreview("```plantuml\n@startuml\nA->B\n@enduml\n```");

        assertEquals("@startuml\nA->B\n@enduml",
                world.bodyOf(world.http.requests("POST", RENDER_PATH).get(0)).get("source").asText());
    }

    @Test
    void embedDiagramsForPreview_図が無ければmediaを呼ばない() {
        assertEquals("本文", service.embedDiagramsForPreview("本文"));
        assertTrue(world.http.requests("POST", RENDER_PATH).isEmpty());
    }

    /**
     * 実物のMediaRenderClientは、通信の失敗をSyncServiceExceptionのまま返さずAiServiceExceptionに包む。
     * そのため、embedDiagramsForPreviewのSyncServiceExceptionを捕まえてプレースホルダに差し替える分岐は
     * 実際には使われず、media-serviceの障害は呼び出し元へ例外として伝わる(docs/coverage/content-service.md参照)。
     */
    @Test
    void embedDiagramsForPreview_media呼び出しに失敗すると例外が呼び出し元へ伝わる() {
        world.http.status("POST", RENDER_PATH, 503, "{\"error\":\"down\"}");

        AiServiceException e = assertThrows(AiServiceException.class,
                () -> service.embedDiagramsForPreview("前\n```plantuml\nA->B\n```\n後"));

        assertTrue(e.getMessage().contains("PlantUMLレンダリング呼び出しに失敗しました"));
    }

    @Test
    void embedDiagramsForPreview_PlantUMLの拒否も例外として伝わる() {
        world.http.status("POST", RENDER_PATH, 400,
                "{\"error\":\"PlantUML が図を描けませんでした: Syntax Error?\"}");

        PlantUmlRenderRejectedException e = assertThrows(PlantUmlRenderRejectedException.class,
                () -> service.embedDiagramsForPreview("```plantuml\nbad\n```"));

        assertTrue(e.getMessage().contains("Syntax Error?"));
    }
}
