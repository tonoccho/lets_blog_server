package com.letsblog.api.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * GitHub REST API(issues)を利用したissue作成クライアント。
 * Octokit等のライブラリは導入せず、OllamaClient/WordPressAdapterと同様にRestClientの薄いラッパーとして実装する。
 */
@Component
public class GithubClient {

    private final RestClient client;

    public GithubClient(RestClient.Builder restClientBuilder) {
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

    private String errorMessage(String owner, String repo, RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 401) {
            return "GitHubの認証に失敗しました。Personal Access Tokenが無効またはスコープが不足している可能性があります。";
        }
        if (status == 404) {
            return "リポジトリが見つかりません: " + owner + "/" + repo;
        }
        return "GitHub issueの作成に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString();
    }
}
