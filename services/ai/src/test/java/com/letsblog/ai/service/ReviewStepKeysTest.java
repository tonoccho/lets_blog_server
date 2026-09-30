package com.letsblog.ai.service;

import com.letsblog.ai.domain.ReviewStepKey;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ReviewStepKeys#parseの回帰テスト(issue #1222)。AC1/AC2が使うステップキーの解析ロジックを、
 * {@link ReviewApiInputValidationIntegrationTest}(実際のController経由)とは別に、
 * 分岐網羅の観点で単体テストとして直接検証する。
 */
class ReviewStepKeysTest {

    @Test
    void 既知のステップキーはそのまま解決する() {
        assertEquals(ReviewStepKey.JAPANESE, ReviewStepKeys.parse("JAPANESE"));
    }

    @Test
    void 小文字_前後空白があっても解決する() {
        assertEquals(ReviewStepKey.STYLE, ReviewStepKeys.parse("  style  "));
    }

    @Test
    void nullは不正な入力として拒否する() {
        assertThrows(InvalidReviewInputException.class, () -> ReviewStepKeys.parse(null));
    }

    @Test
    void 空文字は不正な入力として拒否する() {
        assertThrows(InvalidReviewInputException.class, () -> ReviewStepKeys.parse(""));
    }

    @Test
    void 空白のみは不正な入力として拒否する() {
        assertThrows(InvalidReviewInputException.class, () -> ReviewStepKeys.parse("   "));
    }

    @Test
    void 未知のステップキーは不正な入力として拒否する() {
        assertThrows(InvalidReviewInputException.class, () -> ReviewStepKeys.parse("NOT_A_REAL_STEP"));
    }
}
