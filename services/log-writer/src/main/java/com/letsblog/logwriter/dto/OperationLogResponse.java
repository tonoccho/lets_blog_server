package com.letsblog.logwriter.dto;

import com.letsblog.logwriter.domain.OperationLog;
import java.time.Instant;

/** 操作ログの公開レスポンス(issue #1538)。日時は UTC の Z 終端 RFC 3339 で返す。 */
public record OperationLogResponse(
        Long id,
        String operationId,
        Long userId,
        String actorKeycloakSub,
        String method,
        String path,
        Integer statusCode,
        Long durationMs,
        boolean success,
        String errorMessage,
        Instant createdAt
) {

    public static OperationLogResponse from(OperationLog log) {
        return new OperationLogResponse(
                log.getId(), log.getOperationId(), log.getUserId(), log.getActorKeycloakSub(),
                log.getMethod(), log.getPath(), log.getStatusCode(), log.getDurationMs(),
                log.isSuccess(), log.getErrorMessage(), UtcDateTimes.toInstant(log.getCreatedAt()));
    }
}
