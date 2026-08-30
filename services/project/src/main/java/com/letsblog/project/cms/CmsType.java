package com.letsblog.project.cms;

/**
 * 対応するCMSの種別(legacy-api の {@code com.letsblog.api.cms.CmsType} と同じ値。issue #577 stage2)。
 * project-service は実際のCMS接続処理そのものは持たず、{@link com.letsblog.project.client.CmsProvisioningBridgeClient}
 * 経由でlegacy-apiへ委ねるが、Siteエンティティのcolumn(cms_type)表現・リクエスト/レスポンスの型付けのために
 * この値だけをこちらでも保持する。
 */
public enum CmsType {
    WORDPRESS
}
