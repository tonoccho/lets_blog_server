package com.letsblog.project.cms;

/** legacy-apiのCmsProvisioningBridgeController#exportDatabaseの応答(issue #577 stage2)。dumpはSQLダンプ本体。 */
public record DatabaseExport(String tablePrefix, byte[] dump) {
}
