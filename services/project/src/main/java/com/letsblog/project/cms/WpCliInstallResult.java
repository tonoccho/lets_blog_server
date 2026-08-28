package com.letsblog.project.cms;

/** publishing-serviceのCmsProvisioningBridgeController#installWpCliの応答(issue #577 stage2で新設、issue #710でlegacy-apiからpublishing-serviceへ移管)。 */
public record WpCliInstallResult(String message) {
}
