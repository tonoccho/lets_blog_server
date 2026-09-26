package com.letsblog.logwriter.repository;

import com.letsblog.logwriter.domain.OperationLog;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
    Page<OperationLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    Page<OperationLog> findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(
            Long userId, LocalDateTime start, LocalDateTime end, Pageable pageable);

    List<OperationLog> findByUserIdAndOperationIdOrderByCreatedAtAsc(Long userId, String operationId);

    List<OperationLog> findByCreatedAtBefore(LocalDateTime threshold);
}
