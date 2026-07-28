package com.letsblog.api.domain;

public enum AuditLogAction {
    LOGIN("ログイン"),
    USER_CREATED("ユーザー作成"),
    USER_UPDATED("ユーザー更新"),
    USER_DELETED("ユーザー削除"),
    POST_PUBLISHED("投稿公開"),
    SITE_REGISTERED("サイト登録"),
    PASSWORD_RESET_REQUESTED("パスワード再設定リクエスト"),
    PASSWORD_RESET_CONFIRMED("パスワード再設定完了");

    private final String displayName;

    AuditLogAction(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
