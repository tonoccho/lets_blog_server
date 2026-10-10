package com.letsblog.project.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** letsblog同期の実行スレッドのログが、投入元(保存のリクエスト)の処理IDを持つ(issue #1732)。 */
class LetsblogSyncConfigTest {

    private ExecutorService executor;

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void 同期の実行スレッドは投入時の処理IDを引き継ぎ_終了後は持ち越さない() throws Exception {
        executor = new LetsblogSyncConfig().letsblogSyncExecutor();
        MDC.put("correlationId", "cid-sync");

        String seen = executor.submit(() -> MDC.get("correlationId")).get(5, TimeUnit.SECONDS);
        MDC.clear();
        String next = executor.submit(() -> MDC.get("correlationId")).get(5, TimeUnit.SECONDS);

        assertEquals("cid-sync", seen);
        assertNull(next);
    }
}
