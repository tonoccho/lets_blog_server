package com.letsblog.api.cms;

/**
 * {@link CmsAdapter#testConnection}の結果。失敗時はUIに表示可能な簡潔な理由を持つ。
 */
public record ConnectionCheckResult(boolean ok, String failureReason) {

    public static ConnectionCheckResult success() {
        return new ConnectionCheckResult(true, null);
    }

    public static ConnectionCheckResult failure(String reason) {
        return new ConnectionCheckResult(false, reason);
    }
}
