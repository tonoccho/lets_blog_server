package com.letsblog.api.github;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * GithubClientの回帰テスト。WordPressAdapterTestと同様、MockRestServiceServerでHTTP通信を検証する。
 */
class GithubClientTest {

    private GithubClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GithubClient(builder);
    }

    @Test
    void createIssue_成功時にissue番号とURLを返す() {
        server.expect(requestTo("https://api.github.com/repos/owner/repo/issues"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andExpect(content().string(containsString("\"title\":\"新しい記事タイトル\"")))
                .andRespond(withSuccess(
                        "{\"number\":42,\"html_url\":\"https://github.com/owner/repo/issues/42\"}",
                        MediaType.APPLICATION_JSON));

        GithubIssue issue = client.createIssue("test-token", "owner", "repo", "新しい記事タイトル", "");

        assertEquals(42, issue.number());
        assertEquals("https://github.com/owner/repo/issues/42", issue.htmlUrl());
        server.verify();
    }

    @Test
    void createIssue_401の場合は認証エラーメッセージになる() {
        server.expect(requestTo("https://api.github.com/repos/owner/repo/issues"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("{\"message\":\"Bad credentials\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        GithubApiException exception = assertThrows(GithubApiException.class,
                () -> client.createIssue("invalid-token", "owner", "repo", "タイトル", ""));

        org.hamcrest.MatcherAssert.assertThat(exception.getMessage(), containsString("認証に失敗しました"));
        server.verify();
    }

    @Test
    void createIssue_404の場合はリポジトリ未検出メッセージになる() {
        server.expect(requestTo("https://api.github.com/repos/owner/missing-repo/issues"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .body("{\"message\":\"Not Found\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        GithubApiException exception = assertThrows(GithubApiException.class,
                () -> client.createIssue("test-token", "owner", "missing-repo", "タイトル", ""));

        org.hamcrest.MatcherAssert.assertThat(exception.getMessage(), containsString("リポジトリが見つかりません"));
        server.verify();
    }

    @Test
    void createIssue_その他のHTTPエラーは汎用的なメッセージになる() {
        server.expect(requestTo("https://api.github.com/repos/owner/repo/issues"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .body("{\"message\":\"Validation Failed\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        GithubApiException exception = assertThrows(GithubApiException.class,
                () -> client.createIssue("test-token", "owner", "repo", "タイトル", ""));

        org.hamcrest.MatcherAssert.assertThat(exception.getMessage(), containsString("issueの作成に失敗しました"));
        server.verify();
    }

    @Test
    void listIssues_成功時に一覧をパースする() {
        server.expect(requestTo(containsString("https://api.github.com/repos/owner/repo/issues")))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess(
                        "[{\"number\":1,\"title\":\"記事案1\",\"html_url\":\"https://github.com/owner/repo/issues/1\",\"state\":\"open\"}]",
                        MediaType.APPLICATION_JSON));

        List<GithubIssueSummary> issues = client.listIssues("test-token", "owner", "repo", "open");

        assertEquals(1, issues.size());
        assertEquals(1, issues.get(0).number());
        assertEquals("記事案1", issues.get(0).title());
        assertEquals("https://github.com/owner/repo/issues/1", issues.get(0).htmlUrl());
        assertEquals("open", issues.get(0).state());
        server.verify();
    }

    @Test
    void listIssues_pull_requestキーを持つ要素は除外される() {
        server.expect(requestTo(containsString("/repos/owner/repo/issues")))
                .andRespond(withSuccess(
                        "[{\"number\":1,\"title\":\"記事案\",\"html_url\":\"https://github.com/owner/repo/issues/1\",\"state\":\"open\"},"
                        + "{\"number\":2,\"title\":\"PR\",\"html_url\":\"https://github.com/owner/repo/pull/2\",\"state\":\"open\","
                        + "\"pull_request\":{\"url\":\"https://api.github.com/repos/owner/repo/pulls/2\"}}]",
                        MediaType.APPLICATION_JSON));

        List<GithubIssueSummary> issues = client.listIssues("test-token", "owner", "repo", "all");

        assertEquals(1, issues.size());
        assertEquals(1, issues.get(0).number());
        server.verify();
    }

    @Test
    void listIssues_stateクエリパラメータが送信される() {
        server.expect(requestTo(containsString("state=closed")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        client.listIssues("test-token", "owner", "repo", "closed");

        server.verify();
    }

    @Test
    void listIssues_401の場合は認証エラーメッセージになる() {
        server.expect(requestTo(containsString("/repos/owner/repo/issues")))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("{\"message\":\"Bad credentials\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        GithubApiException exception = assertThrows(GithubApiException.class,
                () -> client.listIssues("invalid-token", "owner", "repo", "open"));

        org.hamcrest.MatcherAssert.assertThat(exception.getMessage(), containsString("認証に失敗しました"));
        server.verify();
    }
}
