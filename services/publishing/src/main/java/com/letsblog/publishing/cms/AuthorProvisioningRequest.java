package com.letsblog.publishing.cms;

/**
 * CmsAdapter.provisionAuthor() へ渡す著者情報。
 * wpRoleはWordPressの標準ロール("administrator"/"editor"/"author"/"contributor"/"subscriber"等)を想定。
 */
public record AuthorProvisioningRequest(
        String email,
        String wpRole,
        String firstName,
        String lastName,
        String displayName,
        String websiteUrl,
        String bio,
        String locale
) {
    /**
     * サイト登録時の管理者プロビジョニング用途など、プロフィール情報を持たない場合の簡易生成。
     */
    public static AuthorProvisioningRequest of(String email) {
        return new AuthorProvisioningRequest(email, "author", null, null, null, null, null, null);
    }
}
