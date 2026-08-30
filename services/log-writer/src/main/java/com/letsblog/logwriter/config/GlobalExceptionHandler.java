package com.letsblog.logwriter.config;

import com.letsblog.common.web.ErrorResponse;
import com.letsblog.logwriter.service.ForbiddenException;
import com.letsblog.logwriter.service.IdentityServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
    }

    /**
     * identity-service/legacy-apiへの同期呼び出し(#572のC12までの暫定策)の失敗。
     * 呼び出し先が落ちている・タイムアウトした場合に暗黙に成功させず、明確なエラー
     * (502 Bad Gateway)として呼び出し元に伝える(identity-serviceのKeycloakUserSyncException
     * ハンドリングと同じ方針)。
     */
    @ExceptionHandler(IdentityServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleIdentityServiceUnavailable(IdentityServiceUnavailableException e) {
        log.error("依存サービスとの同期呼び出しに失敗しました", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }
}
