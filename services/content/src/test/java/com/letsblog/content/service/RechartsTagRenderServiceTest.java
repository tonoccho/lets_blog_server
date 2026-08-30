package com.letsblog.content.service;

import com.letsblog.content.render.MediaRenderClient;
import com.letsblog.content.render.RechartsRenderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * issue #641: RechartsTagRenderServiceは[recharts]タグの記法・属性・Markdownテーブルの
 * 妥当性検証を行う。BlogCard/Amazonと異なり不正入力は例外を投げて投稿自体を拒否するため、
 * 検証ロジックの網羅性を確認する。
 */
@ExtendWith(MockitoExtension.class)
class RechartsTagRenderServiceTest {

    @Mock
    private MediaRenderClient mediaRenderClient;

    private RechartsTagRenderService service;

    @BeforeEach
    void setUp() {
        service = new RechartsTagRenderService(mediaRenderClient);
    }

    private String validTableBody() {
        return "| month | sales |\n|---|---|\n| Jan | 100 |\n| Feb | 200 |";
    }

    @Test
    void render_タグが無ければそのまま返す() {
        assertEquals("本文だけです", service.render("本文だけです"));
    }

    @Test
    void render_type属性が無い場合は例外を投げる() {
        String markdown = "[recharts]\n" + validTableBody() + "\n[/recharts]";

        InvalidRechartsTagException e = assertThrows(InvalidRechartsTagException.class,
                () -> service.render(markdown));
        assertTrue(e.getMessage().contains("type属性は必須です"));
    }

    @Test
    void render_typeの値が不正な場合は例外を投げる() {
        String markdown = "[recharts type=\"pie3d\"]\n" + validTableBody() + "\n[/recharts]";

        assertThrows(InvalidRechartsTagException.class, () -> service.render(markdown));
    }

    @Test
    void render_bar型でxAxis属性が無い場合は例外を投げる() {
        String markdown = "[recharts type=\"bar\"]\n" + validTableBody() + "\n[/recharts]";

        InvalidRechartsTagException e = assertThrows(InvalidRechartsTagException.class,
                () -> service.render(markdown));
        assertTrue(e.getMessage().contains("xAxis属性は必須です"));
    }

    @Test
    void render_xAxisに指定した列がテーブルに無い場合は例外を投げる() {
        String markdown = "[recharts type=\"bar\" xAxis=\"unknown\"]\n" + validTableBody() + "\n[/recharts]";

        assertThrows(InvalidRechartsTagException.class, () -> service.render(markdown));
    }

    @Test
    void render_数値列に非数値が含まれる場合は例外を投げる() {
        String body = "| month | sales |\n|---|---|\n| Jan | not-a-number |";
        String markdown = "[recharts type=\"bar\" xAxis=\"month\"]\n" + body + "\n[/recharts]";

        InvalidRechartsTagException e = assertThrows(InvalidRechartsTagException.class,
                () -> service.render(markdown));
        assertTrue(e.getMessage().contains("数値が無効です"));
    }

    @Test
    void render_円グラフはカラム数が2でなければ例外を投げる() {
        String markdown = "[recharts type=\"pie\"]\n" + validTableBody() + "\n[/recharts]";
        // validTableBody()は2列(month, sales)なので円グラフ自体は許容されるが、xAxis不要のケースを
        // 別途確認するため、3列のテーブルで検証する。
        String threeColumnBody = "| month | sales | profit |\n|---|---|---|\n| Jan | 100 | 10 |";
        String threeColumnMarkdown = "[recharts type=\"pie\"]\n" + threeColumnBody + "\n[/recharts]";

        assertThrows(InvalidRechartsTagException.class, () -> service.render(threeColumnMarkdown));
    }

    @Test
    void render_表の記法が不正な場合は例外を投げる() {
        String markdown = "[recharts type=\"bar\" xAxis=\"month\"]\n本文のみでテーブルではない\n[/recharts]";

        assertThrows(InvalidRechartsTagException.class, () -> service.render(markdown));
    }

    @Test
    void render_カラム数が上限を超える場合は例外を投げる() {
        StringBuilder header = new StringBuilder("|");
        StringBuilder separator = new StringBuilder("|");
        StringBuilder row = new StringBuilder("|");
        for (int i = 0; i < 11; i++) {
            header.append("col").append(i).append("|");
            separator.append("---|");
            row.append(i).append("|");
        }
        String markdown = "[recharts type=\"bar\" xAxis=\"col0\"]\n"
                + header + "\n" + separator + "\n" + row + "\n[/recharts]";

        InvalidRechartsTagException e = assertThrows(InvalidRechartsTagException.class,
                () -> service.render(markdown));
        assertTrue(e.getMessage().contains("カラム数が上限"));
    }

    @Test
    void render_widthが範囲外の場合は例外を投げる() {
        String markdown = "[recharts type=\"bar\" xAxis=\"month\" width=\"1\"]\n" + validTableBody() + "\n[/recharts]";

        assertThrows(InvalidRechartsTagException.class, () -> service.render(markdown));
    }

    @Test
    void render_正常な入力はレンダリングされたチャートHTMLに展開される() {
        when(mediaRenderClient.renderRecharts(
                anyString(), any(), anyString(), any(), any(), anyBoolean(), anyInt(), anyInt(), anyString(),
                anyString(), any()))
                .thenReturn("<div class=\"recharts-wrapper\" style=\"width: 700px; height: 300px;\"></div>");

        String markdown = "[recharts type=\"bar\" xAxis=\"month\"]\n" + validTableBody() + "\n[/recharts]";
        String result = service.render(markdown);

        assertTrue(result.contains("lb-recharts"));
        assertTrue(result.contains("recharts-wrapper"));
    }

    @Test
    void render_mediaRenderClientが失敗した場合は例外を投げる() {
        when(mediaRenderClient.renderRecharts(
                anyString(), any(), anyString(), any(), any(), anyBoolean(), anyInt(), anyInt(), anyString(),
                anyString(), any()))
                .thenThrow(new RechartsRenderException("レンダリングサーバーに接続できません"));

        String markdown = "[recharts type=\"bar\" xAxis=\"month\"]\n" + validTableBody() + "\n[/recharts]";

        InvalidRechartsTagException e = assertThrows(InvalidRechartsTagException.class,
                () -> service.render(markdown));
        assertTrue(e.getMessage().contains("レンダリングサーバーに接続できません"));
    }
}
