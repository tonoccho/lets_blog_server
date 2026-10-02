package com.letsblog.publishing.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link ArticleRejectRequest}の入力検証(issue #1344)。指摘事項は必須で、空・空白だけは拒否する。 */
class ArticleRejectRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "   ", "\n\t"})
    @DisplayName("指摘事項が未指定・空・空白だけなら検証エラーになる")
    void blankIsRejected(String comment) {
        assertThat(validator.validate(new ArticleRejectRequest(comment))).hasSize(1);
    }

    @Test
    @DisplayName("指摘事項があれば検証を通る")
    void nonBlankIsAccepted() {
        assertThat(validator.validate(new ArticleRejectRequest("見出しを直して"))).isEmpty();
    }
}
