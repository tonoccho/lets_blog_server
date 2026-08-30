package com.letsblog.project.repository;

import com.letsblog.project.domain.Project;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    Optional<Project> findBySlug(String slug);

    boolean existsBySlug(String slug);

    List<Project> findAllByOrderByCreatedAtDesc();

    Optional<Project> findByLocalSiteIdOrTestSiteIdOrProductionSiteId(
            Long localSiteId, Long testSiteId, Long productionSiteId);
}
