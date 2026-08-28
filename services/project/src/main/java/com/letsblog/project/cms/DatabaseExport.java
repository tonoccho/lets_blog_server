package com.letsblog.project.cms;

/** publishing-serviceのCmsProvisioningBridgeController#exportDatabaseの応答(issue #577 stage2で新設、issue #710でlegacy-apiからpublishing-serviceへ移管)。dumpはSQLダンプ本体。 */
public record DatabaseExport(String tablePrefix, byte[] dump) {
}
