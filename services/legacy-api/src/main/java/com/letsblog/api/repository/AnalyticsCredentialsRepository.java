package com.letsblog.api.repository;

import com.letsblog.api.domain.AnalyticsCredentials;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AnalyticsCredentialsRepository extends JpaRepository<AnalyticsCredentials, Long> {
    Optional<AnalyticsCredentials> findByProjectId(Long projectId);
    void deleteByProjectId(Long projectId);
}
