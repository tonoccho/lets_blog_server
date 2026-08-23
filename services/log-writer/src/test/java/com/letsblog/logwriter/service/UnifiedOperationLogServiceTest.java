package com.letsblog.logwriter.service;

import com.letsblog.logwriter.client.GenerationJobClient;
import com.letsblog.logwriter.client.GenerationJobSummary;
import com.letsblog.logwriter.domain.AuditLog;
import com.letsblog.logwriter.domain.OperationLog;
import com.letsblog.logwriter.dto.UnifiedLogEntryResponse;
import com.letsblog.logwriter.repository.AuditLogRepository;
import com.letsblog.logwriter.repository.OperationLogRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UnifiedOperationLogServiceの回帰テスト(issue #187、#572でlog-writerへ移設)。3ソースのマージ・
 * ソート・監査ログのadmin限定・種別フィルタ・キーワード検索・ページングを検証する。
 * legacy-api側のUnifiedOperationLogServiceTestを移設し、AI_JOBソースの取得方法のみ
 * GenerationJobRepository直接参照からGenerationJobClient(HTTP)経由に置き換えている。
 */
@ExtendWith(MockitoExtension.class)
class UnifiedOperationLogServiceTest {

    @Mock
    private OperationLogRepository operationLogRepository;
    @Mock
    private GenerationJobClient generationJobClient;
    @Mock
    private AuditLogRepository auditLogRepository;

    private UnifiedOperationLogService service() {
        return new UnifiedOperationLogService(operationLogRepository, generationJobClient, auditLogRepository);
    }

    private OperationLog operationLog(long id, LocalDateTime createdAt) {
        OperationLog log = new OperationLog();
        log.setId(id);
        log.setOperationId("op-" + id);
        log.setUserId(10L);
        log.setActorKeycloakSub("keycloak-sub-op");
        log.setMethod("GET");
        log.setPath("/api/sites");
        log.setDurationMs(5L);
        log.setSuccess(true);
        log.setCreatedAt(createdAt);
        return log;
    }

    private GenerationJobSummary generationJob(long id, LocalDateTime createdAt) {
        return new GenerationJobSummary(id, "draft", "done", createdAt);
    }

    private AuditLog auditLog(long id, LocalDateTime createdAt) {
        AuditLog log = new AuditLog();
        log.setId(id);
        log.setUserId(99L);
        log.setActorKeycloakSub("keycloak-sub-audit");
        log.setAction("LOGIN");
        log.setCreatedAt(createdAt);
        return log;
    }

    private void stubEmptySources() {
        lenient().when(operationLogRepository.findByUserIdOrderByCreatedAtDesc(eq(10L), any()))
                .thenReturn(Page.empty());
        lenient().when(generationJobClient.listRecent("Bearer test-token")).thenReturn(List.of());
        lenient().when(auditLogRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(Page.empty());
    }

    @Test
    void list_3ソースを作成日時降順でマージする() {
        stubEmptySources();
        LocalDateTime now = LocalDateTime.now();
        when(operationLogRepository.findByUserIdOrderByCreatedAtDesc(eq(10L), any()))
                .thenReturn(new PageImpl<>(List.of(operationLog(1L, now.minusMinutes(2)))));
        when(generationJobClient.listRecent("Bearer test-token")).thenReturn(List.of(generationJob(2L, now)));
        when(auditLogRepository.findAllByOrderByCreatedAtDesc(any()))
                .thenReturn(new PageImpl<>(List.of(auditLog(3L, now.minusMinutes(1)))));

        Page<UnifiedLogEntryResponse> result =
                service().list(10L, true, null, null, PageRequest.of(0, 20), "Bearer test-token");

        assertEquals(3, result.getTotalElements());
        assertEquals("AI_JOB", result.getContent().get(0).sourceType());
        assertEquals("AUDIT", result.getContent().get(1).sourceType());
        assertEquals("keycloak-sub-audit", result.getContent().get(1).actorKeycloakSub());
        assertEquals("OPERATION", result.getContent().get(2).sourceType());
        assertEquals("keycloak-sub-op", result.getContent().get(2).actorKeycloakSub());
    }

    @Test
    void list_admin以外は監査ログを取得しない() {
        stubEmptySources();

        service().list(10L, false, null, null, PageRequest.of(0, 20), "Bearer test-token");

        verify(auditLogRepository, never()).findAllByOrderByCreatedAtDesc(any());
    }

    @Test
    void list_typeフィルタで対象ソースのみ取得する() {
        stubEmptySources();

        service().list(10L, true, "AI_JOB", null, PageRequest.of(0, 20), "Bearer test-token");

        verify(operationLogRepository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
        verify(auditLogRepository, never()).findAllByOrderByCreatedAtDesc(any());
        verify(generationJobClient).listRecent("Bearer test-token");
    }

    @Test
    void list_キーワード検索でtitleに一致しないものを除外する() {
        stubEmptySources();
        LocalDateTime now = LocalDateTime.now();
        when(generationJobClient.listRecent("Bearer test-token")).thenReturn(List.of(generationJob(1L, now)));

        Page<UnifiedLogEntryResponse> matched =
                service().list(10L, true, null, "draft", PageRequest.of(0, 20), "Bearer test-token");
        Page<UnifiedLogEntryResponse> unmatched =
                service().list(10L, true, null, "nonexistent", PageRequest.of(0, 20), "Bearer test-token");

        assertEquals(1, matched.getTotalElements());
        assertTrue(unmatched.getContent().isEmpty());
    }

    @Test
    void list_ページングする() {
        stubEmptySources();
        LocalDateTime now = LocalDateTime.now();
        List<GenerationJobSummary> jobs = List.of(
                generationJob(1L, now.minusMinutes(3)),
                generationJob(2L, now.minusMinutes(2)),
                generationJob(3L, now.minusMinutes(1)));
        when(generationJobClient.listRecent("Bearer test-token")).thenReturn(jobs);

        Pageable pageable = PageRequest.of(1, 2);
        Page<UnifiedLogEntryResponse> result = service().list(10L, true, null, null, pageable, "Bearer test-token");

        assertEquals(3, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        assertEquals(1L, result.getContent().get(0).id());
    }
}
