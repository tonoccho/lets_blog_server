package com.letsblog.platform.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RabbitMQ のキュー滞留・DLQ 滞留の判定(issue #589)。
 *
 * <p>#580 で各コンシューマーが {@code <queue>.dlq} を宣言していたが、溜まっても誰も
 * 気付かない状態だった。DLQ に残っている = イベントが処理されずに落ちている、なので
 * 警告ではなくエラーとして扱うことをここで固定する。
 */
@DisplayName("platform-service: RabbitMQのキュー滞留・DLQ滞留の判定(issue #589)")
class RabbitMqQueueStatusServiceTest {

    /** Management API の応答を差し替えられるよう、判定部分だけを取り出して検証する。 */
    private RabbitMqQueueStatusService.QueueStatus judge(String queuesJson) {
        // 実際のHTTP往復は MockRestServiceServer を使う統合的な検証の対象で、
        // ここでは「どの滞留をどう扱うか」だけを見る。
        return new StubbedService(queuesJson).check();
    }

    @Test
    @DisplayName("DLQに1件でもあればERROR(警告ではない)")
    void dlqに残っていればエラー() {
        var status = judge("""
                [{"name":"content.post-published.queue","messages":0},
                 {"name":"content.post-published.queue.dlq","messages":3}]
                """);

        assertFalse(status.ok());
        assertFalse(status.warning(), "DLQの残留は警告ではなくエラーとして扱う");
        assertTrue(status.message().contains("content.post-published.queue.dlq=3"));
    }

    @Test
    @DisplayName("通常キューが閾値(100件)以上ならWARNING")
    void 通常キューの滞留は警告() {
        var status = judge("""
                [{"name":"content.post-published.queue","messages":150}]
                """);

        assertFalse(status.ok());
        assertTrue(status.warning());
        assertTrue(status.message().contains("content.post-published.queue=150"));
    }

    @Test
    @DisplayName("通常キューが閾値未満なら正常(少量の滞留は通常運転)")
    void 少量の滞留は正常() {
        var status = judge("""
                [{"name":"content.post-published.queue","messages":5},
                 {"name":"content.post-published.queue.dlq","messages":0}]
                """);

        assertTrue(status.ok());
        assertEquals(null, status.message());
    }

    @Test
    @DisplayName("全キューが空なら正常")
    void 空なら正常() {
        var status = judge("""
                [{"name":"audit-logs.queue","messages":0}]
                """);

        assertTrue(status.ok());
    }

    /** {@code check()} の判定だけを検証するため、HTTP取得部分を固定値へ差し替えたサブクラス。 */
    private static final class StubbedService extends RabbitMqQueueStatusService {

        private final String queuesJson;

        StubbedService(String queuesJson) {
            super("http://rabbitmq:15672", "guest", "guest");
            this.queuesJson = queuesJson;
        }

        @Override
        protected com.fasterxml.jackson.databind.JsonNode fetchQueues() {
            try {
                return new com.fasterxml.jackson.databind.ObjectMapper().readTree(queuesJson);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
