package com.letsblog.platform.service;

import com.letsblog.platform.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    private FakeHttpServer http;
    private RabbitMqQueueStatusService service;

    @BeforeEach
    void setUp() {
        http = new FakeHttpServer();
        service = new RabbitMqQueueStatusService(http.baseUrl(), "guest", "secret");
    }

    @AfterEach
    void tearDown() {
        http.close();
    }

    /** Management APIの応答はHTTPのサーバー側フェイクで返し、判定は実物のサービスで行う(issue #1825)。 */
    private RabbitMqQueueStatusService.QueueStatus judge(String queuesJson) {
        http.json("GET", "/api/queues", queuesJson);
        return service.check();
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

    @Test
    @DisplayName("管理APIへBasic認証で問い合わせ、対象URLを返す")
    void 管理APIへBasic認証で問い合わせる() {
        var status = judge("[]");

        assertTrue(status.ok());
        assertEquals(http.baseUrl() + "/api/queues", status.targetUrl());
        assertEquals(http.baseUrl() + "/api/queues", service.targetUrl());
        // guest:secret のBase64
        assertEquals("Basic Z3Vlc3Q6c2VjcmV0", http.last().header("Authorization"));
    }

    @Test
    @DisplayName("DLQと滞留が両方あるときはDLQ(ERROR)を優先する")
    void dlqを優先する() {
        var status = judge("""
                [{"name":"a.queue","messages":500},{"name":"a.queue.dlq","messages":1}]
                """);

        assertFalse(status.ok());
        assertFalse(status.warning());
        assertTrue(status.message().contains("a.queue.dlq=1"));
    }

    @Test
    @DisplayName("管理APIがエラーを返せば、滞留を判定できないWARNINGにする")
    void 管理APIのエラーは判定不能の警告() {
        http.status("GET", "/api/queues", 500, "{}");

        var status = service.check();

        assertFalse(status.ok());
        assertTrue(status.warning());
        assertTrue(status.message().contains("到達できず"), status.message());
    }

    @Test
    @DisplayName("管理APIへ接続できなければ、滞留を判定できないWARNINGにする")
    void 接続できなければ警告() {
        var unreachable = new RabbitMqQueueStatusService("http://127.0.0.1:1", "guest", "guest");

        var status = unreachable.check();

        assertTrue(status.warning());
        assertTrue(status.message().contains("到達できず"), status.message());
    }

    @Test
    @DisplayName("応答が配列でなければ、解釈できないWARNINGにする")
    void 配列でない応答は警告() {
        var status = judge("{\"error\":\"x\"}");

        assertFalse(status.ok());
        assertTrue(status.warning());
        assertEquals("RabbitMQ Management APIの応答を解釈できません", status.message());
    }

    @Test
    @DisplayName("応答の本文が空でも、解釈できないWARNINGにする")
    void 空の応答は警告() {
        http.on("GET", "/api/queues", FakeHttpServer.Response.empty(200));

        var status = service.check();

        assertTrue(status.warning());
        assertEquals("RabbitMQ Management APIの応答を解釈できません", status.message());
    }
}
