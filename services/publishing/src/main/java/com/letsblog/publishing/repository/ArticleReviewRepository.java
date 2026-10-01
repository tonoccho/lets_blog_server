package com.letsblog.publishing.repository;

import com.letsblog.publishing.domain.ArticleReview;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArticleReviewRepository extends JpaRepository<ArticleReview, Long> {

    Optional<ArticleReview> findByProjectIdAndGithubPrNumber(Long projectId, Integer githubPrNumber);
}
