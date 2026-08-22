package com.letsblog.logwriter.repository;

import com.letsblog.logwriter.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
