package com.letsblog.analytics.config;

import com.letsblog.analytics.adsense.AdSenseException;
import com.letsblog.analytics.analytics.GoogleAnalyticsException;
import com.letsblog.analytics.service.ForbiddenException;
import com.letsblog.analytics.service.IdentityServiceUnavailableException;
import com.letsblog.analytics.service.ProjectNotFoundException;
import com.letsblog.common.web.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * legacy-apiのGlobalExceptionHandlerのうち、analytics-serviceが移設対象とした例外(issue #578、
 * GoogleAnalyticsReportService/AdSenseReportService/InternalAnalyticsProjectSettingsController/
 * 内部ブリッジ関連)へのハンドリングのみを移設する。ステータスコードの割り当てはlegacy-api版と
 * 同じにしている。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * issue #1468: @PathVariable / @RequestParam の型変換失敗(未知のenum値、数値項目への非数値など)は
     * クライアントの入力誤りなので400で返す。専用ハンドラが無いと、Springは原因連鎖を辿って
     * 下の IllegalArgumentException ハンドラに一致してしまう。内部のクラス名や例外文面は
     * 漏らさず、パラメータ名だけを示す。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of("パラメータ '" + e.getName() + "' の値が不正です。"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProjectNotFound(ProjectNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(GoogleAnalyticsException.class)
    public ResponseEntity<ErrorResponse> handleGoogleAnalyticsException(GoogleAnalyticsException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(AdSenseException.class)
    public ResponseEntity<ErrorResponse> handleAdSenseException(AdSenseException e) {
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
