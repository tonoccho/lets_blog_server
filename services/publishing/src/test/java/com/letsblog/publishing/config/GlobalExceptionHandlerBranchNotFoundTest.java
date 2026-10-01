package com.letsblog.publishing.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.letsblog.common.web.ErrorResponse;
import com.letsblog.publishing.service.BranchNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** {@link GlobalExceptionHandler}のBranchNotFoundExceptionの写像(issue #1339)。 */
class GlobalExceptionHandlerBranchNotFoundTest {

    @Test
    @DisplayName("ブランチが見つからないは404で、メッセージがそのまま載る")
    void mapsTo404() {
        ResponseEntity<ErrorResponse> response = new GlobalExceptionHandler(new MultipartProperties())
                .handleBranchNotFound(new BranchNotFoundException("ブランチが見つかりません: article/x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("ブランチが見つかりません: article/x");
    }
}
