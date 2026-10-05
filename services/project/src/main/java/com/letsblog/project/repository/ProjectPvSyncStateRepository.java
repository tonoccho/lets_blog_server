package com.letsblog.project.repository;

import com.letsblog.project.domain.ProjectPvSyncState;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectPvSyncStateRepository extends JpaRepository<ProjectPvSyncState, Long> {
}
