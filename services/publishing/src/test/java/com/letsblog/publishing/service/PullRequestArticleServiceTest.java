package com.letsblog.publishing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.dto.PullRequestArticleResponse;
import com.letsblog.publishing.github.GithubChangedFile;
import com.letsblog.publishing.github.GithubPullRequestClient;
import com.letsblog.publishing.github.GithubPullRequestDetail;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** {@link PullRequestArticleService}の単体テスト(issue #1338)。 */
@ExtendWith(MockitoExtension.class)
class PullRequestArticleServiceTest {

    private static final GithubAccess ACCESS = new GithubAccess("t", "octo", "blog");
    private static final String HEAD = "headsha1";

    @Mock
    private GithubPullRequestClient client;

    private PullRequestArticleService service() {
        return new PullRequestArticleService(client);
    }

    private static GithubChangedFile added(String path) {
        return new GithubChangedFile(path, "added");
    }

    private void prWithFiles(GithubChangedFile... files) {
        when(client.getPullRequest(ACCESS, 7))
                .thenReturn(new GithubPullRequestDetail(7, HEAD, "article/x", true, false));
        when(client.listPullRequestFiles(ACCESS, 7)).thenReturn(List.of(files));
    }

    @Test
    @DisplayName("PRのheadをrefにarticle.mdとassetsを取得し、スラッグ・front matter・本文・assets(名前とサイズ)を返す")
    void fetchesArticleAtPullRequestHead() {
        prWithFiles(
                added("articles/sample/article.md"),
                added("articles/sample/assets/cover.png"),
                added("articles/sample/assets/sub/large.png"),
                new GithubChangedFile("articles/sample/assets/old.png", "removed"),
                added("README.md"),
                added("articles/README.md"));
        when(client.getFileContent(ACCESS, "articles/sample/article.md", HEAD))
                .thenReturn("---\ntitle: T\nslug: sample\ncategory: news\n---\n本文\n".getBytes(StandardCharsets.UTF_8));
        when(client.getFileContent(ACCESS, "articles/sample/assets/cover.png", HEAD)).thenReturn(new byte[68]);
        when(client.getFileContent(ACCESS, "articles/sample/assets/sub/large.png", HEAD))
                .thenReturn(new byte[1024 * 1024 + 5]);

        PullRequestArticleResponse response = service().fetch(ACCESS, 7);

        assertThat(response.slug()).isEqualTo("sample");
        assertThat(response.frontMatter().title()).isEqualTo("T");
        assertThat(response.frontMatter().slug()).isEqualTo("sample");
        assertThat(response.frontMatter().categories()).containsExactly("news");
        assertThat(response.body()).isEqualTo("本文\n");
        assertThat(response.assets()).containsExactly(
                new PullRequestArticleResponse.Asset("cover.png", 68),
                new PullRequestArticleResponse.Asset("sub/large.png", 1024L * 1024 + 5));
        verify(client, never()).getFileContent(ACCESS, "articles/sample/assets/old.png", HEAD);
    }

    @Test
    @DisplayName("assetsが無い記事はassetsが空")
    void noAssets() {
        prWithFiles(added("articles/sample/article.md"));
        when(client.getFileContent(ACCESS, "articles/sample/article.md", HEAD))
                .thenReturn("本文".getBytes(StandardCharsets.UTF_8));

        assertThat(service().fetch(ACCESS, 7).assets()).isEmpty();
    }

    @Test
    @DisplayName("記事ディレクトリを含まないPRは「記事が見つからない」(NOT_FOUND)")
    void noArticleDirectory() {
        prWithFiles(added("README.md"), added("docs/a.md"));

        assertThatThrownBy(() -> service().fetch(ACCESS, 7))
                .isInstanceOfSatisfying(PullRequestArticleException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(PullRequestArticleException.Kind.NOT_FOUND);
                    assertThat(e.getMessage()).contains("記事").contains("#7");
                });
        verify(client, never()).getFileContent(any(GithubAccess.class), anyString(), anyString());
    }

    @Test
    @DisplayName("記事ディレクトリが削除だけのPRも「記事が見つからない」")
    void onlyRemovedArticle() {
        prWithFiles(new GithubChangedFile("articles/gone/article.md", "removed"));

        assertThatThrownBy(() -> service().fetch(ACCESS, 7))
                .isInstanceOfSatisfying(PullRequestArticleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(PullRequestArticleException.Kind.NOT_FOUND));
    }

    @Test
    @DisplayName("記事ディレクトリが2つ以上あるPRは1 PR = 1 記事に反するとして、ディレクトリ名を挙げて拒否する(MULTIPLE)")
    void multipleArticleDirectories() {
        prWithFiles(added("articles/b/article.md"), added("articles/a/article.md"), added("articles/a/assets/x.png"));

        assertThatThrownBy(() -> service().fetch(ACCESS, 7))
                .isInstanceOfSatisfying(PullRequestArticleException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(PullRequestArticleException.Kind.MULTIPLE);
                    assertThat(e.getMessage()).contains("1 PR = 1 記事").contains("articles/a").contains("articles/b");
                });
    }

    @Test
    @DisplayName("スラッグが^[a-z0-9][a-z0-9-]*$に合わないディレクトリは拒否する(INVALID)")
    void invalidSlug() {
        prWithFiles(added("articles/Bad_Slug/article.md"));

        assertThatThrownBy(() -> service().fetch(ACCESS, 7))
                .isInstanceOfSatisfying(PullRequestArticleException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(PullRequestArticleException.Kind.INVALID);
                    assertThat(e.getMessage()).contains("Bad_Slug");
                });
    }

    @Test
    @DisplayName("先頭がハイフンのスラッグも拒否する")
    void leadingHyphenSlug() {
        prWithFiles(added("articles/-x/article.md"));

        assertThatThrownBy(() -> service().fetch(ACCESS, 7))
                .isInstanceOfSatisfying(PullRequestArticleException.class,
                        e -> assertThat(e.getKind()).isEqualTo(PullRequestArticleException.Kind.INVALID));
    }

    @Test
    @DisplayName("記事ディレクトリはあるがarticle.mdがPRに無ければ、article.mdが無いと報告する(NOT_FOUND)")
    void missingArticleMd() {
        prWithFiles(added("articles/sample/assets/cover.png"));

        assertThatThrownBy(() -> service().fetch(ACCESS, 7))
                .isInstanceOfSatisfying(PullRequestArticleException.class, e -> {
                    assertThat(e.getKind()).isEqualTo(PullRequestArticleException.Kind.NOT_FOUND);
                    assertThat(e.getMessage()).contains("articles/sample/article.md");
                });
    }

    @Test
    @DisplayName("head.shaが読み取れない(空・null)ときはGitHub応答の不備としてGithubApiExceptionにする")
    void blankHeadSha() {
        for (String sha : new String[] {"", null}) {
            org.mockito.Mockito.reset(client);
            when(client.getPullRequest(ACCESS, 7))
                    .thenReturn(new GithubPullRequestDetail(7, sha, "article/x", true, false));
            when(client.listPullRequestFiles(ACCESS, 7)).thenReturn(List.of(added("articles/sample/article.md")));

            assertThatThrownBy(() -> service().fetch(ACCESS, 7))
                    .isInstanceOf(com.letsblog.publishing.github.GithubApiException.class)
                    .hasMessageContaining("head.sha");
        }
    }

    @Test
    @DisplayName("assets/そのもの(ファイル名が無いパス)と、articles以外のディレクトリ配下は記事としても資産としても数えない")
    void ignoresBareAssetsDirectoryAndOtherRoots() {
        prWithFiles(
                added("articles/sample/article.md"),
                added("articles/sample/assets/"),
                added("docs/sample/article.md"));
        when(client.getFileContent(ACCESS, "articles/sample/article.md", HEAD))
                .thenReturn("本文".getBytes(StandardCharsets.UTF_8));

        PullRequestArticleResponse response = service().fetch(ACCESS, 7);

        assertThat(response.slug()).isEqualTo("sample");
        assertThat(response.assets()).isEmpty();
    }

    @Test
    @DisplayName("投稿用の取得は、記事の応答に加えてassetsの中身(assets/からの相対パス → バイト列)をheadから返す")
    void fetchForPublishReturnsAssetBytes() {
        prWithFiles(
                added("articles/sample/article.md"),
                added("articles/sample/assets/cover.png"),
                added("articles/sample/assets/sub/x.png"));
        when(client.getFileContent(ACCESS, "articles/sample/article.md", HEAD))
                .thenReturn("---\ntitle: T\n---\n本文\n".getBytes(StandardCharsets.UTF_8));
        when(client.getFileContent(ACCESS, "articles/sample/assets/cover.png", HEAD)).thenReturn(new byte[] {1, 2});
        when(client.getFileContent(ACCESS, "articles/sample/assets/sub/x.png", HEAD)).thenReturn(new byte[] {3});

        PullRequestArticleService.PublishableArticle result = service().fetchForPublish(ACCESS, 7);

        assertThat(result.article().slug()).isEqualTo("sample");
        assertThat(result.article().body()).isEqualTo("本文\n");
        assertThat(result.article().assets()).containsExactly(
                new PullRequestArticleResponse.Asset("cover.png", 2),
                new PullRequestArticleResponse.Asset("sub/x.png", 1));
        assertThat(result.assetBytes().keySet()).containsExactly("cover.png", "sub/x.png");
        assertThat(result.assetBytes().get("cover.png")).containsExactly(1, 2);
        assertThat(result.assetBytes().get("sub/x.png")).containsExactly(3);
    }
}
