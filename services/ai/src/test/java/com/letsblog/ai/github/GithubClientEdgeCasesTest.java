package com.letsblog.ai.github;

import com.letsblog.ai.testsupport.FakeHttpServer;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GithubClientの境界的な応答(issue #1824): bodyがnull・応答が空や配列でない・assigneesが配列でない・
 * 認証ユーザー取得の401とそれ以外。HTTPはサーバー側のフェイクで受ける。
 * 通常の成功・エラー応答は{@link GithubClientTest}が担当する。
 */
class GithubClientEdgeCasesTest {

    private static final String ISSUES = "/repos/o/r/issues";

    private FakeHttpServer server;
    private GithubClient client;

    @BeforeEach
    void setUp() {
        server = new FakeHttpServer();
        client = new GithubClient(RestClient.builder(), server.baseUrl());
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void createIssue_bodyがnullなら空文字列で作る() {
        server.json("POST", ISSUES, "{\"number\":1,\"html_url\":\"u\"}");

        client.createIssue("t", "o", "r", "タイトル", null);

        assertTrue(server.last().bodyText().contains("\"body\":\"\""), server.last().bodyText());
    }

    @Test
    void createIssue_bodyを渡せばそのまま送る() {
        server.json("POST", ISSUES, "{\"number\":1,\"html_url\":\"u\"}");

        client.createIssue("t", "o", "r", "タイトル", "本文です");

        assertTrue(server.last().bodyText().contains("\"body\":\"本文です\""), server.last().bodyText());
    }

    @Test
    void createIssue_応答が空ならGithubApiExceptionにする() {
        server.on("POST", ISSUES, FakeHttpServer.Response.empty(201));

        GithubApiException e = assertThrows(GithubApiException.class, () -> client.createIssue("t", "o", "r", "タイトル", ""));
        assertTrue(e.getMessage().contains("GitHub API呼び出しに失敗しました"), e.getMessage());
    }

    @Test
    void updateIssueBody_bodyがnullなら空文字列で更新する() {
        server.json("PATCH", ISSUES + "/3", "{\"number\":3,\"html_url\":\"u\"}");

        client.updateIssueBody("t", "o", "r", 3, null);

        assertTrue(server.last().bodyText().contains("\"body\":\"\""), server.last().bodyText());
    }

    @Test
    void updateIssueBody_bodyを渡せばそのまま送る() {
        server.json("PATCH", ISSUES + "/3", "{\"number\":3,\"html_url\":\"u\"}");

        client.updateIssueBody("t", "o", "r", 3, "## 構成");

        assertTrue(server.last().bodyText().contains("\"body\":\"## 構成\""), server.last().bodyText());
    }

    @Test
    void updateIssueBody_応答が空ならGithubApiExceptionにする() {
        server.on("PATCH", ISSUES + "/3", FakeHttpServer.Response.empty(200));

        assertThrows(GithubApiException.class, () -> client.updateIssueBody("t", "o", "r", 3, "本文"));
    }

    @Test
    void listIssues_応答が空なら空リスト() {
        server.on("GET", ISSUES, FakeHttpServer.Response.empty(200));

        assertEquals(List.of(), client.listIssues("t", "o", "r", "open"));
    }

    @Test
    void listIssues_応答が配列でなければ空リスト() {
        server.json("GET", ISSUES, "{\"message\":\"配列ではない\"}");

        assertEquals(List.of(), client.listIssues("t", "o", "r", "open"));
    }

    @Test
    void listIssues_assigneesが無い_または配列でない要素はassigneesが空になる() {
        server.json("GET", ISSUES, "["
                + "{\"number\":1,\"title\":\"a\",\"html_url\":\"u1\",\"state\":\"open\"},"
                + "{\"number\":2,\"title\":\"b\",\"html_url\":\"u2\",\"state\":\"open\",\"assignees\":\"octocat\"},"
                + "{\"number\":3,\"title\":\"c\",\"html_url\":\"u3\",\"state\":\"open\",\"assignees\":[{\"login\":\"octocat\"}]}]");

        List<GithubIssueSummary> issues = client.listIssues("t", "o", "r", "open");

        assertEquals(3, issues.size());
        assertEquals(List.of(), issues.get(0).assignees());
        assertEquals(List.of(), issues.get(1).assignees());
        assertEquals(List.of("octocat"), issues.get(2).assignees());
    }

    @Test
    void getIssueBody_応答が空なら空文字列() {
        server.on("GET", ISSUES + "/3", FakeHttpServer.Response.empty(200));

        assertEquals("", client.getIssueBody("t", "o", "r", 3));
    }

    @Test
    void getAuthenticatedUser_401は認証失敗のメッセージ() {
        server.status("GET", "/user", 401, "{\"message\":\"Bad credentials\"}");

        GithubApiException e = assertThrows(GithubApiException.class, () -> client.getAuthenticatedUser("bad"));
        assertTrue(e.getMessage().contains("認証に失敗しました"), e.getMessage());
    }

    @Test
    void getAuthenticatedUser_401以外のエラーはステータスと本文を添えたメッセージ() {
        server.status("GET", "/user", 500, "{\"message\":\"boom\"}");

        GithubApiException e = assertThrows(GithubApiException.class, () -> client.getAuthenticatedUser("t"));
        assertTrue(e.getMessage().contains("取得に失敗しました"), e.getMessage());
        assertTrue(e.getMessage().contains("500"), e.getMessage());
        assertTrue(e.getMessage().contains("boom"), e.getMessage());
    }

    @Test
    void getAuthenticatedUser_応答が空ならGithubApiExceptionにする() {
        server.on("GET", "/user", FakeHttpServer.Response.empty(200));

        GithubApiException e = assertThrows(GithubApiException.class, () -> client.getAuthenticatedUser("t"));
        assertTrue(e.getMessage().contains("GitHub API呼び出しに失敗しました"), e.getMessage());
    }
}
