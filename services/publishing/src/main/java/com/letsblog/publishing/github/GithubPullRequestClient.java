package com.letsblog.publishing.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.config.LegacyJacksonRestClientConfig;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
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

    private JsonNode get(GithubAccess access, String action, String notFoundMessage, String uriTemplate) {
        try {
            return client.get()
                    .uri(uriTemplate, access.owner(), access.repo())
                    .header("Authorization", "Bearer " + access.token())
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .body(JsonNode.class);
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
