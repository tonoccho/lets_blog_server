package com.letsblog.publishing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.letsblog.publishing.domain.ArticleReview;
import com.letsblog.publishing.domain.ArticleReviewState;
import com.letsblog.publishing.dto.ArticleRejectRequest;
import com.letsblog.publishing.dto.ArticleRejectResponse;
import com.letsblog.publishing.dto.MyArticleReviewResponse;
import com.letsblog.publishing.repository.ArticleReviewRepository;
import com.letsblog.publishing.service.ArticleReviewFeedbackService;
import com.letsblog.publishing.service.ForbiddenException;
import com.letsblog.publishing.testsupport.GithubStubs;
import com.letsblog.publishing.testsupport.PublishingWorld;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ArticleReviewFeedbackController}の単体テスト(issue #1344)。
 *
 * <p>issue #1816で書き直し: AdminAuthorizationService・CurrentActorService・ArticleReviewFeedbackServiceは実物を使い、
 * 外部との通信(identity・project-serviceとGitHubへのHTTPは{@code FakeHttpServer})だけを差し替える。
 * {@link ArticleReviewRepository}はDBアクセスそのものなのでMockito。
 */
class ArticleReviewFeedbackControllerTest {

    private static final long PROJECT_ID = 7L;
    private static final long ACTOR_ID = 3L;
    private static final int PR = 201;
    private static final String ADMIN = "Bearer admin-token";
    private static final String MEMBER = "Bearer member-token";

    private PublishingWorld world;
    private ArticleReviewRepository repository;
    private ArticleReviewFeedbackController controller;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        world.stubGithubAccess(PROJECT_ID, "t", "octo", "blog");
        repository = mock(ArticleReviewRepository.class);
        controller = new ArticleReviewFeedbackController(world.adminAuthorizationService,
                world.currentActorService,
                new ArticleReviewFeedbackService(repository, world.githubPullRequestClient,
                        world.projectServiceClient));
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private ArticleReview review(ArticleReviewState state) {
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

    @Test
    @DisplayName("差し戻しは認可後に、操作者のIDとメールを添えてサービスへ渡す")
    void reject() {
        world.stubActor(ADMIN, ACTOR_ID, "admin", "a@example.com").asUser(ADMIN);
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        GithubStubs.createIssueComment(world.github, "octo", "blog", PR, 5001L);

        ArticleRejectResponse response = controller.reject(PROJECT_ID, PR, new ArticleRejectRequest("直して"));

        assertThat(response.state()).isEqualTo(ArticleReviewState.CHANGES_REQUESTED);
        assertThat(response.commentId()).isEqualTo(5001L);
        assertThat(response.rejectedByUserId()).isEqualTo(ACTOR_ID);
        String body = world.github.requests("POST", "/repos/octo/blog/issues/" + PR + "/comments").get(0).bodyText();
        assertThat(body).contains("直して").contains("a@example.com").contains("ID 3");
        assertThat(review.getState()).isEqualTo(ArticleReviewState.CHANGES_REQUESTED);
    }

    @Test
    @DisplayName("差し戻しもメンバーでも管理者でもなければ拒否され、GitHubに触れない")
    void rejectForbidden() {
        world.stubActor(MEMBER, 10L, "user", "m@example.com").stubProjectMember(PROJECT_ID, 10L, false)
                .asUser(MEMBER);

        assertThatThrownBy(() -> controller.reject(PROJECT_ID, PR, new ArticleRejectRequest("x")))
                .isInstanceOf(ForbiddenException.class);

        assertThat(world.github.requests()).isEmpty();
        verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("差し戻しも操作者を解決できなければログインが必要として拒否する")
    void rejectUnresolvedActor() {
        world.stubActor(ADMIN, null, "admin", "a@example.com").asUser(ADMIN);

        assertThatThrownBy(() -> controller.reject(PROJECT_ID, PR, new ArticleRejectRequest("x")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ログイン");
        assertThat(world.github.requests()).isEmpty();
    }

    @Test
    @DisplayName("自分宛の一覧は認可後に、操作者のIDで絞ってサービスから返す")
    void myReviews() {
        world.stubActor(ADMIN, ACTOR_ID, "admin", "a@example.com").asUser(ADMIN);
        ArticleReview review = review(ArticleReviewState.SUBMITTED);
        when(repository.findByProjectIdAndSubmittedByUserIdOrderBySubmittedAtDesc(PROJECT_ID, ACTOR_ID))
                .thenReturn(List.of(review));

        List<MyArticleReviewResponse> result = controller.myReviews(PROJECT_ID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).prNumber()).isEqualTo(PR);
        assertThat(result.get(0).state()).isEqualTo(ArticleReviewState.SUBMITTED);
    }

    @Test
    @DisplayName("自分宛の一覧もメンバーでも管理者でもなければ拒否され、リポジトリに触れない")
    void myReviewsForbidden() {
        world.stubActor(MEMBER, 10L, "user", "m@example.com").stubProjectMember(PROJECT_ID, 10L, false)
                .asUser(MEMBER);

        assertThatThrownBy(() -> controller.myReviews(PROJECT_ID)).isInstanceOf(ForbiddenException.class);
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("自分宛の一覧も操作者を解決できなければログインが必要として拒否する")
    void myReviewsUnresolvedActor() {
        world.stubActor(ADMIN, null, "admin", "a@example.com").asUser(ADMIN);

        assertThatThrownBy(() -> controller.myReviews(PROJECT_ID))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("ログイン");
        org.mockito.Mockito.verifyNoInteractions(repository);
    }
}
