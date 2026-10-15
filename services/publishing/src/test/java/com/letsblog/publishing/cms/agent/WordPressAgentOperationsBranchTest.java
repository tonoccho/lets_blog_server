package com.letsblog.publishing.cms.agent;

import com.letsblog.publishing.cms.AuthorProvisioningRequest;
import com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials;
import com.letsblog.publishing.cms.ConnectionCheckResult;
import com.letsblog.publishing.cms.PostContent;
import com.letsblog.publishing.cms.PostResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * WordPressAgentOperationsの分岐を埋める補完テスト(issue #1814)。provision-agentとのHTTP通信は
 * MockRestServiceServer(HTTPの境界)で差し替え、WordPressAgentOperations自身は実物で動かす。
 * 送った本文(JSON)と、エラー応答・接続失敗がどの文言の例外になるかを検証する。
 */
class WordPressAgentOperationsBranchTest {

    private static final String BASE = "http://wordpress:9000";

    private MockRestServiceServer server;
    private WordPressAgentOperations operations;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        operations = new WordPressAgentOperations(builder, BASE, "test-token");
    }

    private WordPressCredentials creds() {
        return new WordPressCredentials("http://wordpress/sites/main", "admin", "AGENT",
                null, null, null, null, null, null, "main");
    }

    private void expectError(String path, HttpStatus status, String body) {
        server.expect(requestTo(BASE + path))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private void expectUnreachable(String path) {
        server.expect(requestTo(BASE + path)).andRespond(withException(new IOException("Connection refused")));
    }

    // ---- 接続確認 ----

    @Test
    void testConnection_403はエージェント接続の段階の失敗として返す() {
        expectError("/wp-cli/core-version", HttpStatus.FORBIDDEN, "{\"error\":\"invalid token\"}");

        ConnectionCheckResult result = operations.testConnection(creds());

        assertEquals(false, result.ok());
        assertEquals("エージェントへの接続に失敗しました: invalid token", result.failureReason());
    }

    @Test
    void testConnection_エージェントへ届かなければ接続失敗として返す() {
        expectUnreachable("/wp-cli/core-version");

        ConnectionCheckResult result = operations.testConnection(creds());

        assertEquals(false, result.ok());
        assertTrue(result.failureReason().startsWith("エージェントへの接続に失敗しました: "), result.failureReason());
    }

    // ---- カテゴリ/タグの解決 ----

    @Test
    void resolveCategories_名前がnullまたは空ならエージェントを呼ばず空を返す() {
        assertEquals(List.of(), operations.resolveCategories(creds(), null));
        assertEquals(List.of(), operations.resolveTags(creds(), List.of()));
        server.verify();
    }

    @Test
    void resolveTags_タグの解決はpost_tagを指定しエラー応答なら例外() {
        server.expect(requestTo(BASE + "/wp-cli/resolve-terms"))
                .andExpect(content().string(containsString("\"taxonomy\":\"post_tag\"")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"term failed\"}"));

        AgentOperationException e = assertThrows(AgentOperationException.class,
                () -> operations.resolveTags(creds(), List.of("java")));
        assertEquals("カテゴリ/タグの解決に失敗しました: term failed", e.getMessage());
    }

    @Test
    void resolveCategories_エージェントへ届かなければ例外() {
        expectUnreachable("/wp-cli/resolve-terms");

        AgentOperationException e = assertThrows(AgentOperationException.class,
                () -> operations.resolveCategories(creds(), List.of("News")));
        assertTrue(e.getMessage().startsWith("エージェントへの接続に失敗しました: "), e.getMessage());
    }

    // ---- 著者 ----

    @Test
    void provisionAuthor_指定された項目だけをペイロードに含め_roleは指定を優先する() {
        server.expect(requestTo(BASE + "/wp-cli/provision-author"))
                .andExpect(content().string(containsString("\"wpRole\":\"editor\"")))
                .andExpect(content().string(containsString("\"firstName\":\"First\"")))
                .andExpect(content().string(containsString("\"lastName\":\"Last\"")))
                .andExpect(content().string(containsString("\"displayName\":\"Disp\"")))
                .andExpect(content().string(containsString("\"websiteUrl\":\"https://a.example\"")))
                .andExpect(content().string(containsString("\"bio\":\"bio\"")))
                .andRespond(withSuccess("{\"userId\":\"5\"}", MediaType.APPLICATION_JSON));

        String userId = operations.provisionAuthor(creds(), new AuthorProvisioningRequest(
                "a@example.com", "editor", "First", "Last", "Disp", "https://a.example", "bio", null));

        assertEquals("5", userId);
        server.verify();
    }

    @Test
    void provisionAuthor_roleが無ければauthorで送り_空白の項目は送らない() {
        server.expect(requestTo(BASE + "/wp-cli/provision-author"))
                .andExpect(content().string(containsString("\"wpRole\":\"author\"")))
                .andExpect(content().string(not(containsString("firstName"))))
                .andExpect(content().string(not(containsString("bio"))))
                .andRespond(withSuccess("{\"userId\":\"5\"}", MediaType.APPLICATION_JSON));

        operations.provisionAuthor(creds(), new AuthorProvisioningRequest(
                "a@example.com", null, " ", null, null, null, "", null));

        server.verify();
    }

    @Test
    void provisionAuthor_403は管理者権限の可能性を案内する() {
        expectError("/wp-cli/provision-author", HttpStatus.FORBIDDEN, "{\"error\":\"forbidden\"}");

        AgentOperationException e = assertThrows(AgentOperationException.class,
                () -> operations.provisionAuthor(creds(), AuthorProvisioningRequest.of("a@example.com")));
        assertTrue(e.getMessage().contains("Administrator") && e.getMessage().contains("(詳細: forbidden)"), e.getMessage());
    }

    @Test
    void provisionAuthor_403以外のエラーは詳細だけを添える() {
        expectError("/wp-cli/provision-author", HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"boom\"}");

        AgentOperationException e = assertThrows(AgentOperationException.class,
                () -> operations.provisionAuthor(creds(), AuthorProvisioningRequest.of("a@example.com")));
        assertEquals("WordPress著者の作成/更新に失敗しました: boom", e.getMessage());
    }

    @Test
    void provisionAuthor_エージェントへ届かなければ例外() {
        expectUnreachable("/wp-cli/provision-author");

        AgentOperationException e = assertThrows(AgentOperationException.class,
                () -> operations.provisionAuthor(creds(), AuthorProvisioningRequest.of("a@example.com")));
        assertTrue(e.getMessage().startsWith("エージェントへの接続に失敗しました: "), e.getMessage());
    }

    // ---- 投稿の作成/更新 ----

    private static final String POST_OK = "{\"postId\":\"1\",\"guid\":\"g\",\"status\":\"draft\"}";

    @Test
    void createOrUpdatePost_任意項目をすべて指定するとペイロードに含める() {
        server.expect(requestTo(BASE + "/wp-cli/post"))
                .andExpect(content().string(containsString("\"publishScheduledAt\":\"2026-10-16T01:02:03Z\"")))
                .andExpect(content().string(containsString("\"existingPostId\":\"55\"")))
                .andExpect(content().string(containsString("\"postSlug\":\"slug\"")))
                .andExpect(content().string(containsString("\"categoryIds\":[\"1\"]")))
                .andExpect(content().string(containsString("\"tagIds\":[\"2\"]")))
                .andExpect(content().string(containsString("\"featuredMediaId\":\"77\"")))
                .andExpect(content().string(containsString("\"htmlContent\":\"<p>x</p>\"")))
                .andRespond(withSuccess(POST_OK, MediaType.APPLICATION_JSON));

        PostResult result = operations.createOrUpdatePost(creds(), new PostContent("T", "slug", "<p>x</p>", "future",
                List.of("1"), List.of("2"), "77", null, Instant.parse("2026-10-16T01:02:03Z")), "55");

        assertEquals(new PostResult("1", "g", "draft"), result);
        server.verify();
    }

    @Test
    void createOrUpdatePost_任意項目が無ければ本文は空文字で他は送らない() {
        server.expect(requestTo(BASE + "/wp-cli/post"))
                .andExpect(content().string(containsString("\"htmlContent\":\"\"")))
                .andExpect(content().string(not(containsString("publishScheduledAt"))))
                .andExpect(content().string(not(containsString("existingPostId"))))
                .andExpect(content().string(not(containsString("postSlug"))))
                .andExpect(content().string(not(containsString("categoryIds"))))
                .andExpect(content().string(not(containsString("tagIds"))))
                .andExpect(content().string(not(containsString("featuredMediaId"))))
                .andExpect(content().string(not(containsString("authorId"))))
                .andRespond(withSuccess(POST_OK, MediaType.APPLICATION_JSON));

        operations.createOrUpdatePost(creds(), new PostContent("T", null, null, "draft", null, null, null, null), null);

        server.verify();
    }

    @Test
    void createOrUpdatePost_スラッグが空白なら送らない() {
        server.expect(requestTo(BASE + "/wp-cli/post"))
                .andExpect(content().string(not(containsString("postSlug"))))
                .andRespond(withSuccess(POST_OK, MediaType.APPLICATION_JSON));

        operations.createOrUpdatePost(creds(), new PostContent("T", "  ", "x", "draft", null, null, null, null), null);

        server.verify();
    }

    @Test
    void createOrUpdatePost_エージェントへ届かなければ例外() {
        expectUnreachable("/wp-cli/post");

        AgentOperationException e = assertThrows(AgentOperationException.class, () -> operations.createOrUpdatePost(
                creds(), new PostContent("T", "s", "x", "draft", null, null, null, null), null));
        assertTrue(e.getMessage().startsWith("エージェントへの接続に失敗しました: "), e.getMessage());
    }

    // ---- エラー応答の詳細(agentErrorDetail) ----

    private String deleteFailureMessage(HttpStatus status, String body) {
        expectError("/wp-cli/post-delete", status, body);
        return assertThrows(AgentOperationException.class, () -> operations.deletePost(creds(), "1")).getMessage();
    }

    private static final String PREFIX = "WordPress投稿の削除に失敗しました: ";

    @Test
    void エラー詳細_errorとdetailがあれば_error_detail_の形にする() {
        assertEquals(PREFIX + "failed: because", deleteFailureMessage(HttpStatus.INTERNAL_SERVER_ERROR,
                "{\"error\":\"failed\",\"detail\":\"because\"}"));
    }

    @Test
    void エラー詳細_detailだけならdetailを返す() {
        assertEquals(PREFIX + "because", deleteFailureMessage(HttpStatus.INTERNAL_SERVER_ERROR, "{\"detail\":\"because\"}"));
    }

    @Test
    void エラー詳細_detailが空白ならerrorだけを返す() {
        assertEquals(PREFIX + "failed", deleteFailureMessage(HttpStatus.INTERNAL_SERVER_ERROR,
                "{\"error\":\"failed\",\"detail\":\" \"}"));
    }

    @Test
    void エラー詳細_errorもdetailも空ならステータスと本文をそのまま返す() {
        String message = deleteFailureMessage(HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":\" \"}");

        assertTrue(message.startsWith(PREFIX + "500 INTERNAL_SERVER_ERROR"), message);
        assertTrue(message.contains("{\"error\":\" \"}"), message);
    }

    @Test
    void エラー詳細_JSONでない本文はステータスと本文をそのまま返す() {
        String message = deleteFailureMessage(HttpStatus.BAD_GATEWAY, "upstream down");

        assertEquals(PREFIX + "502 BAD_GATEWAY upstream down", message);
    }

    @Test
    void エラー詳細_本文が空ならステータスだけを返す() {
        String message = deleteFailureMessage(HttpStatus.BAD_GATEWAY, "");

        assertEquals(PREFIX + "502 BAD_GATEWAY ", message);
    }

    @Test
    void エラー詳細_errorもdetailも無いJSONならステータスと本文をそのまま返す() {
        assertEquals(PREFIX + "500 INTERNAL_SERVER_ERROR {}", deleteFailureMessage(HttpStatus.INTERNAL_SERVER_ERROR, "{}"));
    }
}
