package com.letsblog.ai.repository;

import com.letsblog.ai.domain.ArticlePlanSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ArticlePlanSessionRepository extends JpaRepository<ArticlePlanSession, Long> {
    List<ArticlePlanSession> findByProjectIdOrderByUpdatedAtDesc(Long projectId);

    Optional<ArticlePlanSession> findFirstByProjectIdAndGithubIssueNumberOrderByUpdatedAtDesc(
            Long projectId, Integer githubIssueNumber);
}
