package com.letsblog.api.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProhibitedContentFilterServiceTest {

    private final ProhibitedContentFilterService service = new ProhibitedContentFilterService();

    @Test
    void check_安全なプロンプトは全カテゴリ有効でも例外にならない() {
        assertDoesNotThrow(() -> service.check("a cat sitting on a sofa, watercolor style", true, true, true));
    }

    @Test
    void check_性的コンテンツのキーワード英語を含み設定が有効なら例外をスローする() {
        assertThrows(ProhibitedContentException.class,
                () -> service.check("a nude portrait", true, true, true));
    }

    @Test
    void check_性的コンテンツのキーワード日本語を含み設定が有効なら例外をスローする() {
        assertThrows(ProhibitedContentException.class,
                () -> service.check("全裸の人物", true, true, true));
    }

    @Test
    void check_性的コンテンツの設定が無効ならキーワードを含んでいても例外にならない() {
        assertDoesNotThrow(() -> service.check("a nude portrait", false, true, true));
    }

    @Test
    void check_暴力的コンテンツのキーワードを含み設定が有効なら例外をスローする() {
        assertThrows(ProhibitedContentException.class,
                () -> service.check("gore and mutilation", true, true, true));
    }

    @Test
    void check_差別的表現のキーワードを含み設定が有効なら例外をスローする() {
        assertThrows(ProhibitedContentException.class,
                () -> service.check("hate speech poster", true, true, true));
    }

    @Test
    void check_大文字小文字を区別せずに判定する() {
        assertThrows(ProhibitedContentException.class,
                () -> service.check("NUDE portrait", true, true, true));
    }

    @Test
    void check_promptがnullでも例外にならない() {
        assertDoesNotThrow(() -> service.check(null, true, true, true));
    }
}
