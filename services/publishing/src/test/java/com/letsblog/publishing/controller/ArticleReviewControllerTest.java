package com.letsblog.publishing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.domain.ArticleReview;
import com.letsblog.publishing.domain.ArticleReviewState;
import com.letsblog.publishing.dto.ArticleReviewPullRequestResponse;
import com.letsblog.publishing.repository.ArticleReviewRepository;
import com.letsblog.publishing.dto.ArticleReviewResponse;
import com.letsblog.publishing.dto.ArticleSubmissionRequest;
import com.letsblog.publishing.dto.ArticleSubmissionResponse;
import com.letsblog.publishing.github.GithubPullRequestClient;
import com.letsblog.publishing.service.ArticleReviewPublishService;
import com.letsblog.publishing.service.ArticleSubmissionService;
import com.letsblog.publishing.dto.PullRequestArticleResponse;
import com.letsblog.publishing.github.GithubPullRequestSummary;
import com.letsblog.publishing.service.PullRequestArticleService;
import com.letsblog.publishing.service.AdminAuthorizationService;
import com.letsblog.publishing.service.CurrentActorService;
import com.letsblog.publishing.service.ForbiddenException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** {@link ArticleReviewController}の単体テスト(issue #1337)。 */
@ExtendWith(MockitoExtension.class)
class ArticleReviewControllerTest {

    @Mock
    private AdminAuthorizationService adminAuthorizationService;

    @Mock
    private CurrentActorService currentActorService;

    @Mock
    private ProjectServiceClient projectServiceClient;

    @Mock
    private GithubPullRequestClient githubPullRequestClient;

    @Mock
    private PullRequestArticleService pullRequestArticleService;

    @Mock
    private ArticleSubmissionService articleSubmissionService;

    @Mock
    private ArticleReviewPublishService articleReviewPublishService;

    @Mock
    private ArticleReviewRepository articleReviewRepository;

    private ArticleReviewController controller() {
        return new ArticleReviewController(
                adminAuthorizationService, currentActorService, projectServiceClient, githubPullRequestClient,
                pullRequestArticleService, articleSubmissionService, articleReviewPublishService,
                articleReviewRepository);
    }

    private static ArticleReview review(Long projectId, int prNumber, ArticleReviewState state) {
        ArticleReview review = new ArticleReview();
        ReflectionTestUtils.setField(review, "projectId", projectId);
        ReflectionTestUtils.setField(review, "githubPrNumber", prNumber);
        ReflectionTestUtils.setField(review, "state", state);
        return review;
    }

    @Test
    @DisplayName("認可後に操作者でGitHubアクセス情報を解決し、開いているPRの一覧を既存の項目のまま返す")
    void listPullRequests() {
        GithubAccess access = new GithubAccess("t", "octo", "blog");
        when(currentActorService.getCurrentActorId()).thenReturn(3L);
        when(projectServiceClient.resolveGithubAccess(7L, 3L)).thenReturn(access);
        when(githubPullRequestClient.listOpenPullRequests(access)).thenReturn(List.of(
                new GithubPullRequestSummary(1, "記事", "article/a", "2026-09-01T00:00:00Z", "https://x/pull/1")));

        assertThat(controller().listPullRequests(7L)).containsExactly(
                new ArticleReviewPullRequestResponse(
                        1, "記事", "article/a", "2026-09-01T00:00:00Z", "https://x/pull/1", null));
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
    }

    @Test
    @DisplayName("記録のあるPRはそのArticleReviewStateの名前、記録の無いPRはnullを状態に載せる(issue #1677)")
    void listPullRequestsWithState() {
        GithubAccess access = new GithubAccess("t", "octo", "blog");
        when(currentActorService.getCurrentActorId()).thenReturn(3L);
        when(projectServiceClient.resolveGithubAccess(7L, 3L)).thenReturn(access);
        when(githubPullRequestClient.listOpenPullRequests(access)).thenReturn(List.of(
                new GithubPullRequestSummary(1, "a", "article/a", "t1", "u1"),
                new GithubPullRequestSummary(2, "b", "article/b", "t2", "u2"),
                new GithubPullRequestSummary(3, "c", "article/c", "t3", "u3")));
        when(articleReviewRepository.findByProjectId(7L)).thenReturn(List.of(
                review(7L, 1, ArticleReviewState.IN_REVIEW),
                review(7L, 3, ArticleReviewState.CHANGES_REQUESTED)));

        assertThat(controller().listPullRequests(7L))
                .extracting(ArticleReviewPullRequestResponse::number, ArticleReviewPullRequestResponse::state)
                .containsExactly(
                        tuple(1, ArticleReviewState.IN_REVIEW),
                        tuple(2, null),
                        tuple(3, ArticleReviewState.CHANGES_REQUESTED));
    }

    @Test
    @DisplayName("別プロジェクトに同じPR番号の記録があっても、状態はそのプロジェクトの記録だけで決まる(issue #1677)")
    void listPullRequestsIgnoresOtherProjects() {
        GithubAccess access = new GithubAccess("t", "octo", "blog");
        when(currentActorService.getCurrentActorId()).thenReturn(3L);
        when(projectServiceClient.resolveGithubAccess(7L, 3L)).thenReturn(access);
        when(githubPullRequestClient.listOpenPullRequests(access)).thenReturn(List.of(
                new GithubPullRequestSummary(1, "a", "article/a", "t1", "u1"),
                new GithubPullRequestSummary(2, "b", "article/b", "t2", "u2")));
        // 実際のリポジトリは projectId で絞るが、取り違えがあっても別プロジェクトの行は採用しない
        when(articleReviewRepository.findByProjectId(7L)).thenReturn(List.of(
                review(7L, 1, ArticleReviewState.SUBMITTED),
                review(8L, 2, ArticleReviewState.PUBLISHED)));

        assertThat(controller().listPullRequests(7L))
                .extracting(ArticleReviewPullRequestResponse::number, ArticleReviewPullRequestResponse::state)
                .containsExactly(tuple(1, ArticleReviewState.SUBMITTED), tuple(2, null));
        verify(articleReviewRepository).findByProjectId(7L);
        verify(articleReviewRepository, never()).findByProjectIdAndGithubPrNumber(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("メンバーでも管理者でもなければ拒否され、GitHubにもproject-serviceにも触れない")
    void forbidden() {
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        assertThatThrownBy(() -> controller().listPullRequests(7L)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(projectServiceClient, githubPullRequestClient);
    }

    @Test
    @DisplayName("操作者を解決できなければログインが必要として拒否する")
    void unresolvedActor() {
        when(currentActorService.getCurrentActorId()).thenReturn(null);

        assertThatThrownBy(() -> controller().listPullRequests(7L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ログイン");
        verifyNoInteractions(projectServiceClient, githubPullRequestClient);
    }

    @Test
    @DisplayName("記事取得は認可後に操作者でGitHubアクセス情報を解決し、PRの記事を返す")
    void getArticle() {
        GithubAccess access = new GithubAccess("t", "octo", "blog");
        PullRequestArticleResponse article = new PullRequestArticleResponse(
                "sample",
                new PullRequestArticleResponse.FrontMatter("T", "sample", null, List.of(), List.of(), null, null),
                "本文", List.of());
        when(currentActorService.getCurrentActorId()).thenReturn(3L);
        when(projectServiceClient.resolveGithubAccess(7L, 3L)).thenReturn(access);
        when(pullRequestArticleService.fetch(access, 201)).thenReturn(article);

        assertThat(controller().getArticle(7L, 201)).isEqualTo(article);
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
    }

    @Test
    @DisplayName("記事取得もメンバーでも管理者でもなければ拒否され、何にも触れない")
    void getArticleForbidden() {
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        assertThatThrownBy(() -> controller().getArticle(7L, 201)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(projectServiceClient, pullRequestArticleService);
    }

    @Test
    @DisplayName("記事取得も操作者を解決できなければログインが必要として拒否する")
    void getArticleUnresolvedActor() {
        when(currentActorService.getCurrentActorId()).thenReturn(null);

        assertThatThrownBy(() -> controller().getArticle(7L, 201))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ログイン");
        verifyNoInteractions(projectServiceClient, pullRequestArticleService);
    }

    @Test
    @DisplayName("提出は認可後に操作者でGitHubアクセス情報を解決し、操作者のIDを添えて提出サービスへ渡す")
    void submit() {
        GithubAccess access = new GithubAccess("t", "octo", "blog");
        ArticleSubmissionRequest request = new ArticleSubmissionRequest("article/a", 12, "a");
        ArticleSubmissionResponse expected = new ArticleSubmissionResponse(
                9, "https://github.com/octo/blog/pull/9", ArticleReviewState.SUBMITTED, 3L, true);
        when(currentActorService.getCurrentActorId()).thenReturn(3L);
        when(projectServiceClient.resolveGithubAccess(7L, 3L)).thenReturn(access);
        when(articleSubmissionService.submit(7L, 3L, access, request)).thenReturn(expected);

        assertThat(controller().submit(7L, request)).isEqualTo(expected);
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
    }

    @Test
    @DisplayName("提出もメンバーでも管理者でもなければ拒否され、何にも触れない")
    void submitForbidden() {
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        assertThatThrownBy(() -> controller().submit(7L, new ArticleSubmissionRequest("article/a", 12, "a")))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(projectServiceClient, articleSubmissionService);
    }

    @Test
    @DisplayName("提出も操作者を解決できなければログインが必要として拒否する")
    void submitUnresolvedActor() {
        when(currentActorService.getCurrentActorId()).thenReturn(null);

        assertThatThrownBy(() -> controller().submit(7L, new ArticleSubmissionRequest("article/a", 12, "a")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ログイン");
        verifyNoInteractions(projectServiceClient, articleSubmissionService);
    }

    @Test
    @DisplayName("レビュー開始は認可後に操作者でGitHubアクセス情報を解決し、操作者のIDを添えてレビューサービスへ渡す(issue #1341)")
    void review() {
        GithubAccess access = new GithubAccess("t", "octo", "blog");
        ArticleReviewResponse expected = new ArticleReviewResponse(
                201, ArticleReviewState.IN_REVIEW, "http://test.example/sample/", 3L, "55");
        when(currentActorService.getCurrentActorId()).thenReturn(3L);
        when(projectServiceClient.resolveGithubAccess(7L, 3L)).thenReturn(access);
        when(articleReviewPublishService.review(7L, 3L, access, 201)).thenReturn(expected);

        assertThat(controller().review(7L, 201)).isEqualTo(expected);
        verify(adminAuthorizationService).requireProjectMemberOrAdmin(7L);
    }

    @Test
    @DisplayName("レビュー開始もメンバーでも管理者でもなければ拒否され、何にも触れない")
    void reviewForbidden() {
        doThrow(new ForbiddenException("拒否")).when(adminAuthorizationService).requireProjectMemberOrAdmin(7L);

        assertThatThrownBy(() -> controller().review(7L, 201)).isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(projectServiceClient, articleReviewPublishService);
    }

    @Test
    @DisplayName("レビュー開始も操作者を解決できなければログインが必要として拒否する")
    void reviewUnresolvedActor() {
        when(currentActorService.getCurrentActorId()).thenReturn(null);

        assertThatThrownBy(() -> controller().review(7L, 201))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ログイン");
        verifyNoInteractions(projectServiceClient, articleReviewPublishService);
    }
}
