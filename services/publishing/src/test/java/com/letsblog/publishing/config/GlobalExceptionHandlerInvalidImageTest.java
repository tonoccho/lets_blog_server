package com.letsblog.publishing.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.letsblog.common.web.ErrorResponse;
import com.letsblog.publishing.service.InvalidImageUploadException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** {@link GlobalExceptionHandler}の InvalidImageUploadException の写像(issue #1717)。 */
class GlobalExceptionHandlerInvalidImageTest {

    @Test
    @DisplayName("画像の画素数超過は400で、理由がそのまま載る")
    void mapsTo400() {
        ResponseEntity<ErrorResponse> response = new GlobalExceptionHandler(new MultipartProperties())
                .handleInvalidImageUpload(new InvalidImageUploadException("画像の画素数が大きすぎます(上限6,400万画素)。"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("画像の画素数が大きすぎます(上限6,400万画素)。");
    }
}
