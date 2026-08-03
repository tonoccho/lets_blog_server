package com.letsblog.api.github;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
}
