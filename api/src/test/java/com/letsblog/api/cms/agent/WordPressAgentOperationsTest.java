package com.letsblog.api.cms.agent;

import com.letsblog.api.cms.AuthorProvisioningRequest;
import com.letsblog.api.cms.CmsCredentials.WordPressCredentials;
import com.letsblog.api.cms.ConnectionCheckResult;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.cms.PostContent;
import com.letsblog.api.cms.PostResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.http.HttpStatus;

/**
 * WordPressAgentOperationsの回帰テスト。RestClient.Builderは単体で生成し、
 * MockRestServiceServerでprovision-agentとのHTTP通信を検証する。
 */
class WordPressAgentOperationsTest {

    private MockRestServiceServer server;
    private WordPressAgentOperations operations;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        operations = new WordPressAgentOperations(builder, "http://wordpress:9000", "test-token");
    }

    private WordPressCredentials creds() {
        return new WordPressCredentials(
                "http://wordpress/sites/main", "admin", "app-pass",
                "AGENT", null, null, null, null, null, null, "main");
    }

    @Test
    void testConnection_成功時にwp_core_versionの応答を返す() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/core-version"))
                .andExpect(content().json("{\"slug\":\"main\"}"))
                .andRespond(withSuccess("{\"version\":\"6.4.2\"}", MediaType.APPLICATION_JSON));

        ConnectionCheckResult result = operations.testConnection(creds());

        assertEquals(true, result.ok());
        assertEquals("wp core version: 6.4.2", result.detail());
        server.verify();
    }

    @Test
    void testConnection_サイトが見つからなければ接続失敗として返す() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/core-version"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"サイト 'main' が見つかりません\"}"));

        ConnectionCheckResult result = operations.testConnection(creds());

        assertEquals(false, result.ok());
        assertTrue(result.failureReason().startsWith("エージェントへの接続に失敗しました: "));
    }

    @Test
    void testConnection_wp_cli実行が失敗すればwp_cli実行失敗として返す() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/core-version"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"wp core versionの実行に失敗しました\",\"detail\":\"not a wordpress installation\"}"));

        ConnectionCheckResult result = operations.testConnection(creds());

        assertEquals(false, result.ok());
        assertTrue(result.failureReason().startsWith("wp core versionの実行に失敗しました: "));
        assertTrue(result.failureReason().contains("not a wordpress installation"));
    }

    @Test
    void resolveCategories_既存タームが見つかればそれを返す() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/resolve-terms"))
                .andRespond(withSuccess("{\"ids\":[\"5\"]}", MediaType.APPLICATION_JSON));

        List<String> ids = operations.resolveCategories(creds(), List.of("News"));

        assertEquals(List.of("5"), ids);
        server.verify();
    }

    @Test
    void provisionAuthor_ユーザーIDを返す() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/provision-author"))
                .andRespond(withSuccess("{\"userId\":\"12\"}", MediaType.APPLICATION_JSON));

        String userId = operations.provisionAuthor(creds(), AuthorProvisioningRequest.of("author@example.com"));

        assertEquals("12", userId);
        server.verify();
    }

    @Test
    void createOrUpdatePost_投稿結果を返す() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/post"))
                .andRespond(withSuccess(
                        "{\"postId\":\"123\",\"guid\":\"http://wordpress/sites/main/?p=123\",\"status\":\"draft\"}",
                        MediaType.APPLICATION_JSON));

        PostContent content = new PostContent("Test Title", "test-slug", "<p>HTML</p>", "draft", null, null, null);
        PostResult result = operations.createOrUpdatePost(creds(), content, null);

        assertEquals("123", result.id());
        assertEquals("draft", result.status());
        server.verify();
    }

    @Test
    void createOrUpdatePost_失敗時は例外を投げる() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/post"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"投稿の作成/更新に失敗しました\",\"detail\":\"boom\"}"));

        PostContent content = new PostContent("Test Title", null, "<p>HTML</p>", "draft", null, null, null);

        assertThrows(AgentOperationException.class, () -> operations.createOrUpdatePost(creds(), content, null));
    }

    @Test
    void uploadMedia_メディア情報を返す() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/media-upload"))
                .andRespond(withSuccess(
                        "{\"mediaId\":\"77\",\"guid\":\"http://wordpress/sites/main/wp-content/uploads/img.png\"}",
                        MediaType.APPLICATION_JSON));

        MediaUploadResult result = operations.uploadMedia(creds(), "img.png", "image/png", new byte[]{1, 2, 3});

        assertEquals("77", result.id());
        server.verify();
    }

    @Test
    void hasAuthorProvisioningCapability_疎通確認が成功すればtrue() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/core-version"))
                .andRespond(withSuccess("{\"version\":\"6.4.2\"}", MediaType.APPLICATION_JSON));

        assertEquals(true, operations.hasAuthorProvisioningCapability(creds()));
    }

    @Test
    void installWpCli_未対応操作として例外を投げる() {
        assertThrows(UnsupportedOperationException.class, () -> operations.installWpCli(creds()));
    }
}
