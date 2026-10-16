package com.letsblog.logwriter.service;

import com.letsblog.logwriter.domain.AuditLog;
import com.letsblog.logwriter.domain.OperationLog;
import com.letsblog.logwriter.dto.UnifiedLogEntryResponse;
import com.letsblog.logwriter.testsupport.LogWriterIntegrationTestBase;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UnifiedOperationLogServiceの回帰テスト(issue #187、#572でlog-writerへ移設、issue #1829で書き直し)。
 * 3ソースのマージ・ソート・監査ログのadmin限定・種別フィルタ・キーワード検索・ページング・日時の範囲を検証する。
 *
 * <p>OPERATION / AUDIT は実 MySQL の行、AI_JOB / SYSTEM_JOB は ai-service の {@code GET /api/generation-jobs} を
 * 模した HTTP のフェイクの応答から取る(GenerationJobClient は実物)。
 */
class UnifiedOperationLogServiceTest extends LogWriterIntegrationTestBase {

    private static final String TOKEN = "Bearer test-token";
    private static final Pageable PAGE = PageRequest.of(0, 20);
    private static final long USER = 10L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 12, 0);

    @Autowired
    private UnifiedOperationLogService service;

    private static String job(long id, String type, LocalDateTime createdAt) {
        return "{\"id\":" + id + ",\"type\":\"" + type + "\",\"status\":\"done\",\"createdAt\":\"" + createdAt
                + "\",\"updatedAt\":null}";
    }

    private void aiServiceReturns(String... jobs) {
        HTTP.json("GET", "/api/generation-jobs", "[" + String.join(",", jobs) + "]");
    }

    private int aiServiceCalls() {
        return HTTP.requests("GET", "/api/generation-jobs").size();
    }

    private OperationLog operation(long id, LocalDateTime createdAt) {
        return saveOperation(USER, "op-" + id, "GET", "/api/sites", 5L, createdAt);
    }

    private AuditLog audit(LocalDateTime createdAt) {
        return saveAudit(99L, "LOGIN", null, null, "10.0.0.1", createdAt);
    }

    private Page<UnifiedLogEntryResponse> list(boolean admin, String type, String q, Pageable pageable) {
        return service.list(USER, admin, type, q, null, null, pageable, TOKEN);
    }

    private static List<String> sources(Page<UnifiedLogEntryResponse> page) {
        return page.getContent().stream().map(UnifiedLogEntryResponse::sourceType).toList();
    }

    @Test
    void list_3ソースを作成日時降順でマージする() {
        operation(1L, NOW.minusMinutes(2));
        audit(NOW.minusMinutes(1));
        aiServiceReturns(job(2L, "draft", NOW));

        Page<UnifiedLogEntryResponse> result = list(true, null, null, PAGE);

        assertEquals(3, result.getTotalElements());
        assertEquals(List.of("AI_JOB", "AUDIT", "OPERATION"), sources(result));
        assertEquals("audit-sub-99", result.getContent().get(1).actorKeycloakSub());
        assertEquals("sub-10", result.getContent().get(2).actorKeycloakSub());
        assertEquals("Bearer test-token", HTTP.last().header("Authorization"));
    }

    @Test
    void list_操作ログは本人の分だけで_失敗は_FAILED_成功は_SUCCESSになる() {
        operation(1L, NOW);
        OperationLog failed = saveOperation(USER, "op-failed", "POST", "/api/x", 5L, NOW.plusMinutes(1));
        failed.setSuccess(false);
        failed.setErrorMessage("boom");
        operationLogRepository.save(failed);
        saveOperation(11L, "op-other-user", "GET", "/api/y", 5L, NOW);
        aiServiceReturns();

        Page<UnifiedLogEntryResponse> result = list(false, "OPERATION", null, PAGE);

        assertEquals(2, result.getTotalElements());
        UnifiedLogEntryResponse first = result.getContent().get(0);
        assertEquals("FAILED", first.status());
        assertEquals("POST /api/x", first.title());
        assertEquals("boom", first.detail());
        assertEquals("op-failed", first.operationId());
        UnifiedLogEntryResponse second = result.getContent().get(1);
        assertEquals("SUCCESS", second.status());
        assertNull(second.detail());
    }

    @Test
    void list_admin以外は監査ログを含めない() {
        audit(NOW);
        aiServiceReturns();

        Page<UnifiedLogEntryResponse> result = list(false, null, null, PAGE);

        assertEquals(0, result.getTotalElements());
    }

    @Test
    void list_監査ログのタイトルはリソース種別とIDがあるときだけ付く() {
        saveAudit(1L, "A", null, null, "1.1.1.1", NOW);
        saveAudit(1L, "B", "USER", null, "1.1.1.1", NOW.plusMinutes(1));
        saveAudit(1L, "C", "USER", 7L, "1.1.1.1", NOW.plusMinutes(2));

        Page<UnifiedLogEntryResponse> result = list(true, "AUDIT", null, PAGE);

        assertEquals(List.of("C USER #7", "B USER", "A"),
                result.getContent().stream().map(UnifiedLogEntryResponse::title).toList());
        assertEquals("1.1.1.1", result.getContent().get(0).detail());
        assertEquals(0, aiServiceCalls());
    }

    @Test
    void list_typeフィルタで対象ソースのみ取得する() {
        operation(1L, NOW);
        audit(NOW);
        aiServiceReturns(job(5L, "draft", NOW));

        Page<UnifiedLogEntryResponse> result = list(true, "AI_JOB", null, PAGE);

        assertEquals(List.of("AI_JOB"), sources(result));
        assertEquals(1, aiServiceCalls());
    }

    @Test
    void list_種別は大文字小文字を区別せず_空白は未指定と同じ() {
        operation(1L, NOW);
        audit(NOW.plusMinutes(1));
        aiServiceReturns();

        assertEquals(List.of("OPERATION"), sources(list(true, "operation", null, PAGE)));
        assertEquals(List.of("AUDIT", "OPERATION"), sources(list(true, "   ", null, PAGE)));
    }

    private void aiServiceReturnsMixedJobs() {
        aiServiceReturns(
                job(1L, "image_generation", NOW),
                job(2L, "media_garbage_collection_delete", NOW.minusMinutes(1)),
                job(3L, "unknown_type", NOW.minusMinutes(2)));
    }

    @Test
    void list_media_garbage_collection_deleteのジョブはAI_JOBではなくSYSTEM_JOBとして出す() {
        aiServiceReturnsMixedJobs();

        Page<UnifiedLogEntryResponse> result = list(true, null, null, PAGE);

        UnifiedLogEntryResponse gc = result.getContent().stream().filter(e -> e.id() == 2L).findFirst().orElseThrow();
        assertEquals("SYSTEM_JOB", gc.sourceType());
    }

    @Test
    void list_image_generationのジョブは従来どおりAI_JOBとして出す() {
        aiServiceReturnsMixedJobs();

        assertEquals("AI_JOB", list(true, null, null, PAGE).getContent().stream()
                .filter(e -> e.id() == 1L).findFirst().orElseThrow().sourceType());
    }

    @Test
    void list_AI_JOBで絞るとAI分類のジョブだけを返す() {
        aiServiceReturnsMixedJobs();

        Page<UnifiedLogEntryResponse> result = list(true, "AI_JOB", null, PAGE);

        assertEquals(List.of(1L, 3L), result.getContent().stream().map(UnifiedLogEntryResponse::id).toList());
        assertTrue(result.getContent().stream().allMatch(e -> "AI_JOB".equals(e.sourceType())));
    }

    @Test
    void list_SYSTEM_JOBで絞ると非AI分類のジョブだけを返し_ai_serviceから取得する() {
        operation(1L, NOW);
        audit(NOW);
        aiServiceReturnsMixedJobs();

        Page<UnifiedLogEntryResponse> result = list(true, "SYSTEM_JOB", null, PAGE);

        assertEquals(List.of(2L), result.getContent().stream().map(UnifiedLogEntryResponse::id).toList());
        assertEquals("SYSTEM_JOB", result.getContent().get(0).sourceType());
        assertEquals(1, aiServiceCalls());
    }

    @Test
    void list_未知のジョブ種別は例外にならずAI_JOBに入る() {
        aiServiceReturnsMixedJobs();

        Page<UnifiedLogEntryResponse> result = list(true, null, null, PAGE);

        assertEquals(3, result.getTotalElements());
        assertEquals("AI_JOB", result.getContent().stream().filter(e -> e.id() == 3L).findFirst().orElseThrow().sourceType());
    }

    @Test
    void list_OPERATIONで絞るときはジョブを取得しない() {
        operation(1L, NOW);

        list(true, "OPERATION", null, PAGE);

        assertEquals(0, aiServiceCalls());
    }

    @Test
    void list_AUDITで絞るときもジョブを取得しない() {
        audit(NOW);

        list(true, "AUDIT", null, PAGE);

        assertEquals(0, aiServiceCalls());
    }

    @Test
    void list_キーワード検索はtitleまたはdetailに一致するものだけを残す() {
        aiServiceReturns(job(1L, "draft", NOW));
        OperationLog withDetail = saveOperation(USER, "op-2", "GET", "/api/sites", 5L, NOW.minusMinutes(1));
        withDetail.setErrorMessage("Timeout while calling X");
        operationLogRepository.save(withDetail);
        saveOperation(USER, "op-3", "GET", "/api/plain", 5L, NOW.minusMinutes(2));

        assertEquals(List.of("AI_JOB"), sources(list(true, null, "DRAFT", PAGE)));
        assertEquals(List.of("OPERATION"), sources(list(true, null, "timeout", PAGE)));
        assertTrue(list(true, null, "nonexistent", PAGE).getContent().isEmpty());
    }

    @Test
    void list_キーワードがnullまたは空白なら絞り込まない() {
        aiServiceReturns(job(1L, "draft", NOW));
        operation(2L, NOW.minusMinutes(1));

        assertEquals(2, list(true, null, null, PAGE).getTotalElements());
        assertEquals(2, list(true, null, "  ", PAGE).getTotalElements());
    }

    @Test
    void list_ページングする() {
        aiServiceReturns(
                job(1L, "draft", NOW.minusMinutes(3)),
                job(2L, "draft", NOW.minusMinutes(2)),
                job(3L, "draft", NOW.minusMinutes(1)));

        Page<UnifiedLogEntryResponse> result = list(true, null, null, PageRequest.of(1, 2));

        assertEquals(3, result.getTotalElements());
        assertEquals(1, result.getContent().size());
        assertEquals(1L, result.getContent().get(0).id());
    }

    @Test
    void list_ページの開始位置が件数を超えたら空を返す() {
        aiServiceReturns(job(1L, "draft", NOW));

        Page<UnifiedLogEntryResponse> result = list(true, null, null, PageRequest.of(5, 10));

        assertEquals(1, result.getTotalElements());
        assertTrue(result.getContent().isEmpty());
    }

    // ---------------------------------------------- AIジョブ取得失敗時の縮退(issue #825)

    /**
     * AIジョブはai-serviceへの同期HTTP呼び出しで取得する唯一の外部依存で、
     * 操作ログ・監査ログは同じメソッド内で先にDBから取得済みである。
     *
     * <p>#825以前は例外がそのまま突き抜けて統合ログAPI全体が502になり、
     * 取得済みのOPERATION/AUDITまで巻き添えで失われていた。
     */
    @Test
    void list_AIジョブの取得に失敗しても操作ログと監査ログは返す() {
        operation(1L, NOW.minusMinutes(2));
        audit(NOW.minusMinutes(1));
        // ai-service が 404(向き先ミス)を返す。GenerationJobClient は GenerationJobBridgeException に包む。
        HTTP.status("GET", "/api/generation-jobs", 404, "{}");

        Page<UnifiedLogEntryResponse> result = list(true, null, null, PAGE);

        assertEquals(2, result.getTotalElements());
        assertEquals(List.of("AUDIT", "OPERATION"), sources(result));
    }

    @Test
    void list_AIジョブのみを要求して失敗した場合は空を返す() {
        HTTP.status("GET", "/api/generation-jobs", 401, "{}");

        Page<UnifiedLogEntryResponse> result = list(true, "AI_JOB", null, PAGE);

        assertEquals(0, result.getTotalElements());
    }

    @Test
    void list_ai_serviceが空の本文を返してもジョブ無しとして扱う() {
        operation(1L, NOW);
        HTTP.json("GET", "/api/generation-jobs", "null");

        assertEquals(List.of("OPERATION"), sources(list(true, null, null, PAGE)));
    }

    // ---------------------------------------------- 日時の範囲による絞り込み(issue #1138)

    private static final LocalDateTime RANGE_START = LocalDateTime.of(2026, 1, 10, 0, 0);
    private static final LocalDateTime RANGE_END = LocalDateTime.of(2026, 1, 11, 0, 0);

    @Test
    void list_日時の範囲を各ソースの取得段階で絞る() {
        LocalDateTime inside = RANGE_START.plusHours(1);
        // 範囲指定のクエリは直近200件の窓の外(古い側)も引けるので、範囲内の行だけが返る。
        operation(1L, inside);
        operation(8L, RANGE_START.minusSeconds(1));
        operation(9L, RANGE_END.plusSeconds(1));
        audit(inside.plusMinutes(1));
        audit(RANGE_START.minusDays(1));
        aiServiceReturns(
                job(2L, "draft", inside.plusMinutes(2)),
                job(4L, "draft", RANGE_START.minusSeconds(1)),
                job(5L, "draft", RANGE_END.plusSeconds(1)));

        Page<UnifiedLogEntryResponse> result =
                service.list(USER, true, null, null, RANGE_START, RANGE_END, PAGE, TOKEN);

        assertEquals(3, result.getTotalElements());
        assertEquals(List.of("AI_JOB", "AUDIT", "OPERATION"), sources(result));
    }

    @Test
    void list_範囲の端の時刻は含める() {
        operation(1L, RANGE_START);
        operation(2L, RANGE_END);
        aiServiceReturns(job(3L, "draft", RANGE_START), job(4L, "draft", RANGE_END));

        Page<UnifiedLogEntryResponse> result =
                service.list(USER, false, null, null, RANGE_START, RANGE_END, PAGE, TOKEN);

        assertEquals(4, result.getTotalElements());
    }

    @Test
    void list_開始のみ指定なら終了は上限なしで絞る() {
        operation(1L, RANGE_START.plusDays(30));
        operation(5L, RANGE_START.minusDays(1));
        aiServiceReturns(job(2L, "draft", RANGE_START.plusDays(60)), job(3L, "draft", RANGE_START.minusDays(1)));

        Page<UnifiedLogEntryResponse> result =
                service.list(USER, false, null, null, RANGE_START, null, PAGE, TOKEN);

        assertEquals(2, result.getTotalElements());
    }

    @Test
    void list_終了のみ指定なら開始は下限なしで絞る() {
        audit(RANGE_END.minusDays(400));
        audit(RANGE_END.plusDays(1));

        Page<UnifiedLogEntryResponse> result =
                service.list(USER, true, "AUDIT", null, null, RANGE_END, PAGE, TOKEN);

        assertEquals(1, result.getTotalElements());
        assertEquals("AUDIT", result.getContent().get(0).sourceType());
    }

    @Test
    void list_日時未指定なら範囲外の古い行も直近の窓に入る() {
        operation(1L, RANGE_START.minusYears(5));
        audit(RANGE_START.minusYears(5).plusMinutes(1));
        aiServiceReturns();

        Page<UnifiedLogEntryResponse> result = list(true, null, null, PAGE);

        assertEquals(List.of("AUDIT", "OPERATION"), sources(result));
    }
}
