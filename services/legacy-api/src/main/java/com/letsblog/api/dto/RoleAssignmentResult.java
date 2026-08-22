package com.letsblog.api.dto;

/**
 * ロール割り当て/削除の監査ログ("changes")に、対象ユーザーIDとロール名の両方を残すためのDTO。
 */
public record RoleAssignmentResult(Long userId, String roleName) {
}
