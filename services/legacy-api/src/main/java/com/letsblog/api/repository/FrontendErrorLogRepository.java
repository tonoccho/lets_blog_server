package com.letsblog.api.repository;

import com.letsblog.api.domain.FrontendErrorLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface FrontendErrorLogRepository extends JpaRepository<FrontendErrorLog, Long> {
    Page<FrontendErrorLog> findByLevel(FrontendErrorLog.ErrorLevel level, Pageable pageable);

    Page<FrontendErrorLog> findByCreatedAtBetween(LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);

    Page<FrontendErrorLog> findByUrl(String url, Pageable pageable);
}
