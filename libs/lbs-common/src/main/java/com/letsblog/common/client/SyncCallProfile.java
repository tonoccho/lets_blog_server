package com.letsblog.common.client;

import java.time.Duration;

/**
 * サービス間同期呼び出し(issue #581、C12)のタイムアウト既定値。用途別に4段階を用意し、
 * 呼び出し元は自分の呼び出しの性質に最も近いものを選ぶ(必要なら{@link SyncServiceClient.Builder}
 * で個別に上書きできる)。
 *
 * <p>接続タイムアウト(connectTimeout)は全プロファイル共通で短め(3秒)に固定している。
 * 相手サービスが起動していない/ネットワーク到達不可という状況は、処理内容によらず
 * 早期に検知すべきだからである。読み取りタイムアウト(readTimeout)のみ用途に応じて変える。
 *
 * <p>docs/SYNC_SERVICE_CALLS.mdに各呼び出し先ごとの採用プロファイルと根拠を記載している。
 */
public enum SyncCallProfile {

    /**
     * 単純なデータ参照(存在確認、1レコードの取得等)。identity-serviceの{@code /api/identity/me}や
     * legacy-apiの内部ブリッジのプロジェクトメンバー判定など、相手側もDB1回程度の参照で
     * 応答できる呼び出しに使う。
     */
    SHORT(Duration.ofSeconds(3), Duration.ofSeconds(5)),

    /**
     * SHORTより複雑だがDB参照の範囲に収まる呼び出し(一覧取得、複数フィールドの解決等)。
     * 明示的な理由がない同期呼び出しの既定値。
     */
    STANDARD(Duration.ofSeconds(3), Duration.ofSeconds(10)),

    /**
     * 外部プロセス(PlantUMLサーバー、ヘッドレスChromiumによるRecharts/Penpotレンダリング等)に
     * 依存し数秒〜十数秒かかりうる呼び出し。content/legacy-api → media-serviceのレンダリング系が該当。
     */
    RENDER(Duration.ofSeconds(3), Duration.ofSeconds(30)),

    /**
     * LLMプロバイダーへの実際の問い合わせを伴う呼び出し。ai-serviceの
     * {@code LLM_REQUEST_TIMEOUT_SECONDS}(既定120秒)を上回る必要があるため、
     * gatewayのタイムアウト延長と同じ理由で長め(180秒)にする。
     */
    LLM(Duration.ofSeconds(3), Duration.ofSeconds(180));

    private final Duration connectTimeout;
    private final Duration readTimeout;

    SyncCallProfile(Duration connectTimeout, Duration readTimeout) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration readTimeout() {
        return readTimeout;
    }
}
