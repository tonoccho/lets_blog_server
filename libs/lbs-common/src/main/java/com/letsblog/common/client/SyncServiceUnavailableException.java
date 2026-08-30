package com.letsblog.common.client;

/**
 * 接続拒否・DNS解決失敗・コネクションリセット等、応答自体を受け取れなかった通信断で送出する
 * (issue #581)。タイムアウト({@link SyncServiceTimeoutException})とは区別する
 * (タイムアウトは設定時間分待った末の失敗、こちらは即座に失敗が判明したケース)。
 */
public class SyncServiceUnavailableException extends SyncServiceException {

    public SyncServiceUnavailableException(String serviceName, String operation, Throwable cause) {
        super(serviceName, operation, "呼び出し先へ到達できませんでした: " + rootMessage(cause), cause);
    }

    private static String rootMessage(Throwable cause) {
        return cause == null || cause.getMessage() == null ? "" : cause.getMessage();
    }
}
