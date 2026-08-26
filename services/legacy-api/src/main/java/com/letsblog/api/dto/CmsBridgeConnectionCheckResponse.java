package com.letsblog.api.dto;

import com.letsblog.api.cms.ConnectionCheckResult;

/** {@code CmsProvisioningBridgeController#testConnection}のレスポンス。{@link ConnectionCheckResult}をそのまま転写する。 */
public record CmsBridgeConnectionCheckResponse(
        boolean ok, String failureReason, String observedHostKeyFingerprint, String detail) {

    public static CmsBridgeConnectionCheckResponse from(ConnectionCheckResult result) {
        return new CmsBridgeConnectionCheckResponse(
                result.ok(), result.failureReason(), result.observedHostKeyFingerprint(), result.detail());
    }
}
