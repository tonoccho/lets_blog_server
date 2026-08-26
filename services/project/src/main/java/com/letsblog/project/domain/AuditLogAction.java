package com.letsblog.project.domain;

/**
 * project-serviceが記録する監査アクション。legacy-apiのAuditLogAction(多数の非project関連アクションを
 * 含む共有enum)から、本サービスが実際に記録するもの(SSH鍵ペアのCRUD)だけを移設する(issue #577)。
 */
public enum AuditLogAction {
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
