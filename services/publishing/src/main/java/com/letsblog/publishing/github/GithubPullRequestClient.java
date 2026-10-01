package com.letsblog.publishing.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.config.LegacyJacksonRestClientConfig;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * GitHub REST APIのPull Request系を呼ぶクライアント(issue #1337、Epic #1333)。
 * ai-serviceの{@code GithubClient}と同じくRestClientの薄いラッパーで、Octokit等は導入しない。
 *
 * <p>ベースURLは{@code app.github-api-base-url}(既定 https://api.github.com)。受け入れテストが
 * GitHubスタブへ向けられるよう設定値にしている。トークンとowner/repoは呼び出し側が
 * project-serviceの内部ブリッジから解決して渡す(ここでは解決規則を持たない)。
 *
 * <p>ai-serviceの{@code GithubClient}と異なり、403を権限不足として区別して報告する。
 * PR操作にはIssuesスコープだけでは足りず(Pull requests / Contents)、既存トークンのままでは
 * 必ず403に当たるため、汎用メッセージへ落とすと利用者に原因が伝わらない。
 */
@Component
public class GithubPullRequestClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    private static final String GITHUB_JSON = "application/vnd.github+json";
    private static final String GITHUB_RAW = "application/vnd.github.raw";
    private static final int FILES_PER_PAGE = 100;
    /** GitHubが変更ファイル一覧で返す上限(3000件)を{@link #FILES_PER_PAGE}件ずつ辿った場合のページ数。 */
    private static final int MAX_FILE_PAGES = 30;

    private final RestClient client;

    public GithubPullRequestClient(
            RestClient.Builder restClientBuilder, @Value("${app.github-api-base-url}") String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        RestClient.Builder cloned = restClientBuilder.clone().baseUrl(baseUrl).requestFactory(requestFactory);
        LegacyJacksonRestClientConfig.preferJackson2(cloned);
        this.client = cloned.build();
    }

    /**
     * 開いているPRの一覧を返す。
     *
     * <p>GitHubの{@code GET /repos/{o}/{r}/pulls}は既定で30件/ページなので{@code per_page=100}(上限)を付ける。
     * それ以上のページングは当面行わない: 記事PRが100件同時に開くことは想定しないため、
     * Linkヘッダーを辿る実装(と、そのテスト・失敗時の扱い)を持ち込まない。101件目以降が現実になったら
     * ここでページングを足すこと。
     */
    public List<GithubPullRequestSummary> listOpenPullRequests(GithubAccess access) {
        JsonNode response = get(access, "Pull Requestの一覧取得", "リポジトリが見つかりません: " + slug(access),
                "/repos/{owner}/{repo}/pulls?state=open&per_page=100");
        List<GithubPullRequestSummary> result = new ArrayList<>();
        if (response != null && response.isArray()) {
            for (JsonNode node : response) {
                result.add(new GithubPullRequestSummary(
                        node.path("number").asInt(),
                        node.path("title").asText(""),
                        node.path("head").path("ref").asText(""),
                        node.path("created_at").asText(""),
                        node.path("html_url").asText("")));
            }
        }
        return result;
    }

    /** PR1件の詳細(head.sha・head.ref・mergeable・merged)を返す。 */
    public GithubPullRequestDetail getPullRequest(GithubAccess access, int number) {
        JsonNode response = get(access, "Pull Requestの取得",
                "Pull Request #" + number + " が見つかりません: " + slug(access),
                "/repos/{owner}/{repo}/pulls/" + number);
        if (response == null) {
            throw new GithubApiException("GitHubから空の応答を受け取りました(Pull Request #" + number + ")");
        }
        JsonNode mergeable = response.path("mergeable");
        return new GithubPullRequestDetail(
                number,
                response.path("head").path("sha").asText(""),
                response.path("head").path("ref").asText(""),
                mergeable.isBoolean() ? mergeable.asBoolean() : null,
                response.path("merged").asBoolean(false));
    }

    /** リポジトリの{@code default_branch}を返す。 */
    public String getDefaultBranch(GithubAccess access) {
        JsonNode response = get(access, "リポジトリ情報の取得", "リポジトリが見つかりません: " + slug(access),
                "/repos/{owner}/{repo}");
        String branch = response == null ? "" : response.path("default_branch").asText("");
        if (branch.isBlank()) {
            throw new GithubApiException("GitHubの応答からdefault_branchを読み取れませんでした: " + slug(access));
        }
        return branch;
    }

    /**
     * headブランチがリポジトリに在るかを返す(issue #1339)。404は「無い」として偽を返す(GitHubはリポジトリ不在も
     * 404で返すため区別できないが、リポジトリの存在はこの前に解決済みのアクセス情報が保証する)。
     * 認証失敗・権限不足・通信失敗は偽にせず{@link GithubApiException}にする。
     */
    public boolean branchExists(GithubAccess access, String branch) {
        List<Object> vars = new ArrayList<>();
        vars.add(access.owner());
        vars.add(access.repo());
        StringBuilder template = new StringBuilder("/repos/{owner}/{repo}/branches");
        int index = 0;
        for (String segment : branch.split("/")) {
            template.append("/{s").append(index++).append('}');
            vars.add(segment);
        }
        try {
            client.get()
                    .uri(template.toString(), vars.toArray())
                    .header("Authorization", "Bearer " + access.token())
                    .accept(MediaType.parseMediaType(GITHUB_JSON))
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return false;
            }
            throw new GithubApiException(errorMessage(e, "ブランチの確認", "ブランチが見つかりません: " + branch), e);
        } catch (RestClientException e) {
            throw new GithubApiException("GitHub API呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * Pull Requestを作成して返す(issue #1339)。{@code head}は既にプッシュ済みのブランチ名、{@code base}は
     * 呼び出し側が解決したリポジトリの{@code default_branch}。
     */
    public GithubPullRequestSummary createPullRequest(
            GithubAccess access, String title, String body, String head, String base) {
        JsonNode response;
        try {
            response = client.post()
                    .uri("/repos/{owner}/{repo}/pulls", access.owner(), access.repo())
                    .header("Authorization", "Bearer " + access.token())
                    .accept(MediaType.parseMediaType(GITHUB_JSON))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("title", title, "body", body, "head", head, "base", base))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw new GithubApiException(
                    errorMessage(e, "Pull Requestの作成", "リポジトリが見つかりません: " + slug(access)), e);
        } catch (RestClientException e) {
            throw new GithubApiException("GitHub API呼び出しに失敗しました: " + e.getMessage(), e);
        }
        if (response == null) {
            throw new GithubApiException("GitHubから空の応答を受け取りました(Pull Requestの作成: " + head + ")");
        }
        return new GithubPullRequestSummary(
                response.path("number").asInt(),
                response.path("title").asText(""),
                response.path("head").path("ref").asText(""),
                response.path("created_at").asText(""),
                response.path("html_url").asText(""));
    }

    /**
     * PRの変更ファイル一覧(パスと状態)を返す(issue #1338)。{@code per_page=100}でページを辿り、
     * 100件未満のページで止める。GitHubは変更ファイルを最大3000件までしか返さないため、
     * 辿るページ数の上限もそこに合わせる。
     */
    public List<GithubChangedFile> listPullRequestFiles(GithubAccess access, int number) {
        List<GithubChangedFile> result = new ArrayList<>();
        for (int page = 1; page <= MAX_FILE_PAGES; page++) {
            JsonNode response = get(access, "Pull Requestの変更ファイル取得",
                    "Pull Request #" + number + " が見つかりません: " + slug(access),
                    "/repos/{owner}/{repo}/pulls/" + number + "/files?per_page=" + FILES_PER_PAGE + "&page=" + page);
            if (response == null || !response.isArray()) {
                break;
            }
            for (JsonNode node : response) {
                result.add(new GithubChangedFile(node.path("filename").asText(""), node.path("status").asText("")));
            }
            if (response.size() < FILES_PER_PAGE) {
                break;
            }
        }
        return result;
    }

    /**
     * {@code ref}(コミットSHAまたはブランチ名)時点のファイルの中身を返す(issue #1338)。
     *
     * <p>contents APIは1MBを超えるファイルの{@code content}を返さない({@code null}または空で{@code size}のみ)。
     * アイキャッチ画像は容易に1MBを超えるため、その場合は{@code git/blobs/{sha}}を
     * {@code Accept: application/vnd.github.raw}で読み直す(blobs APIの通常のJSON応答は1MB超で
     * base64が肥大し上限にも当たるため、rawで生のバイト列を受ける)。
     */
    public byte[] getFileContent(GithubAccess access, String path, String ref) {
        List<Object> vars = new ArrayList<>();
        vars.add(access.owner());
        vars.add(access.repo());
        StringBuilder template = new StringBuilder("/repos/{owner}/{repo}/contents");
        int index = 0;
        for (String segment : path.split("/")) {
            template.append("/{s").append(index++).append('}');
            vars.add(segment);
        }
        template.append("?ref={ref}");
        vars.add(ref);
        String notFound = "ファイルが見つかりません: " + path + " (ref: " + ref + ", " + slug(access) + ")";
        JsonNode response = exchange(access, "ファイルの取得", notFound, MediaType.parseMediaType(GITHUB_JSON),
                JsonNode.class, template.toString(), vars.toArray());
        if (response == null) {
            throw new GithubApiException("GitHubから空の応答を受け取りました(" + path + ")");
        }
        JsonNode content = response.path("content");
        if (content.isTextual() && "base64".equals(response.path("encoding").asText("base64"))
                && (!content.asText().isEmpty() || response.path("size").asLong(0) == 0)) {
            return Base64.getMimeDecoder().decode(content.asText());
        }
        String sha = response.path("sha").asText("");
        if (sha.isBlank()) {
            throw new GithubApiException("GitHubの応答からファイルの内容もblobのshaも読み取れませんでした: " + path);
        }
        byte[] raw = exchange(access, "blobの取得", "blobが見つかりません: " + path + " (" + sha + ")",
                MediaType.parseMediaType(GITHUB_RAW), byte[].class, "/repos/{owner}/{repo}/git/blobs/{sha}",
                access.owner(), access.repo(), sha);
        if (raw == null || raw.length == 0) {
            throw new GithubApiException("GitHubから空の応答を受け取りました(blob " + sha + ": " + path + ")");
        }
        return raw;
    }

    private JsonNode get(GithubAccess access, String action, String notFoundMessage, String uriTemplate) {
        return exchange(access, action, notFoundMessage, MediaType.parseMediaType(GITHUB_JSON), JsonNode.class,
                uriTemplate, access.owner(), access.repo());
    }

    private <T> T exchange(GithubAccess access, String action, String notFoundMessage, MediaType accept,
            Class<T> type, String uriTemplate, Object... uriVariables) {
        try {
            return client.get()
                    .uri(uriTemplate, uriVariables)
                    .header("Authorization", "Bearer " + access.token())
                    .accept(accept)
                    .retrieve()
                    .body(type);
        } catch (RestClientResponseException e) {
            throw new GithubApiException(errorMessage(e, action, notFoundMessage), e);
        } catch (RestClientException e) {
            throw new GithubApiException("GitHub API呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private static String errorMessage(RestClientResponseException e, String action, String notFoundMessage) {
        int status = e.getStatusCode().value();
        if (status == 401) {
            return "GitHubの認証に失敗しました。Personal Access Tokenが無効または期限切れの可能性があります。";
        }
        if (status == 403) {
            // GitHubはレート制限超過も403で返す。権限不足と取り違えないよう残数ヘッダーで見分ける。
            String remaining = e.getResponseHeaders() == null
                    ? null : e.getResponseHeaders().getFirst("X-RateLimit-Remaining");
            if ("0".equals(remaining)) {
                return "GitHub APIのレート制限に達しました。しばらく待ってから再試行してください。";
            }
            return "GitHubトークンの権限が不足しています。Pull requests と Contents の読み取り権限を"
                    + "付与したトークンを設定してください。";
        }
        if (status == 404) {
            return notFoundMessage;
        }
        return "GitHub " + action + "に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString();
    }

    private static String slug(GithubAccess access) {
        return access.owner() + "/" + access.repo();
    }
}
