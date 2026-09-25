package com.letsblog.identity.dto;

import java.util.List;
import java.util.Map;

/**
 * 一括移行(#562)の結果。一部のユーザーでKeycloak側の作成に失敗しても全体を中断せず、
 * 成功/失敗を分けて返す(失敗したユーザーは後で個別にuserIds指定で再実行できる)。
 */
public record MigrationSummaryResponse(
        List<Long> migratedUserIds,
        Map<Long, String> failedUserIds
) {
}
