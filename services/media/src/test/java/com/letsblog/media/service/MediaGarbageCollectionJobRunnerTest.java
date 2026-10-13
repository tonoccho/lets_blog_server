package com.letsblog.media.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.client.CmsBridgeClient;
import com.letsblog.common.client.GenerationJobClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * MediaGarbageCollectionJobRunnerの回帰テスト。#573 stage3でlegacy-apiから移設したのに伴い、
 * Site/CmsAdapter/GenerationJobRepositoryへの直接アクセスがCmsBridgeClient/GenerationJobClient
 * 経由のHTTP呼び出しに置き換わったことを検証する(挙動自体は変えていない)。
 */
@ExtendWith(MockitoExtension.class)
class MediaGarbageCollectionJobRunnerTest {

    @Mock
    private CmsBridgeClient cmsBridgeClient;
    @Mock
    private GenerationJobClient generationJobClient;
    @Mock
    private AuditLogService auditLogService;

    private MediaGarbageCollectionJobRunner runner() {
        return new MediaGarbageCollectionJobRunner(cmsBridgeClient, generationJobClient, auditLogService, new ObjectMapper());
    }

    @Test
    void runDelete_全件成功で完了しAuditLogを記録する() {
        runner().runDelete(123L, 1L, "local", List.of("10", "20"), 9L, "keycloak-sub-1");

        verify(cmsBridgeClient).deleteMedia(1L, "local", "10");
        verify(cmsBridgeClient).deleteMedia(1L, "local", "20");

        ArgumentCaptor<String> resultPayloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(123L), eq("done"), resultPayloadCaptor.capture());
        assertTrue(resultPayloadCaptor.getValue().contains("\"deletedCount\":2"));
        assertTrue(resultPayloadCaptor.getValue().contains("\"failedCount\":0"));

        verify(auditLogService).log(eq(9L), eq("keycloak-sub-1"), eq(AuditLogService.ACTION_MEDIA_GARBAGE_COLLECTED),
                eq("PROJECT"), eq(1L), anyString(), eq(null), eq(null));
    }

    @Test
    void runDelete_対象が空でも完了として報告する() {
        // deleted/failuresが両方空になる分岐(mediaIds自体が空のケース)。ループが1回も
        // 回らないためreportProgressは呼ばれず、statusはdoneになる。
        runner().runDelete(123L, 1L, "local", List.of(), 9L, "keycloak-sub-1");

        ArgumentCaptor<String> resultPayloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(123L), eq("done"), resultPayloadCaptor.capture());
        assertTrue(resultPayloadCaptor.getValue().contains("\"deletedCount\":0"));
        assertTrue(resultPayloadCaptor.getValue().contains("\"failedCount\":0"));
    }

    @Test
    void runDelete_一部失敗しても完了しfailuresを記録する() {
        doThrow(new RuntimeException("削除エラー")).when(cmsBridgeClient).deleteMedia(1L, "local", "20");
        doNothing().when(cmsBridgeClient).deleteMedia(1L, "local", "10");

        runner().runDelete(123L, 1L, "local", List.of("10", "20"), 9L, "keycloak-sub-1");

        ArgumentCaptor<String> resultPayloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(123L), eq("done"), resultPayloadCaptor.capture());
        assertTrue(resultPayloadCaptor.getValue().contains("\"deletedCount\":1"));
        assertTrue(resultPayloadCaptor.getValue().contains("\"failedCount\":1"));
        verify(auditLogService).log(eq(9L), eq("keycloak-sub-1"), eq(AuditLogService.ACTION_MEDIA_GARBAGE_COLLECTED),
                eq("PROJECT"), eq(1L), anyString(), eq(null), eq(null));
    }

    @Test
    void runDelete_全件失敗はジョブをfailedにするがAuditLogは記録する() {
        // 各アイテムの削除はループ内でtry/catchされるため、全件失敗でも(0件成功でも)ループ自体は
        // 最後まで完走し、AuditLogは記録される(0件成功はGenerationJobのstatusを"failed"にする
        // 判定にのみ影響する)。
        doThrow(new RuntimeException("削除エラー")).when(cmsBridgeClient).deleteMedia(1L, "local", "10");

        runner().runDelete(123L, 1L, "local", List.of("10"), 9L, "keycloak-sub-1");

        ArgumentCaptor<String> resultPayloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(123L), eq("failed"), resultPayloadCaptor.capture());
        assertTrue(resultPayloadCaptor.getValue().contains("\"failedCount\":1"));
        verify(auditLogService).log(eq(9L), eq("keycloak-sub-1"), eq(AuditLogService.ACTION_MEDIA_GARBAGE_COLLECTED),
                eq("PROJECT"), eq(1L), anyString(), eq(null), eq(null));
    }

    @Test
    void runDelete_AuditLogService呼び出し自体が例外を投げてもジョブはfailedとして反映される() {
        // AuditLogService自体はAMQP例外を内部で握りつぶす設計(RabbitMQ未接続でもRuntimeExceptionは
        // 投げない)だが、万一予期しない実行時例外(NPE等)が発生した場合でも、ループ内の個別削除
        // 結果に関わらずジョブは"failed"として反映され、後続のGenerationJobClient呼び出し自体は
        // 到達しない(外側のtry/catchで捕捉されbestエフォートで失敗を反映する)ことを検証する。
        doThrow(new RuntimeException("予期しない失敗")).when(auditLogService)
                .log(any(), any(), any(), any(), any(), any(), any(), any());

        runner().runDelete(123L, 1L, "local", List.of("10"), 9L, "keycloak-sub-1");

        verify(generationJobClient).updateStatus(eq(123L), eq("failed"), anyString());
    }

    /** ジョブ1件の完了行(issue #1731)だけを集める。 */
    private static List<ILoggingEvent> completionLines(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(MediaGarbageCollectionJobRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("job completed:"))
                .collect(Collectors.toList());
    }

    @org.junit.jupiter.api.Test
    void 完了行_成功はINFOでkey_valueの1行を出す() throws Exception {

        List<ILoggingEvent> lines = completionLines(() -> { runner().runDelete(123L, 1L, "local", List.of("10"), 9L, "sub"); });

        org.junit.jupiter.api.Assertions.assertEquals(1, lines.size(), lines.toString());
        org.junit.jupiter.api.Assertions.assertEquals(Level.INFO, lines.get(0).getLevel());
        org.junit.jupiter.api.Assertions.assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=media_garbage_collection job_id=123 duration_ms=\\d+ outcome=success"),
                lines.get(0).getFormattedMessage());
    }

    @org.junit.jupiter.api.Test
    void 完了行_失敗はWARN以上で例外クラス名をoutcomeに出す() throws Exception {
        doThrow(new IllegalStateException("予期しない失敗")).when(auditLogService)
                .log(any(), any(), any(), any(), any(), any(), any(), any());
        List<ILoggingEvent> lines = completionLines(() -> { runner().runDelete(123L, 1L, "local", List.of("10"), 9L, "sub"); });

        org.junit.jupiter.api.Assertions.assertEquals(1, lines.size(), lines.toString());
        org.junit.jupiter.api.Assertions.assertTrue(lines.get(0).getLevel().isGreaterOrEqual(Level.WARN));
        org.junit.jupiter.api.Assertions.assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=media_garbage_collection job_id=123 duration_ms=\\d+ outcome=IllegalStateException"),
                lines.get(0).getFormattedMessage());
    }

    @org.junit.jupiter.api.Test
    void 完了行_全件削除に失敗したジョブはWARNでoutcomeにall_items_failedを出す() {
        doThrow(new RuntimeException("削除不可")).when(cmsBridgeClient).deleteMedia(any(), any(), any());

        List<ILoggingEvent> lines = completionLines(
                () -> runner().runDelete(123L, 1L, "local", List.of("10"), 9L, "sub"));

        org.junit.jupiter.api.Assertions.assertEquals(1, lines.size(), lines.toString());
        org.junit.jupiter.api.Assertions.assertTrue(lines.get(0).getLevel().isGreaterOrEqual(Level.WARN));
        org.junit.jupiter.api.Assertions.assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=media_garbage_collection job_id=123 duration_ms=\\d+ outcome=all_items_failed"),
                lines.get(0).getFormattedMessage());
    }
}
