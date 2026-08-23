package com.letsblog.api.service;

import com.letsblog.api.domain.AuditLog;
import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.domain.OperationLog;
import com.letsblog.api.dto.UnifiedLogEntryResponse;
import com.letsblog.api.repository.AuditLogRepository;
import com.letsblog.api.repository.GenerationJobRepository;
import com.letsblog.api.repository.OperationLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 操作ログ・AIジョブ・監査ログを1画面に統合表示するための集約サービス(issue #187)。
 * 3つのテーブルは記録方法・アクセス権限が異なる別々のドメインのまま残し(既存の記録経路・
 * ジョブ進捗ポーリング・監査AOPには一切手を入れない)、表示層でのみ集約する。
 * 監査ログは元々admin限定のため、adminでない利用者には含めない。操作ログは元々本人限定のため、
 * 常に閲覧者本人の分のみを含める。AIジョブは利用者に紐付く情報を持たないため全員に表示する
 * (既存の/ai-jobs画面も同様に全件表示だった)。
 * 3種類のテーブルを1クエリでページングできないため、各ソースから直近分を取得して
 * メモリ上でマージ・ソート・ページングする(このアプリの利用規模では十分な精度)。
 */
@Service
public class UnifiedOperationLogService {

    private static final int SOURCE_FETCH_LIMIT = 200;

    private final OperationLogRepository operationLogRepository;
    private final GenerationJobRepository generationJobRepository;
    private final AuditLogRepository auditLogRepository;

    public UnifiedOperationLogService(
            OperationLogRepository operationLogRepository,
            GenerationJobRepository generationJobRepository,
            AuditLogRepository auditLogRepository) {
        this.operationLogRepository = operationLogRepository;
        this.generationJobRepository = generationJobRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(readOnly = true)
    public Page<UnifiedLogEntryResponse> list(
            Long viewerUserId, boolean viewerIsAdmin, String sourceType, String query, Pageable pageable) {
        List<UnifiedLogEntryResponse> entries = new ArrayList<>();
        PageRequest fetchWindow = PageRequest.of(0, SOURCE_FETCH_LIMIT);

        if (includeSource(sourceType, "OPERATION")) {
            operationLogRepository.findByUserIdOrderByCreatedAtDesc(viewerUserId, fetchWindow)
                    .forEach(log -> entries.add(fromOperationLog(log)));
        }
        if (includeSource(sourceType, "AI_JOB")) {
            generationJobRepository.findAllByOrderByCreatedAtDesc(fetchWindow)
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

    private UnifiedLogEntryResponse fromGenerationJob(GenerationJob job) {
        return new UnifiedLogEntryResponse(
                "AI_JOB", job.getId(), job.getCreatedAt(), job.getType(), null, job.getStatus(), null, null);
    }

    private UnifiedLogEntryResponse fromAuditLog(AuditLog auditLog) {
        String title = auditLog.getAction().name();
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
