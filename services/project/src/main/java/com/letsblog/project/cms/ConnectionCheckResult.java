package com.letsblog.project.cms;

/** legacy-apiのCmsProvisioningBridgeController#testConnectionの応答を表す(issue #577 stage2)。 */
public record ConnectionCheckResult(boolean ok, String failureReason, String observedHostKeyFingerprint, String detail) {

    public static ConnectionCheckResult failure(String reason) {
        return new ConnectionCheckResult(false, reason, null, null);
    }
}
