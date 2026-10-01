package com.letsblog.publishing.domain;

/**
 * publishing-serviceが記録する監査アクション。legacy-apiのAuditLogAction(多数の非publishing関連
 * アクションを含む共有enum)から、本サービスが実際に記録するもの(投稿の公開・削除)だけを移設する
 * (issue #707)。
 */
public enum AuditLogAction {
    POST_PUBLISHED("投稿公開"),
    POST_DELETED("投稿削除"),

    // issue #1137: bulk-management の delete-all(全環境からの一括削除)5本。
    // 破壊的かつ取り消せない操作である一方、監査ログを1件も残していなかった。
    CATEGORY_BULK_DELETED("カテゴリ一括削除"),
    TAG_BULK_DELETED("タグ一括削除"),
    PLUGIN_BULK_DELETED("プラグイン一括削除"),
    THEME_BULK_DELETED("テーマ一括削除"),
    POST_BULK_DELETED("投稿一括削除");

    private final String displayName;

    AuditLogAction(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
