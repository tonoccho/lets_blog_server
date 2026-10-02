package com.letsblog.platform.dto;

import com.letsblog.platform.dto.ConnectedServiceStatusResponse.Status;

import java.time.Instant;

/**
 * legacy-apiから移設(issue #695、C10-3、元は issue #199)。接続サービスの稼働状況の詳細診断情報。
 * admin限定で返す。httpStatus/targetUrlはHTTPリクエストを伴わないチェック(データベース・
 * Brave Search)ではnullになる。
 */
public record ConnectedServiceStatusDetailResponse(
        String id,
        String name,
        Status status,
        long responseTimeMs,
        Integer httpStatus,
        String errorMessage,
        String targetUrl,
        Instant checkedAt,
        /**
         * この依存/サービスが落ちたときに使えなくなる機能の説明(issue #589)。
         * 対象外(外部依存など、影響を定義していないもの)は {@code null}。
         */
        String impact,
        /**
         * そのサービスが実際に使っている演算デバイス(issue #1397)。ComfyUIは{@code /system_stats}の
         * {@code devices[0].type}({@code cpu}/{@code cuda}等)、OLLAMAは{@code /api/ps}から解決した
         * {@code cpu}/{@code gpu}/{@code gpu+cpu}、モデル未ロードで判別できなければ{@code unknown}。
         * 取得できない・対象外のサービスは {@code null}。付加情報であり、状態判定の根拠にしない。
         * admin限定のこの詳細診断にのみ載せ、全ログインユーザー向けの
         * {@link ConnectedServiceStatusResponse}には含めない。
         */
        String computeDevice) {
}
