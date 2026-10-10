package com.letsblog.ai.github;

import com.letsblog.common.client.ExternalCallLoggingInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.ai.config.LegacyJacksonRestClientConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

/**
 * GitHub REST API(issues)を利用したissue作成クライアント。
 * Octokit等のライブラリは導入せず、LlmClient/WordPressAdapterと同様にRestClientの薄いラッパーとして実装する。
 *
 * ベースURLは{@code app.github-api-base-url}(既定 https://api.github.com)。BraveSearchClientと
 * 同じく設定値にしてあるのは、受け入れテストがスタブへ向けられるようにするため(issue #928)。
 * 実GitHubへ向けたままだとテストのたびに本物のIssueが作られ、担当者が書き換わる。
 */
@Component
public class GithubClient {

    private final RestClient client;

    public GithubClient(
            @Qualifier("githubRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${app.github-api-base-url}") String baseUrl) {
        RestClient.Builder clonedBuilder = restClientBuilder.clone().baseUrl(baseUrl)
                .requestInterceptor(new ExternalCallLoggingInterceptor("github"));
        LegacyJacksonRestClientConfig.preferJackson2(clonedBuilder);
        this.client = clonedBuilder.build();
    }

    public GithubIssue createIssue(String token, String owner, String repo, String title, String body) {
        ObjectNode requestBody = JsonNodeFactory.instance.objectNode()
                .put("title", title)
                .put("body", body != null ? body : "");

        try {
            JsonNode response = client.post()
                    .uri("/repos/{owner}/{repo}/issues", owner, repo)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);

            return new GithubIssue(response.get("number").asInt(), response.get("html_url").asText());
        } catch (RestClientResponseException e) {
            throw new GithubApiException(errorMessage(owner, repo, e), e);
        } catch (Exception e) {
            throw new GithubApiException("GitHub API呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * リポジトリのissue一覧を取得する。GitHubのissues APIはPull Requestも含めて返すため、
     * レスポンスに"pull_request"キーを持つ要素(=PR)は除外する。
     */
    public List<GithubIssueSummary> listIssues(String token, String owner, String repo, String state) {
        try {
            JsonNode response = client.get()
                    .uri("/repos/{owner}/{repo}/issues?state={state}&per_page=100", owner, repo, state)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .body(JsonNode.class);

            List<GithubIssueSummary> issues = new ArrayList<>();
            if (response != null && response.isArray()) {
                for (JsonNode node : response) {
                    if (node.has("pull_request")) {
                        continue;
                    }
                    issues.add(new GithubIssueSummary(
                            node.get("number").asInt(),
                            node.get("title").asText(),
                            node.get("html_url").asText(),
                            node.get("state").asText(),
                            extractAssignees(node)));
                }
            }
            return issues;
        } catch (RestClientResponseException e) {
            throw new GithubApiException(errorMessage(owner, repo, e, "issue一覧の取得"), e);
        } catch (Exception e) {
            throw new GithubApiException("GitHub API呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * 既存issueのbody(description)を上書き更新する。
     */
    public GithubIssue updateIssueBody(String token, String owner, String repo, int issueNumber, String body) {
        ObjectNode requestBody = JsonNodeFactory.instance.objectNode()
                .put("body", body != null ? body : "");

        try {
            JsonNode response = client.patch()
                    .uri("/repos/{owner}/{repo}/issues/{issueNumber}", owner, repo, issueNumber)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);

            return new GithubIssue(response.get("number").asInt(), response.get("html_url").asText());
        } catch (RestClientResponseException e) {
            throw new GithubApiException(errorMessage(owner, repo, e, "issueの更新"), e);
        } catch (Exception e) {
            throw new GithubApiException("GitHub API呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * issueの現在のbody(description)を取得する。まだ未設定の場合は空文字列を返す。
     */
    public String getIssueBody(String token, String owner, String repo, int issueNumber) {
        try {
            JsonNode response = client.get()
                    .uri("/repos/{owner}/{repo}/issues/{issueNumber}", owner, repo, issueNumber)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .body(JsonNode.class);

            return response != null ? response.path("body").asText("") : "";
        } catch (RestClientResponseException e) {
            throw new GithubApiException(errorMessage(owner, repo, e, "issueの取得"), e);
        } catch (Exception e) {
            throw new GithubApiException("GitHub API呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private List<String> extractAssignees(JsonNode node) {
        List<String> assignees = new ArrayList<>();
        JsonNode assigneesNode = node.get("assignees");
        if (assigneesNode != null && assigneesNode.isArray()) {
            for (JsonNode assigneeNode : assigneesNode) {
                assignees.add(assigneeNode.get("login").asText());
            }
        }
        return assignees;
    }

    /**
     * 認証されたユーザー情報を取得する。PATの所有者のGitHub loginを得る。issueのassignee指定に必要。
     */
    public GithubUser getAuthenticatedUser(String token) {
        try {
            JsonNode response = client.get()
                    .uri("/user")
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .retrieve()
                    .body(JsonNode.class);

            return new GithubUser(response.get("login").asText());
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 401) {
                throw new GithubApiException(
                        "GitHubの認証に失敗しました。Personal Access Tokenが無効またはスコープが不足している可能性があります。", e);
            }
            throw new GithubApiException(
                    "GitHub認証ユーザー情報の取得に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            throw new GithubApiException("GitHub API呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * issueのassigneesとlabelsを設定する。既存issueのbodyは上書きしない。
     */
    public GithubIssue assignAndLabelIssue(
            String token, String owner, String repo, int issueNumber, List<String> assignees, List<String> labels) {
        ObjectNode requestBody = JsonNodeFactory.instance.objectNode();
        requestBody.set("assignees", JsonNodeFactory.instance.arrayNode().addAll(
                assignees.stream().map(JsonNodeFactory.instance::textNode).toList()));
        requestBody.set("labels", JsonNodeFactory.instance.arrayNode().addAll(
                labels.stream().map(JsonNodeFactory.instance::textNode).toList()));

        try {
            JsonNode response = client.patch()
                    .uri("/repos/{owner}/{repo}/issues/{issueNumber}", owner, repo, issueNumber)
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(JsonNode.class);

            return new GithubIssue(response.get("number").asInt(), response.get("html_url").asText());
        } catch (RestClientResponseException e) {
            throw new GithubApiException(errorMessage(owner, repo, e, "issueへの割り当て"), e);
        } catch (Exception e) {
            throw new GithubApiException("GitHub API呼び出しに失敗しました: " + e.getMessage(), e);
        }
    }

    private String errorMessage(String owner, String repo, RestClientResponseException e) {
        return errorMessage(owner, repo, e, "issueの作成");
    }

    private String errorMessage(String owner, String repo, RestClientResponseException e, String action) {
        int status = e.getStatusCode().value();
        if (status == 401) {
            return "GitHubの認証に失敗しました。Personal Access Tokenが無効またはスコープが不足している可能性があります。";
        }
        if (status == 404) {
            return "リポジトリが見つかりません: " + owner + "/" + repo;
        }
        return "GitHub " + action + "に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString();
    }
}
