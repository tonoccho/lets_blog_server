package com.letsblog.media.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 既存のキーワードブロック(issue #532)の回帰確認。issue #1085で安全側ネガティブプロンプトの
 * 連結を追加したが、この判定(入力プロンプトに対するキーワード一致)自体は変えない。
 * 連結する安全側の語自体がこの判定に巻き込まれないことも合わせて固定する
 * (連結はブロック判定の対象であるprompt自体には行わないため、常に無関係のはず)。
 */
@DisplayName("media-service: 禁止コンテンツのキーワードブロック(issue #532、#1085で回帰確認)")
class ProhibitedContentFilterServiceTest {

    private final ProhibitedContentFilterService service = new ProhibitedContentFilterService();

    @Test
    void nudeを含むプロンプトはブロックされエラーメッセージは性的コンテンツを示す() {
        ProhibitedContentException e = assertThrows(ProhibitedContentException.class,
                () -> service.check("a nude woman", true, false, false));

        assertEquals(
                "性的コンテンツに該当する可能性のあるキーワードが含まれているため、画像生成をブロックしました。",
                e.getMessage());
    }

    @Test
    void 性的ブロックがOFFならnudeを含んでもブロックされない() {
        assertDoesNotThrow(() -> service.check("a nude woman", false, false, false));
    }

    @Test
    void 意味のないプロンプトはどのカテゴリにも該当せずブロックされない() {
        assertDoesNotThrow(() -> service.check("ああああ", true, true, true));
    }

    @Test
    void 空文字のプロンプトはブロックされない() {
        assertDoesNotThrow(() -> service.check("", true, true, true));
    }

    @Test
    void nullのプロンプトはブロックされない() {
        assertDoesNotThrow(() -> service.check(null, true, true, true));
    }

    @Test
    void 暴力的キーワードを含むプロンプトはブロックされる() {
        assertThrows(ProhibitedContentException.class,
                () -> service.check("gore scene", false, true, false));
    }

    @Test
    void 差別的キーワードを含むプロンプトはブロックされる() {
        assertThrows(ProhibitedContentException.class,
                () -> service.check("nazi flag", false, false, true));
    }
}
