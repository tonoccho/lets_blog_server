package com.letsblog.api.dto;

import com.letsblog.api.domain.Role;

/**
 * ロールの表示名のみを返す軽量版レスポンス。{@link RoleResponse}と異なり権限一覧を含まず、
 * {@code ROLE_MANAGE}権限を持たない一般の認証済みユーザーでも参照できる(issue #472)。
 */
public record RoleOptionResponse(String roleName, String displayName) {
    public static RoleOptionResponse from(Role role) {
        return new RoleOptionResponse(role.getRoleName(), role.getDisplayName());
    }
}
