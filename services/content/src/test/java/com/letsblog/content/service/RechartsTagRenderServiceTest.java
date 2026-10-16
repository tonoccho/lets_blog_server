package com.letsblog.content.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.content.testsupport.ContentWorld;
import com.letsblog.content.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #641: RechartsTagRenderServiceは[recharts]タグの記法・属性・Markdownテーブルの
 * 妥当性検証を行う。BlogCard/Amazonと異なり不正入力は例外を投げて投稿自体を拒否するため、
 * 検証ロジックの網羅性を確認する。
 *
 * <p>issue #1821: MediaRenderClientは実物で、media-serviceの代わりにHTTPフェイクを向ける。
 * 検証を通った入力が実際にmedia-serviceへ送られる内容も確かめる。
 */
class RechartsTagRenderServiceTest {

    private static final String RENDER_PATH = "/api/render/recharts";
    private static final String WRAPPER_HTML = "<div class=\"recharts-wrapper\" style=\"width: 700px; height: 300px;\"></div>";

    private ContentWorld world;
    private RechartsTagRenderService service;

    @BeforeEach
    void setUp() {
        world = new ContentWorld().actingAsAdmin(1L);
        service = world.rechartsTagRenderService;
        world.http.json("POST", RENDER_PATH, "{\"html\":\"" + WRAPPER_HTML.replace("\"", "\\\"") + "\"}");
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private String validTableBody() {
        return "| month | sales |\n|---|---|\n| Jan | 100 |\n| Feb | 200 |";
    }

    private String bar(String attrs, String body) {
        return "[recharts type=\"bar\" xAxis=\"month\"" + attrs + "]\n" + body + "\n[/recharts]";
    }

    private JsonNode sent() {
        return world.bodyOf(world.http.requests("POST", RENDER_PATH).get(0));
    }

    private String messageOf(String markdown) {
        return assertThrows(InvalidRechartsTagException.class, () -> service.render(markdown)).getMessage();
    }

    @Test
    void render_タグが無ければそのまま返す() {
        assertEquals("本文だけです", service.render("本文だけです"));
        assertTrue(world.http.requests("POST", RENDER_PATH).isEmpty());
    }

    @Test
    void render_nullや空の本文はそのまま返す() {
        assertNull(service.render(null));
        assertEquals("", service.render(""));
    }

    @Test
    void render_type属性が無い場合は例外を投げる() {
        String markdown = "[recharts]\n" + validTableBody() + "\n[/recharts]";

        assertTrue(messageOf(markdown).contains("type属性は必須です"));
    }

    @Test
    void render_type属性が空白の場合は例外を投げる() {
        String markdown = "[recharts type=\" \"]\n" + validTableBody() + "\n[/recharts]";

        assertTrue(messageOf(markdown).contains("type属性は必須です"));
    }

    @Test
    void render_typeの値が不正な場合は例外を投げる() {
        String markdown = "[recharts type=\"pie3d\"]\n" + validTableBody() + "\n[/recharts]";

        assertTrue(messageOf(markdown).contains("type属性の値が不正です"));
    }

    @Test
    void render_bar型でxAxis属性が無い場合は例外を投げる() {
        String markdown = "[recharts type=\"bar\"]\n" + validTableBody() + "\n[/recharts]";

        assertTrue(messageOf(markdown).contains("xAxis属性は必須です"));
    }

    @Test
    void render_xAxisに指定した列がテーブルに無い場合は例外を投げる() {
        String markdown = "[recharts type=\"bar\" xAxis=\"unknown\"]\n" + validTableBody() + "\n[/recharts]";

        assertTrue(messageOf(markdown).contains("見つかりません"));
    }

    @Test
    void render_xAxis以外のデータ列が無い場合は例外を投げる() {
        String markdown = bar("", "| month |\n|---|\n| Jan |");

        assertTrue(messageOf(markdown).contains("xAxis以外のデータ列がありません"));
    }

    @Test
    void render_数値列に非数値が含まれる場合は例外を投げる() {
        String markdown = bar("", "| month | sales |\n|---|---|\n| Jan | not-a-number |");

        assertTrue(messageOf(markdown).contains("数値が無効です"));
    }

    @Test
    void render_数値列が空の場合は例外を投げる() {
        String markdown = bar("", "| month | sales |\n|---|---|\n| Jan |  |");

        assertTrue(messageOf(markdown).contains("数値が無効です"));
    }

    @Test
    void render_円グラフはカラム数が2でなければ例外を投げる() {
        String markdown = "[recharts type=\"pie\"]\n"
                + "| month | sales | profit |\n|---|---|---|\n| Jan | 100 | 10 |\n[/recharts]";

        assertTrue(messageOf(markdown).contains("カラム数が2"));
    }

    @Test
    void render_円グラフは項目名の列を数値チェックの対象外にしxAxisとyAxisを送らない() {
        String markdown = "[recharts type=\"pie\" yAxis=\"件数\"]\n"
                + "| name | count |\n|---|---|\n| りんご | 1,200 |\n| みかん | 3.5 |\n[/recharts]";

        String result = service.render(markdown);

        JsonNode body = sent();
        assertEquals("pie", body.get("type").asText());
        assertTrue(body.get("xAxisKey").isNull());
        assertTrue(body.get("yAxisLabel").isNull());
        assertEquals("name", body.get("seriesKeys").get(0).asText());
        assertEquals("count", body.get("seriesKeys").get(1).asText());
        assertEquals("りんご", body.get("data").get(0).get("name").asText());
        assertEquals(1200.0, body.get("data").get(0).get("count").asDouble());
        assertEquals(3.5, body.get("data").get(1).get("count").asDouble());
        assertTrue(result.contains("lb-recharts"));
    }

    @Test
    void render_表の記法が不正な場合は例外を投げる() {
        String markdown = bar("", "本文のみでテーブルではない");

        assertFalse(messageOf(markdown).isBlank());
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

        assertTrue(messageOf(markdown).contains("カラム数が上限"));
    }

    @Test
    void render_データ行数が上限を超える場合は例外を投げる() {
        StringBuilder body = new StringBuilder("| month | sales |\n|---|---|\n");
        for (int i = 0; i < 1001; i++) {
            body.append("| m").append(i).append(" | ").append(i).append(" |\n");
        }

        assertTrue(messageOf(bar("", body.toString())).contains("データ行数が上限"));
    }

    @Test
    void render_widthが範囲外の場合は例外を投げる() {
        assertTrue(messageOf(bar(" width=\"1\"", validTableBody())).contains("範囲で指定してください"));
        assertTrue(messageOf(bar(" height=\"2001\"", validTableBody())).contains("範囲で指定してください"));
    }

    @Test
    void render_widthが数値でない場合は例外を投げる() {
        assertTrue(messageOf(bar(" width=\"wide\"", validTableBody())).contains("width属性の値が不正です"));
    }

    @Test
    void render_サイズはpx付きと空白と既定値とパーセント指定を受け付ける() {
        service.render(bar(" width=\"800px\" height=\"400\"", validTableBody()));
        service.render(bar(" width=\"50%\" height=\" \"", validTableBody()));
        service.render(bar("", validTableBody()));

        var requests = world.http.requests("POST", RENDER_PATH);
        assertEquals(800, world.bodyOf(requests.get(0)).get("width").asInt());
        assertEquals(400, world.bodyOf(requests.get(0)).get("height").asInt());
        assertEquals(700, world.bodyOf(requests.get(1)).get("width").asInt());
        assertEquals(300, world.bodyOf(requests.get(1)).get("height").asInt());
        assertEquals(700, world.bodyOf(requests.get(2)).get("width").asInt());
    }

    @Test
    void render_正常な入力はレンダリングされたチャートHTMLに展開される() {
        String result = service.render(bar("", validTableBody()));

        assertTrue(result.contains("lb-recharts"));
        assertTrue(result.contains("recharts-wrapper"));
        JsonNode body = sent();
        assertEquals("bar", body.get("type").asText());
        assertEquals("month", body.get("xAxisKey").asText());
        assertEquals("sales", body.get("seriesKeys").get(0).asText());
        assertEquals(100.0, body.get("data").get(0).get("sales").asDouble());
        assertEquals("Jan", body.get("data").get(0).get("month").asText());
        assertEquals("Bearer token-1",
                world.http.requests("POST", RENDER_PATH).get(0).header("Authorization"));
    }

    @Test
    void render_既定ではレスポンシブにしタイトルが無ければ見出しを出さない() {
        String result = service.render(bar("", validTableBody()));

        assertTrue(result.contains("width: 100%; max-width: 700px; height: auto; aspect-ratio: 700 / 300;"), result);
        assertFalse(result.contains("lb-recharts-title"), result);
    }

    @Test
    void render_responsiveがfalseなら固定サイズのまま() {
        String result = service.render(bar(" responsive=\"false\"", validTableBody()));

        assertTrue(result.contains("width: 700px; height: 300px;"), result);
    }

    @Test
    void render_responsiveがtrueの指定はレスポンシブにする() {
        String result = service.render(bar(" responsive=\"TRUE\"", validTableBody()));

        assertTrue(result.contains("aspect-ratio"), result);
    }

    @Test
    void render_レンダリング結果にサイズ指定が無ければそのまま使う() {
        world.http.json("POST", RENDER_PATH, "{\"html\":\"<svg></svg>\"}");

        String result = service.render(bar("", validTableBody()));

        assertEquals("<div class=\"lb-recharts\"><svg></svg></div>", result);
    }

    @Test
    void render_タイトルはエスケープして見出しにし_空白のタイトルは出さない() {
        String titled = service.render(bar(" title=\"売上 <b>\"", validTableBody()));
        String blank = service.render(bar(" title=\" \"", validTableBody()));

        assertTrue(titled.contains("<p class=\"lb-recharts-title\" style=\"font-weight:600;margin:0 0 8px;\">売上 &lt;b&gt;</p>"), titled);
        assertFalse(blank.contains("lb-recharts-title"), blank);
    }

    @Test
    void render_テーマとstackedとyAxisを送る() {
        service.render(bar(" theme=\"dark\" stacked=\"true\" yAxis=\"万円\"", validTableBody()));
        service.render(bar(" stacked=\"false\"", validTableBody()));

        var requests = world.http.requests("POST", RENDER_PATH);
        JsonNode dark = world.bodyOf(requests.get(0));
        assertEquals("#e0e0e0", dark.get("textColor").asText());
        assertEquals("#555555", dark.get("gridColor").asText());
        assertTrue(dark.get("stacked").asBoolean());
        assertEquals("万円", dark.get("yAxisLabel").asText());
        JsonNode light = world.bodyOf(requests.get(1));
        assertEquals("#333333", light.get("textColor").asText());
        assertEquals("#e0e0e0", light.get("gridColor").asText());
        assertFalse(light.get("stacked").asBoolean());
        assertTrue(light.get("yAxisLabel").isNull());
    }

    @Test
    void render_複数のタグはそれぞれ展開し前後の本文を残す() {
        String markdown = "前\n" + bar("", validTableBody()) + "\n間\n" + bar("", validTableBody()) + "\n後";

        String result = service.render(markdown);

        assertEquals(2, world.http.requests("POST", RENDER_PATH).size());
        assertTrue(result.startsWith("前\n<div class=\"lb-recharts\">"));
        assertTrue(result.endsWith("</div>\n後"));
    }

    @Test
    void render_media_serviceが失敗した場合は例外を投げる() {
        world.http.status("POST", RENDER_PATH, 503, "{\"error\":\"down\"}");

        assertTrue(messageOf(bar("", validTableBody())).contains("Rechartsレンダリング呼び出しに失敗しました"));
    }

    @Test
    void render_media_serviceの応答が空の場合は例外を投げる() {
        world.http.on("POST", RENDER_PATH, FakeHttpServer.Response.empty(200));

        assertTrue(messageOf(bar("", validTableBody())).contains("空の応答"));
    }

    @Test
    void render_xAxis属性が空白の場合も必須エラーにする() {
        String markdown = "[recharts type=\"bar\" xAxis=\" \"]\n" + validTableBody() + "\n[/recharts]";

        assertTrue(messageOf(markdown).contains("xAxis属性は必須です"));
    }
}
