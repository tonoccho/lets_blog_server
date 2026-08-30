package com.letsblog.common.client;

/**
 * {@link SyncServiceClient}経由のサービス間同期呼び出しが失敗した際の共通基底例外(issue #581)。
 * 下流の5xx・タイムアウト・サーキットブレーカー作動・通信断のいずれも、呼び出し元へそのまま
 * (下流の生の例外/ステータスとして)漏らさず、この階層のいずれかへ翻訳して送出する。
 *
 * <p>呼び出し元(各サービスの業務ロジック)は、この例外(または具象サブタイプ)を捕捉し、
 * 呼び出し先ごとに決めたフォールバック方針(機能縮退/プレースホルダ表示、または明確なエラー
 * として上位へ伝播)を適用する。方針の一覧はdocs/SYNC_SERVICE_CALLS.md参照。
 */
public abstract class SyncServiceException extends RuntimeException {

    private final String serviceName;
    private final String operation;

    protected SyncServiceException(String serviceName, String operation, String message) {
        this(serviceName, operation, message, null);
    }

    protected SyncServiceException(String serviceName, String operation, String message, Throwable cause) {
        super("[" + serviceName + "] " + operation + ": " + message, cause);
        this.serviceName = serviceName;
        this.operation = operation;
    }

    /** 呼び出し先サービス名(例: "media-service")。サーキットブレーカー名・ログ出力に使う。 */
    public String serviceName() {
        return serviceName;
    }

    /** 呼び出し内容の識別子(例: "GET /api/identity/me")。ログ出力・監視に使う。 */
    public String operation() {
        return operation;
    }
}
