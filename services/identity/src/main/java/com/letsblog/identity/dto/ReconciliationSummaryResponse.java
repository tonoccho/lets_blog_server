package com.letsblog.identity.dto;

import java.util.List;

/**
 * 孤児検出(#562)の結果。Keycloak側で削除され、identity-service側では
 * 論理無効化(enabled=false)されたユーザーIDの一覧。
 */
public record ReconciliationSummaryResponse(
        List<Long> deactivatedUserIds
) {
}
