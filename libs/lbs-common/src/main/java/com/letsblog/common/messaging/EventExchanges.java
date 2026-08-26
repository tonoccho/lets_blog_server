package com.letsblog.common.messaging;

/**
 * ドメインイベント配信(issue #580)用のRabbitMQ exchange/routing key/DLQ命名規約の定数。
 * 全サービス(プロデューサー・コンシューマー双方)が同じ値を参照する必要がある。
 *
 * <p>{@link LogExchanges}({@code letsblog.logs})と同じtopic exchange設計を踏襲するが、
 * こちらはサービス跨ぎの状態伝播(ADR-0004がJOIN/クロススキーマFKを禁じるため)を担う
 * ドメインイベント専用の別exchangeとして新設する(ログと業務イベントの関心を混在させない)。
 *
 * <p>exchangeそのものの宣言(durable等)は、このexchangeに関わる全サービス(プロデューサー・
 * コンシューマー問わず)がそれぞれ自分のRabbitMqConfigで宣言する({@link LogExchanges}と同じ方針、
 * RabbitMQ側は同一パラメータでの再宣言を許容する)。キュー・バインディング・DLQの宣言は
 * 各コンシューマーサービスが自分の購読するルーティングキーの分だけ行う(「誰が呼ぶか」ではなく
 * 「誰が読むか」が宣言責任を持つ、log-writerと同じ方針)。
 */
public final class EventExchanges {

    /** ドメインイベント用のtopic exchange。 */
    public static final String EVENTS_EXCHANGE = "letsblog.events";

    /** 配信失敗イベント(リトライ上限超過)を受け取るdead-letter exchange(topic)。 */
    public static final String EVENTS_DLX = "letsblog.events.dlx";

    /**
     * キュー名からDLQ名を作る接尾辞の規約。例: {@code post-published.queue} の
     * DLQは {@code post-published.queue.dlq}。DLQのルーティングキーもこのDLQ名をそのまま使う
     * (DLXへdead-letterされたメッセージのrouting keyは元のキュー名に書き換わるため、
     * DLQ側はキュー名と同じルーティングキーでバインドする)。
     */
    public static final String DLQ_SUFFIX = ".dlq";

    // --- routing keys（初版イベント一覧、issue #580） ---

    /** 投稿の公開状態を反映。発行元: publishing(現状はlegacy-apiが代行)。購読: content。 */
    public static final String POST_PUBLISHED_ROUTING_KEY = "post.published";

    /** 投稿削除に伴う参照整理。発行元: publishing(現状はlegacy-apiが代行)。購読: content, media。 */
    public static final String POST_DELETED_ROUTING_KEY = "post.deleted";

    /** 生成画像の利用可能化。発行元: media。購読: content。 */
    public static final String IMAGE_GENERATED_ROUTING_KEY = "image.generated";

    /** プロジェクト削除に伴う各サービスの設定・データ削除。発行元: project(issue #577で抽出済み)。購読: 全サービス。 */
    public static final String PROJECT_DELETED_ROUTING_KEY = "project.deleted";

    /** サイト削除に伴う参照整理。発行元: project(issue #577で抽出済み)。購読: publishing, content。 */
    public static final String SITE_DELETED_ROUTING_KEY = "site.deleted";

    /** ユーザー無効化に伴う権限キャッシュの破棄。発行元: identity。購読: 全サービス。 */
    public static final String USER_DEACTIVATED_ROUTING_KEY = "user.deactivated";

    private EventExchanges() {
    }
}
