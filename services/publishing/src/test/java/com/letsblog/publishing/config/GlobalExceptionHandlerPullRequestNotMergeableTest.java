package com.letsblog.publishing.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.letsblog.common.web.ErrorResponse;
import com.letsblog.publishing.github.PullRequestNotMergeableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** {@link GlobalExceptionHandler}のPullRequestNotMergeableExceptionの写像(issue #1343)。 */
class GlobalExceptionHandlerPullRequestNotMergeableTest {

    @Test
    @DisplayName("マージできないPRは409で、理由のメッセージがそのまま載る")
    void mapsTo409() {
        ResponseEntity<ErrorResponse> response = new GlobalExceptionHandler(new MultipartProperties())
                .handlePullRequestNotMergeable(new PullRequestNotMergeableException("コンフリクトしています"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("コンフリクトしています");
    }
}
