package com.letsblog.publishing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.publishing.domain.ArticleReview;
import com.letsblog.publishing.domain.ArticleReviewState;
import com.letsblog.publishing.dto.ArticleRejectResponse;
import com.letsblog.publishing.dto.MyArticleReviewResponse;
import com.letsblog.publishing.github.GithubApiException;
import com.letsblog.publishing.repository.ArticleReviewRepository;
import com.letsblog.publishing.testsupport.GithubStubs;
import com.letsblog.publishing.testsupport.PublishingWorld;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;

/**
 * {@link ArticleReviewFeedbackService}の単体テスト(issue #1344)。
 *
 * <p>issue #1815で書き直し: GithubPullRequestClient・ProjectServiceClientは実物を使い、GitHubとproject-serviceへの
 * HTTP({@code FakeHttpServer})だけを差し替える。{@link ArticleReviewRepository}はDBアクセスそのものなのでMockito。
 */
class ArticleReviewFeedbackServiceTest {

    private static final long PROJECT_ID = 7L;
    private static final long ACTOR_ID = 3L;
    private static final int PR = 201;

    private PublishingWorld world;
    private ArticleReviewRepository repository;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        repository = mock(ArticleReviewRepository.class);
        world.stubGithubAccess(PROJECT_ID, "t", "octo", "blog");
        world.asUser("Bearer user-token");
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private ArticleReviewFeedbackService service() {
        return new ArticleReviewFeedbackService(repository, world.githubPullRequestClient, world.projectServiceClient);
    }

    private static ArticleReview review(ArticleReviewState state) {
        ArticleReview review = new ArticleReview();
        review.setProjectId(PROJECT_ID);
        review.setGithubPrNumber(PR);
        review.setGithubIssueNumber(12);
        review.setArticleSlug("sample");
        review.setSubmittedByUserId(ACTOR_ID);
        review.setState(state);
        review.setSubmittedAt(LocalDateTime.of(2026, 10, 1, 9, 0));
        return review;
    }

    private String postedBody() throws Exception {
        var requests = world.github.requests("POST", "/repos/octo/blog/issues/" + PR + "/comments");
        assertThat(requests).hasSize(1);
        JsonNode body = world.mapper.readTree(requests.get(0).body());
        return body.path("body").asText();
    }

    private void assertNoRemoteCalls() {
        assertThat(world.github.requests()).isEmpty();
        assertThat(world.project.requests()).isEmpty();
    }

    @Test
    @DisplayName("レビュー中のPRに、実施者のメールとIDと指摘を含む本文でコメントを投稿し、差し戻しへ遷移して実施者・時刻・コメントIDを記録する")
    void rejectsInReview() throws Exception {
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        GithubStubs.createIssueComment(world.github, "octo", "blog", PR, 5001L);

        ArticleRejectResponse response = service().reject(PROJECT_ID, 9L, "admin@example.com", PR, "見出しを直して");

        assertThat(postedBody())
                .contains("見出しを直して")
                .contains("admin@example.com")
                .contains("ID 9");
        assertThat(world.github.last().header("Authorization")).isEqualTo("Bearer t");
        assertThat(review.getState()).isEqualTo(ArticleReviewState.CHANGES_REQUESTED);
        assertThat(review.getRejectedByUserId()).isEqualTo(9L);
        assertThat(review.getRejectCommentId()).isEqualTo(5001L);
        assertThat(review.getRejectedAt()).isNotNull();
        verify(repository).save(review);
        assertThat(response.prNumber()).isEqualTo(PR);
        assertThat(response.state()).isEqualTo(ArticleReviewState.CHANGES_REQUESTED);
        assertThat(response.commentId()).isEqualTo(5001L);
        assertThat(response.rejectedByUserId()).isEqualTo(9L);
        assertThat(response.rejectedAt()).isEqualTo(review.getRejectedAt().toInstant(java.time.ZoneOffset.UTC));
    }

    @Test
    @DisplayName("メールアドレスを解決できない実施者でも、ユーザーIDで実施者が判別できる本文にする")
    void bodyWithoutEmailStillNamesActorId() throws Exception {
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        GithubStubs.createIssueComment(world.github, "octo", "blog", PR, 5002L);

        service().reject(PROJECT_ID, 9L, null, PR, "直して");

        assertThat(postedBody()).contains("ID 9").contains("直して").doesNotContain("null");
    }

    @Test
    @DisplayName("提出されていないPRは404相当で拒否し、GitHubにもコメントしない")
    void notSubmitted() {
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().reject(PROJECT_ID, 9L, "a@b", PR, "x"))
                .isInstanceOf(ArticleReviewNotFoundException.class)
                .hasMessageContaining("#" + PR);
        assertNoRemoteCalls();
        verify(repository, never()).save(ArgumentMatchers.any());
    }

    @ParameterizedTest
    @EnumSource(value = ArticleReviewState.class, names = "IN_REVIEW", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("レビュー中でないPRは差し戻せず、コメントも状態変更も行われない")
    void notInReview(ArticleReviewState state) {
        ArticleReview review = review(state);
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service().reject(PROJECT_ID, 9L, "a@b", PR, "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("レビュー中");
        assertNoRemoteCalls();
        verify(repository, never()).save(ArgumentMatchers.any());
        assertThat(review.getState()).isEqualTo(state);
        assertThat(review.getRejectedByUserId()).isNull();
    }

    @Test
    @DisplayName("コメント投稿が失敗したら状態は進まず保存もされない")
    void commentFailureKeepsState() {
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        world.github.status("POST", "/repos/octo/blog/issues/" + PR + "/comments", 500, "{\"message\":\"失敗\"}");

        assertThatThrownBy(() -> service().reject(PROJECT_ID, 9L, "a@b", PR, "x"))
                .isInstanceOf(GithubApiException.class);
        assertThat(review.getState()).isEqualTo(ArticleReviewState.IN_REVIEW);
        verify(repository, never()).save(ArgumentMatchers.any());
    }

    @Test
    @DisplayName("自分宛の一覧は提出者で絞った行を返し、差し戻しにはGitHubから取得した指摘本文と差し戻し時刻が付く")
    void myReviewsIncludesRejectComment() {
        ArticleReview rejected = review(ArticleReviewState.CHANGES_REQUESTED);
        rejected.markChangesRequested(9L, 5001L);
        when(repository.findByProjectIdAndSubmittedByUserIdOrderBySubmittedAtDesc(PROJECT_ID, ACTOR_ID))
                .thenReturn(List.of(rejected));
        GithubStubs.issueComment(world.github, "octo", "blog", 5001L, "見出しを直して");

        List<MyArticleReviewResponse> result = service().myReviews(PROJECT_ID, ACTOR_ID);

        assertThat(result).hasSize(1);
        MyArticleReviewResponse row = result.get(0);
        assertThat(row.prNumber()).isEqualTo(PR);
        assertThat(row.articleSlug()).isEqualTo("sample");
        assertThat(row.state()).isEqualTo(ArticleReviewState.CHANGES_REQUESTED);
        assertThat(row.submittedAt()).isEqualTo(java.time.Instant.parse("2026-10-01T09:00:00Z"));
        assertThat(row.rejectComment()).isEqualTo("見出しを直して");
        assertThat(row.rejectedAt()).isEqualTo(rejected.getRejectedAt().toInstant(java.time.ZoneOffset.UTC));
    }

    @Test
    @DisplayName("差し戻し以外の行はGitHubを呼ばず、アクセス情報も解決せず、指摘と差し戻し時刻は空")
    void myReviewsNonRejectedRows() {
        when(repository.findByProjectIdAndSubmittedByUserIdOrderBySubmittedAtDesc(PROJECT_ID, ACTOR_ID))
                .thenReturn(List.of(review(ArticleReviewState.IN_REVIEW), review(ArticleReviewState.SUBMITTED)));

        List<MyArticleReviewResponse> result = service().myReviews(PROJECT_ID, ACTOR_ID);

        assertThat(result).extracting(MyArticleReviewResponse::state)
                .containsExactly(ArticleReviewState.IN_REVIEW, ArticleReviewState.SUBMITTED);
        assertThat(result).allSatisfy(row -> {
            assertThat(row.rejectComment()).isNull();
            assertThat(row.rejectedAt()).isNull();
        });
        assertNoRemoteCalls();
    }

    @Test
    @DisplayName("提出した記事が無ければ空の一覧を返す")
    void myReviewsEmpty() {
        when(repository.findByProjectIdAndSubmittedByUserIdOrderBySubmittedAtDesc(PROJECT_ID, ACTOR_ID))
                .thenReturn(List.of());

        assertThat(service().myReviews(PROJECT_ID, ACTOR_ID)).isEmpty();
        assertNoRemoteCalls();
    }

    @Test
    @DisplayName("GitHub側で指摘コメントが消されていても一覧は返り、指摘本文だけが空になる")
    void myReviewsDeletedComment() {
        ArticleReview rejected = review(ArticleReviewState.CHANGES_REQUESTED);
        rejected.markChangesRequested(9L, 5001L);
        when(repository.findByProjectIdAndSubmittedByUserIdOrderBySubmittedAtDesc(PROJECT_ID, ACTOR_ID))
                .thenReturn(List.of(rejected));
        world.github.status("GET", "/repos/octo/blog/issues/comments/5001", 404, "{\"message\":\"Not Found\"}");

        MyArticleReviewResponse row = service().myReviews(PROJECT_ID, ACTOR_ID).get(0);

        assertThat(row.state()).isEqualTo(ArticleReviewState.CHANGES_REQUESTED);
        assertThat(row.rejectComment()).isNull();
        assertThat(row.rejectedAt()).isNotNull();
    }

    @Test
    @DisplayName("コメントIDが記録されていない差し戻しの行はGitHubを呼ばず、指摘本文は空")
    void myReviewsRejectedWithoutCommentId() {
        ArticleReview rejected = review(ArticleReviewState.CHANGES_REQUESTED);
        when(repository.findByProjectIdAndSubmittedByUserIdOrderBySubmittedAtDesc(PROJECT_ID, ACTOR_ID))
                .thenReturn(List.of(rejected));

        MyArticleReviewResponse row = service().myReviews(PROJECT_ID, ACTOR_ID).get(0);

        assertThat(row.rejectComment()).isNull();
        assertThat(world.github.requests("GET", ".*/issues/comments/.*")).isEmpty();
    }

    @Test
    @DisplayName("差し戻しの行が複数あっても、GitHubのアクセス情報は1度だけ解決して使い回す")
    void myReviewsResolvesAccessOnce() {
        ArticleReview first = review(ArticleReviewState.CHANGES_REQUESTED);
        first.markChangesRequested(9L, 5001L);
        ArticleReview second = review(ArticleReviewState.CHANGES_REQUESTED);
        second.markChangesRequested(9L, 5002L);
        when(repository.findByProjectIdAndSubmittedByUserIdOrderBySubmittedAtDesc(PROJECT_ID, ACTOR_ID))
                .thenReturn(List.of(first, second));
        GithubStubs.issueComment(world.github, "octo", "blog", 5001L, "一つ目");
        GithubStubs.issueComment(world.github, "octo", "blog", 5002L, "二つ目");

        List<MyArticleReviewResponse> result = service().myReviews(PROJECT_ID, ACTOR_ID);

        assertThat(result).extracting(MyArticleReviewResponse::rejectComment).containsExactly("一つ目", "二つ目");
        assertThat(world.project.requests("GET", "/api/internal/project/projects/7/github-access")).hasSize(1);
    }

    @Test
    @DisplayName("メールアドレスが空白の実施者でも、ユーザーIDで実施者が判別できる本文にする")
    void bodyWithBlankEmail() throws Exception {
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        GithubStubs.createIssueComment(world.github, "octo", "blog", PR, 5003L);

        service().reject(PROJECT_ID, 9L, "  ", PR, "直して");

        assertThat(postedBody()).contains("ID 9").doesNotContain("()");
    }
}
