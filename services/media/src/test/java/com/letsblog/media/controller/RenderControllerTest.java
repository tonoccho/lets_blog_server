package com.letsblog.media.controller;

import com.letsblog.media.ai.PenpotClient;
import com.letsblog.media.dto.PenpotDesignFileRequest;
import com.letsblog.media.dto.PenpotDesignFileResponse;
import com.letsblog.media.dto.PlantUmlRenderRequest;
import com.letsblog.media.dto.RechartsRenderResponse;
import com.letsblog.media.render.PlantUmlClient;
import com.letsblog.media.render.RechartsChartConfig;
import com.letsblog.media.render.RechartsRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RenderController(#573)の回帰テスト。PlantUMLは legacy-api から移設した既存エンドポイントの挙動、
 * Recharts/Penpotはlegacy-api側の{@code PlantUmlEmbedService}等が委譲する先として新設した
 * エンドポイントの応答形状を検証する。
 */
@ExtendWith(MockitoExtension.class)
class RenderControllerTest {

    @Mock
    private PlantUmlClient plantUmlClient;
    @Mock
    private RechartsRenderer rechartsRenderer;
    @Mock
    private PenpotClient penpotClient;

    private RenderController controller;

    @BeforeEach
    void setUp() {
        controller = new RenderController(plantUmlClient, rechartsRenderer, penpotClient);
    }

    @Test
    void renderPlantUml_PNGバイト列をそのまま返す() {
        byte[] png = new byte[]{1, 2, 3};
        when(plantUmlClient.renderPng("@startuml\nA->B\n@enduml")).thenReturn(png);

        ResponseEntity<byte[]> response = controller.renderPlantUml(new PlantUmlRenderRequest("@startuml\nA->B\n@enduml"));

        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertArrayEquals(png, response.getBody());
    }

    @Test
    void renderRecharts_レンダリング結果をhtmlフィールドへ包んで返す() {
        RechartsChartConfig config = new RechartsChartConfig(
                "bar", List.of(Map.of("month", "2024-01", "revenue", 100000.0)), "month", List.of("revenue"),
                List.of("#4e79a7"), false, 700, 300, "#333333", "#e0e0e0", null);
        when(rechartsRenderer.render(config)).thenReturn("<div class=\"recharts-wrapper\"></div>");

        RechartsRenderResponse response = controller.renderRecharts(config);

        assertEquals("<div class=\"recharts-wrapper\"></div>", response.html());
        verify(rechartsRenderer).render(config);
    }

    @Test
    void createPenpotDesignFile_PenpotClientのDesignFileをレスポンスDTOへ写す() {
        PenpotClient.DesignFile designFile = new PenpotClient.DesignFile(
                "file-id", "project-id", "http://localhost:9001/#/view/file-id?page-id=p&share-id=s");
        when(penpotClient.createDesignFile(eq("カスタムタグ: my-button"), any())).thenReturn(designFile);

        PenpotDesignFileResponse response = controller.createPenpotDesignFile(
                new PenpotDesignFileRequest("カスタムタグ: my-button", "プロンプト内容"));

        assertEquals("file-id", response.fileId());
        assertEquals("project-id", response.projectId());
        assertEquals("http://localhost:9001/#/view/file-id?page-id=p&share-id=s", response.url());
    }
}
