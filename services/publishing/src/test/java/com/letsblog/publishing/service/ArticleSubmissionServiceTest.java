package com.letsblog.publishing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.domain.ArticleReview;
import com.letsblog.publishing.domain.ArticleReviewState;
import com.letsblog.publishing.dto.ArticleSubmissionRequest;
import com.letsblog.publishing.dto.ArticleSubmissionResponse;
import com.letsblog.publishing.github.GithubApiException;
import com.letsblog.publishing.github.GithubPullRequestClient;
import com.letsblog.publishing.github.GithubPullRequestSummary;
import com.letsblog.publishing.repository.ArticleReviewRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link ArticleSubmissionService}の単体テスト(issue #1339)。 */
@ExtendWith(MockitoExtension.class)
class ArticleSubmissionServiceTest {

    private static final GithubAccess ACCESS = new GithubAccess("t", "octo", "blog");
    private static final ArticleSubmissionRequest REQUEST =
            new ArticleSubmissionRequest("article/sample", 12, "sample");

    @Mock
    private GithubPullRequestClient githubPullRequestClient;

    @Mock
    private ArticleReviewRepository repository;

    private ArticleSubmissionService service() {
        return new ArticleSubmissionService(githubPullRequestClient, repository);
    }

    private static GithubPullRequestSummary pr(int number, String head) {
        return new GithubPullRequestSummary(number, "t", head, "2026-09-01T00:00:00Z",
                "https://github.com/octo/blog/pull/" + number);
    }

    @Test
    @DisplayName("ブランチが無ければブランチが見つからないとして失敗し、PR作成も記録もしない")
    void branchNotFound() {
        when(githubPullRequestClient.branchExists(ACCESS, "article/sample")).thenReturn(false);

        assertThatThrownBy(() -> service().submit(7L, 3L, ACCESS, REQUEST))
                .isInstanceOf(BranchNotFoundException.class)
                .hasMessageContaining("ブランチが見つかりません")
                .hasMessageContaining("article/sample");
        verify(githubPullRequestClient, never()).createPullRequest(any(), anyString(), anyString(), anyString(),
                anyString());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("開いているPRが無ければdefault_branchをbaseにPRを作り、Closes付きの本文で提出者を記録する")
    void createsPullRequestAndRecordsSubmitter() {
        when(githubPullRequestClient.branchExists(ACCESS, "article/sample")).thenReturn(true);
        when(githubPullRequestClient.listOpenPullRequests(ACCESS)).thenReturn(List.of(pr(5, "article/other")));
        when(githubPullRequestClient.getDefaultBranch(ACCESS)).thenReturn("develop");
        when(githubPullRequestClient.createPullRequest(
                        any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(pr(9, "article/sample"));
        when(repository.findByProjectIdAndGithubPrNumber(7L, 9)).thenReturn(Optional.empty());

        ArticleSubmissionResponse response = service().submit(7L, 3L, ACCESS, REQUEST);

        ArgumentCaptor<String> title = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(githubPullRequestClient).createPullRequest(
                org.mockito.ArgumentMatchers.eq(ACCESS), title.capture(), body.capture(),
                org.mockito.ArgumentMatchers.eq("article/sample"), org.mockito.ArgumentMatchers.eq("develop"));
        assertThat(title.getValue()).contains("sample").contains("#12");
        assertThat(body.getValue()).contains("Closes #12");

        ArgumentCaptor<ArticleReview> saved = ArgumentCaptor.forClass(ArticleReview.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getProjectId()).isEqualTo(7L);
        assertThat(saved.getValue().getGithubPrNumber()).isEqualTo(9);
        assertThat(saved.getValue().getGithubIssueNumber()).isEqualTo(12);
        assertThat(saved.getValue().getArticleSlug()).isEqualTo("sample");
        assertThat(saved.getValue().getSubmittedByUserId()).isEqualTo(3L);
        assertThat(saved.getValue().getState()).isEqualTo(ArticleReviewState.SUBMITTED);

        assertThat(response.prNumber()).isEqualTo(9);
        assertThat(response.url()).isEqualTo("https://github.com/octo/blog/pull/9");
        assertThat(response.state()).isEqualTo(ArticleReviewState.SUBMITTED);
        assertThat(response.submittedByUserId()).isEqualTo(3L);
        assertThat(response.created()).isTrue();
    }

    @Test
    @DisplayName("同じheadに開いているPRがあれば作らず、その番号を返して状態を提出済みへ戻す(提出者は元のまま)")
    void resubmitReusesOpenPullRequest() {
        ArticleReview existing = new ArticleReview();
        existing.setProjectId(7L);
        existing.setGithubPrNumber(5);
        existing.setSubmittedByUserId(2L);
        existing.setState(ArticleReviewState.CHANGES_REQUESTED);
        when(githubPullRequestClient.branchExists(ACCESS, "article/sample")).thenReturn(true);
        when(githubPullRequestClient.listOpenPullRequests(ACCESS))
                .thenReturn(List.of(pr(4, "article/other"), pr(5, "article/sample")));
        when(repository.findByProjectIdAndGithubPrNumber(7L, 5)).thenReturn(Optional.of(existing));

        ArticleSubmissionResponse response = service().submit(7L, 3L, ACCESS, REQUEST);

        verify(githubPullRequestClient, never()).createPullRequest(any(), anyString(), anyString(), anyString(),
                anyString());
        verify(githubPullRequestClient, never()).getDefaultBranch(any());
        verify(repository).save(existing);
        assertThat(existing.getState()).isEqualTo(ArticleReviewState.SUBMITTED);
        assertThat(existing.getSubmittedByUserId()).isEqualTo(2L);
        assertThat(response.prNumber()).isEqualTo(5);
        assertThat(response.state()).isEqualTo(ArticleReviewState.SUBMITTED);
        assertThat(response.submittedByUserId()).isEqualTo(2L);
        assertThat(response.created()).isFalse();
    }

    @Test
    @DisplayName("開いているPRはあるが記録が無い(拡張を介さず作られたPR)なら、呼んだ利用者で記録を作る")
    void openPullRequestWithoutRecordGetsRecorded() {
        when(githubPullRequestClient.branchExists(ACCESS, "article/sample")).thenReturn(true);
        when(githubPullRequestClient.listOpenPullRequests(ACCESS)).thenReturn(List.of(pr(5, "article/sample")));
        when(repository.findByProjectIdAndGithubPrNumber(7L, 5)).thenReturn(Optional.empty());

        ArticleSubmissionResponse response = service().submit(7L, 3L, ACCESS, REQUEST);

        ArgumentCaptor<ArticleReview> saved = ArgumentCaptor.forClass(ArticleReview.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getSubmittedByUserId()).isEqualTo(3L);
        assertThat(saved.getValue().getGithubPrNumber()).isEqualTo(5);
        assertThat(saved.getValue().getState()).isEqualTo(ArticleReviewState.SUBMITTED);
        assertThat(response.created()).isFalse();
        assertThat(response.submittedByUserId()).isEqualTo(3L);
    }

    @Test
    @DisplayName("GitHubの失敗はそのまま伝播し、何も記録しない")
    void githubFailureIsNotRecorded() {
        when(githubPullRequestClient.branchExists(ACCESS, "article/sample")).thenReturn(true);
        when(githubPullRequestClient.listOpenPullRequests(ACCESS)).thenReturn(List.of());
        when(githubPullRequestClient.getDefaultBranch(ACCESS)).thenReturn("main");
        when(githubPullRequestClient.createPullRequest(
                        any(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new GithubApiException("作成に失敗"));

        assertThatThrownBy(() -> service().submit(7L, 3L, ACCESS, REQUEST))
                .isInstanceOf(GithubApiException.class);
        verifyNoMoreInteractions(repository);
    }
}
