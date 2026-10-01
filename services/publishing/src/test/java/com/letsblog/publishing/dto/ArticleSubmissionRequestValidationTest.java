package com.letsblog.publishing.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link ArticleSubmissionRequest}の入力検証(issue #1339)。 */
class ArticleSubmissionRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("3項目が揃っていれば有効")
    void valid() {
        assertThat(validator.validate(new ArticleSubmissionRequest("article/a", 12, "my-article"))).isEmpty();
    }

    @Test
    @DisplayName("headブランチ名が空、Issue番号が無い/0以下、スラッグが空・不正なら無効")
    void invalid() {
        assertThat(validator.validate(new ArticleSubmissionRequest(" ", 12, "a"))).hasSize(1);
        assertThat(validator.validate(new ArticleSubmissionRequest("article/a", null, "a"))).hasSize(1);
        assertThat(validator.validate(new ArticleSubmissionRequest("article/a", 0, "a"))).hasSize(1);
        assertThat(validator.validate(new ArticleSubmissionRequest("article/a", 12, ""))).isNotEmpty();
        assertThat(validator.validate(new ArticleSubmissionRequest("article/a", 12, "Bad Slug/x"))).hasSize(1);
    }
}
