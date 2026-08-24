package com.letsblog.content.repository;

import com.letsblog.content.domain.ProjectContentSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProjectContentSettingsRepository extends JpaRepository<ProjectContentSettings, Long> {
    Optional<ProjectContentSettings> findByProjectId(Long projectId);
    void deleteByProjectId(Long projectId);
}
