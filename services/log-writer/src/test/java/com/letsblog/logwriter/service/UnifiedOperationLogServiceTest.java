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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                service().list(10L, true, null, null, null, null, PageRequest.of(0, 20), "Bearer test-token");

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

        service().list(10L, false, null, null, null, null, PageRequest.of(0, 20), "Bearer test-token");

        verify(auditLogRepository, never()).findAllByOrderByCreatedAtDesc(any());
    }

    @Test
    void list_typeフィルタで対象ソースのみ取得する() {
        stubEmptySources();

        service().list(10L, true, "AI_JOB", null, null, null, PageRequest.of(0, 20), "Bearer test-token");

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
                service().list(10L, true, null, "draft", null, null, PageRequest.of(0, 20), "Bearer test-token");
        Page<UnifiedLogEntryResponse> unmatched =
                service().list(10L, true, null, "nonexistent", null, null, PageRequest.of(0, 20), "Bearer test-token");

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
        Page<UnifiedLogEntryResponse> result = service().list(10L, true, null, null, null, null, pageable, "Bearer test-token");

        assertEquals(3, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        assertEquals(1L, result.getContent().get(0).id());
    }

    // ---------------------------------------------- AIジョブ取得失敗時の縮退(issue #825)

    /**
     * AIジョブはai-serviceへの同期HTTP呼び出しで取得する唯一の外部依存で、
     * 操作ログ・監査ログは同じメソッド内で先にDBから取得済みである。
     *
     * <p>#825以前は例外がそのまま突き抜けて統合ログAPI全体が502になり、
     * 取得済みのOPERATION/AUDITまで巻き添えで失われていた。しかも本番では
     * GenerationJobClientが移設済みのエンドポイントを呼び続けていたため、
     * この失敗が常時発生し /operation-logs 画面は常に空だった。
     */
    @Test
    void list_AIジョブの取得に失敗しても操作ログと監査ログは返す() {
        stubEmptySources();
        LocalDateTime now = LocalDateTime.now();
        when(operationLogRepository.findByUserIdOrderByCreatedAtDesc(eq(10L), any()))
                .thenReturn(new PageImpl<>(List.of(operationLog(1L, now.minusMinutes(2)))));
        when(auditLogRepository.findAllByOrderByCreatedAtDesc(any()))
                .thenReturn(new PageImpl<>(List.of(auditLog(3L, now.minusMinutes(1)))));
        when(generationJobClient.listRecent("Bearer test-token"))
                .thenThrow(new GenerationJobUnavailableException("ai-service down", new RuntimeException()));

        Page<UnifiedLogEntryResponse> result =
                service().list(10L, true, null, null, null, null, PageRequest.of(0, 20), "Bearer test-token");

        assertEquals(2, result.getTotalElements());
        assertEquals("AUDIT", result.getContent().get(0).sourceType());
        assertEquals("OPERATION", result.getContent().get(1).sourceType());
    }

    @Test
    void list_AIジョブのみを要求して失敗した場合は空を返す() {
        stubEmptySources();
        when(generationJobClient.listRecent("Bearer test-token"))
                .thenThrow(new GenerationJobUnavailableException("ai-service down", new RuntimeException()));

        Page<UnifiedLogEntryResponse> result =
                service().list(10L, true, "AI_JOB", null, null, null, PageRequest.of(0, 20), "Bearer test-token");

        assertEquals(0, result.getTotalElements());
    }

    /**
     * identity-serviceの障害は握り潰さない。操作者を解決できないまま統合ログを返すと
     * 「他人のログが見えているのか自分のログなのか」が保証できなくなるため、502のままにする。
     * 型で区別している理由は{@code GenerationJobUnavailableException}のJavadoc参照。
     */
    @Test
    void list_identity障害を表す例外は握り潰さない() {
        stubEmptySources();
        when(generationJobClient.listRecent("Bearer test-token"))
                .thenThrow(new IdentityServiceUnavailableException("identity down", new RuntimeException()));

        assertThrows(IdentityServiceUnavailableException.class,
                () -> service().list(10L, true, null, null, null, null, PageRequest.of(0, 20), "Bearer test-token"));
    }

    // ---------------------------------------------- 日時の範囲による絞り込み(issue #1138)

    private static final LocalDateTime RANGE_START = LocalDateTime.of(2026, 1, 10, 0, 0);
    private static final LocalDateTime RANGE_END = LocalDateTime.of(2026, 1, 11, 0, 0);
    private static final LocalDateTime MIN_BOUND = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime MAX_BOUND = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

    @Test
    void list_日時の範囲を各ソースの取得段階で絞る() {
        stubEmptySources();
        LocalDateTime inside = RANGE_START.plusHours(1);
        // 範囲指定のクエリは直近200件の窓の外(古い側)も引けるので、範囲内の行だけが返る。
        when(operationLogRepository.findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                eq(10L), eq(RANGE_START), eq(RANGE_END), any()))
                .thenReturn(new PageImpl<>(List.of(operationLog(1L, inside))));
        when(auditLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(eq(RANGE_START), eq(RANGE_END), any()))
                .thenReturn(new PageImpl<>(List.of(auditLog(3L, inside.plusMinutes(1)))));
        when(generationJobClient.listRecent("Bearer test-token")).thenReturn(List.of(
                generationJob(2L, inside.plusMinutes(2)),
                generationJob(4L, RANGE_START.minusSeconds(1)),
                generationJob(5L, RANGE_END.plusSeconds(1))));

        Page<UnifiedLogEntryResponse> result = service().list(
                10L, true, null, null, RANGE_START, RANGE_END, PageRequest.of(0, 20), "Bearer test-token");

        assertEquals(3, result.getTotalElements());
        assertEquals(List.of("AI_JOB", "AUDIT", "OPERATION"),
                result.getContent().stream().map(UnifiedLogEntryResponse::sourceType).toList());
        verify(operationLogRepository, never()).findByUserIdOrderByCreatedAtDesc(any(), any());
        verify(auditLogRepository, never()).findAllByOrderByCreatedAtDesc(any());
    }

    @Test
    void list_開始のみ指定なら終了は上限なしで絞る() {
        stubEmptySources();
        when(operationLogRepository.findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                eq(10L), eq(RANGE_START), eq(MAX_BOUND), any()))
                .thenReturn(new PageImpl<>(List.of(operationLog(1L, RANGE_START.plusDays(30)))));
        when(generationJobClient.listRecent("Bearer test-token")).thenReturn(List.of(
                generationJob(2L, RANGE_START.plusDays(60)), generationJob(3L, RANGE_START.minusDays(1))));

        Page<UnifiedLogEntryResponse> result = service().list(
                10L, false, null, null, RANGE_START, null, PageRequest.of(0, 20), "Bearer test-token");

        assertEquals(2, result.getTotalElements());
    }

    @Test
    void list_終了のみ指定なら開始は下限なしで絞る() {
        stubEmptySources();
        when(auditLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(eq(MIN_BOUND), eq(RANGE_END), any()))
                .thenReturn(new PageImpl<>(List.of(auditLog(3L, RANGE_END.minusDays(400)))));

        Page<UnifiedLogEntryResponse> result = service().list(
                10L, true, "AUDIT", null, null, RANGE_END, PageRequest.of(0, 20), "Bearer test-token");

        assertEquals(1, result.getTotalElements());
        assertEquals("AUDIT", result.getContent().get(0).sourceType());
    }

    @Test
    void list_日時未指定なら従来どおり直近の窓のクエリを使う() {
        stubEmptySources();

        service().list(10L, true, null, null, null, null, PageRequest.of(0, 20), "Bearer test-token");

        verify(operationLogRepository).findByUserIdOrderByCreatedAtDesc(eq(10L), any());
        verify(auditLogRepository).findAllByOrderByCreatedAtDesc(any());
        verify(operationLogRepository, never())
                .findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(any(), any(), any(), any());
        verify(auditLogRepository, never()).findByCreatedAtBetweenOrderByCreatedAtDesc(any(), any(), any());
    }
}
