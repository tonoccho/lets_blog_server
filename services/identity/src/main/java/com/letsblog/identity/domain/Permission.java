package com.letsblog.identity.domain;

public enum Permission {
    // ユーザー管理
    USER_CREATE("ユーザー作成"),
    USER_READ("ユーザー閲覧"),
    USER_UPDATE("ユーザー更新"),
    USER_DELETE("ユーザー削除"),
    USER_ROLE_MANAGE("ユーザーロール変更"),

    // 投稿管理
    POST_CREATE("投稿作成"),
    POST_READ("投稿閲覧"),
    POST_UPDATE("投稿更新"),
    POST_DELETE("投稿削除"),
    POST_PUBLISH("投稿公開"),

    // サイト管理
    SITE_CREATE("サイト登録"),
    SITE_READ("サイト閲覧"),
    SITE_UPDATE("サイト設定変更"),
    SITE_DELETE("サイト削除"),

    // 監査ログ
    AUDIT_LOG_VIEW("監査ログ閲覧"),
    AUDIT_LOG_DELETE("監査ログ削除"),

    // システム
    SYSTEM_CONFIG("システム設定変更"),
    ROLE_MANAGE("ロール・権限管理");

    private final String description;

    Permission(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
