package com.letsblog.media.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.common.messaging.AuditLogMessage;
import com.letsblog.media.testsupport.BrokenObjectMapper;
import com.letsblog.media.testsupport.FakeHttpServer;
import com.letsblog.media.testsupport.MediaWorld;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

/**
 * MediaGarbageCollectionJobRunnerの回帰テスト。#573 stage3でlegacy-apiから移設したのに伴い、
 * Site/CmsAdapter/GenerationJobRepositoryへの直接アクセスがCmsBridgeClient/GenerationJobClient
 * 経由のHTTP呼び出しに置き換わったことを検証する(挙動自体は変えていない)。
 *
 * <p>issue #1249: 削除ループは、ジョブ起動時のユーザーBearerトークンではなくmedia-service自身の
 * Client Credentialsトークンでpublishing-serviceを呼ぶ。
 *
 * <p>issue #1819: {@code CmsBridgeClient}・{@code GenerationJobClient}・{@code AuditLogService}・
 * {@code ServiceTokenClient}は実物。publishing-service・ai-service・トークンエンドポイントをフェイクHTTPサーバー、
 * 監査ログのMQ送信を{@code RabbitTemplate}のmockが演じる。
 */
class MediaGarbageCollectionJobRunnerTest {

    private static final String DELETE_PATH = "/api/internal/publishing/projects/1/media/\\d+";

    private final MediaWorld world = new MediaWorld();
    private final MediaGarbageCollectionJobRunner runner = world.gcRunner;

    @AfterEach
    void close() {
        world.close();
    }

    private AuditLogMessage audit() {
        List<Object> messages = world.publishedMessages();
        assertEquals(1, messages.size(), "監査ログは1件");
        return (AuditLogMessage) messages.get(0);
    }

    private void failDeleteOf(String mediaId, int status) {
        world.http.on("DELETE", "/api/internal/publishing/projects/1/media/" + mediaId,
                FakeHttpServer.Response.json(status, "{\"error\":\"" + status + "\"}"));
    }

    @Test
    void runDelete_全件成功で完了しAuditLogを記録する() {
        runner.runDelete(123L, 1L, "local", List.of("10", "20"), 9L, "keycloak-sub-1");

        assertEquals(List.of("/api/internal/publishing/projects/1/media/10", "/api/internal/publishing/projects/1/media/20"),
                world.http.requests("DELETE", DELETE_PATH).stream().map(FakeHttpServer.Request::path).toList());

        JsonNode done = world.jobUpdates("done").get(0).payload();
        assertEquals(123L, world.jobUpdates("done").get(0).jobId());
        assertEquals(2, done.get("deletedCount").asInt());
        assertEquals(0, done.get("failedCount").asInt());

        AuditLogMessage audit = audit();
        assertEquals(9L, audit.userId());
        assertEquals("keycloak-sub-1", audit.actorKeycloakSub());
        assertEquals(AuditLogService.ACTION_MEDIA_GARBAGE_COLLECTED, audit.action());
        assertEquals("PROJECT", audit.resourceType());
        assertEquals(1L, audit.resourceId());
        assertNull(audit.remoteIp());
        assertNull(audit.userAgent());
        assertThat(audit.changes()).contains("\"deletedMediaIds\":[\"10\",\"20\"]");
    }

    @Test
    void runDelete_削除リクエストのAuthorizationはServiceTokenClientのトークンである() {
        runner.runDelete(123L, 1L, "local", List.of("10", "20"), 9L, "keycloak-sub-1");

        assertThat(world.http.requests("DELETE", DELETE_PATH))
                .allSatisfy(r -> assertEquals("Bearer " + MediaWorld.SERVICE_TOKEN, r.header("Authorization")));
    }

    @Test
    void runDelete_対象が空でも完了として報告する() {
        // deleted/failuresが両方空になる分岐(mediaIds自体が空のケース)。ループが1回も
        // 回らないためreportProgressは呼ばれず、statusはdoneになる。
        runner.runDelete(123L, 1L, "local", List.of(), 9L, "keycloak-sub-1");

        assertTrue(world.jobUpdates("running").isEmpty());
        JsonNode done = world.jobUpdates("done").get(0).payload();
        assertEquals(0, done.get("deletedCount").asInt());
        assertEquals(0, done.get("failedCount").asInt());
    }

    @Test
    void runDelete_最初の削除の直後に進捗をrunningで報告する() {
        runner.runDelete(123L, 1L, "local", List.of("10", "20"), 9L, "keycloak-sub-1");

        JsonNode progress = world.jobUpdates("running").get(0).payload();
        assertEquals("deleting", progress.get("phase").asText());
        assertEquals(50, progress.get("percent").asInt());
        assertEquals(1, progress.get("bytesDone").asInt());
        assertEquals(2, progress.get("bytesTotal").asInt());
    }

    @Test
    void runDelete_一部失敗しても完了しfailuresを記録する() {
        failDeleteOf("20", 404);

        runner.runDelete(123L, 1L, "local", List.of("10", "20"), 9L, "keycloak-sub-1");

        JsonNode done = world.jobUpdates("done").get(0).payload();
        assertEquals(1, done.get("deletedCount").asInt());
        assertEquals(1, done.get("failedCount").asInt());
        assertThat(done.get("failures").get("20").asText()).contains("404");
        assertEquals(AuditLogService.ACTION_MEDIA_GARBAGE_COLLECTED, audit().action());
    }

    @Test
    void runDelete_一部のメディアが401でも残りの削除を続け_ステータス付きで記録し_終端状態を通知する() {
        failDeleteOf("20", 401);
        ListAppender<ILoggingEvent> logs = attachLogs();
        try {
            runner.runDelete(123L, 1L, "local", List.of("10", "20", "30"), 9L, "keycloak-sub-1");
        } finally {
            detach(logs);
        }

        assertEquals(3, world.http.requests("DELETE", DELETE_PATH).size(), "401の後も残りを続ける");
        JsonNode done = world.jobUpdates("done").get(0).payload();
        assertEquals(2, done.get("deletedCount").asInt());
        assertEquals(1, done.get("failedCount").asInt());
        assertThat(done.get("failures").get("20").asText()).contains("401");
        assertThat(audit().changes()).contains("\"20\":").contains("401");

        List<ILoggingEvent> warns = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage()).contains("123").contains("20").contains("401");
    }

    @Test
    void runDelete_全件失敗はジョブをfailedにするがAuditLogは記録する() {
        failDeleteOf("10", 404);

        runner.runDelete(123L, 1L, "local", List.of("10"), 9L, "keycloak-sub-1");

        JsonNode failed = world.jobUpdates("failed").get(0).payload();
        assertEquals(1, failed.get("failedCount").asInt());
        assertEquals(AuditLogService.ACTION_MEDIA_GARBAGE_COLLECTED, audit().action());
    }

    @Test
    void runDelete_全件が401ならfailedで終端通知する() {
        failDeleteOf("10", 401);
        failDeleteOf("20", 401);

        runner.runDelete(123L, 1L, "local", List.of("10", "20"), 9L, "keycloak-sub-1");

        assertEquals(2, world.http.requests("DELETE", DELETE_PATH).size());
        assertEquals(1, world.jobUpdates("failed").size());
        assertTrue(world.jobUpdates("done").isEmpty());
    }

    @Test
    void runDelete_監査ログの送信が予期しない例外を投げてもジョブはfailedとして反映される() {
        // AuditLogService自体はAMQP例外を内部で握りつぶす設計(RabbitMQ未接続でもRuntimeExceptionは
        // 投げない)だが、万一予期しない実行時例外が発生した場合でも、ジョブは"failed"として反映される
        // (外側のtry/catchで捕捉されbestエフォートで失敗を反映する)ことを検証する。
        doThrow(new IllegalStateException("予期しない失敗")).when(world.rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class));

        runner.runDelete(123L, 1L, "local", List.of("10"), 9L, "keycloak-sub-1");

        JsonNode failed = world.jobUpdates("failed").get(0).payload();
        assertEquals("予期しない失敗", failed.get("error").asText());
        assertTrue(world.jobUpdates("done").isEmpty());
    }

    @Test
    void runDelete_結果のJSON化に失敗しても空のJSONで終端通知する() {
        MediaGarbageCollectionJobRunner broken = new MediaGarbageCollectionJobRunner(
                world.cmsBridgeClient, world.generationJobClient, world.auditLogService, BrokenObjectMapper.create());

        broken.runDelete(123L, 1L, "local", List.of(), 9L, "keycloak-sub-1");

        var update = world.http.requests("PATCH", "/api/internal/ai/generation-jobs/123").get(0);
        assertEquals("{}", world.bodyOf(update).get("resultPayload").asText());
    }

    private static ListAppender<ILoggingEvent> attachLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(MediaGarbageCollectionJobRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detach(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(MediaGarbageCollectionJobRunner.class)).detachAppender(appender);
    }

    /** ジョブ1件の完了行(issue #1731)だけを集める。 */
    private static List<ILoggingEvent> completionLines(Runnable action) {
        ListAppender<ILoggingEvent> appender = attachLogs();
        try {
            action.run();
        } finally {
            detach(appender);
        }
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("job completed:"))
                .collect(Collectors.toList());
    }

    @Test
    void 完了行_成功はINFOでkey_valueの1行を出す() {
        List<ILoggingEvent> lines = completionLines(() -> runner.runDelete(123L, 1L, "local", List.of("10"), 9L, "sub"));

        assertEquals(1, lines.size(), lines.toString());
        assertEquals(Level.INFO, lines.get(0).getLevel());
        assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=media_garbage_collection job_id=123 duration_ms=\\d+ outcome=success"),
                lines.get(0).getFormattedMessage());
    }

    @Test
    void 完了行_失敗はWARN以上で例外クラス名をoutcomeに出す() {
        doThrow(new IllegalStateException("予期しない失敗")).when(world.rabbitTemplate)
                .convertAndSend(anyString(), anyString(), any(Object.class));
        List<ILoggingEvent> lines = completionLines(() -> runner.runDelete(123L, 1L, "local", List.of("10"), 9L, "sub"));

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).getLevel().isGreaterOrEqual(Level.WARN));
        assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=media_garbage_collection job_id=123 duration_ms=\\d+ outcome=IllegalStateException"),
                lines.get(0).getFormattedMessage());
    }

    @Test
    void 完了行_全件削除に失敗したジョブはWARNでoutcomeにall_items_failedを出す() {
        failDeleteOf("10", 404);

        List<ILoggingEvent> lines = completionLines(() -> runner.runDelete(123L, 1L, "local", List.of("10"), 9L, "sub"));

        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).getLevel().isGreaterOrEqual(Level.WARN));
        assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=media_garbage_collection job_id=123 duration_ms=\\d+ outcome=all_items_failed"),
                lines.get(0).getFormattedMessage());
    }
}
