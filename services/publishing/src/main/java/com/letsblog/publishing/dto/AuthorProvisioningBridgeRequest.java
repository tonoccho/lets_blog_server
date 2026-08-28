package com.letsblog.publishing.dto;

/**
 * legacy-apiの{@code ProjectUserSyncService#provisionUserOnSite}から、著者(WordPressユーザー)の
 * 作成/更新を依頼するための内部ブリッジリクエスト(issue #707、#575設計判断4の書き込み側)。
 * {@code cms.AuthorProvisioningRequest}と同じ形。
 */
public record AuthorProvisioningBridgeRequest(
        String email,
        String wpRole,
        String firstName,
        String lastName,
        String displayName,
        String websiteUrl,
        String bio,
        String locale
) {
}
