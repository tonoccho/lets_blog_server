package com.letsblog.publishing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.letsblog.publishing.client.ContentServiceClient;
import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.client.ProjectServiceClient.ProjectBridge;
import com.letsblog.publishing.client.ProjectServiceClient.SiteBridge;
import com.letsblog.publishing.cms.CmsApiException;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.domain.ArticleReview;
import com.letsblog.publishing.domain.ArticleReviewState;
import com.letsblog.publishing.dto.ArticleApproveResponse;
import com.letsblog.publishing.dto.PostPublishCommand;
import com.letsblog.publishing.dto.PostPublishResponse;
import com.letsblog.publishing.dto.PullRequestArticleResponse;
import com.letsblog.publishing.dto.PullRequestArticleResponse.Asset;
import com.letsblog.publishing.dto.PullRequestArticleResponse.FrontMatter;
import com.letsblog.publishing.github.GithubApiException;
import com.letsblog.publishing.github.GithubPullRequestClient;
import com.letsblog.publishing.github.GithubPullRequestDetail;
import com.letsblog.publishing.github.PullRequestNotMergeableException;
import com.letsblog.publishing.repository.ArticleReviewRepository;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link ArticleReviewApprovalService}の単体テスト(issue #1343)。 */
@ExtendWith(MockitoExtension.class)
class ArticleReviewApprovalServiceTest {

    private static final GithubAccess ACCESS = new GithubAccess("t", "octo", "blog");
    private static final long PROJECT_ID = 7L;
    private static final long ACTOR_ID = 3L;
    private static final int PR = 201;
    private static final String HEAD_REF = "article/sample";
    private static final String PROD_SITE_KEY = "prod-site";

    @Mock
    private ProjectServiceClient projectServiceClient;
    @Mock
    private PullRequestArticleService pullRequestArticleService;
    @Mock
    private ContentServiceClient contentServiceClient;
    @Mock
    private PostPublishService postPublishService;
    @Mock
    private ArticleReviewRepository repository;
    @Mock
    private GithubPullRequestClient githubClient;

    private ArticleReviewApprovalService service() {
        return new ArticleReviewApprovalService(projectServiceClient, pullRequestArticleService, contentServiceClient,
                postPublishService, repository, githubClient);
    }

    private void productionSiteLinked() {
        when(projectServiceClient.getProject(PROJECT_ID)).thenReturn(new ProjectBridge(PROJECT_ID, 1L, 22L, 33L, "TEST"));
        when(projectServiceClient.getSite(33L)).thenReturn(Optional.of(
                new SiteBridge(33L, PROD_SITE_KEY, "本番", "http://p.example", CmsType.WORDPRESS, true, "prod-site")));
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

    private void mergeable(Boolean mergeable, boolean merged) {
        when(githubClient.getPullRequest(ACCESS, PR))
                .thenReturn(new GithubPullRequestDetail(PR, "sha1", HEAD_REF, mergeable, merged));
    }

    private static FrontMatter frontMatter(String title, String slug, String status, String scheduled) {
        return new FrontMatter(title, slug, status, List.of("news"), List.of("alpha"), "assets/cover.png", scheduled);
    }

    private void articleFetched(FrontMatter frontMatter, Map<String, byte[]> assets) {
        List<Asset> names = assets.entrySet().stream().map(e -> new Asset(e.getKey(), e.getValue().length)).toList();
        when(pullRequestArticleService.fetchForPublish(ACCESS, PR)).thenReturn(
                new PullRequestArticleService.PublishableArticle(
                        new PullRequestArticleResponse("dir-slug", frontMatter, "本文", names), assets));
    }

    private void readyToApprove(FrontMatter frontMatter) {
        readyToApprove(frontMatter, review(ArticleReviewState.IN_REVIEW));
    }

    private void readyToApprove(FrontMatter frontMatter, ArticleReview review) {
        productionSiteLinked();
        inReview(review);
        mergeable(true, false);
        articleFetched(frontMatter, Map.of());
        when(contentServiceClient.findPostBySlug(anyString(), anyString())).thenReturn(Optional.empty());
        when(postPublishService.publish(any())).thenReturn(new PostPublishResponse("77", "http://p.example/sample/", "publish"));
    }

    private PostPublishCommand publishedCommand() {
        ArgumentCaptor<PostPublishCommand> captor = ArgumentCaptor.forClass(PostPublishCommand.class);
        verify(postPublishService).publish(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("本番のサイトへfront matterのstatusとpublish_scheduled_atのまま投稿し、投稿→マージ→ブランチ削除の順に行い、公開済みへ遷移して本番URLを記録する")
    void approves() throws IOException {
        productionSiteLinked();
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        inReview(review);
        mergeable(true, false);
        Map<String, byte[]> assets = new LinkedHashMap<>();
        assets.put("cover.png", new byte[] {1});
        articleFetched(frontMatter("タイトル", "sample", "publish", "2026-12-25T09:00:00Z"), assets);
        when(contentServiceClient.findPostBySlug(PROD_SITE_KEY, "sample")).thenReturn(Optional.empty());
        when(postPublishService.publish(any())).thenReturn(new PostPublishResponse("77", "http://p.example/sample/", "future"));

        ArticleApproveResponse response = service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        PostPublishCommand command = publishedCommand();
        assertThat(command.siteKey()).isEqualTo(PROD_SITE_KEY);
        assertThat(command.title()).isEqualTo("タイトル");
        assertThat(command.slug()).isEqualTo("sample");
        assertThat(command.status()).isEqualTo("publish");
        assertThat(command.publishScheduledAt()).isEqualTo("2026-12-25T09:00:00Z");
        assertThat(command.categories()).containsExactly("news");
        assertThat(command.tags()).containsExactly("alpha");
        assertThat(command.wpPostId()).isNull();
        assertThat(command.imageReferences()).containsExactly("assets/cover.png");
        assertThat(command.images()).hasSize(1);
        assertThat(command.images().get(0).getBytes()).containsExactly(1);
        assertThat(command.featuredImageFilename()).isEqualTo("assets/cover.png");

        InOrder order = inOrder(postPublishService, githubClient, repository);
        order.verify(postPublishService).publish(any());
        order.verify(githubClient).mergePullRequest(ACCESS, PR);
        order.verify(repository).save(review);
        order.verify(githubClient).deleteBranch(ACCESS, HEAD_REF);

        assertThat(review.getState()).isEqualTo(ArticleReviewState.PUBLISHED);
        assertThat(review.getProductionPostUrl()).isEqualTo("http://p.example/sample/");
        assertThat(review.getPublishedByUserId()).isEqualTo(ACTOR_ID);
        assertThat(response).isEqualTo(new ArticleApproveResponse(
                PR, ArticleReviewState.PUBLISHED, "http://p.example/sample/", "77", true, true));
    }

    @Test
    @DisplayName("front matterにstatusが無い(null)記事は、既存の本番投稿と同じ既定のdraftで投稿する")
    void nullStatusDefaultsToDraft() {
        readyToApprove(frontMatter("T", "sample", null, null));

        service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        PostPublishCommand command = publishedCommand();
        assertThat(command.status()).isEqualTo("draft");
        assertThat(command.publishScheduledAt()).isNull();
    }

    @Test
    @DisplayName("front matterのstatusが空白でも既定のdraftで投稿する")
    void blankStatusDefaultsToDraft() {
        readyToApprove(frontMatter("T", "sample", "  ", null));

        service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        assertThat(publishedCommand().status()).isEqualTo("draft");
    }

    @Test
    @DisplayName("同じスラッグの投稿が本番に既にあれば、そのwpPostIdを渡して更新にする")
    void updatesExistingProductionPost() {
        readyToApprove(frontMatter("T", "sample", "publish", null));
        when(contentServiceClient.findPostBySlug(PROD_SITE_KEY, "sample"))
                .thenReturn(Optional.of(new ContentServiceClient.PostSlugLookup("55", "draft")));

        service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        assertThat(publishedCommand().wpPostId()).isEqualTo("55");
    }

    @Test
    @DisplayName("front matterにslugが無ければ記事ディレクトリ名のスラッグで照会・投稿する")
    void fallsBackToDirectorySlug() {
        readyToApprove(frontMatter("T", " ", "publish", null));

        service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        verify(contentServiceClient).findPostBySlug(PROD_SITE_KEY, "dir-slug");
        assertThat(publishedCommand().slug()).isEqualTo("dir-slug");
    }

    @Test
    @DisplayName("front matterのslugがnullでも記事ディレクトリ名のスラッグを使う")
    void nullSlugFallsBack() {
        readyToApprove(frontMatter("T", null, "publish", null));

        service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        assertThat(publishedCommand().slug()).isEqualTo("dir-slug");
    }

    @Test
    @DisplayName("PRが提出されていなければ404用の例外で、GitHubにも本番にも触れない")
    void notSubmitted() {
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(ArticleReviewNotFoundException.class);
        verifyNoInteractions(githubClient, postPublishService);
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
        verifyNoInteractions(githubClient, postPublishService);
        verify(repository, never()).save(any());
        assertThat(review.getState()).isEqualTo(state);
    }

    @Test
    @DisplayName("本番環境のサイトが紐づいていなければ409用の例外で、投稿もマージもしない")
    void noProductionSite() {
        inReview(review(ArticleReviewState.IN_REVIEW));
        when(projectServiceClient.getProject(PROJECT_ID)).thenReturn(new ProjectBridge(PROJECT_ID, 1L, 22L, null, "TEST"));

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("本番環境");
        verifyNoInteractions(githubClient, postPublishService);
    }

    @Test
    @DisplayName("本番環境のサイトが見つからなければ409用の例外で、投稿もマージもしない")
    void productionSiteMissing() {
        inReview(review(ArticleReviewState.IN_REVIEW));
        when(projectServiceClient.getProject(PROJECT_ID)).thenReturn(new ProjectBridge(PROJECT_ID, 1L, 22L, 33L, "TEST"));
        when(projectServiceClient.getSite(33L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("33");
        verifyNoInteractions(githubClient, postPublishService);
    }

    @Test
    @DisplayName("コンフリクト(mergeable=false)のPRはコンフリクトが原因と分かる例外で、投稿もマージも削除も状態変更もしない")
    void conflict() {
        productionSiteLinked();
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        inReview(review);
        mergeable(false, false);

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(PullRequestNotMergeableException.class)
                .hasMessageContaining("コンフリクト");
        verifyNoInteractions(postPublishService);
        verify(githubClient, never()).mergePullRequest(any(), anyInt());
        verify(githubClient, never()).deleteBranch(any(), anyString());
        verify(repository, never()).save(any());
        assertThat(review.getState()).isEqualTo(ArticleReviewState.IN_REVIEW);
    }

    @Test
    @DisplayName("mergeableがnull(GitHubが計算中)はマージ可能と見なさず、投稿もマージもしない")
    void mergeableUnknownIsNotMergeable() {
        productionSiteLinked();
        inReview(review(ArticleReviewState.IN_REVIEW));
        mergeable(null, false);

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(PullRequestNotMergeableException.class)
                .hasMessageContaining("計算中");
        verifyNoInteractions(postPublishService);
        verify(githubClient, never()).mergePullRequest(any(), anyInt());
    }

    @Test
    @DisplayName("既にマージ済みのPRは拒否し、投稿もしない")
    void alreadyMerged() {
        productionSiteLinked();
        inReview(review(ArticleReviewState.IN_REVIEW));
        mergeable(true, true);

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(PullRequestNotMergeableException.class)
                .hasMessageContaining("マージ済み");
        verifyNoInteractions(postPublishService);
        verify(githubClient, never()).mergePullRequest(any(), anyInt());
    }

    @Test
    @DisplayName("titleの無い記事は投稿せず、マージも削除も状態変更もしない")
    void missingTitle() {
        productionSiteLinked();
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        inReview(review);
        mergeable(true, false);
        articleFetched(frontMatter(" ", "sample", "publish", null), Map.of());

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(PullRequestArticleException.class);
        verifyNoInteractions(postPublishService);
        verify(githubClient, never()).mergePullRequest(any(), anyInt());
        assertThat(review.getState()).isEqualTo(ArticleReviewState.IN_REVIEW);
    }

    @Test
    @DisplayName("titleがnullの記事も投稿しない")
    void nullTitle() {
        productionSiteLinked();
        inReview(review(ArticleReviewState.IN_REVIEW));
        mergeable(true, false);
        articleFetched(frontMatter(null, "sample", "publish", null), Map.of());

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(PullRequestArticleException.class);
        verifyNoInteractions(postPublishService);
    }

    @Test
    @DisplayName("本番投稿が失敗したらPRはマージされず、ブランチも削除されず、状態はレビュー中のまま")
    void productionPostFailure() {
        productionSiteLinked();
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        inReview(review);
        mergeable(true, false);
        articleFetched(frontMatter("T", "sample", "publish", null), Map.of());
        when(contentServiceClient.findPostBySlug(anyString(), anyString())).thenReturn(Optional.empty());
        when(postPublishService.publish(any())).thenThrow(new CmsApiException("CMSが応答しません"));

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(CmsApiException.class);
        verify(githubClient, never()).mergePullRequest(any(), anyInt());
        verify(githubClient, never()).deleteBranch(any(), anyString());
        verify(repository, never()).save(any());
        assertThat(review.getState()).isEqualTo(ArticleReviewState.IN_REVIEW);
        assertThat(review.getProductionPostUrl()).isNull();
    }

    @Test
    @DisplayName("マージに失敗したらブランチは削除せず状態も進めない。投稿済みである旨をエラーに含める")
    void mergeFailure() {
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        readyToApprove(frontMatter("T", "sample", "publish", null), review);
        doThrow(new PullRequestNotMergeableException("保護規則により拒否されました"))
                .when(githubClient).mergePullRequest(ACCESS, PR);

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(PullRequestNotMergeableException.class)
                .hasMessageContaining("保護規則")
                .hasMessageContaining("本番")
                .hasMessageContaining("http://p.example/sample/");
        verify(githubClient, never()).deleteBranch(any(), anyString());
        verify(repository, never()).save(any());
        assertThat(review.getState()).isEqualTo(ArticleReviewState.IN_REVIEW);
    }

    @Test
    @DisplayName("マージ時のGitHub API失敗(通信・権限)でもブランチは削除せず状態も進めない")
    void mergeApiFailure() {
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        readyToApprove(frontMatter("T", "sample", "publish", null), review);
        doThrow(new GithubApiException("権限が不足しています")).when(githubClient).mergePullRequest(ACCESS, PR);

        assertThatThrownBy(() -> service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(GithubApiException.class);
        verify(githubClient, never()).deleteBranch(any(), anyString());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("ブランチ削除に失敗しても、マージ済みで公開済みの結果は保存して返し、branchDeletedをfalseにする")
    void branchDeleteFailureKeepsPublished() {
        ArticleReview review = review(ArticleReviewState.IN_REVIEW);
        readyToApprove(frontMatter("T", "sample", "publish", null), review);
        doThrow(new GithubApiException("削除できません")).when(githubClient).deleteBranch(ACCESS, HEAD_REF);

        ArticleApproveResponse response = service().approve(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        verify(repository).save(review);
        assertThat(review.getState()).isEqualTo(ArticleReviewState.PUBLISHED);
        assertThat(response.merged()).isTrue();
        assertThat(response.branchDeleted()).isFalse();
        assertThat(response.productionPostUrl()).isEqualTo("http://p.example/sample/");
    }
}
