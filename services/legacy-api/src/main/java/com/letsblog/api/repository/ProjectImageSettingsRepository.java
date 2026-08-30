package com.letsblog.api.repository;

import com.letsblog.api.domain.ProjectImageSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProjectImageSettingsRepository extends JpaRepository<ProjectImageSettings, Long> {
    Optional<ProjectImageSettings> findByProjectId(Long projectId);
    void deleteByProjectId(Long projectId);
}
