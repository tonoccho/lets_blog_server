package com.letsblog.publishing.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.letsblog.common.web.ErrorResponse;
import com.letsblog.publishing.service.PullRequestArticleException;
import com.letsblog.publishing.service.PullRequestArticleException.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** {@link GlobalExceptionHandler}のPullRequestArticleExceptionの写像(issue #1338)。 */
class GlobalExceptionHandlerPullRequestArticleTest {

    private ResponseEntity<ErrorResponse> handle(Kind kind) {
        return new GlobalExceptionHandler(new MultipartProperties())
                .handlePullRequestArticle(new PullRequestArticleException(kind, "原因の文面"));
    }

    @Test
    @DisplayName("記事が見つからないは404、1 PR = 1 記事違反は409、不正な記事は422で、メッセージがそのまま載る")
    void mapsKindToStatus() {
        assertThat(handle(Kind.NOT_FOUND).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handle(Kind.MULTIPLE).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<ErrorResponse> invalid = handle(Kind.INVALID);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(invalid.getBody()).isNotNull();
        assertThat(invalid.getBody().error()).isEqualTo("原因の文面");
    }
}
