package com.letsblog.identity.dto;

import java.util.List;

/**
 * 一括移行(#562)の対象を限定するためのリクエスト。userIdsを省略/nullにした場合は、
 * まだkeycloak_subが設定されていない全ユーザーが対象になる。
 */
public record MigrateToKeycloakRequest(
        List<Long> userIds
) {
}
