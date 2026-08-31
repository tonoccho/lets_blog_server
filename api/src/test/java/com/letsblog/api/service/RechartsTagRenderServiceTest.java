package com.letsblog.api.service;

import com.letsblog.api.render.RechartsChartConfig;
import com.letsblog.api.render.RechartsRenderException;
import com.letsblog.api.render.RechartsRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RechartsTagRenderServiceTest {

    @Mock
    private RechartsRenderer rechartsRenderer;

    private RechartsTagRenderService service;

    @BeforeEach
    void setUp() {
        service = new RechartsTagRenderService(rechartsRenderer);
        lenient().when(rechartsRenderer.render(any())).thenReturn("<div class=\"recharts-wrapper\" "
                + "style=\"position: relative; width: 700px; height: 300px;\"><svg></svg></div>");
    }

    private String barTag(String attrs, String table) {
        return "[recharts type=\"bar\" xAxis=\"month\"" + attrs + "]\n" + table + "\n[/recharts]";
    }

    private static final String SIMPLE_TABLE =
            "| month | revenue |\n|-------|---------|\n| 2024-01 | 100000 |\n| 2024-02 | 120000 |";

    @Test
    void render_rechartsタグが無い場合はそのまま返す() {
        String markdown = "普通の本文です。";

        String result = service.render(markdown);

        assertEquals(markdown, result);
        verifyNoInteractions(rechartsRenderer);
    }

    @Test
    void render_bar型のタグをチャートHTMLへ展開する() {
        String markdown = "前文\n" + barTag("", SIMPLE_TABLE) + "\n後文";

        String result = service.render(markdown);

        assertTrue(result.contains("前文"));
        assertTrue(result.contains("後文"));
        assertTrue(result.contains("class=\"lb-recharts\""));
        assertTrue(result.contains("<svg>"));
        assertFalse(result.contains("[recharts"));
    }

    @Test
    void render_タイトル属性が指定された場合は見出しを埋め込む() {
        String markdown = barTag(" title=\"売上推移\"", SIMPLE_TABLE);

        String result = service.render(markdown);

        assertTrue(result.contains("売上推移"));
        assertTrue(result.contains("lb-recharts-title"));
    }

    @Test
    void render_タイトルはHTMLエスケープする() {
        String markdown = barTag(" title=\"<script>alert(1)</script>\"", SIMPLE_TABLE);

        String result = service.render(markdown);

        assertFalse(result.contains("<script>"));
        assertTrue(result.contains("&lt;script&gt;"));
    }

    @Test
    void render_複数のrechartsタグをすべて展開する() {
        String markdown = barTag("", SIMPLE_TABLE) + "\n\n" + barTag("", SIMPLE_TABLE);

        String result = service.render(markdown);

        assertEquals(2, result.split("lb-recharts\"").length - 1);
    }

    @Test
    void render_type属性が無い場合はInvalidRechartsTagException() {
        String markdown = "[recharts xAxis=\"month\"]\n" + SIMPLE_TABLE + "\n[/recharts]";

        InvalidRechartsTagException e = assertThrows(InvalidRechartsTagException.class,
                () -> service.render(markdown));
        assertTrue(e.getMessage().contains("type属性"));
    }

    @Test
    void render_type属性の値が不正な場合はInvalidRechartsTagException() {
        String markdown = "[recharts type=\"scatter\" xAxis=\"month\"]\n" + SIMPLE_TABLE + "\n[/recharts]";

        InvalidRechartsTagException e = assertThrows(InvalidRechartsTagException.class,
                () -> service.render(markdown));
        assertTrue(e.getMessage().contains("scatter"));
    }

    @Test
    void render_bar型でxAxis属性が無い場合はInvalidRechartsTagException() {
        String markdown = "[recharts type=\"bar\"]\n" + SIMPLE_TABLE + "\n[/recharts]";

        InvalidRechartsTagException e = assertThrows(InvalidRechartsTagException.class,
                () -> service.render(markdown));
        assertTrue(e.getMessage().contains("xAxis"));
    }

    @Test
    void render_xAxisが見出しに存在しない場合はInvalidRechartsTagException() {
        String markdown = "[recharts type=\"bar\" xAxis=\"unknown\"]\n" + SIMPLE_TABLE + "\n[/recharts]";

        InvalidRechartsTagException e = assertThrows(InvalidRechartsTagException.class,
                () -> service.render(markdown));
        assertTrue(e.getMessage().contains("unknown"));
    }

    @Test
    void render_pie型はカラム数が2以外だとInvalidRechartsTagException() {
        String markdown = "[recharts type=\"pie\"]\n" + SIMPLE_TABLE.replace("| revenue |", "| revenue | extra |")
                .replace("|---------|", "|---------|-------|")
                .replace("| 100000 |", "| 100000 | x |")
                .replace("| 120000 |", "| 120000 | y |")
                + "\n[/recharts]";

        InvalidRechartsTagException e = assertThrows(InvalidRechartsTagException.class,
                () -> service.render(markdown));
        assertTrue(e.getMessage().contains("pie"));
    }

    @Test
    void render_pie型は2カラムのテーブルを項目名と数値として扱う() {
        String markdown = "[recharts type=\"pie\" title=\"割合\"]\n"
                + "| device | users |\n|--------|-------|\n| Desktop | 45000 |\n| Mobile | 35000 |\n[/recharts]";

        service.render(markdown);

        ArgumentCaptor<RechartsChartConfig> captor = ArgumentCaptor.forClass(RechartsChartConfig.class);
        verify(rechartsRenderer).render(captor.capture());
        RechartsChartConfig config = captor.getValue();
        assertEquals("pie", config.type());
        assertEquals(List.of("device", "users"), config.seriesKeys());
        assertEquals("Desktop", config.data().get(0).get("device"));
        assertEquals(45000.0, config.data().get(0).get("users"));
    }

    @Test
    void render_数値として解釈できないセルはInvalidRechartsTagException() {
        String table = "| month | revenue |\n|-------|---------|\n| 2024-01 | abc |";
        String markdown = barTag("", table);

        InvalidRechartsTagException e = assertThrows(InvalidRechartsTagException.class,
                () -> service.render(markdown));
        assertTrue(e.getMessage().contains("行1"));
        assertTrue(e.getMessage().contains("abc"));
    }

    @Test
    void render_数値のカンマ区切りは許容する() {
        String table = "| month | revenue |\n|-------|---------|\n| 2024-01 | 1,000,000 |";
        String markdown = barTag("", table);

        service.render(markdown);

        ArgumentCaptor<RechartsChartConfig> captor = ArgumentCaptor.forClass(RechartsChartConfig.class);
        verify(rechartsRenderer).render(captor.capture());
        assertEquals(1_000_000.0, captor.getValue().data().get(0).get("revenue"));
    }

    @Test
    void render_カラム数が上限を超える場合はInvalidRechartsTagException() {
        String header = "| a | b | c | d | e | f | g | h | i | j | k |";
        String sep = "|---|---|---|---|---|---|---|---|---|---|---|";
        String row = "| 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 | 1 |";
        String markdown = "[recharts type=\"bar\" xAxis=\"a\"]\n" + header + "\n" + sep + "\n" + row + "\n[/recharts]";

        InvalidRechartsTagException e = assertThrows(InvalidRechartsTagException.class,
                () -> service.render(markdown));
        assertTrue(e.getMessage().contains("カラム数"));
    }

    @Test
    void render_stacked属性がconfigへ伝わる() {
        String markdown = barTag(" stacked=\"true\"", SIMPLE_TABLE);

        service.render(markdown);

        ArgumentCaptor<RechartsChartConfig> captor = ArgumentCaptor.forClass(RechartsChartConfig.class);
        verify(rechartsRenderer).render(captor.capture());
        assertTrue(captor.getValue().stacked());
    }

    @Test
    void render_theme_darkでテキスト色とグリッド色が変わる() {
        String markdown = barTag(" theme=\"dark\"", SIMPLE_TABLE);

        service.render(markdown);

        ArgumentCaptor<RechartsChartConfig> captor = ArgumentCaptor.forClass(RechartsChartConfig.class);
        verify(rechartsRenderer).render(captor.capture());
        assertEquals("#e0e0e0", captor.getValue().textColor());
    }

    @Test
    void render_width_height属性がpx指定として解釈される() {
        String markdown = barTag(" width=\"500px\" height=\"250\"", SIMPLE_TABLE);

        service.render(markdown);

        ArgumentCaptor<RechartsChartConfig> captor = ArgumentCaptor.forClass(RechartsChartConfig.class);
        verify(rechartsRenderer).render(captor.capture());
        assertEquals(500, captor.getValue().width());
        assertEquals(250, captor.getValue().height());
    }

    @Test
    void render_width属性がパーセント指定の場合は既定値を使う() {
        String markdown = barTag(" width=\"100%\"", SIMPLE_TABLE);

        service.render(markdown);

        ArgumentCaptor<RechartsChartConfig> captor = ArgumentCaptor.forClass(RechartsChartConfig.class);
        verify(rechartsRenderer).render(captor.capture());
        assertEquals(700, captor.getValue().width());
    }

    @Test
    void render_width属性が範囲外の場合はInvalidRechartsTagException() {
        String markdown = barTag(" width=\"5\"", SIMPLE_TABLE);

        InvalidRechartsTagException e = assertThrows(InvalidRechartsTagException.class,
                () -> service.render(markdown));
        assertTrue(e.getMessage().contains("width"));
    }

    @Test
    void render_responsive属性が既定trueでラッパーのスタイルを可変幅へ書き換える() {
        String markdown = barTag("", SIMPLE_TABLE);

        String result = service.render(markdown);

        assertTrue(result.contains("max-width: 700px"));
        assertTrue(result.contains("aspect-ratio: 700 / 300"));
    }

    @Test
    void render_responsive_falseの場合は固定サイズのまま返す() {
        String markdown = barTag(" responsive=\"false\"", SIMPLE_TABLE);

        String result = service.render(markdown);

        assertTrue(result.contains("width: 700px; height: 300px"));
        assertFalse(result.contains("aspect-ratio"));
    }

    @Test
    void render_レンダリングに失敗した場合はInvalidRechartsTagExceptionへ変換する() {
        when(rechartsRenderer.render(any())).thenThrow(new RechartsRenderException("タイムアウトしました"));
        String markdown = barTag("", SIMPLE_TABLE);

        InvalidRechartsTagException e = assertThrows(InvalidRechartsTagException.class,
                () -> service.render(markdown));
        assertTrue(e.getMessage().contains("タイムアウトしました"));
    }

    @Test
    void render_xAxis以外の列が数値として渡される() {
        String markdown = barTag("", SIMPLE_TABLE);

        service.render(markdown);

        ArgumentCaptor<RechartsChartConfig> captor = ArgumentCaptor.forClass(RechartsChartConfig.class);
        verify(rechartsRenderer).render(captor.capture());
        Map<String, Object> firstRow = captor.getValue().data().get(0);
        assertEquals("2024-01", firstRow.get("month"));
        assertEquals(100000.0, firstRow.get("revenue"));
        assertEquals(List.of("revenue"), captor.getValue().seriesKeys());
    }
}
