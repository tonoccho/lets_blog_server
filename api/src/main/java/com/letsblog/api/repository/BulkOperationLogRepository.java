package com.letsblog.api.repository;

import com.letsblog.api.domain.BulkOperationLog;
import com.letsblog.api.domain.BulkOperationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BulkOperationLogRepository extends JpaRepository<BulkOperationLog, Long> {
    List<BulkOperationLog> findByProjectIdOrderByCreatedAtDesc(Long projectId);
    List<BulkOperationLog> findByProjectIdAndStatusOrderByCreatedAtAsc(Long projectId, BulkOperationStatus status);
}
