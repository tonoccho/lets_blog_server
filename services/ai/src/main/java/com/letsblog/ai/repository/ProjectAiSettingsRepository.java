package com.letsblog.ai.repository;

import com.letsblog.ai.domain.ProjectAiSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProjectAiSettingsRepository extends JpaRepository<ProjectAiSettings, Long> {
    Optional<ProjectAiSettings> findByProjectId(Long projectId);
    void deleteByProjectId(Long projectId);
}
