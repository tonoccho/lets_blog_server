package com.letsblog.content.dto;

/**
 * ロールの表示名のみを返す軽量版レスポンス(legacy-apiのRoleOptionResponseと同じ形)。
 * rolesテーブルはlegacy-apiに残るドメインのため、{@link com.letsblog.content.client.ProjectBridgeClient}
 * が内部ブリッジ経由で取得した結果をそのままこの形へ詰め替えて返す(legacy-api版と異なり、
 * ローカルのRoleエンティティは持たない)。
 */
public record RoleOptionResponse(String roleName, String displayName) {
}
