package com.letsblog.publishing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.domain.ArticleReview;
import com.letsblog.publishing.domain.ArticleReviewState;
import com.letsblog.publishing.dto.ArticleReviewResponse;
import com.letsblog.publishing.dto.PostPublishCommand;
import com.letsblog.publishing.dto.PullRequestArticleResponse;
import com.letsblog.publishing.dto.PullRequestArticleResponse.Asset;
import com.letsblog.publishing.dto.PullRequestArticleResponse.FrontMatter;
import com.letsblog.publishing.cms.ssh.SshOperationException;
import com.letsblog.publishing.repository.ArticleReviewRepository;
import com.letsblog.publishing.testsupport.FakeWordPress;
import com.letsblog.publishing.testsupport.GithubStubs;
import com.letsblog.publishing.testsupport.PublishingWorld;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ArticleReviewPublishService}の単体テスト(issue #1341)。
 *
 * <p>issue #1815で書き直し: 自前のクラス(ProjectServiceClient・ContentServiceClient・PullRequestArticleService・
 * GithubPullRequestClient・PostPublishService・CmsAdapterFactory・WordPressAdapter等)は実物を使い、外部との通信
 * (GitHub・project/content/identity/media-serviceへのHTTP、WordPressへのSSH)だけを差し替える。
 * 投稿の内容は、WordPressのフェイク({@code FakeWordPress})に実際に書き込まれた状態で確かめる。
 * {@link ArticleReviewRepository}はDBアクセスそのものなのでMockito。
 */
class ArticleReviewPublishServiceTest {

    private static final GithubAccess ACCESS = new GithubAccess("t", "octo", "blog");
    private static final long PROJECT_ID = 7L;
    private static final long ACTOR_ID = 3L;
    private static final int PR = 201;
    private static final String TEST_SITE_KEY = "test-site";
    private static final String ADMIN = "Bearer admin-token";

    private PublishingWorld world;
    private ArticleReviewRepository repository;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld().withTestAndProductionSites(PROJECT_ID, 22L, TEST_SITE_KEY, 33L, "prod-site");
        world.stubActor(ADMIN, ACTOR_ID, "admin", "admin@example.com").asUser(ADMIN);
        repository = mock(ArticleReviewRepository.class);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private ArticleReviewPublishService service() {
        return new ArticleReviewPublishService(world.projectServiceClient,
                new PullRequestArticleService(world.githubPullRequestClient), world.contentServiceClient,
                world.postPublishService, repository);
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

    private static String articleMd(String title, String slug, String featured) {
        StringBuilder md = new StringBuilder("---\n");
        if (title != null) {
            md.append("title: \"").append(title).append("\"\n");
        }
        if (slug != null) {
            md.append("slug: \"").append(slug).append("\"\n");
        }
        md.append("status: draft\ncategories: [news]\ntags: [alpha, beta]\n");
        if (featured != null) {
            md.append("featured_image: ").append(featured).append("\n");
        }
        md.append("publish_scheduled_at: \"2026-12-25T09:00:00Z\"\n---\n本文");
        return md.toString();
    }

    private void articleInPullRequest(String dirSlug, String md, Map<String, byte[]> assets) {
        GithubStubs.articlePullRequest(world.github, "octo", "blog", PR, "sha1", "article/" + dirSlug, true, false,
                dirSlug, md, assets);
    }

    private FakeWordPress.Post onlyPost() {
        assertThat(world.ssh.posts).hasSize(1);
        return world.ssh.posts.values().iterator().next();
    }

    private FakeWordPress.Media uploaded(String filename) {
        return world.ssh.media.values().stream().filter(m -> m.url.endsWith("-" + filename)).findFirst()
                .orElseThrow(() -> new AssertionError(filename + " はアップロードされていません"));
    }

    private void assertNothingPublished() {
        assertThat(world.ssh.posts).isEmpty();
        assertThat(world.ssh.media).isEmpty();
        assertThat(world.content.requests("PUT", "/api/internal/content/posts")).isEmpty();
    }

    @Test
    @DisplayName("テスト環境のサイトへ即公開(publish)で投稿し、レビュー中へ遷移してURLとレビュー実施者を記録し、応答にURLを含める")
    void publishesToTestSiteAndMovesToInReview() throws IOException {
        ArticleReview review = submittedReview();
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        Map<String, byte[]> assets = new LinkedHashMap<>();
        assets.put("cover.png", new byte[] {1});
        assets.put("sub/x.png", new byte[] {2, 3});
        articleInPullRequest("sample", articleMd("タイトル", "sample", "assets/cover.png"), assets);

        ArticleReviewResponse response = service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        FakeWordPress.Post post = onlyPost();
        assertThat(post.title).isEqualTo("タイトル");
        assertThat(post.slug).isEqualTo("sample");
        assertThat(post.status).isEqualTo("publish");
        assertThat(post.categories).isEqualTo(world.ssh.terms.get("category:news"));
        assertThat(post.taxInput).contains(world.ssh.terms.get("post_tag:alpha"))
                .contains(world.ssh.terms.get("post_tag:beta"));
        assertThat(post.dateGmt).as("テスト環境への投稿に公開予約は付けない").isNull();
        assertThat(world.ssh.contentOf(post.id)).isEqualTo("<p>本文</p>");
        assertThat(uploaded("sample-0001.png").bytes).containsExactly(1);
        assertThat(uploaded("sample-0002.png").bytes).containsExactly(2, 3);
        assertThat(post.thumbnailId).isEqualTo(uploaded("sample-0001.png").id);
        // 投稿先はテスト環境のサイト(site id 22)。
        assertThat(world.content.requests("PUT", "/api/internal/content/posts")).hasSize(1);
        assertThat(world.content.last().bodyText()).contains("\"siteId\":22");

        assertThat(review.getState()).isEqualTo(ArticleReviewState.IN_REVIEW);
        assertThat(review.getTestPostUrl()).isEqualTo(post.link);
        assertThat(review.getReviewedByUserId()).isEqualTo(ACTOR_ID);
        assertThat(review.getSubmittedByUserId()).isEqualTo(5L);
        verify(repository).save(review);
        assertThat(response).isEqualTo(new ArticleReviewResponse(
                PR, ArticleReviewState.IN_REVIEW, post.link, ACTOR_ID, post.id));
    }

    @Test
    @DisplayName("同じスラッグの投稿が既にテスト環境にあれば、そのwpPostIdを渡して更新にする(重複させない)")
    void updatesExistingPostFoundBySlug() {
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(submittedReview()));
        articleInPullRequest("sample", articleMd("T", "sample", null), Map.of());
        world.stubContentSlug(TEST_SITE_KEY, "sample", "55", "publish");
        world.ssh.addPost("55", "sample", "publish");

        ArticleReviewResponse response = service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        assertThat(response.wpPostId()).isEqualTo("55");
        assertThat(world.ssh.posts).hasSize(1);
        assertThat(world.ssh.posts.get("55").title).isEqualTo("T");
    }

    @Test
    @DisplayName("アセットもアイキャッチも無い記事は、画像なし・featuredImageFilenameなしで投稿する")
    void noAssets() {
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(submittedReview()));
        articleInPullRequest("sample", articleMd("T", "sample", null), Map.of());

        service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        assertThat(world.ssh.media).isEmpty();
        assertThat(onlyPost().thumbnailId).isNull();
    }

    @Test
    @DisplayName("front matterにslugが無ければ記事ディレクトリ名のスラッグで投稿・照会する")
    void fallsBackToDirectorySlug() {
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(submittedReview()));
        articleInPullRequest("dir-slug", articleMd("T", null, null), Map.of());

        service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        assertThat(onlyPost().slug).isEqualTo("dir-slug");
        assertThat(world.content.requests("GET", "/api/posts/test-site/by-slug/dir-slug")).hasSize(1);
    }

    @Test
    @DisplayName("front matterにslugが空文字なら記事ディレクトリ名のスラッグを使う")
    void blankFrontMatterSlug() {
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(submittedReview()));
        articleInPullRequest("dir-slug", articleMd("T", " ", null), Map.of());

        service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        assertThat(onlyPost().slug).isEqualTo("dir-slug");
    }

    @Test
    @DisplayName("画像のcontent typeは拡張子から決め、不明な拡張子は汎用のバイナリにする")
    void contentTypesFromExtension() throws IOException {
        Map<String, byte[]> assets = new LinkedHashMap<>();
        for (String name : List.of("a.PNG", "b.jpg", "c.jpeg", "d.gif", "e.webp", "f.svg", "g.bin", "noext")) {
            assets.put(name, new byte[] {1});
        }
        PullRequestArticleResponse article = new PullRequestArticleResponse("sample",
                new FrontMatter("T", "sample", "draft", List.of(), List.of(), null, null), "本文",
                assets.entrySet().stream().map(e -> new Asset(e.getKey(), 1)).toList());

        PostPublishCommand command = ArticleReviewPublishService.command(
                TEST_SITE_KEY, "sample", null, article, assets, "publish", null);

        assertThat(command.images().stream().map(f -> f.getContentType()).toList()).containsExactly(
                "image/png", "image/jpeg", "image/jpeg", "image/gif", "image/webp", "image/svg+xml",
                "application/octet-stream", "application/octet-stream");
        assertThat(command.imageReferences()).hasSize(8).allMatch(r -> r.startsWith("assets/"));
    }

    @Test
    @DisplayName("再レビュー(既にレビュー中)でも同じ行を最新のURLとレビュー実施者で更新する")
    void reReviewUpdatesSameRow() {
        ArticleReview review = submittedReview();
        review.markInReview("http://old/", 9L);
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        articleInPullRequest("sample", articleMd("T", "sample", null), Map.of());
        world.stubContentSlug(TEST_SITE_KEY, "sample", "55", "publish");
        world.ssh.addPost("55", "sample", "publish");

        service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        assertThat(review.getState()).isEqualTo(ArticleReviewState.IN_REVIEW);
        assertThat(review.getTestPostUrl()).isEqualTo("https://example.com/?p=55");
        assertThat(review.getReviewedByUserId()).isEqualTo(ACTOR_ID);
    }

    @Test
    @DisplayName("公開済み(PUBLISHED)の行はレビュー中へ戻さず、何も投稿せず状態も変えずに拒否する")
    void publishedRowIsRejected() {
        ArticleReview review = submittedReview();
        review.setState(ArticleReviewState.PUBLISHED);
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("公開済み");

        assertThat(review.getState()).isEqualTo(ArticleReviewState.PUBLISHED);
        assertThat(world.github.requests()).isEmpty();
        assertNothingPublished();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("修正依頼中(CHANGES_REQUESTED)の行は再度テスト環境へ投稿してレビュー中へ遷移できる")
    void changesRequestedRowCanBeReReviewed() {
        ArticleReview review = submittedReview();
        review.setState(ArticleReviewState.CHANGES_REQUESTED);
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        articleInPullRequest("sample", articleMd("T", "sample", null), Map.of());

        service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR);

        assertThat(review.getState()).isEqualTo(ArticleReviewState.IN_REVIEW);
    }

    @Test
    @DisplayName("テスト環境のサイトが紐づいていなければ、その旨のエラーにして何も投稿せず状態も変えない")
    void testSiteNotLinked() {
        world.bindProject(1L, PROJECT_ID, 1L, null, 33L);

        assertThatThrownBy(() -> service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("テスト環境");
        assertThat(world.github.requests()).isEmpty();
        assertNothingPublished();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("テスト環境のサイトIDが指すサイトが存在しなければ、その旨のエラーにして何も投稿せず状態も変えない")
    void testSiteMissing() {
        world.bindProject(1L, PROJECT_ID, 1L, 99L, 33L);

        assertThatThrownBy(() -> service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("テスト環境");
        assertThat(world.github.requests()).isEmpty();
        assertNothingPublished();
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("提出されていないPRは見つからないエラーにして、何も投稿しない")
    void reviewRowMissing() {
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(ArticleReviewNotFoundException.class)
                .hasMessageContaining("#" + PR);
        assertThat(world.github.requests()).isEmpty();
        assertNothingPublished();
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("titleが無い記事は不正として投稿せず、状態も変えない")
    void missingTitle() {
        ArticleReview review = submittedReview();
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        articleInPullRequest("sample", articleMd(" ", "sample", null), Map.of());

        assertThatThrownBy(() -> service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOfSatisfying(PullRequestArticleException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(PullRequestArticleException.Kind.INVALID);
                    assertThat(e.getMessage()).contains("title");
                });
        assertNothingPublished();
        verify(repository, never()).save(any());
        assertThat(review.getState()).isEqualTo(ArticleReviewState.SUBMITTED);
    }

    @Test
    @DisplayName("titleがnullの記事も不正として扱う")
    void nullTitle() {
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(submittedReview()));
        articleInPullRequest("sample", articleMd(null, "sample", null), Map.of());

        assertThatThrownBy(() -> service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(PullRequestArticleException.class);
        assertNothingPublished();
    }

    @Test
    @DisplayName("投稿が失敗したら例外をそのまま伝え、レビュー中へ進めず保存もしない(アップロード済み画像の巻き戻しもしない)")
    void publishFailureKeepsState() {
        ArticleReview review = submittedReview();
        when(repository.findByProjectIdAndGithubPrNumber(PROJECT_ID, PR)).thenReturn(Optional.of(review));
        articleInPullRequest("sample", articleMd("T", "sample", null), Map.of("cover.png", new byte[] {1}));
        world.ssh.whenFail("post create", "boom");

        assertThatThrownBy(() -> service().review(PROJECT_ID, ACTOR_ID, ACCESS, PR))
                .isInstanceOf(SshOperationException.class)
                .hasMessageContaining("boom");

        assertThat(review.getState()).isEqualTo(ArticleReviewState.SUBMITTED);
        assertThat(review.getTestPostUrl()).isNull();
        assertThat(world.ssh.media).as("アップロード済みの画像は巻き戻さず残す").hasSize(1);
        verify(repository, never()).save(any());
    }
}
