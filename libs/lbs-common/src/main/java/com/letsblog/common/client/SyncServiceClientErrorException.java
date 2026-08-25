package com.letsblog.common.client;

/**
 * 下流サービスが4xx(呼び出し内容自体が不正、または権限が無い等)を返した場合に送出する
 * (issue #581)。呼び出し元(このサービス自身)の呼び方・入力値が原因である可能性が高いため、
 * {@link SyncServiceServerErrorException}とは区別する(サーキットブレーカーの失敗カウントにも
 * 含めない。下流サービス自体は健全に応答しているため)。
 */
public class SyncServiceClientErrorException extends SyncServiceException {

    private final int statusCode;
    private final String responseBody;

    public SyncServiceClientErrorException(
            String serviceName, String operation, int statusCode, String responseBody, Throwable cause) {
        super(serviceName, operation, statusCode + "を返しました: " + responseBody, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }
}
