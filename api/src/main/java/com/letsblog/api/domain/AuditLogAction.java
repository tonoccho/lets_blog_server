package com.letsblog.api.domain;

public enum AuditLogAction {
    LOGIN("ログイン"),
    USER_CREATED("ユーザー作成"),
    USER_UPDATED("ユーザー更新"),
    USER_DELETED("ユーザー削除"),
    POST_PUBLISHED("投稿公開"),
    POST_DELETED("投稿削除"),
    SITE_REGISTERED("サイト登録"),
    PASSWORD_RESET_REQUESTED("パスワード再設定リクエスト"),
    PASSWORD_RESET_CONFIRMED("パスワード再設定完了"),
    TWO_FACTOR_ENABLED("2FA有効化"),
    TWO_FACTOR_DISABLED("2FA無効化"),
    USER_ROLE_ASSIGNED("ユーザーロール割り当て"),
    USER_ROLE_REMOVED("ユーザーロール削除"),
    CUSTOM_TAG_CREATED("カスタムタグ作成"),
    CUSTOM_TAG_UPDATED("カスタムタグ更新"),
    CUSTOM_TAG_DELETED("カスタムタグ削除"),
    SITE_DELETED("サイト削除"),
    WORDPRESS_PROVISIONED("WordPress自動構築"),
    WORDPRESS_ADOPTED("既存WordPressサイト取り込み"),
    PROJECT_CREATED("プロジェクト作成"),
    PROJECT_UPDATED("プロジェクト更新"),
    PROJECT_DELETED("プロジェクト削除"),
    PROJECT_ENVIRONMENT_BOUND("プロジェクト環境紐付け"),
    PROJECT_ENVIRONMENT_UNBOUND("プロジェクト環境切離し"),
    PROJECT_USER_ADDED("プロジェクトユーザー追加"),
    PROJECT_USER_ROLE_UPDATED("プロジェクトユーザーロール変更"),
    PROJECT_USER_REMOVED("プロジェクトユーザー削除"),
    DB_BACKUP_DOWNLOADED("DBバックアップダウンロード"),
    DB_RESTORED("DBリストア"),
    SYSTEM_SETTING_UPDATED("システム設定更新"),
    SSH_KEY_PAIR_CREATED("SSH鍵ペア作成"),
    SSH_KEY_PAIR_DELETED("SSH鍵ペア削除");

    private final String displayName;

    AuditLogAction(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
