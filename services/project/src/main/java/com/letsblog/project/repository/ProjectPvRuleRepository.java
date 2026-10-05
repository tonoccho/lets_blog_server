package com.letsblog.project.repository;

import com.letsblog.project.domain.ProjectPvRule;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectPvRuleRepository extends JpaRepository<ProjectPvRule, Long> {

    List<ProjectPvRule> findByProjectIdOrderByIdAsc(Long projectId);

    Optional<ProjectPvRule> findByIdAndProjectId(Long id, Long projectId);
}
