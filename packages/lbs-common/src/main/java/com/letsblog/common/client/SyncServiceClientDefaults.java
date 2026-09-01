package com.letsblog.common.client;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link SyncServiceClient}の既定サーキットブレーカー・リトライ設定(issue #581)。個別の呼び出しで
 * 異なる方針が必要な場合は{@link SyncServiceClient.Builder#circuitBreakerConfig(CircuitBreakerConfig)}・
 * {@link SyncServiceClient.Builder#retryConfig(RetryConfig)}で上書きできるが、まずはこの既定値を使う
 * ことを推奨する(docs/SYNC_SERVICE_CALLS.md「新しい同期呼び出しを追加する手順」参照)。
 */
public final class SyncServiceClientDefaults {

    private SyncServiceClientDefaults() {
    }

    /**
     * サーキットブレーカー既定値。
     * <ul>
     *   <li>直近10回の呼び出し(カウントベース)のうち5回以上完了して初めて評価を始める
     *       (起動直後・低トラフィック時に1〜2回失敗しただけでOPENにしない)。</li>
     *   <li>失敗率50%以上でOPENへ遷移する。</li>
     *   <li>OPEN後30秒はHALF_OPENへ遷移せず即座に失敗させ、以降HALF_OPENで3回まで試行を許可する。</li>
     *   <li>「失敗」としてカウントするのは{@link SyncServiceServerErrorException}・
     *       {@link SyncServiceTimeoutException}・{@link SyncServiceUnavailableException}
     *       (5xx・タイムアウト・通信断、下流サービスの不調を示すもの)のみ。4xx
     *       ({@link SyncServiceClientErrorException}、呼び出し側の入力不備等)は下流が健全に
     *       応答している証拠なのでカウントしない。</li>
     * </ul>
     */
    public static CircuitBreakerConfig circuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordExceptions(
                        SyncServiceServerErrorException.class,
                        SyncServiceTimeoutException.class,
                        SyncServiceUnavailableException.class)
                .build();
    }

    /**
     * リトライ既定値。冪等なGETのみに適用する(POST/PATCH/DELETEは呼び出し側が明示的にretryable=falseで
     * 呼ぶため、この設定を使っても実際にはリトライされない。{@link SyncServiceClient}参照)。
     * <ul>
     *   <li>最大3回試行(初回+リトライ2回)。</li>
     *   <li>200ms始点・倍率2.0の指数バックオフ(200ms→400ms)。</li>
     *   <li>リトライ対象は{@link SyncServiceTimeoutException}・{@link SyncServiceUnavailableException}
     *       (接続断・タイムアウト、応答を受け取れなかった場合の翻訳後の型。{@link SyncServiceClient}が
     *       サーキットブレーカーへ渡すのと同じ翻訳後の例外をリトライ判定にも使う)のみ。
     *       {@link SyncServiceServerErrorException}/{@link SyncServiceClientErrorException}
     *       (下流が4xx/5xxを明示的に返した場合)は再試行しても結果が変わらない可能性が高く、
     *       下流に無駄な負荷もかけるため対象外とする(media-service/legacy-apiの既存
     *       MediaRenderClientの方針を踏襲)。</li>
     * </ul>
     */
    public static RetryConfig retryConfig() {
        return RetryConfig.custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(200), 2.0))
                .retryExceptions(SyncServiceTimeoutException.class, SyncServiceUnavailableException.class)
                .build();
    }
}
