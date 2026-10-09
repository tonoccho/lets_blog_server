package com.letsblog.logwriter.repository;

import com.letsblog.logwriter.domain.AuditLog;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    Page<AuditLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<AuditLog> findByActionOrderByCreatedAtDesc(String action, Pageable pageable);

    Page<AuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime start, LocalDateTime end, Pageable pageable);

    Page<AuditLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 閾値より古い行を最大{@code limit}件削除し、削除した件数を返す(issue #1727)。全件をメモリへ読み込まず、
     * 1回の削除を短いトランザクションに収める。呼び出し側が0件または{@code limit}未満になるまで繰り返す。
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM audit_logs WHERE created_at < :threshold LIMIT :limit", nativeQuery = true)
    int deleteBatchBefore(@Param("threshold") LocalDateTime threshold, @Param("limit") int limit);
}
