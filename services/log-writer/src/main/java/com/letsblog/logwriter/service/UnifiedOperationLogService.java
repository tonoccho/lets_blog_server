package com.letsblog.logwriter.service;

import com.letsblog.logwriter.client.GenerationJobClient;
import com.letsblog.logwriter.client.GenerationJobSummary;
import com.letsblog.logwriter.domain.AuditLog;
import com.letsblog.logwriter.domain.OperationLog;
import com.letsblog.logwriter.dto.UnifiedLogEntryResponse;
import com.letsblog.logwriter.repository.AuditLogRepository;
import com.letsblog.logwriter.repository.OperationLogRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 操作ログ・AIジョブ・監査ログを1画面に統合表示するための集約サービス(issue #187、
 * #572でlog-writerへ移設)。
 *
 * <p>OPERATION/AUDITはlog-writer自身が所有するlbs_logスキーマから直接取得する。AI_JOBのみ、
 * issue #572の時点でも引き続きlegacy-apiが所有するgeneration_jobsテーブル(lets_blogスキーマ)に
 * 由来するため、{@link GenerationJobClient}経由の同期HTTP呼び出しで取得する(AIサービス抽出は
 * Phase 19の別Issueで行う。ADR-0004によりlbs_logスキーマからのクロススキーマ参照はできない)。
 * AIジョブは利用者に紐付く情報を持たないため全員に表示する(既存の/ai-jobs画面も同様に全件表示)。
 * 監査ログは元々admin限定のため、adminでない利用者には含めない。操作ログは元々本人限定のため、
 * 常に閲覧者本人の分のみを含める。
 * 3種類のソースを1クエリでページングできないため、各ソースから直近分を取得してメモリ上で
 * マージ・ソート・ページングする(このアプリの利用規模では十分な精度。元のUnifiedOperationLogService
 * のJavadoc参照)。
 */
@Service
public class UnifiedOperationLogService {

    private static final int SOURCE_FETCH_LIMIT = 200;

    private final OperationLogRepository operationLogRepository;
    private final GenerationJobClient generationJobClient;
    private final AuditLogRepository auditLogRepository;

    public UnifiedOperationLogService(
            OperationLogRepository operationLogRepository,
            GenerationJobClient generationJobClient,
            AuditLogRepository auditLogRepository) {
        this.operationLogRepository = operationLogRepository;
        this.generationJobClient = generationJobClient;
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * @param bearerToken 呼び出し元の{@code Authorization}ヘッダー(AI_JOBソース取得のため
     *                    legacy-apiへ転送する。GenerationJobClientのJavadoc参照)。
     */
    @Transactional(readOnly = true)
    public Page<UnifiedLogEntryResponse> list(
            Long viewerUserId, boolean viewerIsAdmin, String sourceType, String query, Pageable pageable,
            String bearerToken) {
        List<UnifiedLogEntryResponse> entries = new ArrayList<>();
        PageRequest fetchWindow = PageRequest.of(0, SOURCE_FETCH_LIMIT);

        if (includeSource(sourceType, "OPERATION")) {
            operationLogRepository.findByUserIdOrderByCreatedAtDesc(viewerUserId, fetchWindow)
                    .forEach(log -> entries.add(fromOperationLog(log)));
        }
        if (includeSource(sourceType, "AI_JOB")) {
            generationJobClient.listRecent(bearerToken).stream()
                    .limit(SOURCE_FETCH_LIMIT)
                    .forEach(job -> entries.add(fromGenerationJob(job)));
        }
        if (viewerIsAdmin && includeSource(sourceType, "AUDIT")) {
            auditLogRepository.findAllByOrderByCreatedAtDesc(fetchWindow)
                    .forEach(auditLog -> entries.add(fromAuditLog(auditLog)));
        }

        List<UnifiedLogEntryResponse> filtered = entries;
        if (query != null && !query.isBlank()) {
            String needle = query.toLowerCase(Locale.ROOT);
            filtered = filtered.stream().filter(entry -> matches(entry, needle)).toList();
        }

        List<UnifiedLogEntryResponse> sorted = filtered.stream()
                .sorted(Comparator.comparing(UnifiedLogEntryResponse::createdAt).reversed())
                .toList();

        int start = Math.min((int) pageable.getOffset(), sorted.size());
        int end = Math.min(start + pageable.getPageSize(), sorted.size());
        return new PageImpl<>(sorted.subList(start, end), pageable, sorted.size());
    }

    private boolean includeSource(String requestedType, String candidateType) {
        return requestedType == null || requestedType.isBlank() || requestedType.equalsIgnoreCase(candidateType);
    }

    private boolean matches(UnifiedLogEntryResponse entry, String needle) {
        return entry.title().toLowerCase(Locale.ROOT).contains(needle)
                || (entry.detail() != null && entry.detail().toLowerCase(Locale.ROOT).contains(needle));
    }

    private UnifiedLogEntryResponse fromOperationLog(OperationLog log) {
        return new UnifiedLogEntryResponse(
                "OPERATION",
                log.getId(),
                log.getCreatedAt(),
                log.getMethod() + " " + log.getPath(),
                log.getErrorMessage(),
                log.isSuccess() ? "SUCCESS" : "FAILED",
                log.getOperationId(),
                log.getActorKeycloakSub());
    }

    private UnifiedLogEntryResponse fromGenerationJob(GenerationJobSummary job) {
        return new UnifiedLogEntryResponse(
                "AI_JOB", job.id(), job.createdAt(), job.type(), null, job.status(), null, null);
    }

    private UnifiedLogEntryResponse fromAuditLog(AuditLog auditLog) {
        String title = auditLog.getAction();
        if (auditLog.getResourceType() != null) {
            title += " " + auditLog.getResourceType();
            if (auditLog.getResourceId() != null) {
                title += " #" + auditLog.getResourceId();
            }
        }
        return new UnifiedLogEntryResponse(
                "AUDIT", auditLog.getId(), auditLog.getCreatedAt(), title, auditLog.getRemoteIp(), null, null,
                auditLog.getActorKeycloakSub());
    }
}
