package com.letsblog.publishing.cms;

/**
 * {@link CmsAdapter#testConnection}の結果。失敗時はUIに表示可能な簡潔な理由を持つ
 * (SSHトランスポートの場合、どの段階(SSH接続/wp-cli実行)で失敗したかを含める)。
 * observedHostKeyFingerprintはSSHトランスポートの場合のみ設定される(TOFUで観測したホスト鍵fingerprint)。
 * detailは成功時の付加情報(SSHトランスポートの場合の`wp core version`応答等)を保持する。
 */
public record ConnectionCheckResult(boolean ok, String failureReason, String observedHostKeyFingerprint, String detail) {

    public static ConnectionCheckResult success() {
        return new ConnectionCheckResult(true, null, null, null);
    }

    public static ConnectionCheckResult success(String observedHostKeyFingerprint) {
        return new ConnectionCheckResult(true, null, observedHostKeyFingerprint, null);
    }

    public static ConnectionCheckResult success(String observedHostKeyFingerprint, String detail) {
        return new ConnectionCheckResult(true, null, observedHostKeyFingerprint, detail);
    }

    public static ConnectionCheckResult failure(String reason) {
        return new ConnectionCheckResult(false, reason, null, null);
    }
}
