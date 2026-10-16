package com.letsblog.platform.service;

import com.letsblog.common.net.ConnectionDestinationGuard;
import com.letsblog.common.net.GuardedTarget;
import java.time.Duration;
import java.util.function.Function;
import org.springframework.web.client.RestClient;

/**
 * {@link PenpotReachabilityChecker}の(パッケージ内の)テスト用コンストラクタを、testsupportから使うための入口(issue #1825)。
 * チェッカーは実物で、差し替えるのは「検査した接続先へ固定した通信」のHTTPクライアントだけ。
 */
public final class PenpotCheckerFactory {

    private PenpotCheckerFactory() {
    }

    public static PenpotReachabilityChecker create(
            ConnectionDestinationGuard guard, Function<GuardedTarget, RestClient> clientFactory) {
        return new PenpotReachabilityChecker(guard, Duration.ofSeconds(3), clientFactory);
    }
}
