package com.letsblog.publishing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.domain.ArticleReview;
import com.letsblog.publishing.domain.ArticleReviewState;
import com.letsblog.publishing.dto.ArticleRejectResponse;
import com.letsblog.publishing.dto.MyArticleReviewResponse;
import com.letsblog.publishing.github.GithubApiException;
import com.letsblog.publishing.github.GithubIssueComment;
import com.letsblog.publishing.github.GithubPullRequestClient;
import com.letsblog.publishing.repository.ArticleReviewRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link ArticleReviewFeedbackService}の単体テスト(issue #1344)。 */
@ExtendWith(MockitoExtension.class)
class ArticleReviewFeedbackServiceTest {

    private static final GithubAccess ACCESS = new GithubAccess("t", "octo", "blog");
    private static final long PROJECT_ID = 7L;
    private static final long ACTOR_ID = 3L;
    private static final int PR = 201;

    @Mock
    private ArticleReviewRepository repository;
    @Mock
    private GithubPullRequestClient githubClient;
    @Mock
    private ProjectServiceClient projectServiceClient;

    private ArticleReviewFeedbackService service() {
        return new ArticleReviewFeedbackService(repository, githubClient, projectServiceClient);
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

    private String postedBody() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(githubClient).createIssueComment(any(), anyInt(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("レビュー中のPRに、実施者のメールとIDと指摘を含む本文でコメントを投稿し、差し戻しへ遷移して実施者・時刻・コメントIDを記録する")
    void rejectsInReview() {
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        when(projectServiceClient.resolveGithubAccess(PROJECT_ID, 9L)).thenReturn(ACCESS);
        when(githubClient.createIssueComment(any(), anyInt(), anyString()))
                .thenReturn(new GithubIssueComment(5001L, "x"));

        ArticleRejectResponse response = service().reject(PROJECT_ID, 9L, "admin@example.com", PR, "見出しを直して");

        verify(githubClient).createIssueComment(org.mockito.ArgumentMatchers.eq(ACCESS), org.mockito.ArgumentMatchers.eq(PR), anyString());
        assertThat(postedBody())
                .contains("見出しを直して")
                .contains("admin@example.com")
                .contains("ID 9");
        assertThat(review.getState()).isEqualTo(ArticleReviewState.CHANGES_REQUESTED);
        assertThat(review.getRejectedByUserId()).isEqualTo(9L);
        assertThat(review.getRejectCommentId()).isEqualTo(5001L);
        assertThat(review.getRejectedAt()).isNotNull();
        verify(repository).save(review);
        assertThat(response.prNumber()).isEqualTo(PR);
        assertThat(response.state()).isEqualTo(ArticleReviewState.CHANGES_REQUESTED);
        assertThat(response.commentId()).isEqualTo(5001L);
        assertThat(response.rejectedByUserId()).isEqualTo(9L);
        assertThat(response.rejectedAt()).isEqualTo(review.getRejectedAt());
    }

    @Test
    @DisplayName("メールアドレスを解決できない実施者でも、ユーザーIDで実施者が判別できる本文にする")
    void bodyWithoutEmailStillNamesActorId() {
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        when(projectServiceClient.resolveGithubAccess(PROJECT_ID, 9L)).thenReturn(ACCESS);
        when(githubClient.createIssueComment(any(), anyInt(), anyString()))
                .thenReturn(new GithubIssueComment(5002L, "x"));

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
        verifyNoInteractions(githubClient, projectServiceClient);
        verify(repository, never()).save(any());
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
        verifyNoInteractions(githubClient, projectServiceClient);
        verify(repository, never()).save(any());
        assertThat(review.getState()).isEqualTo(state);
        assertThat(review.getRejectedByUserId()).isNull();
    }

    @Test
    @DisplayName("コメント投稿が失敗したら状態は進まず保存もされない")
    void commentFailureKeepsState() {
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        when(projectServiceClient.resolveGithubAccess(PROJECT_ID, 9L)).thenReturn(ACCESS);
        when(githubClient.createIssueComment(any(), anyInt(), anyString()))
                .thenThrow(new GithubApiException("失敗"));

        assertThatThrownBy(() -> service().reject(PROJECT_ID, 9L, "a@b", PR, "x"))
                .isInstanceOf(GithubApiException.class);
        assertThat(review.getState()).isEqualTo(ArticleReviewState.IN_REVIEW);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("自分宛の一覧は提出者で絞った行を返し、差し戻しにはGitHubから取得した指摘本文と差し戻し時刻が付く")
    void myReviewsIncludesRejectComment() {
        ArticleReview rejected = review(ArticleReviewState.CHANGES_REQUESTED);
        rejected.markChangesRequested(9L, 5001L);
        when(repository.findByProjectIdAndSubmittedByUserIdOrderBySubmittedAtDesc(PROJECT_ID, ACTOR_ID))
                .thenReturn(List.of(rejected));
        when(projectServiceClient.resolveGithubAccess(PROJECT_ID, ACTOR_ID)).thenReturn(ACCESS);
        when(githubClient.findIssueComment(ACCESS, 5001L))
                .thenReturn(Optional.of(new GithubIssueComment(5001L, "見出しを直して")));

        List<MyArticleReviewResponse> result = service().myReviews(PROJECT_ID, ACTOR_ID);

        assertThat(result).hasSize(1);
        MyArticleReviewResponse row = result.get(0);
        assertThat(row.prNumber()).isEqualTo(PR);
        assertThat(row.articleSlug()).isEqualTo("sample");
        assertThat(row.state()).isEqualTo(ArticleReviewState.CHANGES_REQUESTED);
        assertThat(row.submittedAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 9, 0));
        assertThat(row.rejectComment()).isEqualTo("見出しを直して");
        assertThat(row.rejectedAt()).isEqualTo(rejected.getRejectedAt());
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
        verifyNoInteractions(githubClient, projectServiceClient);
    }

    @Test
    @DisplayName("提出した記事が無ければ空の一覧を返す")
    void myReviewsEmpty() {
        when(repository.findByProjectIdAndSubmittedByUserIdOrderBySubmittedAtDesc(PROJECT_ID, ACTOR_ID))
                .thenReturn(List.of());

        assertThat(service().myReviews(PROJECT_ID, ACTOR_ID)).isEmpty();
        verifyNoInteractions(githubClient, projectServiceClient);
    }

    @Test
    @DisplayName("GitHub側で指摘コメントが消されていても一覧は返り、指摘本文だけが空になる")
    void myReviewsDeletedComment() {
        ArticleReview rejected = review(ArticleReviewState.CHANGES_REQUESTED);
        rejected.markChangesRequested(9L, 5001L);
        when(repository.findByProjectIdAndSubmittedByUserIdOrderBySubmittedAtDesc(PROJECT_ID, ACTOR_ID))
                .thenReturn(List.of(rejected));
        when(projectServiceClient.resolveGithubAccess(PROJECT_ID, ACTOR_ID)).thenReturn(ACCESS);
        when(githubClient.findIssueComment(ACCESS, 5001L)).thenReturn(Optional.empty());

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
        verify(githubClient, never()).findIssueComment(any(), anyLong());
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
        when(projectServiceClient.resolveGithubAccess(PROJECT_ID, ACTOR_ID)).thenReturn(ACCESS);
        when(githubClient.findIssueComment(ACCESS, 5001L))
                .thenReturn(Optional.of(new GithubIssueComment(5001L, "一つ目")));
        when(githubClient.findIssueComment(ACCESS, 5002L))
                .thenReturn(Optional.of(new GithubIssueComment(5002L, "二つ目")));

        List<MyArticleReviewResponse> result = service().myReviews(PROJECT_ID, ACTOR_ID);

        assertThat(result).extracting(MyArticleReviewResponse::rejectComment).containsExactly("一つ目", "二つ目");
        verify(projectServiceClient, org.mockito.Mockito.times(1)).resolveGithubAccess(PROJECT_ID, ACTOR_ID);
    }

    @Test
    @DisplayName("メールアドレスが空白の実施者でも、ユーザーIDで実施者が判別できる本文にする")
    void bodyWithBlankEmail() {
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        when(projectServiceClient.resolveGithubAccess(PROJECT_ID, 9L)).thenReturn(ACCESS);
        when(githubClient.createIssueComment(any(), anyInt(), anyString()))
                .thenReturn(new GithubIssueComment(5003L, "x"));

        service().reject(PROJECT_ID, 9L, "  ", PR, "直して");

        assertThat(postedBody()).contains("ID 9").doesNotContain("()");
    }
}
