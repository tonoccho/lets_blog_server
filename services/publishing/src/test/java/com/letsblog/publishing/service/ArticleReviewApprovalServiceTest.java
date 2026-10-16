package com.letsblog.publishing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.letsblog.publishing.cms.ssh.SshOperationException;
import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.domain.ArticleReview;
import com.letsblog.publishing.domain.ArticleReviewState;
import com.letsblog.publishing.dto.ArticleApproveResponse;
import com.letsblog.publishing.github.GithubApiException;
import com.letsblog.publishing.github.PullRequestNotMergeableException;
import com.letsblog.publishing.repository.ArticleReviewRepository;
import com.letsblog.publishing.testsupport.FakeWordPress;
import com.letsblog.publishing.testsupport.GithubStubs;
import com.letsblog.publishing.testsupport.PublishingWorld;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link ArticleReviewApprovalService}の単体テスト(issue #1343)。
 *
 * <p>issue #1815で書き直し: 自前のクラス(ProjectServiceClient・ContentServiceClient・PullRequestArticleService・
 * GithubPullRequestClient・PostPublishService等)は実物を使い、外部との通信(GitHub・project/content/identity/
 * media-serviceへのHTTP、WordPressへのSSH)だけを差し替える。投稿は、WordPressのフェイクに実際に書き込まれた
 * 状態で確かめる。{@link ArticleReviewRepository}はDBアクセスそのものなのでMockito。
 */
class ArticleReviewApprovalServiceTest {

    private static final GithubAccess ACCESS = new GithubAccess("t", "octo", "blog");
    private static final long PROJECT_ID = 7L;
    private static final long ACTOR_ID = 3L;
    private static final int PR = 201;
    private static final String HEAD_REF = "article/sample";
    private static final String PROD_SITE_KEY = "prod-site";
    private static final String ADMIN = "Bearer admin-token";

    private PublishingWorld world;
    private ArticleReviewRepository repository;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld().withTestAndProductionSites(PROJECT_ID, 22L, "test-site", 33L, PROD_SITE_KEY);
        world.stubActor(ADMIN, ACTOR_ID, "admin", "admin@example.com").asUser(ADMIN);
        repository = mock(ArticleReviewRepository.class);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private ArticleReviewApprovalService service() {
        return new ArticleReviewApprovalService(world.projectServiceClient,
                new PullRequestArticleService(world.githubPullRequestClient), world.contentServiceClient,
                world.postPublishService, repository, world.githubPullRequestClient);
    }

    private ArticleReview review(ArticleReviewState state) {
        ArticleReview review = new ArticleReview();
        review.setProjectId(PROJECT_ID);
        review.setGithubPrNumber(PR);
        review.setGithubIssueNumber(12);
        review.setArticleSlug("sample");
        review.setSubmittedByUserId(5L);
        review.setState(state);
        return review;
    }

    private void inReview(ArticleReview review) {
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
    }

    /** PRの詳細だけを置く(記事は置かない)。 */
    private void mergeable(Boolean mergeable, boolean merged) {
        GithubStubs.pullRequest(world.github, "octo", "blog", PR, "sha1", HEAD_REF, mergeable, merged);
    }

    private static String articleMd(String title, String slug, String status, String scheduled) {
        StringBuilder md = new StringBuilder("---\n");
        if (title != null) {
            md.append("title: \"").append(title).append("\"\n");
        }
        if (slug != null) {
            md.append("slug: \"").append(slug).append("\"\n");
        }
        if (status != null) {
            md.append("status: \"").append(status).append("\"\n");
        }
        md.append("categories: [news]\ntags: [alpha]\nfeatured_image: assets/cover.png\n");
        if (scheduled != null) {
            md.append("publish_scheduled_at: \"").append(scheduled).append("\"\n");
        }
        md.append("---\n本文");
        return md.toString();
    }

    private void articleInPullRequest(String md, Map<String, byte[]> assets) {
        GithubStubs.articlePullRequest(world.github, "octo", "blog", PR, "sha1", HEAD_REF, true, false, "dir-slug",
                md, assets);
    }

    private void readyToApprove(String md) {
        readyToApprove(md, review(ArticleReviewState.IN_REVIEW));
    }

    private void readyToApprove(String md, ArticleReview review) {
        inReview(review);
        articleInPullRequest(md, Map.of());
        GithubStubs.mergeOk(world.github, "octo", "blog", PR);
        GithubStubs.deleteBranchOk(world.github, "octo", "blog", HEAD_REF);
    }

    private FakeWordPress.Post onlyPost() {
        assertThat(world.ssh.posts).hasSize(1);
        return world.ssh.posts.values().iterator().next();
    }

    private void assertNoProductionWrite() {
        assertThat(world.ssh.posts).isEmpty();
        assertThat(world.ssh.media).isEmpty();
        assertThat(world.github.requests("PUT", ".*")).as("マージしない").isEmpty();
        assertThat(world.github.requests("DELETE", ".*")).as("ブランチを削除しない").isEmpty();
    }

    @Test
    @DisplayName("本番のサイトへfront matterのstatusとpublish_scheduled_atのまま投稿し、投稿→マージ→ブランチ削除の順に行い、公開済みへ遷移して本番URLを記録する")
    void approves() {
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        inReview(review);
        Map<String, byte[]> assets = new LinkedHashMap<>();
        assets.put("cover.png", new byte[] {1});
        String scheduledAt = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30).withNano(0).toString();
        articleInPullRequest(articleMd("タイトル", "sample", "publish", scheduledAt), assets);
        GithubStubs.mergeOk(world.github, "octo", "blog", PR);
        GithubStubs.deleteBranchOk(world.github, "octo", "blog", HEAD_REF);
        // 保存の時点で、投稿とマージが済んでいてブランチ削除はまだであることを記録する。
        List<String> atSave = new ArrayList<>();
        doAnswer(inv -> {
            atSave.add("posts=" + world.ssh.posts.size());
            atSave.add("merged=" + !world.github.requests("PUT", ".*/merge").isEmpty());
            atSave.add("deleted=" + !world.github.requests("DELETE", ".*").isEmpty());
            return inv.getArgument(0);
        }).when(repository).save(any());

        ArticleApproveResponse response = service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        FakeWordPress.Post post = onlyPost();
        assertThat(post.title).isEqualTo("タイトル");
        assertThat(post.slug).isEqualTo("sample");
        assertThat(post.status).as("予約日時があれば本番ではfuture").isEqualTo("future");
        assertThat(post.dateGmt).isNotNull();
        assertThat(post.categories).isEqualTo(world.ssh.terms.get("category:news"));
        assertThat(post.taxInput).contains(world.ssh.terms.get("post_tag:alpha"));
        assertThat(world.ssh.media.values()).hasSize(1);
        assertThat(world.ssh.media.values().iterator().next().bytes).containsExactly(1);
        assertThat(post.thumbnailId).isEqualTo(world.ssh.media.values().iterator().next().id);
        assertThat(world.content.last().bodyText()).as("本番のサイト(id 33)へ反映").contains("\"siteId\":33");

        assertThat(atSave).containsExactly("posts=1", "merged=true", "deleted=false");
        assertThat(world.github.requests("DELETE", ".*/git/refs/heads/article/sample")).hasSize(1);
        assertThat(review.getState()).isEqualTo(ArticleReviewState.PUBLISHED);
        assertThat(review.getProductionPostUrl()).isEqualTo(post.link);
        assertThat(review.getPublishedByUserId()).isEqualTo(ACTOR_ID);
        assertThat(response).isEqualTo(new ArticleApproveResponse(
                PR, ArticleReviewState.PUBLISHED, post.link, post.id, true, true));
    }

    @Test
    @DisplayName("front matterにstatusが無い(null)記事は、既存の本番投稿と同じ既定のdraftで投稿する")
    void nullStatusDefaultsToDraft() {
        readyToApprove(articleMd("T", "sample", null, null));

        service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        FakeWordPress.Post post = onlyPost();
        assertThat(post.status).isEqualTo("draft");
        assertThat(post.dateGmt).isNull();
    }

    @Test
    @DisplayName("front matterのstatusが空白でも既定のdraftで投稿する")
    void blankStatusDefaultsToDraft() {
        readyToApprove(articleMd("T", "sample", "  ", null));

        service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        assertThat(onlyPost().status).isEqualTo("draft");
    }

    @Test
    @DisplayName("同じスラッグの投稿が本番に既にあれば、そのwpPostIdを渡して更新にする")
    void updatesExistingProductionPost() {
        readyToApprove(articleMd("T", "sample", "publish", null));
        world.stubContentSlug(PROD_SITE_KEY, "sample", "55", "draft");
        world.ssh.addPost("55", "sample", "draft");

        ArticleApproveResponse response = service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        assertThat(response.wpPostId()).isEqualTo("55");
        assertThat(world.ssh.posts).hasSize(1);
        assertThat(world.ssh.posts.get("55").status).isEqualTo("publish");
    }

    @Test
    @DisplayName("front matterにslugが無ければ記事ディレクトリ名のスラッグで照会・投稿する")
    void fallsBackToDirectorySlug() {
        readyToApprove(articleMd("T", " ", "publish", null));

        service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        assertThat(world.content.requests("GET", "/api/posts/prod-site/by-slug/dir-slug")).hasSize(1);
        assertThat(onlyPost().slug).isEqualTo("dir-slug");
    }

    @Test
    @DisplayName("front matterのslugがnullでも記事ディレクトリ名のスラッグを使う")
    void nullSlugFallsBack() {
        readyToApprove(articleMd("T", null, "publish", null));

        service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        assertThat(onlyPost().slug).isEqualTo("dir-slug");
    }

    @Test
    @DisplayName("PRが提出されていなければ404用の例外で、GitHubにも本番にも触れない")
    void notSubmitted() {
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(ArticleReviewNotFoundException.class);
        assertThat(world.github.requests()).isEmpty();
        assertThat(world.ssh.posts).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = ArticleReviewState.class, names = {"SUBMITTED", "CHANGES_REQUESTED", "PUBLISHED"})
    @DisplayName("レビュー中でなければ拒否し、投稿もマージもしない")
    void rejectsWhenNotInReview(ArticleReviewState state) {
        ArticleReview review = review(state);
        inReview(review);

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("レビュー中ではない")
                .hasMessageContaining(state.name());
        assertThat(world.github.requests()).isEmpty();
        assertThat(world.ssh.posts).isEmpty();
        verify(repository, never()).save(any());
        assertThat(review.getState()).isEqualTo(state);
    }

    @Test
    @DisplayName("本番環境のサイトが紐づいていなければ409用の例外で、投稿もマージもしない")
    void noProductionSite() {
        inReview(review(ArticleReviewState.IN_REVIEW));
        world.bindProject(22L, PROJECT_ID, 1L, 22L, null);

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("本番環境");
        assertThat(world.github.requests()).isEmpty();
        assertThat(world.ssh.posts).isEmpty();
    }

    @Test
    @DisplayName("本番環境のサイトが見つからなければ409用の例外で、投稿もマージもしない")
    void productionSiteMissing() {
        inReview(review(ArticleReviewState.IN_REVIEW));
        world.bindProject(22L, PROJECT_ID, 1L, 22L, 44L);

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("44");
        assertThat(world.github.requests()).isEmpty();
        assertThat(world.ssh.posts).isEmpty();
    }

    @Test
    @DisplayName("コンフリクト(mergeable=false)のPRはコンフリクトが原因と分かる例外で、投稿もマージも削除も状態変更もしない")
    void conflict() {
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        inReview(review);
        mergeable(false, false);

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(PullRequestNotMergeableException.class)
                .hasMessageContaining("コンフリクト");
        assertNoProductionWrite();
        verify(repository, never()).save(any());
        assertThat(review.getState()).isEqualTo(ArticleReviewState.IN_REVIEW);
    }

    @Test
    @DisplayName("mergeableがnull(GitHubが計算中)はマージ可能と見なさず、投稿もマージもしない")
    void mergeableUnknownIsNotMergeable() {
        inReview(review(ArticleReviewState.IN_REVIEW));
        mergeable(null, false);

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(PullRequestNotMergeableException.class)
                .hasMessageContaining("計算中");
        assertNoProductionWrite();
    }

    @Test
    @DisplayName("既にマージ済みのPRは拒否し、投稿もしない")
    void alreadyMerged() {
        inReview(review(ArticleReviewState.IN_REVIEW));
        mergeable(true, true);

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(PullRequestNotMergeableException.class)
                .hasMessageContaining("マージ済み");
        assertNoProductionWrite();
    }

    @Test
    @DisplayName("titleの無い記事は投稿せず、マージも削除も状態変更もしない")
    void missingTitle() {
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        inReview(review);
        articleInPullRequest(articleMd(" ", "sample", "publish", null), Map.of());

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(PullRequestArticleException.class);
        assertNoProductionWrite();
        assertThat(review.getState()).isEqualTo(ArticleReviewState.IN_REVIEW);
    }

    @Test
    @DisplayName("titleがnullの記事も投稿しない")
    void nullTitle() {
        inReview(review(ArticleReviewState.IN_REVIEW));
        articleInPullRequest(articleMd(null, "sample", "publish", null), Map.of());

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(PullRequestArticleException.class);
        assertNoProductionWrite();
    }

    @Test
    @DisplayName("本番投稿が失敗したらPRはマージされず、ブランチも削除されず、状態はレビュー中のまま")
    void productionPostFailure() {
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        readyToApprove(articleMd("T", "sample", "publish", null), review);
        world.ssh.whenFail("post create", "CMSが応答しません");

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(SshOperationException.class);
        assertThat(world.github.requests("PUT", ".*")).isEmpty();
        assertThat(world.github.requests("DELETE", ".*")).isEmpty();
        verify(repository, never()).save(any());
        assertThat(review.getState()).isEqualTo(ArticleReviewState.IN_REVIEW);
        assertThat(review.getProductionPostUrl()).isNull();
    }

    @Test
    @DisplayName("マージに失敗したらブランチは削除せず状態も進めない。投稿済みである旨をエラーに含める")
    void mergeFailure() {
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        readyToApprove(articleMd("T", "sample", "publish", null), review);
        world.github.status("PUT", "/repos/octo/blog/pulls/" + PR + "/merge", 405,
                "{\"message\":\"保護規則により拒否されました\"}");

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(PullRequestNotMergeableException.class)
                .hasMessageContaining("保護規則")
                .hasMessageContaining("本番")
                .hasMessageContaining(onlyPost().link);
        assertThat(world.github.requests("DELETE", ".*")).isEmpty();
        verify(repository, never()).save(any());
        assertThat(review.getState()).isEqualTo(ArticleReviewState.IN_REVIEW);
    }

    @Test
    @DisplayName("マージ時のGitHub API失敗(通信・権限)でもブランチは削除せず状態も進めない")
    void mergeApiFailure() {
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        readyToApprove(articleMd("T", "sample", "publish", null), review);
        world.github.status("PUT", "/repos/octo/blog/pulls/" + PR + "/merge", 403,
                "{\"message\":\"権限が不足しています\"}");

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(GithubApiException.class);
        assertThat(world.github.requests("DELETE", ".*")).isEmpty();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("ブランチ削除に失敗しても、マージ済みで公開済みの結果は保存して返し、branchDeletedをfalseにする")
    void branchDeleteFailureKeepsPublished() {
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        readyToApprove(articleMd("T", "sample", "publish", null), review);
        world.github.status("DELETE", ".*/git/refs/heads/.*", 422, "{\"message\":\"削除できません\"}");

        ArticleApproveResponse response = service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        verify(repository).save(review);
        assertThat(review.getState()).isEqualTo(ArticleReviewState.PUBLISHED);
        assertThat(response.merged()).isTrue();
        assertThat(response.branchDeleted()).isFalse();
        assertThat(response.productionPostUrl()).isEqualTo(onlyPost().link);
    }
}
