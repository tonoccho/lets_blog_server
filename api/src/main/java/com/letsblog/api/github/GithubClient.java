package com.letsblog.api.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;

/**
 * GitHub REST API(issues)を利用したissue作成クライアント。
 * Octokit等のライブラリは導入せず、OllamaClient/WordPressAdapterと同様にRestClientの薄いラッパーとして実装する。
 */
@Component
public class GithubClient {

    private final RestClient client;

    public GithubClient(@Qualifier("githubRestClientBuilder") RestClient.Builder restClientBuilder) {
        this.client = restClientBuilder.clone().baseUrl("https://api.github.com").build();
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
                            node.get("state").asText()));
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
