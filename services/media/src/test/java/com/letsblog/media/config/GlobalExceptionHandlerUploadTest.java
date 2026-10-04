package com.letsblog.media.config;

import com.letsblog.common.web.ErrorResponse;
import com.letsblog.media.service.InvalidImageUploadException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** issue #1599: 画像アップロードの拒否は400/413で理由を返す。 */
@DisplayName("GlobalExceptionHandler の画像アップロード拒否(issue #1599)")
class GlobalExceptionHandlerUploadTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("不正な画像は400で理由を返す")
    void 不正な画像は400() {
        ResponseEntity<ErrorResponse> response = handler.handleInvalidImageUpload(new InvalidImageUploadException("理由"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("理由", response.getBody().error());
    }

    @Test
    @DisplayName("multipartの上限超過は413で上限つきの理由を返す")
    void multipart上限超過は413() {
        ResponseEntity<ErrorResponse> response = handler.handleMaxUploadSize(
                new MaxUploadSizeExceededException(20L * 1024 * 1024));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("ファイルサイズが上限(20MB)を超えています。", response.getBody().error());
    }
}
