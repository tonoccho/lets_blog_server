package com.letsblog.content.domain;

/**
 * content-serviceが記録する監査アクション。legacy-apiのAuditLogAction(多数の非content関連アクションを
 * 含む共有enum)から、本サービスが実際に記録するもの(カスタムタグのCRUD)だけを移設する(issue #576)。
 */
public enum AuditLogAction {
    CUSTOM_TAG_CREATED("カスタムタグ作成"),
    CUSTOM_TAG_UPDATED("カスタムタグ更新"),
    CUSTOM_TAG_DELETED("カスタムタグ削除");

    private final String displayName;

    AuditLogAction(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
