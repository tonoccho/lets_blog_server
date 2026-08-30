package com.letsblog.common.auth;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** CircuitBreakerの単体テスト(#567)。 */
class CircuitBreakerTest {

    @Test
    void 初期状態はCLOSEDで呼び出しを許可する() {
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(30));
        assertDoesNotThrow(breaker::checkAllowed);
    }

    @Test
    void しきい値未満の失敗ではOPENにならない() {
        CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofSeconds(30));
        breaker.recordFailure();
        breaker.recordFailure();
        assertDoesNotThrow(breaker::checkAllowed);
    }

    @Test
    void しきい値到達でOPENになりcheckAllowedが例外を送出する() {
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(30));
        breaker.recordFailure();
        breaker.recordFailure();
        assertThrows(ServiceTokenUnavailableException.class, breaker::checkAllowed);
    }

    @Test
    void 成功で連続失敗カウントとOPEN状態がリセットされる() {
        CircuitBreaker breaker = new CircuitBreaker(2, Duration.ofSeconds(30));
        breaker.recordFailure();
        breaker.recordFailure();
        assertThrows(ServiceTokenUnavailableException.class, breaker::checkAllowed);

        breaker.recordSuccess();

        assertDoesNotThrow(breaker::checkAllowed);
    }

    @Test
    void クールダウン経過後は半開状態としてcheckAllowedが通る() {
        // 実時間ではなく時刻源を進めて検証する。以前はクールダウンを1msに縮めてThread.sleep()で
        // 待っていたが、assertThrows()に渡すメソッド参照の初回生成だけで約1msかかるため、
        // 「まだクールダウン中」を確かめる最初のassertThrowsが実行環境によっては通り抜けていた。
        MutableClock clock = MutableClock.atEpoch();
        Duration cooldown = Duration.ofSeconds(30);
        CircuitBreaker breaker = new CircuitBreaker(1, cooldown, clock);
        breaker.recordFailure();
        assertThrows(ServiceTokenUnavailableException.class, breaker::checkAllowed);

        // 満了直前はまだOPENのまま
        clock.advance(cooldown.minusMillis(1));
        assertThrows(ServiceTokenUnavailableException.class, breaker::checkAllowed);

        // 満了時点で半開状態として1回だけ通す
        clock.advance(Duration.ofMillis(1));
        assertDoesNotThrow(breaker::checkAllowed);
    }
}
