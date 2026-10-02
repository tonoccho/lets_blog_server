package com.letsblog.ai.repository;

import com.letsblog.ai.domain.ArticlePlanSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ArticlePlanSessionRepository extends JpaRepository<ArticlePlanSession, Long> {
    // updated_atは秒精度のため、同一秒の更新ではIDの降順を第2キーにして順序を一意にする(issue #1375)。
    List<ArticlePlanSession> findByProjectIdOrderByUpdatedAtDescIdDesc(Long projectId);

    Optional<ArticlePlanSession> findFirstByProjectIdAndGithubIssueNumberOrderByUpdatedAtDescIdDesc(
            Long projectId, Integer githubIssueNumber);
}
