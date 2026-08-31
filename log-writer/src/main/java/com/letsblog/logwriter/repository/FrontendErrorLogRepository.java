package com.letsblog.logwriter.repository;

import com.letsblog.logwriter.domain.FrontendErrorLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FrontendErrorLogRepository extends JpaRepository<FrontendErrorLog, Long> {
}
