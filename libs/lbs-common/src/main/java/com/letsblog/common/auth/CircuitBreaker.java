package com.letsblog.common.auth;

import java.time.Duration;
import java.time.Instant;

/**
 * 連続失敗時に一定時間トークン取得を即座に失敗させる、{@link ServiceTokenClient}専用の
 * 簡易サーキットブレーカー(#567の受入基準: トークン取得失敗時にサーキットブレーカーが働き、
 * 呼び出し元が無限に待たない)。
 *
 * <p>CLOSED(通常)状態で{@code failureThreshold}回連続失敗するとOPEN状態に遷移し、
 * {@code openDuration}が経過するまでは{@link #checkAllowed()}が実際のHTTP呼び出しを行わずに
 * 即座に{@link ServiceTokenUnavailableException}を送出する(トークンエンドポイントが
 * 到達不可・過負荷の状況で、呼び出しのたびに接続/読み取りタイムアウトいっぱいまで
 * 待たされることを防ぐ)。openDuration経過後は半開状態として1回だけ試行を許可し、
 * その結果(recordSuccess/recordFailure)で再度CLOSED/OPENへ遷移する。
 *
 * <p>スレッドセーフではない。{@link ServiceTokenClient#getAccessToken()}が
 * {@code synchronized}で呼び出し全体を保護する前提で使う。
 */
final class CircuitBreaker {

    private static final int DEFAULT_FAILURE_THRESHOLD = 3;
    private static final Duration DEFAULT_OPEN_DURATION = Duration.ofSeconds(30);

    private final int failureThreshold;
    private final Duration openDuration;

    private int consecutiveFailures;
    private Instant openedAt;

    static CircuitBreaker withDefaults() {
        return new CircuitBreaker(DEFAULT_FAILURE_THRESHOLD, DEFAULT_OPEN_DURATION);
    }

    CircuitBreaker(int failureThreshold, Duration openDuration) {
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    /** OPEN状態でクールダウン未経過なら例外を送出する。呼び出し可能ならそのまま戻る。 */
    void checkAllowed() {
        if (openedAt == null) {
            return;
        }
        if (Instant.now().isBefore(openedAt.plus(openDuration))) {
            throw new ServiceTokenUnavailableException(
                    "サービストークンエンドポイントへの接続が連続して失敗したため、サーキットブレーカーが"
                            + "作動中です。しばらく待ってから再試行してください。");
        }
        // クールダウン経過。半開状態として1回だけ試行を許可する。
        openedAt = null;
    }

    void recordSuccess() {
        consecutiveFailures = 0;
        openedAt = null;
    }

    void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            openedAt = Instant.now();
        }
    }
}
