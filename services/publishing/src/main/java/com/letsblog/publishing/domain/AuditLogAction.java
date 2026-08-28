package com.letsblog.publishing.domain;

/**
 * publishing-serviceが記録する監査アクション。legacy-apiのAuditLogAction(多数の非publishing関連
 * アクションを含む共有enum)から、本サービスが実際に記録するもの(投稿の公開・削除)だけを移設する
 * (issue #707)。
 */
public enum AuditLogAction {
    POST_PUBLISHED("投稿公開"),
    POST_DELETED("投稿削除");

    private final String displayName;

    AuditLogAction(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
