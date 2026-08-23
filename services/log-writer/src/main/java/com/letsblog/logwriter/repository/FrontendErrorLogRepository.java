package com.letsblog.logwriter.repository;

import com.letsblog.logwriter.domain.FrontendErrorLog;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FrontendErrorLogRepository extends JpaRepository<FrontendErrorLog, Long> {
    Page<FrontendErrorLog> findByLevel(String level, Pageable pageable);

    Page<FrontendErrorLog> findByCreatedAtBetween(LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);

    Page<FrontendErrorLog> findByUrl(String url, Pageable pageable);
}
