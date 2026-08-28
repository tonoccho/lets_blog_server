package com.letsblog.project.cms;

/** publishing-serviceのCmsProvisioningBridgeController#testConnectionの応答を表す(issue #577 stage2で新設、issue #710でlegacy-apiからpublishing-serviceへ移管)。 */
public record ConnectionCheckResult(boolean ok, String failureReason, String observedHostKeyFingerprint, String detail) {

    public static ConnectionCheckResult failure(String reason) {
        return new ConnectionCheckResult(false, reason, null, null);
    }
}
