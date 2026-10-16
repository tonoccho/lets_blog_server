package com.letsblog.publishing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.letsblog.publishing.domain.ArticleReview;
import com.letsblog.publishing.domain.ArticleReviewState;
import com.letsblog.publishing.dto.ArticleReviewPullRequestResponse;
import com.letsblog.publishing.dto.ArticleReviewResponse;
import com.letsblog.publishing.dto.ArticleSubmissionRequest;
import com.letsblog.publishing.dto.ArticleSubmissionResponse;
import com.letsblog.publishing.dto.PullRequestArticleResponse;
import com.letsblog.publishing.repository.ArticleReviewRepository;
import com.letsblog.publishing.service.ArticleReviewPublishService;
import com.letsblog.publishing.service.ArticleSubmissionService;
import com.letsblog.publishing.service.ForbiddenException;
import com.letsblog.publishing.service.PullRequestArticleService;
import com.letsblog.publishing.testsupport.GithubStubs;
import com.letsblog.publishing.testsupport.PublishingWorld;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ArticleReviewController}の単体テスト(issue #1337)。
 *
 * <p>issue #1816で書き直し: AdminAuthorizationService・CurrentActorService・ProjectServiceClient・
 * GithubPullRequestClient・PullRequestArticleService・ArticleSubmissionService・ArticleReviewPublishServiceは
 * 実物を使い、外部との通信(identity・project・content・media-serviceとGitHubへのHTTPは{@code FakeHttpServer}、
 * WordPressへのSSHは{@code FakeWordPress})だけを差し替える。{@link ArticleReviewRepository}はDBアクセスそのものなので
 * Mockito。
 */
class ArticleReviewControllerTest {

    private static final long PROJECT_ID = 7L;
    private static final long ACTOR_ID = 3L;
    private static final int PR = 201;
    private static final String ADMIN = "Bearer admin-token";
    private static final String MEMBER = "Bearer member-token";

    private PublishingWorld world;
    private ArticleReviewRepository repository;
    private ArticleReviewController controller;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld().withTestAndProductionSites(PROJECT_ID, 22L, "test-site", 33L, "prod-site");
        world.stubGithubAccess(PROJECT_ID, "t", "octo", "blog");
        repository = mock(ArticleReviewRepository.class);
        PullRequestArticleService articleService = new PullRequestArticleService(world.githubPullRequestClient);
        controller = new ArticleReviewController(world.adminAuthorizationService, world.currentActorService,
                world.projectServiceClient, world.githubPullRequestClient, articleService,
                new ArticleSubmissionService(world.githubPullRequestClient, repository),
                new ArticleReviewPublishService(world.projectServiceClient, articleService,
                        world.contentServiceClient, world.postPublishService, repository),
                repository);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private void asAdmin() {
        world.stubActor(ADMIN, ACTOR_ID, "admin", "a@example.com").asUser(ADMIN);
    }

    private void asNonMember() {
        world.stubActor(MEMBER, 10L, "user", "m@example.com").stubProjectMember(PROJECT_ID, 10L, false)
                .asUser(MEMBER);
    }

    private void asAdminWithoutId() {
        world.stubActor(ADMIN, null, "admin", "a@example.com").asUser(ADMIN);
    }

    private static ArticleReview review(Long projectId, int prNumber, ArticleReviewState state) {
        ArticleReview review = new ArticleReview();
        review.setProjectId(projectId);
        review.setGithubPrNumber(prNumber);
        review.setState(state);
        return review;
    }

    private void assertNothingTouched() {
        assertThat(world.github.requests()).isEmpty();
        assertThat(world.ssh.posts).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("認可後に操作者でGitHubアクセス情報を解決し、開いているPRの一覧を既存の項目のまま返す")
    void listPullRequests() {
        asAdmin();
        GithubStubs.openPullRequests(world.github, "octo", "blog",
                1, "記事", "article/a");

        List<ArticleReviewPullRequestResponse> result = controller.listPullRequests(PROJECT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).number()).isEqualTo(1);
        assertThat(result.get(0).title()).isEqualTo("記事");
        assertThat(result.get(0).state()).isNull();
        assertThat(world.github.last().header("Authorization")).isEqualTo("Bearer t");
    }

    @Test
    @DisplayName("記録のあるPRはそのArticleReviewStateの名前、記録の無いPRはnullを状態に載せる(issue #1677)")
    void listPullRequestsWithState() {
        asAdmin();
        GithubStubs.openPullRequests(world.github, "octo", "blog",
                1, "a", "article/a", 2, "b", "article/b", 3, "c", "article/c");
        when(repository.findByProjectId(PROJECT_ID)).thenReturn(List.of(
                review(PROJECT_ID, 1, ArticleReviewState.IN_REVIEW),
                review(PROJECT_ID, 3, ArticleReviewState.CHANGES_REQUESTED)));

        assertThat(controller.listPullRequests(PROJECT_ID))
                .extracting(ArticleReviewPullRequestResponse::number, ArticleReviewPullRequestResponse::state)
                .containsExactly(
                        tuple(1, ArticleReviewState.IN_REVIEW),
                        tuple(2, null),
                        tuple(3, ArticleReviewState.CHANGES_REQUESTED));
    }

    @Test
    @DisplayName("別プロジェクトに同じPR番号の記録があっても、状態はそのプロジェクトの記録だけで決まる(issue #1677)")
    void listPullRequestsIgnoresOtherProjects() {
        asAdmin();
        GithubStubs.openPullRequests(world.github, "octo", "blog",
                1, "a", "article/a", 2, "b", "article/b");
        // 実際のリポジトリは projectId で絞るが、取り違えがあっても別プロジェクトの行は採用しない
        when(repository.findByProjectId(PROJECT_ID)).thenReturn(List.of(
                review(PROJECT_ID, 1, ArticleReviewState.SUBMITTED),
                review(8L, 2, ArticleReviewState.PUBLISHED)));

        assertThat(controller.listPullRequests(PROJECT_ID))
                .extracting(ArticleReviewPullRequestResponse::number, ArticleReviewPullRequestResponse::state)
                .containsExactly(tuple(1, ArticleReviewState.SUBMITTED), tuple(2, null));
        verify(repository).findByProjectId(PROJECT_ID);
        verify(repository, never()).findByProjectIdAndGithubPrNumber(
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("メンバーでも管理者でもなければ拒否され、GitHubにもproject-serviceにも触れない")
    void forbidden() {
        asNonMember();

        assertThatThrownBy(() -> controller.listPullRequests(PROJECT_ID)).isInstanceOf(ForbiddenException.class);
        assertNothingTouched();
        assertThat(world.project.requests()).isEmpty();
    }

    @Test
    @DisplayName("操作者を解決できなければログインが必要として拒否する")
    void unresolvedActor() {
        asAdminWithoutId();

        assertThatThrownBy(() -> controller.listPullRequests(PROJECT_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ログイン");
        assertNothingTouched();
    }

    @Test
    @DisplayName("記事取得は認可後に操作者でGitHubアクセス情報を解決し、PRの記事を返す")
    void getArticle() {
        asAdmin();
        GithubStubs.articlePullRequest(world.github, "octo", "blog", PR, "sha1", "article/sample", true, false,
                "sample", "---\ntitle: \"T\"\nslug: \"sample\"\n---\n本文", Map.of());

        PullRequestArticleResponse article = controller.getArticle(PROJECT_ID, PR);

        assertThat(article.slug()).isEqualTo("sample");
        assertThat(article.frontMatter().title()).isEqualTo("T");
        assertThat(article.body()).contains("本文");
    }

    @Test
    @DisplayName("記事取得もメンバーでも管理者でもなければ拒否され、何にも触れない")
    void getArticleForbidden() {
        asNonMember();

        assertThatThrownBy(() -> controller.getArticle(PROJECT_ID, PR)).isInstanceOf(ForbiddenException.class);
        assertNothingTouched();
    }

    @Test
    @DisplayName("記事取得も操作者を解決できなければログインが必要として拒否する")
    void getArticleUnresolvedActor() {
        asAdminWithoutId();

        assertThatThrownBy(() -> controller.getArticle(PROJECT_ID, PR))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ログイン");
        assertNothingTouched();
    }

    @Test
    @DisplayName("提出は認可後に操作者でGitHubアクセス情報を解決し、操作者のIDを添えて提出サービスへ渡す")
    void submit() {
        asAdmin();
        GithubStubs.branch(world.github, "octo", "blog", "article/a", true);
        GithubStubs.openPullRequests(world.github, "octo", "blog");
        GithubStubs.defaultBranch(world.github, "octo", "blog", "develop");
        GithubStubs.createPullRequest(world.github, "octo", "blog", 9, "article/a");
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, 9)).thenReturn(Optional.empty());

        ArticleSubmissionResponse response =
                controller.submit(PROJECT_ID, new ArticleSubmissionRequest("article/a", 12, "a"));

        assertThat(response.prNumber()).isEqualTo(9);
        assertThat(response.state()).isEqualTo(ArticleReviewState.SUBMITTED);
        assertThat(response.submittedByUserId()).isEqualTo(ACTOR_ID);
        assertThat(response.created()).isTrue();
    }

    @Test
    @DisplayName("提出もメンバーでも管理者でもなければ拒否され、何にも触れない")
    void submitForbidden() {
        asNonMember();

        assertThatThrownBy(() -> controller.submit(PROJECT_ID, new ArticleSubmissionRequest("article/a", 12, "a")))
                .isInstanceOf(ForbiddenException.class);
        assertNothingTouched();
    }

    @Test
    @DisplayName("提出も操作者を解決できなければログインが必要として拒否する")
    void submitUnresolvedActor() {
        asAdminWithoutId();

        assertThatThrownBy(() -> controller.submit(PROJECT_ID, new ArticleSubmissionRequest("article/a", 12, "a")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ログイン");
        assertNothingTouched();
    }

    @Test
    @DisplayName("レビュー開始は認可後に操作者でGitHubアクセス情報を解決し、操作者のIDを添えてレビューサービスへ渡す(issue #1341)")
    void review() {
        asAdmin();
        ArticleReview submitted = review(PROJECT_ID, PR, ArticleReviewState.SUBMITTED);
        submitted.setGithubIssueNumber(12);
        submitted.setArticleSlug("sample");
        submitted.setSubmittedByUserId(5L);
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(submitted));
        GithubStubs.articlePullRequest(world.github, "octo", "blog", PR, "sha1", "article/sample", true, false,
                "sample", "---\ntitle: \"T\"\nslug: \"sample\"\n---\n本文", Map.of());

        ArticleReviewResponse response = controller.review(PROJECT_ID, PR);

        assertThat(response.state()).isEqualTo(ArticleReviewState.IN_REVIEW);
        assertThat(response.reviewedByUserId()).isEqualTo(ACTOR_ID);
        assertThat(world.ssh.posts).hasSize(1);
        assertThat(world.ssh.posts.get(response.wpPostId()).slug).isEqualTo("sample");
        assertThat(submitted.getState()).isEqualTo(ArticleReviewState.IN_REVIEW);
    }

    @Test
    @DisplayName("レビュー開始もメンバーでも管理者でもなければ拒否され、何にも触れない")
    void reviewForbidden() {
        asNonMember();

        assertThatThrownBy(() -> controller.review(PROJECT_ID, PR)).isInstanceOf(ForbiddenException.class);
        assertNothingTouched();
    }

    @Test
    @DisplayName("レビュー開始も操作者を解決できなければログインが必要として拒否する")
    void reviewUnresolvedActor() {
        asAdminWithoutId();

        assertThatThrownBy(() -> controller.review(PROJECT_ID, PR))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ログイン");
        assertNothingTouched();
    }
}
