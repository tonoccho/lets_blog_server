package com.letsblog.api.domain;

/**
 * provision-agentへ送るwp-cli側のaction識別子は、この列挙子の名前を小文字化した値
 * (例: CATEGORY_CREATE → "category_create")をそのまま使う。追加時は
 * wordpress/provision-agent/index.php の /bulk-management ハンドラにも対応するcase節を追加すること。
 */
public enum BulkOperationType {
    CATEGORY_CREATE,
    CATEGORY_EDIT,
    CATEGORY_DELETE,
    PLUGIN_INSTALL,
    PLUGIN_ACTIVATE,
    PLUGIN_DEACTIVATE,
    PLUGIN_DELETE,
    THEME_INSTALL,
    THEME_ACTIVATE,
    THEME_DELETE;

    public String wpCliAction() {
        return name().toLowerCase();
    }

    public boolean isCategory() {
        return this == CATEGORY_CREATE || this == CATEGORY_EDIT || this == CATEGORY_DELETE;
    }

    public boolean supportsZipUpload() {
        return this == PLUGIN_INSTALL || this == THEME_INSTALL;
    }
}
