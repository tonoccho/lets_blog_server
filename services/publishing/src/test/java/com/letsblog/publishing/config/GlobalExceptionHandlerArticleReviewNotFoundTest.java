package com.letsblog.publishing.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.letsblog.common.web.ErrorResponse;
import com.letsblog.publishing.service.ArticleReviewNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** {@link GlobalExceptionHandler}のArticleReviewNotFoundExceptionの写像(issue #1341)。 */
class GlobalExceptionHandlerArticleReviewNotFoundTest {

    @Test
    @DisplayName("提出されていないPRのレビュー開始は404で、メッセージがそのまま載る")
    void mapsTo404() {
        ResponseEntity<ErrorResponse> response = new GlobalExceptionHandler(new MultipartProperties())
                .handleArticleReviewNotFound(new ArticleReviewNotFoundException("Pull Request #9 は提出されていません"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("Pull Request #9 は提出されていません");
    }
}
