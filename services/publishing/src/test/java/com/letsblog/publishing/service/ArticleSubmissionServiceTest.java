package com.letsblog.publishing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.domain.ArticleReview;
import com.letsblog.publishing.domain.ArticleReviewState;
import com.letsblog.publishing.dto.ArticleSubmissionRequest;
import com.letsblog.publishing.dto.ArticleSubmissionResponse;
import com.letsblog.publishing.github.GithubApiException;
import com.letsblog.publishing.repository.ArticleReviewRepository;
import com.letsblog.publishing.testsupport.GithubStubs;
import com.letsblog.publishing.testsupport.PublishingWorld;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link ArticleSubmissionService}の単体テスト(issue #1339)。
 *
 * <p>issue #1815で書き直し: GithubPullRequestClientは実物を使い、GitHubへのHTTP({@code FakeHttpServer})だけを
 * 差し替える。{@link ArticleReviewRepository}はDBアクセスそのもの(Spring Dataのインタフェース)なのでMockito。
 */
class ArticleSubmissionServiceTest {

    private static final GithubAccess ACCESS = new GithubAccess("t", "octo", "blog");
    private static final ArticleSubmissionRequest REQUEST =
            new ArticleSubmissionRequest("article/sample", 12, "sample");

    private PublishingWorld world;
    private ArticleReviewRepository repository;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        repository = mock(ArticleReviewRepository.class);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private ArticleSubmissionService service() {
        return new ArticleSubmissionService(world.githubPullRequestClient, repository);
    }

    private JsonNode createdPullRequestBody() throws Exception {
        var requests = world.github.requests("POST", "/repos/octo/blog/pulls");
        assertThat(requests).hasSize(1);
        return world.mapper.readTree(requests.get(0).body());
    }

    @Test
    @DisplayName("ブランチが無ければブランチが見つからないとして失敗し、PR作成も記録もしない")
    void branchNotFound() {
        GithubStubs.branch(world.github, "octo", "blog", "article/sample", false);

        assertThatThrownBy(() -> service().submit(7L, 3L, ACCESS, REQUEST))
                .isInstanceOf(BranchNotFoundException.class)
                .hasMessageContaining("ブランチが見つかりません")
                .hasMessageContaining("article/sample");
        assertThat(world.github.requests("POST", ".*")).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("開いているPRが無ければdefault_branchをbaseにPRを作り、Closes付きの本文で提出者を記録する")
    void createsPullRequestAndRecordsSubmitter() throws Exception {
        GithubStubs.branch(world.github, "octo", "blog", "article/sample", true);
        GithubStubs.openPullRequests(world.github, "octo", "blog", 5, "other", "article/other");
        GithubStubs.defaultBranch(world.github, "octo", "blog", "develop");
        GithubStubs.createPullRequest(world.github, "octo", "blog", 9, "article/sample");
        when(repository.findByProjectIdAndGithubPrNumber(7L, 9)).thenReturn(Optional.empty());

        ArticleSubmissionResponse response = service().submit(7L, 3L, ACCESS, REQUEST);

        JsonNode body = createdPullRequestBody();
        assertThat(body.path("head").asText()).isEqualTo("article/sample");
        assertThat(body.path("base").asText()).isEqualTo("develop");
        assertThat(body.path("title").asText()).contains("sample").contains("#12");
        assertThat(body.path("body").asText()).contains("Closes #12");

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
        GithubStubs.branch(world.github, "octo", "blog", "article/sample", true);
        GithubStubs.openPullRequests(world.github, "octo", "blog",
                4, "other", "article/other", 5, "mine", "article/sample");
        when(repository.findByProjectIdAndGithubPrNumber(7L, 5)).thenReturn(Optional.of(existing));

        ArticleSubmissionResponse response = service().submit(7L, 3L, ACCESS, REQUEST);

        assertThat(world.github.requests("POST", ".*")).isEmpty();
        assertThat(world.github.requests("GET", "/repos/octo/blog")).as("default_branchも引かない").isEmpty();
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
        GithubStubs.branch(world.github, "octo", "blog", "article/sample", true);
        GithubStubs.openPullRequests(world.github, "octo", "blog", 5, "mine", "article/sample");
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
        GithubStubs.branch(world.github, "octo", "blog", "article/sample", true);
        GithubStubs.openPullRequests(world.github, "octo", "blog");
        GithubStubs.defaultBranch(world.github, "octo", "blog", "main");
        world.github.status("POST", "/repos/octo/blog/pulls", 422, "{\"message\":\"Validation Failed\"}");

        assertThatThrownBy(() -> service().submit(7L, 3L, ACCESS, REQUEST))
                .isInstanceOf(GithubApiException.class);
        verifyNoMoreInteractions(repository);
    }
}
