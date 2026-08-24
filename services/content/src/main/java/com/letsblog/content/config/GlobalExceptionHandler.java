package com.letsblog.content.config;

import com.letsblog.content.client.AiServiceException;
import com.letsblog.content.contentcache.ContentScrapingException;
import com.letsblog.content.render.RechartsRenderException;
import com.letsblog.content.service.CustomTagNotFoundException;
import com.letsblog.content.service.ForbiddenException;
import com.letsblog.content.service.IdentityServiceUnavailableException;
import com.letsblog.content.service.InvalidCustomTagContentException;
import com.letsblog.content.service.InvalidPlantUmlTagException;
import com.letsblog.content.service.InvalidRechartsTagException;
import com.letsblog.content.service.PostNotFoundException;
import com.letsblog.content.service.SiteNotFoundException;
import com.letsblog.common.web.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * legacy-apiのGlobalExceptionHandlerのうち、content-serviceが移設対象とした例外(issue #576、
 * カスタムタグ/記事プレビュー/コンテンツキャッシュ/内部ブリッジ関連)へのハンドリングのみを移設する。
 * ステータスコードの割り当てはlegacy-api版と同じにしている。CustomTagTemplateNotFoundExceptionは
 * legacy-api版にもハンドラが無い(未マップ、Springの既定の500応答になる)ため、同じ挙動を保つために
 * あえて追加していない。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(SiteNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSiteNotFound(SiteNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(PostNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePostNotFound(PostNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(CustomTagNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCustomTagNotFound(CustomTagNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(InvalidCustomTagContentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCustomTagContent(InvalidCustomTagContentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(InvalidRechartsTagException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRechartsTag(InvalidRechartsTagException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(InvalidPlantUmlTagException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPlantUmlTag(InvalidPlantUmlTagException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(ContentScrapingException.class)
    public ResponseEntity<ErrorResponse> handleContentScrapingException(ContentScrapingException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ErrorResponse> handleAiServiceException(AiServiceException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(RechartsRenderException.class)
    public ResponseEntity<ErrorResponse> handleRechartsRenderException(RechartsRenderException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    /** legacy-api/identity-serviceへの内部ブリッジ呼び出しの失敗(#572/#573/#574と同じ方針)。 */
    @ExceptionHandler(IdentityServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleIdentityServiceUnavailable(IdentityServiceUnavailableException e) {
        log.warn("legacy-api/identity-serviceへのブリッジ呼び出しに失敗しました: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "message", fe.getDefaultMessage()
                ))
                .collect(Collectors.toList());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of("Validation failed", errors));
    }
}
