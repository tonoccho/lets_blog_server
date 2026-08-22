package com.letsblog.logwriter.repository;

import com.letsblog.logwriter.domain.OperationLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OperationLogRepository extends JpaRepository<OperationLog, Long> {
}
