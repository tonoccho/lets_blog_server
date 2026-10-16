package com.letsblog.publishing.github;

import com.letsblog.publishing.client.ProjectServiceClient.GithubAccess;
import com.letsblog.publishing.testsupport.FakeHttpServer;
import com.letsblog.publishing.testsupport.FakeHttpServer.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GitHubが本文の無い200を返したときの{@link GithubPullRequestClient}(issue #1816)。GitHub APIは
 * {@link FakeHttpServer}(サーバー側のフェイク)で模し、クライアントは実物を使う。
 */
class GithubPullRequestClientEmptyResponseTest {

    private static final GithubAccess ACCESS = new GithubAccess("t", "octo", "blog");

    private FakeHttpServer github;
    private GithubPullRequestClient client;

    @BeforeEach
    void setUp() {
        github = new FakeHttpServer();
        client = new GithubPullRequestClient(RestClient.builder(), github.baseUrl());
    }

    @AfterEach
    void tearDown() {
        github.close();
    }

    @Test
    void getDefaultBranch_本文の無い応答はdefault_branchを読み取れないとして失敗する() {
        github.on("GET", "/repos/octo/blog", Response.empty(200));

        assertThatThrownBy(() -> client.getDefaultBranch(ACCESS))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("default_branch");
    }

    @Test
    void getFileContent_blobの本文が空なら空の応答として失敗する() {
        github.json("GET", "/repos/octo/blog/contents/a/b.png",
                "{\"sha\":\"abc\",\"size\":5,\"encoding\":\"none\",\"content\":\"\"}");
        github.on("GET", "/repos/octo/blog/git/blobs/abc", Response.empty(200));

        assertThatThrownBy(() -> client.getFileContent(ACCESS, "a/b.png", "main"))
                .isInstanceOf(GithubApiException.class)
                .hasMessageContaining("空の応答")
                .hasMessageContaining("abc");
    }

    @Test
    void getFileContent_blobの本文があればそのバイト列を返す() {
        github.json("GET", "/repos/octo/blog/contents/a/b.png",
                "{\"sha\":\"abc\",\"size\":2,\"encoding\":\"none\",\"content\":\"\"}");
        github.on("GET", "/repos/octo/blog/git/blobs/abc",
                Response.bytes(200, "application/vnd.github.raw", new byte[] {1, 2}));

        assertThat(client.getFileContent(ACCESS, "a/b.png", "main")).containsExactly(1, 2);
    }
}
