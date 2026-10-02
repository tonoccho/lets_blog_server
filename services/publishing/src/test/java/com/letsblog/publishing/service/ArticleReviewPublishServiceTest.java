package com.letsblog.publishing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.letsblog.publishing.dto.ArticleReviewResponse;
import com.letsblog.publishing.dto.PostPublishCommand;
import com.letsblog.publishing.dto.PostPublishResponse;
import com.letsblog.publishing.dto.PullRequestArticleResponse;
import com.letsblog.publishing.dto.PullRequestArticleResponse.Asset;
import com.letsblog.publishing.dto.PullRequestArticleResponse.FrontMatter;
import com.letsblog.publishing.repository.ArticleReviewRepository;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link ArticleReviewPublishService}の単体テスト(issue #1341)。 */
@ExtendWith(MockitoExtension.class)
class ArticleReviewPublishServiceTest {

    private static final GithubAccess ACCESS = new GithubAccess("t", "octo", "blog");
    private static final long PROJECT_ID = 7L;
    private static final long ACTOR_ID = 3L;
    private static final int PR = 201;
    private static final String TEST_SITE_KEY = "test-site";

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

    private ArticleReviewPublishService service() {
        return new ArticleReviewPublishService(
                projectServiceClient, pullRequestArticleService, contentServiceClient, postPublishService, repository);
    }

    private void testSiteLinked() {
        when(projectServiceClient.getProject(PROJECT_ID)).thenReturn(new ProjectBridge(PROJECT_ID, 1L, 22L, 33L, "TEST"));
        when(projectServiceClient.getSite(22L)).thenReturn(Optional.of(
                new SiteBridge(22L, TEST_SITE_KEY, "テスト", "http://t.example", CmsType.WORDPRESS, true, "test-site")));
    }

    private ArticleReview submittedReview() {
        ArticleReview review = new ArticleReview();
        review.setProjectId(PROJECT_ID);
        review.setGithubPrNumber(PR);
        review.setGithubIssueNumber(12);
        review.setArticleSlug("sample");
        review.setSubmittedByUserId(5L);
        review.setState(ArticleReviewState.SUBMITTED);
        return review;
    }

    private static PullRequestArticleService.PublishableArticle article(
            String dirSlug, FrontMatter frontMatter, Map<String, byte[]> assets) {
        List<Asset> names = assets.entrySet().stream().map(e -> new Asset(e.getKey(), e.getValue().length)).toList();
        return new PullRequestArticleService.PublishableArticle(
                new PullRequestArticleResponse(dirSlug, frontMatter, "本文", names), assets);
    }

    private static FrontMatter frontMatter(String title, String slug, String featured) {
        return new FrontMatter(title, slug, "draft", List.of("news"), List.of("alpha", "beta"), featured,
                "2026-12-25T09:00:00Z");
    }

    private void articleFetched(PullRequestArticleService.PublishableArticle article) {
        when(pullRequestArticleService.fetchForPublish(ACCESS, PR)).thenReturn(article);
    }

    private PostPublishCommand publishedCommand() {
        ArgumentCaptor<PostPublishCommand> captor = ArgumentCaptor.forClass(PostPublishCommand.class);
        verify(postPublishService).publish(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("テスト環境のサイトへ即公開(publish)で投稿し、レビュー中へ遷移してURLとレビュー実施者を記録し、応答にURLを含める")
    void publishesToTestSiteAndMovesToInReview() throws IOException {
        testSiteLinked();
        ArticleReview review = submittedReview();
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        Map<String, byte[]> assets = new LinkedHashMap<>();
        assets.put("cover.png", new byte[] {1});
        assets.put("sub/x.png", new byte[] {2, 3});
        articleFetched(article("sample", frontMatter("タイトル", "sample", "assets/cover.png"), assets));
        when(contentServiceClient.findPostBySlug(TEST_SITE_KEY, "sample")).thenReturn(Optional.empty());
        when(postPublishService.publish(any())).thenReturn(new PostPublishResponse("55", "http://t.example/sample/", "publish"));

        ArticleReviewResponse response = service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        PostPublishCommand command = publishedCommand();
        assertThat(command.siteKey()).isEqualTo(TEST_SITE_KEY);
        assertThat(command.title()).isEqualTo("タイトル");
        assertThat(command.slug()).isEqualTo("sample");
        assertThat(command.status()).isEqualTo("publish");
        assertThat(command.categories()).containsExactly("news");
        assertThat(command.tags()).containsExactly("alpha", "beta");
        assertThat(command.wpPostId()).isNull();
        assertThat(command.markdown()).isEqualTo("本文");
        assertThat(command.publishScheduledAt()).isNull();
        assertThat(command.featuredImageFilename()).isEqualTo("assets/cover.png");
        assertThat(command.imageReferences()).containsExactly("assets/cover.png", "assets/sub/x.png");
        assertThat(command.images()).hasSize(2);
        assertThat(command.images().get(0).getBytes()).containsExactly(1);
        assertThat(command.images().get(1).getBytes()).containsExactly(2, 3);
        assertThat(command.images().get(1).getOriginalFilename()).isEqualTo("assets/sub/x.png");

        assertThat(review.getState()).isEqualTo(ArticleReviewState.IN_REVIEW);
        assertThat(review.getTestPostUrl()).isEqualTo("http://t.example/sample/");
        assertThat(review.getReviewedByUserId()).isEqualTo(ACTOR_ID);
        assertThat(review.getSubmittedByUserId()).isEqualTo(5L);
        verify(repository).save(review);
        assertThat(response).isEqualTo(new ArticleReviewResponse(
                PR, ArticleReviewState.IN_REVIEW, "http://t.example/sample/", ACTOR_ID, "55"));
    }

    @Test
    @DisplayName("同じスラッグの投稿が既にテスト環境にあれば、そのwpPostIdを渡して更新にする(重複させない)")
    void updatesExistingPostFoundBySlug() {
        testSiteLinked();
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(submittedReview()));
        articleFetched(article("sample", frontMatter("T", "sample", null), Map.of()));
        when(contentServiceClient.findPostBySlug(TEST_SITE_KEY, "sample"))
                .thenReturn(Optional.of(new ContentServiceClient.PostSlugLookup("55", "publish")));
        when(postPublishService.publish(any())).thenReturn(new PostPublishResponse("55", "http://t.example/sample/", "publish"));

        service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        assertThat(publishedCommand().wpPostId()).isEqualTo("55");
    }

    @Test
    @DisplayName("アセットもアイキャッチも無い記事は、画像なし・featuredImageFilenameなしで投稿する")
    void noAssets() {
        testSiteLinked();
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(submittedReview()));
        articleFetched(article("sample", frontMatter("T", "sample", null), Map.of()));
        when(contentServiceClient.findPostBySlug(TEST_SITE_KEY, "sample")).thenReturn(Optional.empty());
        when(postPublishService.publish(any())).thenReturn(new PostPublishResponse("1", "u", "publish"));

        service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        PostPublishCommand command = publishedCommand();
        assertThat(command.images()).isEmpty();
        assertThat(command.imageReferences()).isEmpty();
        assertThat(command.featuredImageFilename()).isNull();
    }

    @Test
    @DisplayName("front matterにslugが無ければ記事ディレクトリ名のスラッグで投稿・照会する")
    void fallsBackToDirectorySlug() {
        testSiteLinked();
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(submittedReview()));
        articleFetched(article("dir-slug", frontMatter("T", null, null), Map.of()));
        when(contentServiceClient.findPostBySlug(TEST_SITE_KEY, "dir-slug")).thenReturn(Optional.empty());
        when(postPublishService.publish(any())).thenReturn(new PostPublishResponse("1", "u", "publish"));

        service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        assertThat(publishedCommand().slug()).isEqualTo("dir-slug");
    }

    @Test
    @DisplayName("front matterにslugが空文字なら記事ディレクトリ名のスラッグを使う")
    void blankFrontMatterSlug() {
        testSiteLinked();
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(submittedReview()));
        articleFetched(article("dir-slug", frontMatter("T", " ", null), Map.of()));
        when(contentServiceClient.findPostBySlug(TEST_SITE_KEY, "dir-slug")).thenReturn(Optional.empty());
        when(postPublishService.publish(any())).thenReturn(new PostPublishResponse("1", "u", "publish"));

        service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        assertThat(publishedCommand().slug()).isEqualTo("dir-slug");
    }

    @Test
    @DisplayName("画像のcontent typeは拡張子から決め、不明な拡張子は汎用のバイナリにする")
    void contentTypesFromExtension() {
        testSiteLinked();
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(submittedReview()));
        Map<String, byte[]> assets = new LinkedHashMap<>();
        for (String name : List.of("a.PNG", "b.jpg", "c.jpeg", "d.gif", "e.webp", "f.svg", "g.bin", "noext")) {
            assets.put(name, new byte[] {1});
        }
        articleFetched(article("sample", frontMatter("T", "sample", null), assets));
        when(contentServiceClient.findPostBySlug(TEST_SITE_KEY, "sample")).thenReturn(Optional.empty());
        when(postPublishService.publish(any())).thenReturn(new PostPublishResponse("1", "u", "publish"));

        service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        assertThat(publishedCommand().images().stream().map(f -> f.getContentType()).toList()).containsExactly(
                "image/png", "image/jpeg", "image/jpeg", "image/gif", "image/webp", "image/svg+xml",
                "application/octet-stream", "application/octet-stream");
    }

    @Test
    @DisplayName("再レビュー(既にレビュー中)でも同じ行を最新のURLとレビュー実施者で更新する")
    void reReviewUpdatesSameRow() {
        testSiteLinked();
        ArticleReview review = submittedReview();
        review.markInReview("http://old/", 9L);
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        articleFetched(article("sample", frontMatter("T", "sample", null), Map.of()));
        when(contentServiceClient.findPostBySlug(TEST_SITE_KEY, "sample"))
                .thenReturn(Optional.of(new ContentServiceClient.PostSlugLookup("55", "publish")));
        when(postPublishService.publish(any())).thenReturn(new PostPublishResponse("55", "http://new/", "publish"));

        service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        assertThat(review.getState()).isEqualTo(ArticleReviewState.IN_REVIEW);
        assertThat(review.getTestPostUrl()).isEqualTo("http://new/");
        assertThat(review.getReviewedByUserId()).isEqualTo(ACTOR_ID);
    }

    @Test
    @DisplayName("公開済み(PUBLISHED)の行はレビュー中へ戻さず、何も投稿せず状態も変えずに拒否する")
    void publishedRowIsRejected() {
        testSiteLinked();
        ArticleReview review = submittedReview();
        review.setState(ArticleReviewState.PUBLISHED);
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("公開済み");

        assertThat(review.getState()).isEqualTo(ArticleReviewState.PUBLISHED);
        verifyNoInteractions(pullRequestArticleService, postPublishService, contentServiceClient);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("修正依頼中(CHANGES_REQUESTED)の行は再度テスト環境へ投稿してレビュー中へ遷移できる")
    void changesRequestedRowCanBeReReviewed() {
        testSiteLinked();
        ArticleReview review = submittedReview();
        review.setState(ArticleReviewState.CHANGES_REQUESTED);
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        articleFetched(article("sample", frontMatter("T", "sample", null), Map.of()));
        when(contentServiceClient.findPostBySlug(TEST_SITE_KEY, "sample")).thenReturn(Optional.empty());
        when(postPublishService.publish(any())).thenReturn(new PostPublishResponse("55", "http://new/", "publish"));

        service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        assertThat(review.getState()).isEqualTo(ArticleReviewState.IN_REVIEW);
    }

    @Test
    @DisplayName("テスト環境のサイトが紐づいていなければ、その旨のエラーにして何も投稿せず状態も変えない")
    void testSiteNotLinked() {
        when(projectServiceClient.getProject(PROJECT_ID)).thenReturn(new ProjectBridge(PROJECT_ID, 1L, null, 33L, "TEST"));

        assertThatThrownBy(() -> service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("テスト環境");
        verifyNoInteractions(pullRequestArticleService, contentServiceClient, postPublishService, repository);
    }

    @Test
    @DisplayName("テスト環境のサイトIDが指すサイトが存在しなければ、その旨のエラーにして何も投稿せず状態も変えない")
    void testSiteMissing() {
        when(projectServiceClient.getProject(PROJECT_ID)).thenReturn(new ProjectBridge(PROJECT_ID, 1L, 22L, 33L, "TEST"));
        when(projectServiceClient.getSite(22L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("テスト環境");
        verifyNoInteractions(pullRequestArticleService, contentServiceClient, postPublishService, repository);
    }

    @Test
    @DisplayName("提出されていないPRは見つからないエラーにして、何も投稿しない")
    void reviewRowMissing() {
        testSiteLinked();
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(ArticleReviewNotFoundException.class)
                .hasMessageContaining("#" + PR);
        verifyNoInteractions(pullRequestArticleService, contentServiceClient, postPublishService);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("titleが無い記事は不正として投稿せず、状態も変えない")
    void missingTitle() {
        testSiteLinked();
        ArticleReview review = submittedReview();
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        articleFetched(article("sample", frontMatter(" ", "sample", null), Map.of()));

        assertThatThrownBy(() -> service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOfSatisfying(PullRequestArticleException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(PullRequestArticleException.Kind.INVALID);
                    assertThat(e.getMessage()).contains("title");
                });
        verifyNoInteractions(postPublishService);
        verify(repository, never()).save(any());
        assertThat(review.getState()).isEqualTo(ArticleReviewState.SUBMITTED);
    }

    @Test
    @DisplayName("titleがnullの記事も不正として扱う")
    void nullTitle() {
        testSiteLinked();
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(submittedReview()));
        articleFetched(article("sample", frontMatter(null, "sample", null), Map.of()));

        assertThatThrownBy(() -> service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(PullRequestArticleException.class);
        verifyNoInteractions(postPublishService);
    }

    @Test
    @DisplayName("投稿が失敗したら例外をそのまま伝え、レビュー中へ進めず保存もしない(アップロード済み画像の巻き戻しもしない)")
    void publishFailureKeepsState() {
        testSiteLinked();
        ArticleReview review = submittedReview();
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        articleFetched(article("sample", frontMatter("T", "sample", null), Map.of("cover.png", new byte[] {1})));
        when(contentServiceClient.findPostBySlug(TEST_SITE_KEY, "sample")).thenReturn(Optional.empty());
        CmsApiException failure = new CmsApiException("投稿に失敗しました", new RuntimeException("boom"));
        when(postPublishService.publish(any())).thenThrow(failure);

        assertThatThrownBy(() -> service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR)).isSameAs(failure);

        assertThat(review.getState()).isEqualTo(ArticleReviewState.SUBMITTED);
        assertThat(review.getTestPostUrl()).isNull();
        verify(repository, never()).save(any());
    }
}
