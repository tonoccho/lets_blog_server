package com.letsblog.publishing.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.letsblog.common.web.ErrorResponse;
import com.letsblog.publishing.github.GithubApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** {@link GlobalExceptionHandler}のGithubApiExceptionの写像(issue #1337)。汎用の500にせず502で原因を伝える。 */
class GlobalExceptionHandlerGithubTest {

    @Test
    @DisplayName("GithubApiExceptionは502になり、メッセージがそのまま応答に載る")
    void githubApiExceptionIs502() {
        ResponseEntity<ErrorResponse> response = new GlobalExceptionHandler(new MultipartProperties())
                .handleGithubApiException(new GithubApiException("GitHubトークンの権限が不足しています"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("GitHubトークンの権限が不足しています");
    }
}
