package com.letsblog.api.dto;

/** {@code CmsProvisioningBridgeController#exportDatabase}のレスポンス。dumpBase64はSQLダンプ本体をBase64化したもの。 */
public record CmsBridgeExportDatabaseResponse(String tablePrefix, String dumpBase64) {
}
