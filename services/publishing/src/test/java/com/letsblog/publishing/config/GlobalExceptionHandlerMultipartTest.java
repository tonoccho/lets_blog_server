package com.letsblog.publishing.config;

import com.letsblog.common.web.ErrorResponse;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException;
import org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GlobalExceptionHandler}のmultipartサイズ超過の写像(issue #1061)。
 *
 * <p>{@link com.letsblog.publishing.integration.MultipartOversizeIntegrationTest}が実HTTP経由で
 * 「実際にどの例外型が到達するか」を含めて検証するのに対し、こちらは到達しうる例外型それぞれと、
 * <b>サイズ超過ではない</b>{@code IllegalStateException}が従来どおり409のままであることを
 * 網羅的に押さえる(分岐カバレッジ)。
 */
@DisplayName("publishing-service: GlobalExceptionHandlerのmultipartサイズ超過の写像(issue #1061)")
class GlobalExceptionHandlerMultipartTest {

    private static final long MAX_FILE = 500L * 1024 * 1024;
    private static final long MAX_REQUEST = 512L * 1024 * 1024;

    private GlobalExceptionHandler handler() {
        final MultipartProperties properties = new MultipartProperties();
        properties.setMaxFileSize(DataSize.ofBytes(MAX_FILE));
        properties.setMaxRequestSize(DataSize.ofBytes(MAX_REQUEST));
        return new GlobalExceptionHandler(properties);
    }

    @Test
    @DisplayName("MaxUploadSizeExceededExceptionは413になり、上限値がメッセージに含まれる")
    void maxUploadSizeExceededは413() {
        final ResponseEntity<ErrorResponse> response =
                handler().handleMaxUploadSizeExceeded(new MaxUploadSizeExceededException(MAX_FILE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error())
                .contains(String.valueOf(MAX_FILE))
                .contains(String.valueOf(MAX_REQUEST));
    }

    @Test
    @DisplayName("Tomcatがサイズ超過をIllegalStateExceptionでラップして投げても413になる")
    void ファイルサイズ超過をラップしたillegalStateは413() {
        final IllegalStateException wrapped = new InvalidParameterException(
                new FileSizeLimitExceededException("The field images exceeds its maximum permitted size", 2, 1));

        final ResponseEntity<ErrorResponse> response = handler().handleIllegalState(wrapped);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).contains(String.valueOf(MAX_FILE));
    }

    @Test
    @DisplayName("リクエスト全体のサイズ超過をラップしたIllegalStateExceptionも413になる")
    void リクエストサイズ超過をラップしたillegalStateは413() {
        final IllegalStateException wrapped = new IllegalStateException(
                new SizeLimitExceededException("the request was rejected because its size exceeds", 2, 1));

        final ResponseEntity<ErrorResponse> response = handler().handleIllegalState(wrapped);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    @DisplayName("サイズ超過ではないIllegalStateExceptionは従来どおり409のまま")
    void サイズ超過でないillegalStateは409() {
        final ResponseEntity<ErrorResponse> response =
                handler().handleIllegalState(new IllegalStateException("サイトの状態が競合しています"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("サイトの状態が競合しています");
    }

    @Test
    @DisplayName("サイズ超過ではない原因を連ねたIllegalStateExceptionも409のまま")
    void サイズ超過でない原因付きillegalStateは409() {
        final ResponseEntity<ErrorResponse> response = handler().handleIllegalState(
                new IllegalStateException("失敗", new IllegalArgumentException("原因")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("IllegalStateExceptionの原因にMaxUploadSizeExceededExceptionがあっても413になる")
    void 原因にmaxUploadSizeExceededがあれば413() {
        final IllegalStateException wrapped =
                new IllegalStateException("multipart解決に失敗", new MaxUploadSizeExceededException(MAX_FILE));

        final ResponseEntity<ErrorResponse> response = handler().handleIllegalState(wrapped);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    /**
     * 原因の連鎖を辿る段数には上限を設けてある(循環している連鎖で止まらなくなるのを防ぐため)。
     * 上限より深いところにサイズ超過があった場合は検知せず、従来どおり409のままにする。
     * 実際の例外連鎖がこの深さになることはない(Tomcat → Spring で高々数段)。
     */
    @Test
    @DisplayName("原因の連鎖が辿る上限より深いサイズ超過は検知せず409のまま")
    void 上限より深い原因は検知しない() {
        Throwable cause = new FileSizeLimitExceededException("exceeds its maximum permitted size", 2, 1);
        for (int i = 0; i < 25; i++) {
            cause = new IllegalStateException("段" + i, cause);
        }

        final ResponseEntity<ErrorResponse> response = handler().handleIllegalState((IllegalStateException) cause);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("原因が自己参照していても無限ループしない")
    void 自己参照する原因でも停止する() {
        final IllegalStateException selfReferencing = new IllegalStateException("自己参照") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        final ResponseEntity<ErrorResponse> response = handler().handleIllegalState(selfReferencing);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
