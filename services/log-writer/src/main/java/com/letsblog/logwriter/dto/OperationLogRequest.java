package com.letsblog.logwriter.dto;

import com.letsblog.logwriter.domain.OperationLog;

public record OperationLogRequest(
        String operationId,
        String method,
        String path,
        Integer statusCode,
        Long durationMs,
        boolean success,
        String errorMessage
) {
    public OperationLog toDomain(Long userId, String actorKeycloakSub) {
        OperationLog log = new OperationLog();
        log.setUserId(userId);
        log.setActorKeycloakSub(actorKeycloakSub);
        log.setOperationId(operationId);
        log.setMethod(method);
        log.setPath(path);
        log.setStatusCode(statusCode);
        log.setDurationMs(durationMs != null ? durationMs : 0L);
        log.setSuccess(success);
        log.setErrorMessage(errorMessage);
        return log;
    }
}
