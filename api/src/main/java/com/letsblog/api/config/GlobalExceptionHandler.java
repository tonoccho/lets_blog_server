package com.letsblog.api.config;

import com.letsblog.api.cms.CmsApiException;
import com.letsblog.api.cms.agent.AgentOperationException;
import com.letsblog.api.cms.ssh.SshOperationException;
import com.letsblog.api.github.GithubApiException;
import com.letsblog.api.service.ArticlePlanSessionNotFoundException;
import com.letsblog.api.service.CustomTagNotFoundException;
import com.letsblog.api.service.EmailAlreadyExistsException;
import com.letsblog.api.service.EmailSendException;
import com.letsblog.api.service.ForbiddenException;
import com.letsblog.api.service.GeneratedImageNotFoundException;
import com.letsblog.api.service.GenerationJobNotFoundException;
import com.letsblog.api.service.InvalidCredentialsException;
import com.letsblog.api.service.InvalidRoleException;
import com.letsblog.api.service.InvalidTokenException;
import com.letsblog.api.service.InvalidTotpCodeException;
import com.letsblog.api.service.MailTemplateNotFoundException;
import com.letsblog.api.service.ProjectNotFoundException;
import com.letsblog.api.service.ProjectUserNotFoundException;
import com.letsblog.api.service.ProvisioningException;
import com.letsblog.api.service.QrCodeGenerationException;
import com.letsblog.api.service.RoleNotFoundException;
import com.letsblog.api.service.SiteNotFoundException;
import com.letsblog.api.service.TwoFactorSecretNotFoundException;
import com.letsblog.api.service.UserNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(SiteNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleSiteNotFound(SiteNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(CustomTagNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleCustomTagNotFound(CustomTagNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleProjectNotFound(ProjectNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ArticlePlanSessionNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleArticlePlanSessionNotFound(ArticlePlanSessionNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(GenerationJobNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleGenerationJobNotFound(GenerationJobNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(GeneratedImageNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleGeneratedImageNotFound(GeneratedImageNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ProjectUserNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleProjectUserNotFound(ProjectUserNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RoleNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleRoleNotFound(RoleNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleUserNotFound(UserNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<Map<String, String>> handleEmailAlreadyExists(EmailAlreadyExistsException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InvalidRoleException.class)
    public ResponseEntity<Map<String, String>> handleInvalidRole(InvalidRoleException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, String>> handleInvalidCredentials(InvalidCredentialsException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<Map<String, String>> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<Map<String, String>> handleInvalidToken(InvalidTokenException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(EmailSendException.class)
    public ResponseEntity<Map<String, String>> handleEmailSendError(EmailSendException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MailTemplateNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleMailTemplateNotFound(MailTemplateNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(CmsApiException.class)
    public ResponseEntity<Map<String, String>> handleCmsApiException(CmsApiException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(ProvisioningException.class)
    public ResponseEntity<Map<String, String>> handleProvisioningException(ProvisioningException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(SshOperationException.class)
    public ResponseEntity<Map<String, String>> handleSshOperationException(SshOperationException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(AgentOperationException.class)
    public ResponseEntity<Map<String, String>> handleAgentOperationException(AgentOperationException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(GithubApiException.class)
    public ResponseEntity<Map<String, String>> handleGithubApiException(GithubApiException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(TwoFactorSecretNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleTwoFactorSecretNotFound(TwoFactorSecretNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(InvalidTotpCodeException.class)
    public ResponseEntity<Map<String, String>> handleInvalidTotpCode(InvalidTotpCodeException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(QrCodeGenerationException.class)
    public ResponseEntity<Map<String, String>> handleQrCodeGeneration(QrCodeGenerationException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("validation error");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
    }
}
