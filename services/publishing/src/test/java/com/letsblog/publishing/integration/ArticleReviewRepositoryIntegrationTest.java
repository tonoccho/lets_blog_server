package com.letsblog.publishing.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.letsblog.publishing.domain.ArticleReview;
import com.letsblog.publishing.domain.ArticleReviewState;
import com.letsblog.publishing.repository.ArticleReviewRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * V2マイグレーションの{@code article_reviews}テーブルとエンティティの結合テスト(issue #1339)。
 * 実MySQL(ADR-0006)上で、(project_id, github_pr_number)の一意制約と、stateが文字列で
 * 保存されることを確かめる。スキーマとエンティティの整合は{@code ddl-auto: validate}が起動時に検証する。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("publishing-service: article_reviewsの永続化(issue #1339)")
class ArticleReviewRepositoryIntegrationTest {

    private static final long PROJECT_ID = 913_391L;

    @Autowired
    private ArticleReviewRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM article_reviews WHERE project_id = ?", PROJECT_ID);
    }

    private static ArticleReview review(int prNumber) {
        ArticleReview review = new ArticleReview();
        review.setProjectId(PROJECT_ID);
        review.setGithubPrNumber(prNumber);
        review.setGithubIssueNumber(12);
        review.setArticleSlug("sample");
        review.setSubmittedByUserId(3L);
        review.setState(ArticleReviewState.SUBMITTED);
        return review;
    }

    @Test
    @DisplayName("保存した行をプロジェクトとPR番号で引け、stateは文字列で、タイムスタンプが入る")
    void savesAndFinds() {
        repository.saveAndFlush(review(9));

        ArticleReview found = repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, 9).orElseThrow();
        assertThat(found.getSubmittedByUserId()).isEqualTo(3L);
        assertThat(found.getArticleSlug()).isEqualTo("sample");
        assertThat(found.getGithubIssueNumber()).isEqualTo(12);
        assertThat(found.getState()).isEqualTo(ArticleReviewState.SUBMITTED);
        assertThat(found.getSubmittedAt()).isNotNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM article_reviews WHERE project_id = ? AND github_pr_number = 9",
                String.class, PROJECT_ID)).isEqualTo("SUBMITTED");
        assertThat(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, 10)).isEmpty();
    }

    @Test
    @DisplayName("同じ(project_id, github_pr_number)は二重に保存できない")
    void uniquePerProjectAndPr() {
        repository.saveAndFlush(review(9));

        assertThatThrownBy(() -> repository.saveAndFlush(review(9)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("更新したstateが保存される(再提出で提出済みへ戻る)")
    void updatesState() {
        ArticleReview saved = repository.saveAndFlush(review(9));
        saved.setState(ArticleReviewState.CHANGES_REQUESTED);
        repository.saveAndFlush(saved);
        saved.setState(ArticleReviewState.SUBMITTED);
        repository.saveAndFlush(saved);

        assertThat(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, 9).orElseThrow().getState())
                .isEqualTo(ArticleReviewState.SUBMITTED);
    }

    @Test
    @DisplayName("レビュー中へ遷移させるとテスト環境の投稿URLとレビュー実施者が保存され、提出者は変わらない(issue #1341)")
    void persistsInReviewWithUrlAndReviewer() {
        ArticleReview saved = repository.saveAndFlush(review(9));
        assertThat(saved.getTestPostUrl()).isNull();
        assertThat(saved.getReviewedByUserId()).isNull();

        saved.markInReview("http://test.example/sample/", 8L);
        repository.saveAndFlush(saved);

        ArticleReview found = repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, 9).orElseThrow();
        assertThat(found.getState()).isEqualTo(ArticleReviewState.IN_REVIEW);
        assertThat(found.getTestPostUrl()).isEqualTo("http://test.example/sample/");
        assertThat(found.getReviewedByUserId()).isEqualTo(8L);
        assertThat(found.getSubmittedByUserId()).isEqualTo(3L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT state FROM article_reviews WHERE project_id = ? AND github_pr_number = 9",
                String.class, PROJECT_ID)).isEqualTo("IN_REVIEW");
    }

    @Test
    @DisplayName("再提出(提出済みへ戻す)しても、記録したテスト環境URLとレビュー実施者は消えない(後続Issueが扱う)")
    void resubmissionKeepsLastReviewRecord() {
        ArticleReview saved = repository.saveAndFlush(review(9));
        saved.markInReview("http://test.example/sample/", 8L);
        repository.saveAndFlush(saved);
        saved.markSubmitted();
        repository.saveAndFlush(saved);

        ArticleReview found = repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, 9).orElseThrow();
        assertThat(found.getState()).isEqualTo(ArticleReviewState.SUBMITTED);
        assertThat(found.getTestPostUrl()).isEqualTo("http://test.example/sample/");
    }
}
