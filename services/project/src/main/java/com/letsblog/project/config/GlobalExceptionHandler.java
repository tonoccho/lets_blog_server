package com.letsblog.project.config;

import com.letsblog.project.client.AiServiceException;
import com.letsblog.project.service.AiServiceGenerationException;
import com.letsblog.project.service.ForbiddenException;
import com.letsblog.project.service.IdentityServiceUnavailableException;
import com.letsblog.project.service.InvalidCustomTagContentException;
import com.letsblog.project.service.InvalidSiteNameException;
import com.letsblog.project.service.ProjectNotFoundException;
import com.letsblog.project.service.ProvisioningException;
import com.letsblog.project.service.SiteAlreadyProvisionedException;
import com.letsblog.project.service.SiteNotFoundException;
import com.letsblog.project.service.SshKeyPairNotFoundException;
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
 * legacy-apiのGlobalExceptionHandlerのうち、project-serviceが移設対象とした例外(issue #577、
 * SSH鍵ペア/組み込みタグデザイン設定/内部ブリッジ関連)へのハンドリングのみを移設する。
 * ステータスコードの割り当てはlegacy-api版と同じにしている。
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

    @ExceptionHandler(SshKeyPairNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSshKeyPairNotFound(SshKeyPairNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(SiteNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSiteNotFound(SiteNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProjectNotFound(ProjectNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(ProvisioningException.class)
    public ResponseEntity<ErrorResponse> handleProvisioningException(ProvisioningException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(SiteAlreadyProvisionedException.class)
    public ResponseEntity<ErrorResponse> handleSiteAlreadyProvisionedException(SiteAlreadyProvisionedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(AiServiceGenerationException.class)
    public ResponseEntity<ErrorResponse> handleAiServiceGenerationException(AiServiceGenerationException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(InvalidCustomTagContentException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCustomTagContent(InvalidCustomTagContentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(InvalidSiteNameException.class)
    public ResponseEntity<ErrorResponse> handleInvalidSiteName(InvalidSiteNameException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ErrorResponse> handleAiServiceException(AiServiceException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    /** legacy-api/identity-serviceへの内部ブリッジ呼び出しの失敗(#572/#573/#574/#576と同じ方針)。 */
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
