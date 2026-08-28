package com.letsblog.publishing.domain;

/**
 * provision-agentへ送るwp-cli側のaction識別子は、この列挙子の名前を小文字化した値
 * (例: CATEGORY_CREATE → "category_create")をそのまま使う。追加時は
 * wordpress/provision-agent/index.php の /bulk-management ハンドラにも対応するcase節を追加すること。
 */
public enum BulkOperationType {
    CATEGORY_CREATE,
    CATEGORY_EDIT,
    CATEGORY_DELETE,
    TAG_CREATE,
    TAG_EDIT,
    TAG_DELETE,
    PLUGIN_INSTALL,
    PLUGIN_ACTIVATE,
    PLUGIN_DEACTIVATE,
    PLUGIN_DELETE,
    THEME_INSTALL,
    THEME_ACTIVATE,
    THEME_DELETE,
    // 比較テーブルの一覧取得(読み取り)が失敗した際に、作業ログへエラーを記録する専用の種別。
    // wp-cli/REST操作を実際に発行するわけではないため、wpCliAction()は使わない。
    CATEGORY_FETCH,
    TAG_FETCH,
    PLUGIN_FETCH,
    THEME_FETCH,
    POST_FETCH,
    // アセット画像の全環境アップロード(BulkManagementService#uploadImageToAllEnvironments)。
    // applyToSite()のwp-cli/REST分岐は経由せずCmsAdapter.uploadMediaを直接呼ぶため、
    // wpCliAction()は使わない。
    MEDIA_UPLOAD,
    // ポスト/ページの削除・ステータス変更(スラッグで全環境へ適用)。
    POST_DELETE,
    POST_STATUS_UPDATE;

    public String wpCliAction() {
        return name().toLowerCase();
    }

    public boolean supportsZipUpload() {
        return this == PLUGIN_INSTALL || this == THEME_INSTALL;
    }

    /**
     * 作成・編集は比較テーブル上「マスター環境のみ」を対象に実行する(他環境への反映は同期操作で行う)。
     * ProjectController#applyBulkOperationでこの制約を検証する。
     */
    public boolean requiresMasterEnvironment() {
        return this == CATEGORY_CREATE || this == CATEGORY_EDIT || this == TAG_CREATE || this == TAG_EDIT;
    }
}
