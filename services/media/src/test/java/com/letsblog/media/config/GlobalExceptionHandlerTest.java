package com.letsblog.media.config;

import com.letsblog.common.web.ErrorResponse;
import com.letsblog.media.service.ProhibitedContentException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 不適切コンテンツでブロックしたときの応答(issue #936 / AT-10 のシナリオ11)。
 *
 * <p>この契約は issue #532 の実装時点から「400 と理由の文面」だった。ところが #583 の
 * legacy-api 解体で {@code ProhibitedContentException} が media-service へ移った際、
 * legacy-api 側にあった {@code @ExceptionHandler} が移設されず、
 * <b>ブロックが素の 500 Internal Server Error になっていた</b>。
 * 利用者には理由が何も出ず、フィルタが働いたのかサーバーが壊れたのかも区別できない。
 *
 * <p>受け入れシナリオ(`features/media/image-settings.feature`)がこの退行を利用者側から
 * 捕まえるが、状態コードと本文の契約はここで固定する。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void 不適切コンテンツのブロックは400で理由を返す() {
        ResponseEntity<ErrorResponse> response = handler.handleProhibitedContent(
                new ProhibitedContentException("性的コンテンツに該当する可能性のあるキーワードが含まれているため、画像生成をブロックしました。"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(
                "性的コンテンツに該当する可能性のあるキーワードが含まれているため、画像生成をブロックしました。",
                response.getBody().error());
    }
}
