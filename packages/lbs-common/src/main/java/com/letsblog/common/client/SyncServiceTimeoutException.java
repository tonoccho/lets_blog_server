package com.letsblog.common.client;

/**
 * 接続タイムアウト・読み取りタイムアウトのいずれかで、設定した時間内に下流から応答が
 * 得られなかった場合に送出する(issue #581)。呼び出し元をハングさせないことがこの例外の目的であり、
 * {@link SyncCallProfile}で選んだ読み取りタイムアウトを超えて待たされることはない。
 */
public class SyncServiceTimeoutException extends SyncServiceException {

    public SyncServiceTimeoutException(String serviceName, String baseUrl, String operation, Throwable cause) {
        super(serviceName, baseUrl, operation, "呼び出しがタイムアウトしました", cause);
    }
}
