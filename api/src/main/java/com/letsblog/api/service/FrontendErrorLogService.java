package com.letsblog.api.service;

import com.letsblog.api.domain.FrontendErrorLog;
import com.letsblog.api.repository.FrontendErrorLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Transactional
public class FrontendErrorLogService {

    private final FrontendErrorLogRepository repository;

    public FrontendErrorLogService(FrontendErrorLogRepository repository) {
        this.repository = repository;
    }

    public void logError(FrontendErrorLog errorLog) {
        if (errorLog.getCreatedAt() == null) {
            errorLog.setCreatedAt(LocalDateTime.now());
        }
        repository.save(errorLog);
    }

    @Transactional(readOnly = true)
    public Page<FrontendErrorLog> findAll(Pageable pageable) {
        return repository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<FrontendErrorLog> findByLevel(FrontendErrorLog.ErrorLevel level, Pageable pageable) {
        return repository.findByLevel(level, pageable);
    }

    @Transactional(readOnly = true)
    public Page<FrontendErrorLog> findByDateRange(LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return repository.findByCreatedAtBetween(startDate, endDate, pageable);
    }

    @Transactional(readOnly = true)
    public Page<FrontendErrorLog> findByUrl(String url, Pageable pageable) {
        return repository.findByUrl(url, pageable);
    }
}
