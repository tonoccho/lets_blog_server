package com.letsblog.content.markdown;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Markdown から HTML への変換。実物のレンダラーへ文字列を渡す(外部との通信は無い)。 */
class MarkdownRendererTest {

    private final MarkdownRenderer renderer = new MarkdownRenderer();

    @Test
    void 見出しと段落をHTMLにする() {
        String html = renderer.render("# 題\n\n本文");

        assertThat(html).contains("<h1").contains("題</h1>").contains("<p>本文</p>");
    }

    @Test
    void nullは空文字として扱い_空のHTMLを返す() {
        assertThat(renderer.render(null)).isEmpty();
    }

    @Test
    void 空文字も空のHTMLを返す() {
        assertThat(renderer.render("")).isEmpty();
    }
}
