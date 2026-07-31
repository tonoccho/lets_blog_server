package com.letsblog.api.cms;

/**
 * {@link CmsAdapter#testConnection}の結果。失敗時はUIに表示可能な簡潔な理由を持つ。
 * observedHostKeyFingerprintはSSHトランスポートの場合のみ設定される(TOFUで観測したホスト鍵fingerprint)。
 */
public record ConnectionCheckResult(boolean ok, String failureReason, String observedHostKeyFingerprint) {

    public static ConnectionCheckResult success() {
        return new ConnectionCheckResult(true, null, null);
    }

    public static ConnectionCheckResult success(String observedHostKeyFingerprint) {
        return new ConnectionCheckResult(true, null, observedHostKeyFingerprint);
    }

    public static ConnectionCheckResult failure(String reason) {
        return new ConnectionCheckResult(false, reason, null);
    }
}
