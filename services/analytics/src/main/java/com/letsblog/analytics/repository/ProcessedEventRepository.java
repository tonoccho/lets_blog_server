package com.letsblog.analytics.repository;

import com.letsblog.analytics.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {
}
