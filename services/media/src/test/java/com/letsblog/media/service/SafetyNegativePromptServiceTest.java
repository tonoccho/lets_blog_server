package com.letsblog.media.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * カテゴリ別の安全側ネガティブプロンプト連結ロジック(issue #1085)。
 *
 * <p>{@link ProjectImageDefaultsResolver}と同様に{@code @Value}で受け取った設定値
 * (カテゴリごとの抑制語)を、有効なカテゴリだけユーザーのnegativePromptへ連結する。
 * 連結は{@code ", "}区切りで行い、既に同じ語(大小文字無視)が含まれていれば重複させない。
 */
@DisplayName("media-service: 安全側ネガティブプロンプトの連結(issue #1085)")
class SafetyNegativePromptServiceTest {

    private SafetyNegativePromptService service(String sexual, String violent, String discriminatory) {
        return new SafetyNegativePromptService(sexual, violent, discriminatory);
    }

    @Test
    void 性的カテゴリがONなら抑制語を連結する() {
        SafetyNegativePromptService service = service("nsfw, nude", "", "");

        String result = service.appendSafetyWords("blurry", true, false, false);

        assertEquals("blurry, nsfw, nude", result);
    }

    @Test
    void 性的カテゴリがOFFなら連結しない() {
        SafetyNegativePromptService service = service("nsfw, nude", "", "");

        String result = service.appendSafetyWords("blurry", false, false, false);

        assertEquals("blurry", result);
    }

    @Test
    void 暴力的カテゴリがONなら暴力側の抑制語を連結する() {
        SafetyNegativePromptService service = service("", "gore, gory", "");

        String result = service.appendSafetyWords("blurry", false, true, false);

        assertEquals("blurry, gore, gory", result);
    }

    @Test
    void 差別的カテゴリがONなら差別側の抑制語を連結する() {
        SafetyNegativePromptService service = service("", "", "nazi symbol");

        String result = service.appendSafetyWords("blurry", false, false, true);

        assertEquals("blurry, nazi symbol", result);
    }

    @Test
    void 複数カテゴリがONならすべて連結される() {
        SafetyNegativePromptService service = service("nsfw", "gore", "nazi symbol");

        String result = service.appendSafetyWords("blurry", true, true, true);

        assertEquals("blurry, nsfw, gore, nazi symbol", result);
    }

    @Test
    void 既存のnegativePromptに同じ語が大小文字違いで含まれていれば重複させない() {
        SafetyNegativePromptService service = service("nsfw, nude", "", "");

        String result = service.appendSafetyWords("blurry, NSFW", true, false, false);

        assertEquals("blurry, NSFW, nude", result);
    }

    @Test
    void 設定値が空文字ならそのカテゴリがONでも連結しない() {
        SafetyNegativePromptService service = service("", "gore", "");

        String result = service.appendSafetyWords("blurry", true, true, false);

        assertEquals("blurry, gore", result);
    }

    @Test
    void negativePromptが空文字でも抑制語だけを設定する() {
        SafetyNegativePromptService service = service("nsfw", "", "");

        String result = service.appendSafetyWords("", true, false, false);

        assertEquals("nsfw", result);
    }

    @Test
    void negativePromptがnullでも抑制語だけを設定する() {
        SafetyNegativePromptService service = service("nsfw", "", "");

        String result = service.appendSafetyWords(null, true, false, false);

        assertEquals("nsfw", result);
    }

    @Test
    void すべてのカテゴリがOFFならnegativePromptをそのまま返す() {
        SafetyNegativePromptService service = service("nsfw", "gore", "nazi symbol");

        String result = service.appendSafetyWords("cat", false, false, false);

        assertEquals("cat", result);
    }

    @Test
    void 抑制語がすべて既存のnegativePromptと重複していれば何も足さず返す() {
        SafetyNegativePromptService service = service("NSFW, Nude", "", "");

        String result = service.appendSafetyWords("nsfw, nude", true, false, false);

        assertEquals("nsfw, nude", result);
    }

    @Test
    void 抑制語の設定値に空要素があっても無視して連結する() {
        SafetyNegativePromptService service = service("nsfw,, nude", "", "");

        String result = service.appendSafetyWords("blurry", true, false, false);

        assertEquals("blurry, nsfw, nude", result);
    }

    @Test
    void 既存のnegativePromptに空要素があっても無視して重複判定する() {
        SafetyNegativePromptService service = service("nsfw", "", "");

        String result = service.appendSafetyWords("blurry,, NSFW", true, false, false);

        assertEquals("blurry,, NSFW", result);
    }
}
