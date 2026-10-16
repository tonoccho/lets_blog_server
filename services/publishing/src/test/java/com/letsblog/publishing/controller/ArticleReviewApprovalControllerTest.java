package com.letsblog.publishing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.letsblog.publishing.domain.ArticleReview;
import com.letsblog.publishing.domain.ArticleReviewState;
import com.letsblog.publishing.dto.ArticleApproveResponse;
import com.letsblog.publishing.repository.ArticleReviewRepository;
import com.letsblog.publishing.service.ArticleReviewApprovalService;
import com.letsblog.publishing.service.ForbiddenException;
import com.letsblog.publishing.service.PullRequestArticleService;
import com.letsblog.publishing.testsupport.GithubStubs;
import com.letsblog.publishing.testsupport.PublishingWorld;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ArticleReviewApprovalController}の単体テスト(issue #1343)。
 *
 * <p>issue #1816で書き直し: AdminAuthorizationService・CurrentActorService・ProjectServiceClient・
 * ArticleReviewApprovalService(とその先のGitHub・WordPress)は実物を使い、外部との通信(identity・project・
 * content・media-serviceとGitHubへのHTTPは{@code FakeHttpServer}、WordPressへのSSHは{@code FakeWordPress})だけを
 * 差し替える。{@link ArticleReviewRepository}はDBアクセスそのものなのでMockito。
 */
class ArticleReviewApprovalControllerTest {

    private static final long PROJECT_ID = 7L;
    private static final long ACTOR_ID = 3L;
    private static final int PR = 201;
    private static final String HEAD_REF = "article/sample";
    private static final String ADMIN = "Bearer admin-token";
    private static final String MEMBER = "Bearer member-token";

    private PublishingWorld world;
    private ArticleReviewRepository repository;
    private ArticleReviewApprovalController controller;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld().withTestAndProductionSites(PROJECT_ID, 22L, "test-site", 33L, "prod-site");
        world.stubGithubAccess(PROJECT_ID, "t", "octo", "blog");
        repository = mock(ArticleReviewRepository.class);
        ArticleReviewApprovalService service = new ArticleReviewApprovalService(world.projectServiceClient,
                new PullRequestArticleService(world.githubPullRequestClient), world.contentServiceClient,
                world.postPublishService, repository, world.githubPullRequestClient);
        controller = new ArticleReviewApprovalController(world.adminAuthorizationService,
                world.currentActorService, world.projectServiceClient, service);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private ArticleReview inReview() {
        ArticleReview review = new ArticleReview();
        review.setProjectId(PROJECT_ID);
        review.setGithubPrNumber(PR);
        review.setGithubIssueNumber(12);
        review.setArticleSlug("sample");
        review.setSubmittedByUserId(5L);
        review.setState(ArticleReviewState.IN_REVIEW);
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        return review;
    }

    private void articleReadyToMerge() {
        String md = "---\ntitle: \"題\"\nslug: \"sample\"\nstatus: \"publish\"\n---\n本文";
        GithubStubs.articlePullRequest(world.github, "octo", "blog", PR, "sha1", HEAD_REF, true, false, "dir-slug",
                md, Map.of());
        GithubStubs.mergeOk(world.github, "octo", "blog", PR);
        GithubStubs.deleteBranchOk(world.github, "octo", "blog", HEAD_REF);
    }

    @Test
    @DisplayName("レビュー完了は認可後に、操作者のGitHubアクセスを解決してサービスへ渡す")
    void approve() {
        world.stubActor(ADMIN, ACTOR_ID, "admin", "admin@example.com").asUser(ADMIN);
        ArticleReview review = inReview();
        articleReadyToMerge();

        ArticleApproveResponse response = controller.approve(PROJECT_ID, PR);

        assertThat(response.state()).isEqualTo(ArticleReviewState.PUBLISHED);
        assertThat(response.prNumber()).isEqualTo(PR);
        assertThat(review.getState()).isEqualTo(ArticleReviewState.PUBLISHED);
        assertThat(review.getPublishedByUserId()).isEqualTo(ACTOR_ID);
        assertThat(world.github.requests("PUT", ".*/merge")).hasSize(1);
        assertThat(world.github.last().header("Authorization")).isEqualTo("Bearer t");
        assertThat(world.ssh.posts).hasSize(1);
    }

    @Test
    @DisplayName("プロジェクトのメンバーなら管理者でなくても承認できる")
    void memberCanApprove() {
        world.stubActor(MEMBER, 10L, "user", "m@example.com").stubProjectMember(PROJECT_ID, 10L, true)
                .asUser(MEMBER);
        inReview();
        articleReadyToMerge();

        assertThat(controller.approve(PROJECT_ID, PR).state()).isEqualTo(ArticleReviewState.PUBLISHED);
        assertThat(world.identity.requests("GET", "/api/internal/identity/projects/7/members/10")).isNotEmpty();
    }

    @Test
    @DisplayName("メンバーでも管理者でもなければ拒否され、GitHubにもWordPressにも触れない")
    void forbidden() {
        world.stubActor(MEMBER, 10L, "user", "m@example.com").stubProjectMember(PROJECT_ID, 10L, false)
                .asUser(MEMBER);

        assertThatThrownBy(() -> controller.approve(PROJECT_ID, PR)).isInstanceOf(ForbiddenException.class);

        assertThat(world.github.requests()).isEmpty();
        assertThat(world.ssh.posts).isEmpty();
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("操作者を解決できなければログインが必要として拒否する")
    void unresolvedActor() {
        // adminだがidが解決できない操作者(identity-serviceがidを返さない)。
        world.stubActor(ADMIN, null, "admin", "admin@example.com").asUser(ADMIN);

        assertThatThrownBy(() -> controller.approve(PROJECT_ID, PR))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ログイン");
        assertThat(world.github.requests()).isEmpty();
    }
}
