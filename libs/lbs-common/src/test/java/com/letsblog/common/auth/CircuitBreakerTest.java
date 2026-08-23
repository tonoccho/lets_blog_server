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
    void クールダウン経過後は半開状態としてcheckAllowedが通る() throws InterruptedException {
        CircuitBreaker breaker = new CircuitBreaker(1, Duration.ofMillis(1));
        breaker.recordFailure();
        assertThrows(ServiceTokenUnavailableException.class, breaker::checkAllowed);

        Thread.sleep(10);

        assertDoesNotThrow(breaker::checkAllowed);
    }
}
