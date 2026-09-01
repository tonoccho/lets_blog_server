package com.letsblog.common.client;

/**
 * サーキットブレーカーがOPEN状態のため、実際のHTTP呼び出しを試みずに即座に失敗させた場合に
 * 送出する(issue #581)。下流サービスが連続して失敗している間、呼び出しのたびにタイムアウト
 * いっぱいまで待たされることを防ぐ(呼び出し元の連鎖障害を防ぐのがこの例外の目的)。
 */
public class SyncServiceCircuitOpenException extends SyncServiceException {

    public SyncServiceCircuitOpenException(String serviceName, String baseUrl, String operation) {
        super(serviceName, baseUrl, operation, "サーキットブレーカーが作動中のため呼び出しを行いませんでした");
    }
}
