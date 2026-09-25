package com.letsblog.project.domain;

/**
 * project-serviceが記録する監査アクション。legacy-apiのAuditLogAction(多数の非project関連アクションを
 * 含む共有enum)から、本サービスが実際に記録するもの(SSH鍵ペアのCRUD)だけを移設する(issue #577)。
 */
public enum AuditLogAction {
    SSH_KEY_PAIR_CREATED("SSH鍵ペア作成"),
    SSH_KEY_PAIR_DELETED("SSH鍵ペア削除"),
    SITE_REGISTERED("サイト登録"),
    SITE_DELETED("サイト削除"),
    WORDPRESS_PROVISIONED("WordPress自動構築"),
    WORDPRESS_ADOPTED("既存WordPressサイト取り込み"),
    PROJECT_CREATED("プロジェクト作成"),
    PROJECT_UPDATED("プロジェクト更新"),
    PROJECT_DELETED("プロジェクト削除"),
    PROJECT_ENVIRONMENT_BOUND("プロジェクト環境紐付け"),
    PROJECT_ENVIRONMENT_UNBOUND("プロジェクト環境切離し");

    private final String displayName;

    AuditLogAction(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
