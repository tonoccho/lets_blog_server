package com.letsblog.logwriter.repository;

import com.letsblog.logwriter.domain.OperationLog;
import com.letsblog.logwriter.dto.OperationStat;
import com.letsblog.logwriter.dto.RouteDurationRow;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
    Page<OperationLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<OperationLog> findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long userId, LocalDateTime start, LocalDateTime end, Pageable pageable);

    List<OperationLog> findByUserIdAndOperationIdOrderByCreatedAtAsc(Long userId, String operationId);

    /**
     * 閾値より古い行を最大{@code limit}件削除し、削除した件数を返す(issue #1727)。全件をメモリへ読み込まず、
     * 1回の削除を短いトランザクションに収める。呼び出し側が0件または{@code limit}未満になるまで繰り返す。
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM operation_logs WHERE created_at < :threshold LIMIT :limit", nativeQuery = true)
    int deleteBatchBefore(@Param("threshold") LocalDateTime threshold, @Param("limit") int limit);

    /** 管理者向け: 利用者を問わずoperationIdの全行(issue #1471)。 */
    List<OperationLog> findByOperationIdOrderByCreatedAtAsc(String operationId);

    /** ルート別集計用。期間で絞った行のmethod/path/duration_msだけを取る(idx_created_atが効く、issue #1471)。 */
    @Query("select new com.letsblog.logwriter.dto.RouteDurationRow(o.method, o.path, o.durationMs) "
            + "from OperationLog o where o.createdAt between :start and :end")
    List<RouteDurationRow> findRouteDurations(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /** 操作別集計用。operation_idごとの合計・件数・最小のcreated_at・利用者ID(issue #1471)。 */
    @Query("select new com.letsblog.logwriter.dto.OperationStat(o.operationId, sum(o.durationMs), count(o), "
            + "min(o.createdAt), min(o.userId)) "
            + "from OperationLog o where o.createdAt between :start and :end group by o.operationId")
    List<OperationStat> aggregateOperations(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}
