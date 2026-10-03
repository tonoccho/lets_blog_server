package com.letsblog.logwriter.dto;

import com.letsblog.logwriter.domain.AuditLog;
import java.time.Instant;

/** 監査ログの公開レスポンス(issue #1538)。日時は UTC の Z 終端 RFC 3339 で返す。 */
public record AuditLogResponse(
        Long id,
        Long userId,
        String actorKeycloakSub,
        String action,
        String resourceType,
        Long resourceId,
        String changes,
        String remoteIp,
        String userAgent,
        Instant createdAt
) {

    public static AuditLogResponse from(AuditLog log) {
        return new AuditLogResponse(
                log.getId(), log.getUserId(), log.getActorKeycloakSub(), log.getAction(),
                log.getResourceType(), log.getResourceId(), log.getChanges(), log.getRemoteIp(),
                log.getUserAgent(), UtcDateTimes.toInstant(log.getCreatedAt()));
    }
}
