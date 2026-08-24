package com.letsblog.media.config;

import com.letsblog.common.web.ErrorResponse;
import com.letsblog.media.ai.AiServiceException;
import com.letsblog.media.render.RechartsRenderException;
import com.letsblog.media.service.DiagramNotFoundException;
import com.letsblog.media.service.GeneratedImageNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * legacy-apiのGlobalExceptionHandler(#573でmedia-serviceへ移設した機能に対応する部分)を踏襲する。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(GeneratedImageNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleGeneratedImageNotFound(GeneratedImageNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(DiagramNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDiagramNotFound(DiagramNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ErrorResponse> handleAiServiceException(AiServiceException e) {
        log.warn("外部レンダリングサービスの呼び出しに失敗しました", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(RechartsRenderException.class)
    public ResponseEntity<ErrorResponse> handleRechartsRenderException(RechartsRenderException e) {
        log.warn("Rechartsのレンダリングに失敗しました", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }
}
