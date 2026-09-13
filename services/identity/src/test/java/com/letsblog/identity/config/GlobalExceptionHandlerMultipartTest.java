package com.letsblog.identity.config;

import com.letsblog.common.web.ErrorResponse;
import com.letsblog.identity.service.AvatarNotFoundException;
import com.letsblog.identity.service.UnsupportedAvatarFormatException;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.servlet.autoconfigure.MultipartProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1241 AC5(要件5): アバターアップロードのmultipartサイズ超過(20MB)を413へ写像する。
 * platform-service(issue #1061)と同じ問題・同じ対処のidentity-service版。
 */
@DisplayName("identity-service: GlobalExceptionHandlerのmultipartサイズ超過の写像(issue #1241)")
class GlobalExceptionHandlerMultipartTest {

    private static final long MAX_FILE = 20L * 1024 * 1024;
    private static final long MAX_REQUEST = 20L * 1024 * 1024;

    private GlobalExceptionHandler handler() {
        MultipartProperties properties = new MultipartProperties();
        properties.setMaxFileSize(DataSize.ofBytes(MAX_FILE));
        properties.setMaxRequestSize(DataSize.ofBytes(MAX_REQUEST));
        return new GlobalExceptionHandler(properties);
    }

    @Test
    @DisplayName("MaxUploadSizeExceededExceptionは413になる")
    void maxUploadSizeExceededは413() {
        ResponseEntity<ErrorResponse> response =
                handler().handleMaxUploadSizeExceeded(new MaxUploadSizeExceededException(MAX_FILE));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().error().contains(String.valueOf(MAX_FILE)));
    }

    @Test
    @DisplayName("Tomcatがサイズ超過をIllegalStateExceptionでラップして投げても413になる")
    void ファイルサイズ超過をラップしたillegalStateは413() {
        IllegalStateException wrapped = new InvalidParameterException(
                new FileSizeLimitExceededException("exceeds its maximum permitted size", 2, 1));

        ResponseEntity<ErrorResponse> response = handler().handleIllegalState(wrapped);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
    }

    @Test
    @DisplayName("サイズ超過ではないUnsupportedAvatarFormatExceptionは400になる")
    void 対応外形式は400() {
        ResponseEntity<ErrorResponse> response =
                handler().handleUnsupportedAvatarFormat(new UnsupportedAvatarFormatException("対応していない画像形式です"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    @DisplayName("未設定のアバター取得は404になる")
    void アバター未設定は404() {
        ResponseEntity<ErrorResponse> response =
                handler().handleAvatarNotFound(new AvatarNotFoundException("アバターは未設定です"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    @DisplayName("サイズ超過ではないIllegalStateExceptionはそのまま再送出される(従来通り未処理)")
    void サイズ超過でないillegalStateはそのまま再送出される() {
        IllegalStateException notSizeRelated = new IllegalStateException("無関係な状態異常");

        try {
            handler().handleIllegalState(notSizeRelated);
            throw new AssertionError("例外が再送出されるはず");
        } catch (IllegalStateException e) {
            assertEquals("無関係な状態異常", e.getMessage());
        }
    }

    @Test
    @DisplayName("IllegalStateExceptionの原因が直接MaxUploadSizeExceededExceptionでも413になる")
    void 原因が直接maxUploadSizeExceededなら413() {
        IllegalStateException wrapped =
                new IllegalStateException("multipart解決に失敗", new MaxUploadSizeExceededException(MAX_FILE));

        ResponseEntity<ErrorResponse> response = handler().handleIllegalState(wrapped);

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
    }

    @Test
    @DisplayName("原因の連鎖が辿る上限より深いサイズ超過は検知せず再送出する")
    void 上限より深い原因は検知せず再送出する() {
        Throwable cause = new FileSizeLimitExceededException("exceeds its maximum permitted size", 2, 1);
        for (int i = 0; i < 25; i++) {
            cause = new IllegalStateException("段" + i, cause);
        }
        IllegalStateException deep = (IllegalStateException) cause;

        try {
            handler().handleIllegalState(deep);
            throw new AssertionError("例外が再送出されるはず");
        } catch (IllegalStateException e) {
            assertEquals(deep, e);
        }
    }

    @Test
    @DisplayName("原因が自己参照していても無限ループせず再送出する")
    void 自己参照する原因でも停止して再送出する() {
        IllegalStateException selfReferencing = new IllegalStateException("自己参照") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        try {
            handler().handleIllegalState(selfReferencing);
            throw new AssertionError("例外が再送出されるはず");
        } catch (IllegalStateException e) {
            assertEquals(selfReferencing, e);
        }
    }
}
