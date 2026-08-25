package com.letsblog.api.config;

import com.letsblog.api.ai.AiServiceException;
import com.letsblog.api.client.AnalyticsServiceException;
import com.letsblog.api.cms.CmsApiException;
import com.letsblog.api.cms.agent.AgentOperationException;
import com.letsblog.api.cms.ssh.SshOperationException;
import com.letsblog.api.service.AiServiceGenerationException;
import com.letsblog.api.service.BackupException;
import com.letsblog.api.service.EmailAlreadyExistsException;
import com.letsblog.api.service.EmailSendException;
import com.letsblog.api.service.ForbiddenException;
import com.letsblog.api.service.InvalidCredentialsException;
import com.letsblog.api.service.InvalidCustomTagContentException;
import com.letsblog.api.service.InvalidPlantUmlTagException;
import com.letsblog.api.service.InvalidRechartsTagException;
import com.letsblog.api.service.InvalidRoleException;
import com.letsblog.api.service.InvalidTokenException;
import com.letsblog.api.service.InvalidTotpCodeException;
import com.letsblog.api.service.MailTemplateNotFoundException;
import com.letsblog.api.service.ProhibitedContentException;
import com.letsblog.api.service.ProjectNotFoundException;
import com.letsblog.api.service.ProjectUserNotFoundException;
import com.letsblog.api.service.ProvisioningException;
import com.letsblog.api.service.QrCodeGenerationException;
import com.letsblog.api.service.SiteAlreadyProvisionedException;
import com.letsblog.api.service.SiteNotFoundException;
import com.letsblog.api.service.SshKeyPairNotFoundException;
import com.letsblog.api.service.TwoFactorSecretNotFoundException;
import com.letsblog.api.exception.RateLimitExceededException;
import com.letsblog.api.service.UserNotFoundException;
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

    @ExceptionHandler(ProjectNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProjectNotFound(ProjectNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(ProjectUserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProjectUserNotFound(ProjectUserNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFound(UserNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyExists(EmailAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(InvalidRoleException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRole(InvalidRoleException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
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

    @ExceptionHandler(ProhibitedContentException.class)
    public ResponseEntity<ErrorResponse> handleProhibitedContent(ProhibitedContentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(EmailSendException.class)
    public ResponseEntity<ErrorResponse> handleEmailSendError(EmailSendException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(MailTemplateNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleMailTemplateNotFound(MailTemplateNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(CmsApiException.class)
    public ResponseEntity<ErrorResponse> handleCmsApiException(CmsApiException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ErrorResponse> handleAiServiceException(AiServiceException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(AiServiceGenerationException.class)
    public ResponseEntity<ErrorResponse> handleAiServiceGenerationException(AiServiceGenerationException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    /** analytics-serviceへの内部ブリッジ呼び出しの失敗(issue #578、#572/#573/#574と同じ方針)。 */
    @ExceptionHandler(AnalyticsServiceException.class)
    public ResponseEntity<ErrorResponse> handleAnalyticsServiceException(AnalyticsServiceException e) {
        log.warn("analytics-serviceへのブリッジ呼び出しに失敗しました: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(ProvisioningException.class)
    public ResponseEntity<ErrorResponse> handleProvisioningException(ProvisioningException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(SiteAlreadyProvisionedException.class)
    public ResponseEntity<ErrorResponse> handleSiteAlreadyProvisionedException(SiteAlreadyProvisionedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(SshOperationException.class)
    public ResponseEntity<ErrorResponse> handleSshOperationException(SshOperationException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(AgentOperationException.class)
    public ResponseEntity<ErrorResponse> handleAgentOperationException(AgentOperationException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(TwoFactorSecretNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTwoFactorSecretNotFound(TwoFactorSecretNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(InvalidTotpCodeException.class)
    public ResponseEntity<ErrorResponse> handleInvalidTotpCode(InvalidTotpCodeException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(QrCodeGenerationException.class)
    public ResponseEntity<ErrorResponse> handleQrCodeGeneration(QrCodeGenerationException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(BackupException.class)
    public ResponseEntity<ErrorResponse> handleBackupException(BackupException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(SshKeyPairNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSshKeyPairNotFound(SshKeyPairNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
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

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<Map<String, String>> handleRateLimitExceeded(RateLimitExceededException e) {
        log.warn("Rate limit exceeded: {}", e.getRateLimiterName());
        Map<String, String> response = Map.of(
                "error", "Rate limit exceeded",
                "message", e.getMessage(),
                "retry-after", "60"
        );
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "60")
                .body(response);
    }
}
