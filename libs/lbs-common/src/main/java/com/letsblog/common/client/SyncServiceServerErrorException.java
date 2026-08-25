package com.letsblog.common.client;

/**
 * 下流サービスが5xxを返した場合に送出する(issue #581)。下流の生のステータス・本文をそのまま
 * 呼び出し元(このサービスのAPI利用者)へ伝播させない(受入基準「下流サービス停止時に、呼び出し元が
 * ハングせず明確なエラーを返す」)ため、常にこの型に翻訳した上で、呼び出し元の業務ロジックが
 * フォールバック方針(機能縮退/明確なエラー)を選ぶ。
 */
public class SyncServiceServerErrorException extends SyncServiceException {

    private final int statusCode;
    private final String responseBody;

    public SyncServiceServerErrorException(
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
