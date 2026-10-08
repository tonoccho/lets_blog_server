package com.letsblog.publishing.repository;

import com.letsblog.publishing.domain.ArticleReview;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ArticleReviewRepository extends JpaRepository<ArticleReview, Long> {

    Optional<ArticleReview> findByProjectIdAndGithubPrNumber(Long projectId, Integer githubPrNumber);

    /** プロジェクトのレビュー記録をまとめて返す(issue #1677、PR一覧の状態をPRごとのクエリなしで付けるため)。 */
    List<ArticleReview> findByProjectId(Long projectId);

    /** 提出者が{@code submittedByUserId}の行を、提出日時の新しい順に返す(issue #1344、自分宛のレビュー一覧)。 */
    List<ArticleReview> findByProjectIdAndSubmittedByUserIdOrderBySubmittedAtDesc(
            Long projectId, Long submittedByUserId);
}
