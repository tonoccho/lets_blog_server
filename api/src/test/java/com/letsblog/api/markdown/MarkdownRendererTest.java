package com.letsblog.api.markdown;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownRendererTest {

    private MarkdownRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new MarkdownRenderer();
    }

    @Test
    void render_toc組み込みタグをH2からH4見出しのアンカーリンク付き目次に展開する() {
        String markdown = "# タイトル\n\n"
                + "[toc]\n\n"
                + "## セクション1\n\n本文\n\n"
                + "### サブセクション1-1\n\n本文\n\n"
                + "#### 詳細1-1-1\n\n本文\n\n"
                + "## セクション2\n\n本文";

        String html = renderer.render(markdown);

        assertTrue(html.contains("<a href=\"#セクション1\">セクション1</a>"), "目次にH2へのアンカーリンクが含まれること: " + html);
        assertTrue(html.contains("<a href=\"#サブセクション1-1\">サブセクション1-1</a>"), "目次にH3へのアンカーリンクが含まれること: " + html);
        assertTrue(html.contains("<a href=\"#詳細1-1-1\">詳細1-1-1</a>"), "目次にH4へのアンカーリンクが含まれること: " + html);
        assertTrue(html.contains("<h2 id=\"セクション1\">セクション1</h2>"), "H2見出しにアンカー用idが付与されること: " + html);
        assertTrue(html.contains("<h3 id=\"サブセクション1-1\">サブセクション1-1</h3>"), "H3見出しにアンカー用idが付与されること: " + html);
        assertTrue(html.contains("<h4 id=\"詳細1-1-1\">詳細1-1-1</h4>"), "H4見出しにアンカー用idが付与されること: " + html);
    }

    @Test
    void render_toc組み込みタグはH1見出しを目次に含めない() {
        String markdown = "# タイトル\n\n[toc]\n\n## セクション1\n\n本文";

        String html = renderer.render(markdown);

        assertFalse(html.contains("<a href=\"#タイトル\">"), "H1見出しは目次の対象外であること: " + html);
    }

    @Test
    void render_大文字のTOC記法もflexmark標準仕様として引き続き使える() {
        String markdown = "[TOC]\n\n## セクション1\n\n本文";

        String html = renderer.render(markdown);

        assertTrue(html.contains("セクション1"), "大文字[TOC]でも目次が生成されること: " + html);
    }

    @Test
    void render_toc記法を含まない本文は従来通りレンダリングされる() {
        String markdown = "# タイトル\n\n本文です。";

        String html = renderer.render(markdown);

        assertTrue(html.contains("<h1"), "見出しがそのままレンダリングされること: " + html);
        assertTrue(html.contains("本文です。"));
    }

    @Test
    void render_nullとから文字列は空文字列を返す() {
        assertTrue(renderer.render(null).isEmpty());
        assertTrue(renderer.render("").isEmpty());
    }
}
