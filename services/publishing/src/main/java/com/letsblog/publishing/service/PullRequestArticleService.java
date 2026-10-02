package com.letsblog.publishing.service;

import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.dto.PullRequestArticleResponse;
import com.letsblog.publishing.dto.PullRequestArticleResponse.Asset;
import com.letsblog.publishing.github.GithubApiException;
import com.letsblog.publishing.github.GithubChangedFile;
import com.letsblog.publishing.github.GithubPullRequestClient;
import com.letsblog.publishing.github.GithubPullRequestDetail;
import com.letsblog.publishing.service.PullRequestArticleException.Kind;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * PRのheadから記事1本分(article.mdとassets/)を取得する(issue #1338)。
 *
 * <p>記事ディレクトリはPRの変更ファイル一覧から{@code articles/<slug>/}を特定する。front matterへ
 * Issue番号やリポジトリ名を書き戻す方式は採らない(#505で廃止済み)。ファイルはPRの
 * {@code head.sha}をrefに読むので、既定ブランチの内容が混ざらず、headを進めれば応答も変わる。
 */
@Service
public class PullRequestArticleService {

    /** 拡張側{@code articleScaffold.ts}の{@code SLUG_PATTERN}と同じ。パス区切りの混入も弾く。 */
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    private static final String ARTICLES_ROOT = "articles";

    private final GithubPullRequestClient githubPullRequestClient;

    public PullRequestArticleService(GithubPullRequestClient githubPullRequestClient) {
        this.githubPullRequestClient = githubPullRequestClient;
    }

    /**
     * 投稿用に取得した記事。{@code assetBytes}は{@code assets/}からの相対パス(応答の{@code Asset#name}と同じ)
     * をキーにしたバイト列で、{@code article.assets()}と同じ順序。
     */
    public record PublishableArticle(PullRequestArticleResponse article, Map<String, byte[]> assetBytes) {
    }

    public PullRequestArticleResponse fetch(GithubAccess access, int prNumber) {
        return fetchForPublish(access, prNumber).article();
    }

    /** {@link #fetch}と同じ記事に、assetsの中身(headの時点のバイト列)を添えて返す(issue #1341、投稿用)。 */
    public PublishableArticle fetchForPublish(GithubAccess access, int prNumber) {
        GithubPullRequestDetail detail = githubPullRequestClient.getPullRequest(access, prNumber);
        List<GithubChangedFile> files = githubPullRequestClient.listPullRequestFiles(access, prNumber).stream()
                .filter(file -> !file.removed())
                .toList();

        String slug = resolveSlug(prNumber, files);
        String directory = ARTICLES_ROOT + "/" + slug + "/";
        String articlePath = directory + "article.md";
        if (files.stream().noneMatch(file -> file.path().equals(articlePath))) {
            throw new PullRequestArticleException(Kind.NOT_FOUND,
                    "Pull Request #" + prNumber + " に " + articlePath + " が含まれていません: 記事が見つかりません");
        }
        String ref = detail.headSha();
        if (ref == null || ref.isBlank()) {
            throw new GithubApiException("GitHubの応答からPull Request #" + prNumber + " のhead.shaを読み取れませんでした");
        }

        String markdown = new String(githubPullRequestClient.getFileContent(access, articlePath, ref),
                StandardCharsets.UTF_8);
        ArticleFrontMatterParser.ParsedArticle parsed = ArticleFrontMatterParser.parse(markdown);

        String assetsPrefix = directory + "assets/";
        Map<String, byte[]> assetBytes = new TreeMap<>();
        files.stream()
                .map(GithubChangedFile::path)
                .filter(path -> path.startsWith(assetsPrefix) && path.length() > assetsPrefix.length())
                .forEach(path -> assetBytes.put(path.substring(assetsPrefix.length()),
                        githubPullRequestClient.getFileContent(access, path, ref)));
        List<Asset> assets = assetBytes.entrySet().stream()
                .map(entry -> new Asset(entry.getKey(), entry.getValue().length))
                .toList();

        return new PublishableArticle(
                new PullRequestArticleResponse(slug, parsed.frontMatter(), parsed.content(), assets), assetBytes);
    }

    /** 変更ファイルの{@code articles/<slug>/...}から記事ディレクトリを1つに特定する。 */
    private static String resolveSlug(int prNumber, List<GithubChangedFile> files) {
        TreeSet<String> slugs = new TreeSet<>();
        for (GithubChangedFile file : files) {
            String[] segments = file.path().split("/");
            if (segments.length >= 3 && ARTICLES_ROOT.equals(segments[0])) {
                slugs.add(segments[1]);
            }
        }
        if (slugs.isEmpty()) {
            throw new PullRequestArticleException(Kind.NOT_FOUND,
                    "Pull Request #" + prNumber + " に記事が見つかりません: " + ARTICLES_ROOT
                            + "/<slug>/ を含む変更がありません");
        }
        if (slugs.size() > 1) {
            throw new PullRequestArticleException(Kind.MULTIPLE,
                    "Pull Request #" + prNumber + " に記事ディレクトリが" + slugs.size() + "つ含まれています("
                            + String.join(", ", slugs.stream().map(s -> ARTICLES_ROOT + "/" + s).toList())
                            + ")。1 PR = 1 記事の前提に反するため取得できません");
        }
        String slug = slugs.first();
        if (!SLUG_PATTERN.matcher(slug).matches()) {
            throw new PullRequestArticleException(Kind.INVALID,
                    "記事ディレクトリ名「" + slug + "」はスラッグとして不正です(英小文字・数字・ハイフンのみ、先頭は英小文字か数字)");
        }
        return slug;
    }
}
