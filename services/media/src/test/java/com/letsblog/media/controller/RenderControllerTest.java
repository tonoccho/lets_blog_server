package com.letsblog.media.controller;

import com.letsblog.media.dto.PlantUmlRenderRequest;
import com.letsblog.media.dto.RechartsRenderResponse;
import com.letsblog.media.render.PlantUmlClient;
import com.letsblog.media.render.RechartsChartConfig;
import com.letsblog.media.render.RechartsRenderer;
import com.letsblog.media.testsupport.FakeHttpServer;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RenderController(#573)の回帰テスト。PlantUMLは legacy-api から移設した既存エンドポイントの挙動、
 * Rechartsはlegacy-api側の{@code PlantUmlEmbedService}等が委譲する先として新設した
 * エンドポイントの応答形状を検証する。
 *
 * <p>issue #1820: {@code PlantUmlClient}・{@code RechartsRenderer}は実物。PlantUMLサーバーはHTTPのサーバー側を
 * 模したフェイクで、ヘッドレスChromiumは外部プロセスなので{@code Browser}・{@code Page}だけをモックする。
 */
class RenderControllerTest {

    private final FakeHttpServer plantUml = new FakeHttpServer();
    private final Browser browser = mock(Browser.class);

    private RenderController controller;

    @BeforeEach
    void setUp() {
        controller = new RenderController(new PlantUmlClient(plantUml.baseUrl()), new RechartsRenderer(browser));
    }

    @AfterEach
    void close() {
        plantUml.close();
    }

    @Test
    void renderPlantUml_PNGバイト列をそのまま返す() {
        byte[] png = new byte[]{1, 2, 3};
        plantUml.on("GET", "/png/.*", FakeHttpServer.Response.bytes(200, "image/png", png));

        ResponseEntity<byte[]> response = controller.renderPlantUml(new PlantUmlRenderRequest("@startuml\nA->B\n@enduml"));

        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertArrayEquals(png, response.getBody());
        assertEquals(1, plantUml.requests("GET", "/png/.*").size());
    }

    @Test
    void renderRecharts_レンダリング結果をhtmlフィールドへ包んで返す() {
        RechartsChartConfig config = new RechartsChartConfig(
                "bar", List.of(Map.of("month", "2024-01", "revenue", 100000.0)), "month", List.of("revenue"),
                List.of("#4e79a7"), false, 700, 300, "#333333", "#e0e0e0", null);
        Page page = mock(Page.class);
        Map<String, Object> evaluated = new HashMap<>();
        evaluated.put("result", "<div class=\"recharts-wrapper\"></div>");
        evaluated.put("error", null);
        lenient().when(page.evaluate("() => ({ result: window.__chartResult, error: window.__chartError })"))
                .thenReturn(evaluated);
        when(browser.newPage()).thenReturn(page);

        RechartsRenderResponse response = controller.renderRecharts(config);

        assertEquals("<div class=\"recharts-wrapper\"></div>", response.html());
        verify(page).close();
        assertTrue(response.html().contains("recharts-wrapper"));
    }
}
