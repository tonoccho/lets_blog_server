package com.letsblog.identity.repository;

import com.letsblog.identity.domain.ProjectUser;
import com.letsblog.identity.domain.ProjectUserId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectUserRepository extends JpaRepository<ProjectUser, ProjectUserId> {
    List<ProjectUser> findByProjectId(Long projectId);
    Optional<ProjectUser> findByProjectIdAndUserId(Long projectId, Long userId);

    List<ProjectUser> findByUserId(Long userId);
    void deleteByProjectIdAndUserId(Long projectId, Long userId);
}
