package com.letsblog.ai.config;

import com.letsblog.ai.ai.AiServiceException;
import com.letsblog.ai.github.GithubApiException;
import com.letsblog.ai.service.ArticlePlanSessionNotFoundException;
import com.letsblog.ai.service.ForbiddenException;
import com.letsblog.ai.service.GenerationJobNotFoundException;
import com.letsblog.ai.service.IdentityServiceUnavailableException;
import com.letsblog.ai.service.InvalidReviewInputException;
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
 * legacy-apiのGlobalExceptionHandlerのうち、ai-serviceが移設対象とした例外(issue #574、
 * ArticlePlanService/AiAssistService/GenerationJobController/GithubClient/内部ブリッジ関連)への
 * ハンドリングのみを移設する。ステータスコードの割り当てはlegacy-api版と同じにしている。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    /**
     * レビュー関連API(issue #1222)の入力検証エラー。{@link InvalidReviewInputException}の
     * Javadoc参照。IllegalArgumentExceptionではなく専用の例外型にしたことで、上の
     * {@link #handleIllegalArgument}(409)の原因連鎖探索に巻き込まれず400を返せる。
     */
    @ExceptionHandler(InvalidReviewInputException.class)
    public ResponseEntity<ErrorResponse> handleInvalidReviewInput(InvalidReviewInputException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(ArticlePlanSessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleArticlePlanSessionNotFound(ArticlePlanSessionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(GenerationJobNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleGenerationJobNotFound(GenerationJobNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(GithubApiException.class)
    public ResponseEntity<ErrorResponse> handleGithubApiException(GithubApiException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ErrorResponse> handleAiServiceException(AiServiceException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    /** legacy-api/identity-serviceへの内部ブリッジ呼び出しの失敗(#572/#573と同じ方針)。 */
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
