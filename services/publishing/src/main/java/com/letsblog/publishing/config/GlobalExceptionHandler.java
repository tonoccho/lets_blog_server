package com.letsblog.publishing.config;

import com.letsblog.common.web.ErrorResponse;
import com.letsblog.publishing.cms.CmsApiException;
import com.letsblog.publishing.cms.agent.AgentOperationException;
import com.letsblog.publishing.cms.ssh.SshOperationException;
import com.letsblog.publishing.render.MediaRenderException;
import com.letsblog.publishing.service.ForbiddenException;
import com.letsblog.publishing.service.IdentityServiceUnavailableException;
import com.letsblog.publishing.service.InvalidPlantUmlTagException;
import com.letsblog.publishing.service.InvalidRechartsTagException;
import com.letsblog.publishing.service.ProjectNotFoundException;
import com.letsblog.publishing.service.ProvisioningException;
import com.letsblog.publishing.service.SiteNotFoundException;
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
 * publishing-service(issue #707)のグローバル例外ハンドリング。legacy-apiのGlobalExceptionHandlerから、
 * 本サービスへ移設した例外(cms/*, PlantUML組み込みタグ)へのハンドリングのみを移設・新設する。
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

    @ExceptionHandler(SiteNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleSiteNotFound(SiteNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(ProjectNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleProjectNotFound(ProjectNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(e.getMessage()));
    }

    /** BulkManagementController/TaxonomyControllerが使う(issue #708)。 */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(InvalidPlantUmlTagException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPlantUmlTag(InvalidPlantUmlTagException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(InvalidRechartsTagException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRechartsTag(InvalidRechartsTagException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(CmsApiException.class)
    public ResponseEntity<ErrorResponse> handleCmsApiException(CmsApiException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(SshOperationException.class)
    public ResponseEntity<ErrorResponse> handleSshOperationException(SshOperationException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(AgentOperationException.class)
    public ResponseEntity<ErrorResponse> handleAgentOperationException(AgentOperationException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    @ExceptionHandler(MediaRenderException.class)
    public ResponseEntity<ErrorResponse> handleMediaRenderException(MediaRenderException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    /**
     * CmsProvisioningBridgeControllerが委譲するサイトプロビジョニングの致命的な失敗(issue #710で
     * legacy-apiから移設)。CMSアダプタの解決自体に失敗した場合のみ投げられる(部分的な失敗は
     * ProvisioningResultのエラーフィールドとして200で返る)。
     */
    @ExceptionHandler(ProvisioningException.class)
    public ResponseEntity<ErrorResponse> handleProvisioningException(ProvisioningException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ErrorResponse.of(e.getMessage()));
    }

    /** project-service/legacy-api/content-serviceへの内部ブリッジ呼び出しの失敗(#572/#573/#574と同じ方針)。 */
    @ExceptionHandler(IdentityServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleIdentityServiceUnavailable(IdentityServiceUnavailableException e) {
        log.warn("identity-service/legacy-api/project-service/content-serviceへのブリッジ呼び出しに失敗しました: {}",
                e.getMessage());
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
