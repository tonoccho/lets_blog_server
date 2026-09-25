package com.letsblog.publishing.cms.ssh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShellQuoteTest {

    @Test
    void single_通常の文字列はシングルクォートで囲む() {
        assertEquals("'hello'", ShellQuote.single("hello"));
    }

    @Test
    void single_nullは空のクォート文字列を返す() {
        assertEquals("''", ShellQuote.single(null));
    }

    @Test
    void single_シングルクォートを含む値はエスケープする() {
        assertEquals("'it'\\''s'", ShellQuote.single("it's"));
    }

    @Test
    void single_シェルメタ文字を含んでいてもクォート内に留まる() {
        String malicious = "'; rm -rf / #";
        String quoted = ShellQuote.single(malicious);

        assertEquals("''\\''; rm -rf / #'", quoted);
    }

    @Test
    void single_バッククォートやドル記号もそのまま囲む() {
        assertEquals("'$(whoami) `id`'", ShellQuote.single("$(whoami) `id`"));
    }

    @Test
    void single_空文字列はクォートのみを返す() {
        assertEquals("''", ShellQuote.single(""));
    }
}
