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
    private final String baseUrl;
    private final String operation;

    protected SyncServiceException(String serviceName, String baseUrl, String operation, String message) {
        this(serviceName, baseUrl, operation, message, null);
    }

    protected SyncServiceException(
            String serviceName, String baseUrl, String operation, String message, Throwable cause) {
        super(buildMessage(serviceName, baseUrl, operation, message), cause);
        this.serviceName = serviceName;
        this.baseUrl = baseUrl;
        this.operation = operation;
    }

    /**
     * 論理サービス名だけでなく実際の宛先も含める(issue #827)。
     *
     * <p>{@code serviceName}は呼び出し元が付ける論理名にすぎず、設定した{@code baseUrl}が
     * 別サービスを指していても食い違いに気付けない。実際、#825は「移設済みのエンドポイントを
     * 別サービスへ問い合わせ続けていた」バグだったが、ログは{@code [ai-service] ... 404}と出る
     * 一方で実際の宛先はlegacy-apiであり、切り分けを著しく難しくした。
     *
     * <p>接続自体が失敗した場合はSpringの{@code ResourceAccessException}がURLを含むため
     * cause経由で読めるが、「別のサービスを叩いてしまっている」ときこそ読めない、という
     * 切り分け上いちばん困る方向に情報が欠けていた。
     */
    private static String buildMessage(String serviceName, String baseUrl, String operation, String message) {
        String target = (baseUrl == null || baseUrl.isBlank()) ? serviceName : serviceName + " @ " + baseUrl;
        return "[" + target + "] " + operation + ": " + message;
    }

    /** 呼び出し先サービス名(例: "media-service")。サーキットブレーカー名・ログ出力に使う。 */
    public String serviceName() {
        return serviceName;
    }

    /**
     * 実際に呼び出したベースURL(例: "http://ai:8080")。設定ミスによる向き先違いを
     * ログから切り分けるために保持する(issue #827)。取得できない場合はnull。
     */
    public String baseUrl() {
        return baseUrl;
    }

    /** 呼び出し内容の識別子(例: "GET /api/identity/me")。ログ出力・監視に使う。 */
    public String operation() {
        return operation;
    }
}
