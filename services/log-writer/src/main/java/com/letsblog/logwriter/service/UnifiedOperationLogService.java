package com.letsblog.logwriter.service;

import com.letsblog.logwriter.client.GenerationJobClient;
import com.letsblog.logwriter.client.GenerationJobSummary;
import com.letsblog.logwriter.domain.AuditLog;
import com.letsblog.logwriter.domain.OperationLog;
import com.letsblog.logwriter.dto.UnifiedLogEntryResponse;
import com.letsblog.logwriter.repository.AuditLogRepository;
import com.letsblog.logwriter.repository.OperationLogRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
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
 * ai-serviceが所有するgeneration_jobsテーブルに由来するため、{@link GenerationJobClient}経由の
 * 同期HTTP呼び出しで取得する(ADR-0004によりlbs_logスキーマからのクロススキーマ参照はできない)。
 * #572の時点ではlegacy-apiが所有していたが、AIサービス抽出でai-serviceへ移り、
 * 本サービスの問い合わせ先は#825で追随した。
 * AIジョブは利用者に紐付く情報を持たないため全員に表示する(既存の/ai-jobs画面も同様に全件表示)。
 * 監査ログは元々admin限定のため、adminでない利用者には含めない。操作ログは元々本人限定のため、
 * 常に閲覧者本人の分のみを含める。
 * 3種類のソースを1クエリでページングできないため、各ソースから直近分を取得してメモリ上で
 * マージ・ソート・ページングする(このアプリの利用規模では十分な精度。元のUnifiedOperationLogService
 * のJavadoc参照)。
 */
@Service
@Slf4j
public class UnifiedOperationLogService {

    private static final int SOURCE_FETCH_LIMIT = 200;

    /** 日時の範囲で片側だけが指定されたときの、もう一方の側(実質「境界なし」)。 */
    private static final LocalDateTime MIN_BOUND = LocalDateTime.of(1970, 1, 1, 0, 0);
    private static final LocalDateTime MAX_BOUND = LocalDateTime.of(9999, 12, 31, 23, 59, 59);

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
     * @param startDate 範囲の開始(含む)。{@code startDate}と{@code endDate}の両方がnullなら従来どおり
     *                  各ソースの直近{@value #SOURCE_FETCH_LIMIT}件。どちらかが指定されたら、各ソースの
     *                  <b>取得段階</b>でその範囲に絞ってから直近{@value #SOURCE_FETCH_LIMIT}件を取る
     *                  (マージ後に絞るだけでは直近の窓の外にある古いログへ到達できない、issue #1138)。
     *                  AI_JOBはai-serviceが直近分しか返さないため、取得後に範囲で絞る。
     * @param endDate 範囲の終了(含む)。
     * @param bearerToken 呼び出し元の{@code Authorization}ヘッダー(AI_JOBソース取得のため
     *                    ai-serviceへ転送する。GenerationJobClientのJavadoc参照)。
     */
    @Transactional(readOnly = true)
    public Page<UnifiedLogEntryResponse> list(
            Long viewerUserId, boolean viewerIsAdmin, String sourceType, String query,
            LocalDateTime startDate, LocalDateTime endDate, Pageable pageable, String bearerToken) {
        List<UnifiedLogEntryResponse> entries = new ArrayList<>();
        PageRequest fetchWindow = PageRequest.of(0, SOURCE_FETCH_LIMIT);
        boolean ranged = startDate != null || endDate != null;
        LocalDateTime from = startDate != null ? startDate : MIN_BOUND;
        LocalDateTime to = endDate != null ? endDate : MAX_BOUND;

        if (includeSource(sourceType, "OPERATION")) {
            Page<OperationLog> operationLogs = ranged
                    ? operationLogRepository.findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                            viewerUserId, from, to, fetchWindow)
                    : operationLogRepository.findByUserIdOrderByCreatedAtDesc(viewerUserId, fetchWindow);
            operationLogs.forEach(log -> entries.add(fromOperationLog(log)));
        }
        if (includeSource(sourceType, "AI_JOB")) {
            // AIジョブはai-serviceへの同期HTTP呼び出しで取得する唯一の外部依存。ここが落ちても
            // DBから取得済みの操作ログ・監査ログは返す(issue #825)。
            //
            // #825以前は例外がそのまま突き抜けて統合ログAPI全体が502になり、同じメソッド内で
            // 先にDBから取れていたOPERATION/AUDITまで巻き添えで見えなくなっていた。実際、
            // 本クライアントが移設済みのエンドポイントを呼び続けていたため、
            // /operation-logs 画面は常に空だった。
            //
            // 握り潰さずWARNで残すのは、「AIジョブが出ない」ことに誰も気付かない状態を
            // 作らないため。
            try {
                generationJobClient.listRecent(bearerToken).stream()
                        .filter(job -> !ranged || (!job.createdAt().isBefore(from) && !job.createdAt().isAfter(to)))
                        .limit(SOURCE_FETCH_LIMIT)
                        .forEach(job -> entries.add(fromGenerationJob(job)));
            } catch (GenerationJobUnavailableException e) {
                // 例外そのものも渡してスタックトレースを残す。縮退により失敗がHTTPレスポンスに
                // 現れなくなったので、ここが唯一の手がかりになる。
                log.warn("AIジョブの取得に失敗したため、統合操作ログからAI_JOBソースを除外します: {}",
                        e.getMessage(), e);
            }
        }
        if (viewerIsAdmin && includeSource(sourceType, "AUDIT")) {
            Page<AuditLog> auditLogs = ranged
                    ? auditLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(from, to, fetchWindow)
                    : auditLogRepository.findAllByOrderByCreatedAtDesc(fetchWindow);
            auditLogs.forEach(auditLog -> entries.add(fromAuditLog(auditLog)));
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
