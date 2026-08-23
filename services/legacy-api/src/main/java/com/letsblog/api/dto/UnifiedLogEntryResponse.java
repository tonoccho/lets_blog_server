package com.letsblog.api.dto;

import java.time.LocalDateTime;

/**
 * 操作ログ・AIジョブ・監査ログを一元表示するための共通形式(issue #187)。
 * sourceTypeは"OPERATION"/"AI_JOB"/"AUDIT"のいずれか。
 *
 * <p>{@code actorKeycloakSub}はissue #569で追加。OPERATION/AUDITはJWTから解決したKeycloakの
 * subクレームを持つ場合がある(X-Actor-Idヘッダー経由の操作、またはまだKeycloak移行前の
 * Web/VSCode拡張からの操作ではnull)。AI_JOBはactor概念を持たないため常にnull。
 */
public record UnifiedLogEntryResponse(
        String sourceType,
        Long id,
        LocalDateTime createdAt,
        String title,
        String detail,
        String status,
        String operationId,
        String actorKeycloakSub
) {
}
