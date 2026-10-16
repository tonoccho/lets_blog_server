package com.letsblog.content.service;

import com.letsblog.content.domain.CustomTagFormat;
import com.letsblog.content.domain.ContentType;
import com.letsblog.content.testsupport.ContentWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * legacy-apiのArticlePreviewServiceTestのうち、content-serviceへ移設した記事本文レンダリング
 * (renderHtml)の振る舞いを引き継いだテスト(issue #576)。
 *
 * <p>issue #1821: カスタムタグ・ブログカード・Amazon・Recharts・PlantUML・目次・ラッパー・MarkdownRendererは
 * すべて実物で、本物のパイプラインを通す。差し替えているのは、media-service・project-serviceのHTTP(フェイク)、
 * リポジトリ(DB)、ブラウザ(外部プロセス)だけ。
 */
class ArticlePreviewServiceTest {

    private static final String ERROR_PREFIX = "<div role=\"alert\"";

    private ContentWorld world;
    private ArticlePreviewService service;

    @BeforeEach
    void setUp() {
        world = new ContentWorld().actingAsAdmin(1L);
        service = world.articlePreviewService;
        world.settingsFor(1L, "acme");
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void renderHtml_カスタムタグ展開後にMarkdownをHTML変換しラッパーで囲む() {
        world.tag("note", "<div class=\"note\">{{content}}</div>", null, CustomTagFormat.BLOCK, 1L);

        String html = service.renderHtml(1L, "**bold**\n\n[note]\n注意\n[/note]");

        assertTrue(html.startsWith("<div class=\"lets-blog-rendered acme\"><p><strong>bold</strong></p>"), html);
        assertTrue(html.contains("<div class=\"note\">注意</div>"), html);
        assertTrue(html.endsWith("</div>"), html);
    }

    @Test
    void renderHtml_ブログカードを展開しAmazonは本番サイト向けの実リンクにしない() {
        String blog = "https://example.com/article";
        String amazon = "https://amazon.co.jp/dp/xxx";
        world.cached(blog, ContentType.BLOGCARD, Map.of("title", "記事", "url", blog), LocalDateTime.now());
        world.cached(amazon, ContentType.AMAZON,
                Map.of("productName", "商品", "productUrl", amazon, "price", "1000"), LocalDateTime.now());

        String html = service.renderHtml(1L, "[blogcard " + blog + "]\n\n[amazon " + amazon + "]");

        assertTrue(html.contains("lb-blogcard"), html);
        assertTrue(html.contains("lb-amazon-card"), html);
        // プレビューは特定サイトに紐付かないので、Amazonのリンクは非活性(aではなくdiv)
        assertFalse(html.contains("<a class=\"lb-amazon-card\""), html);
    }

    @Test
    void renderHtml_rechartsタグが不正な場合はレンダリングを中止してエラーメッセージを返す() {
        String html = service.renderHtml(1L, "[recharts]\n| a | b |\n|---|---|\n| 1 | 2 |\n[/recharts]");

        assertTrue(html.startsWith(ERROR_PREFIX), html);
        assertTrue(html.contains("チャートのレンダリングエラー"), html);
        assertTrue(html.contains("type属性は必須です"), html);
        assertFalse(html.contains("lets-blog-rendered"), "中止したのでラッパーは付かない");
    }

    @Test
    void renderHtml_recharts展開後の内容がMarkdown変換される() {
        String html = service.renderHtml(1L,
                "[recharts type=\"bar\" xAxis=\"m\"]\n| m | v |\n|---|---|\n| a | 1 |\n[/recharts]");

        assertTrue(html.contains("<svg></svg>"), html);
        assertTrue(html.startsWith("<div class=\"lets-blog-rendered acme\">"), html);
    }

    @Test
    void renderHtml_plantumlフェンスをdataURI画像へ差し替えてからMarkdown変換する() {
        String html = service.renderHtml(1L, "```plantuml\n@startuml\nA->B\n@enduml\n```");

        String base64 = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
        assertTrue(html.contains("<img src=\"data:image/png;base64," + base64 + "\" alt=\"diagram\""), html);
    }

    @Test
    void renderHtml_plantumlタグをdataURI画像へ差し替えてからMarkdown変換する() {
        String html = service.renderHtml(1L, "[plantuml]\nA->B\n[/plantuml]");

        String base64 = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
        assertTrue(html.contains("<img src=\"data:image/png;base64," + base64 + "\" alt=\"diagram\""), html);
        assertEquals(1, world.http.requests("POST", "/api/render/plantuml").size());
    }

    @Test
    void renderHtml_PlantUMLサーバーに接続できない場合は同じエラー表示で中止する() {
        world.http.status("POST", "/api/render/plantuml", 503, "{\"error\":\"down\"}");

        String html = service.renderHtml(1L, "[plantuml]\nA->B\n[/plantuml]");

        assertTrue(html.startsWith(ERROR_PREFIX), html);
        assertTrue(html.contains("PlantUMLサーバーに接続できません"), html);
        assertTrue(html.contains("PlantUML図のレンダリングエラー"), html);
        assertFalse(html.contains("lets-blog-rendered"), html);
    }

    @Test
    void renderHtml_PlantUMLが図を拒否した場合もその文言で中止し接続できない旨は出さない() {
        world.http.status("POST", "/api/render/plantuml", 400,
                "{\"error\":\"PlantUML が図を描けませんでした: Syntax Error?\"}");

        String html = service.renderHtml(1L, "[plantuml]\nbad\n[/plantuml]");

        assertTrue(html.contains("PlantUML が図を描けませんでした: Syntax Error?"), html);
        assertTrue(html.contains("PlantUML図のレンダリングエラー"), html);
        assertFalse(html.contains("接続できません"), html);
    }

    @Test
    void renderHtml_目次はテンプレートを適用して目印を付ける() {
        world.tagDesign("TOC", null, "<nav>{{toc}}</nav>");

        String html = service.renderHtml(1L, "[toc]\n\n## 第一章\n\n本文");

        assertTrue(html.contains("<nav><ul class=\"lb-toc-list\">"), html);
        assertTrue(html.contains("<!-- lbs:embed "), html);
    }

    @Test
    void renderHtml_projectIdが無ければ固定クラスだけのラッパーで囲む() {
        String html = service.renderHtml(null, "本文");

        assertEquals("<div class=\"lets-blog-rendered\"><p>本文</p>\n</div>", html);
    }
}
