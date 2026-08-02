package com.letsblog.api.repository;

import com.letsblog.api.domain.BulkOperationLog;
import com.letsblog.api.domain.BulkOperationLogLevel;
import com.letsblog.api.domain.BulkOperationStatus;
import com.letsblog.api.domain.BulkOperationType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BulkOperationLogRepository extends JpaRepository<BulkOperationLog, Long> {
    List<BulkOperationLog> findByProjectIdOrderByCreatedAtDesc(Long projectId);
    List<BulkOperationLog> findByProjectIdAndStatusOrderByCreatedAtAsc(Long projectId, BulkOperationStatus status);

    @Query("SELECT l FROM BulkOperationLog l WHERE l.projectId = :projectId "
            + "AND (:operationType IS NULL OR l.operationType = :operationType) "
            + "AND (:environment IS NULL OR l.environment = :environment) "
            + "AND (:level IS NULL OR l.level = :level) "
            + "ORDER BY l.createdAt DESC")
    List<BulkOperationLog> findByFilters(
            @Param("projectId") Long projectId,
            @Param("operationType") BulkOperationType operationType,
            @Param("environment") String environment,
            @Param("level") BulkOperationLogLevel level);

    void deleteByProjectId(Long projectId);
}
