package com.letsblog.platform.domain;

/**
 * platform-serviceが記録する監査アクション。legacy-apiのAuditLogAction(多数の非platform関連
 * アクションを含む共有enum)から、本サービスが実際に記録するもの(システム設定の更新)だけを
 * 移設する(issue #693、content-service(#576)のAuditLogActionと同じ方針)。
 */
public enum AuditLogAction {
    SYSTEM_SETTING_UPDATED("システム設定更新");

    private final String displayName;

    AuditLogAction(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
