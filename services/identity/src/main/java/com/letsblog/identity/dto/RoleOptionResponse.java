package com.letsblog.identity.dto;

import com.letsblog.identity.domain.Role;

/**
 * ロールの表示名のみを返す軽量版レスポンス。権限詳細を返すレスポンスと異なり権限一覧を含まず、
 * {@code ROLE_MANAGE}権限を持たない一般の認証済みユーザーでも参照できる(issue #472)。
 */
public record RoleOptionResponse(String roleName, String displayName) {
    public static RoleOptionResponse from(Role role) {
        return new RoleOptionResponse(role.getRoleName(), role.getDisplayName());
    }
}
