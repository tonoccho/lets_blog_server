package com.letsblog.logwriter.dto;

import com.letsblog.logwriter.domain.FrontendErrorLog;
import java.time.Instant;

/** フロントエンドエラーログの公開レスポンス(issue #1538)。日時は UTC の Z 終端 RFC 3339 で返す。 */
public record FrontendErrorLogResponse(
        Long id,
        String message,
        String stack,
        String componentStack,
        Long userId,
        String actorKeycloakSub,
        String level,
        String context,
        String url,
        String userAgent,
        Instant timestamp,
        Instant createdAt
) {

    public static FrontendErrorLogResponse from(FrontendErrorLog log) {
        return new FrontendErrorLogResponse(
                log.getId(), log.getMessage(), log.getStack(), log.getComponentStack(), log.getUserId(),
                log.getActorKeycloakSub(), log.getLevel(), log.getContext(), log.getUrl(), log.getUserAgent(),
                UtcDateTimes.toInstant(log.getTimestamp()), UtcDateTimes.toInstant(log.getCreatedAt()));
    }
}
