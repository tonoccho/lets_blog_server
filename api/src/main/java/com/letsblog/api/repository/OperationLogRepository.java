package com.letsblog.api.repository;

import com.letsblog.api.domain.OperationLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
    Page<OperationLog> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);

    List<OperationLog> findByUserIdAndOperationIdOrderByCreatedAtAsc(Long userId, String operationId);

    List<OperationLog> findByCreatedAtBefore(LocalDateTime threshold);
}
