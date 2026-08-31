package com.letsblog.api.cms.agent;

import com.letsblog.api.cms.AuthorProvisioningRequest;
import com.letsblog.api.cms.CmsCredentials.WordPressCredentials;
import com.letsblog.api.cms.ConnectionCheckResult;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.cms.PostContent;
import com.letsblog.api.cms.PostResult;
import com.letsblog.api.cms.ReferencePost;
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
                "http://wordpress/sites/main", "admin",
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

        PostContent content = new PostContent("Test Title", "test-slug", "<p>HTML</p>", "draft", null, null, null, null);
        PostResult result = operations.createOrUpdatePost(creds(), content, null);

        assertEquals("123", result.id());
        assertEquals("draft", result.status());
        server.verify();
    }

    @Test
    void createOrUpdatePost_authorId指定時はペイロードに含める() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/post"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"authorId\":\"42\"")))
                .andRespond(withSuccess(
                        "{\"postId\":\"123\",\"guid\":\"http://wordpress/sites/main/?p=123\",\"status\":\"draft\"}",
                        MediaType.APPLICATION_JSON));

        PostContent content = new PostContent("Test Title", "test-slug", "<p>HTML</p>", "draft", null, null, null, "42");
        operations.createOrUpdatePost(creds(), content, null);

        server.verify();
    }

    @Test
    void createOrUpdatePost_カテゴリとタグを空リストにした更新はペイロードに空配列を含める() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/post"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"categoryIds\":[]")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"tagIds\":[]")))
                .andRespond(withSuccess(
                        "{\"postId\":\"123\",\"guid\":\"http://wordpress/sites/main/?p=123\",\"status\":\"draft\"}",
                        MediaType.APPLICATION_JSON));

        PostContent content = new PostContent(
                "Test Title", "test-slug", "<p>HTML</p>", "draft", List.of(), List.of(), null, null);
        operations.createOrUpdatePost(creds(), content, "123");

        server.verify();
    }

    @Test
    void findAuthorIdByEmail_見つかればIDを返す() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/find-author"))
                .andRespond(withSuccess("{\"userId\":\"11\"}", MediaType.APPLICATION_JSON));

        assertEquals("11", operations.findAuthorIdByEmail(creds(), "author@example.com").orElse(null));
        server.verify();
    }

    @Test
    void findAuthorIdByEmail_見つからなければ空を返す() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/find-author"))
                .andRespond(withSuccess("{\"userId\":null}", MediaType.APPLICATION_JSON));

        assertTrue(operations.findAuthorIdByEmail(creds(), "unknown@example.com").isEmpty());
    }

    @Test
    void createOrUpdatePost_失敗時は例外を投げる() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/post"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"投稿の作成/更新に失敗しました\",\"detail\":\"boom\"}"));

        PostContent content = new PostContent("Test Title", null, "<p>HTML</p>", "draft", null, null, null, null);

        assertThrows(AgentOperationException.class, () -> operations.createOrUpdatePost(creds(), content, null));
    }

    @Test
    void postExists_存在すればtrueを返す() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/post-exists"))
                .andExpect(content().json("{\"slug\":\"main\",\"postId\":\"42\"}"))
                .andRespond(withSuccess("{\"exists\":true}", MediaType.APPLICATION_JSON));

        assertEquals(true, operations.postExists(creds(), "42"));
        server.verify();
    }

    @Test
    void postExists_存在しなければfalseを返す() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/post-exists"))
                .andRespond(withSuccess("{\"exists\":false}", MediaType.APPLICATION_JSON));

        assertEquals(false, operations.postExists(creds(), "42"));
    }

    @Test
    void postExists_エージェント接続失敗時は判定不能としてtrueを返す() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/post-exists"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"boom\"}"));

        assertEquals(true, operations.postExists(creds(), "42"));
    }

    @Test
    void deletePost_成功時は例外を投げない() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/post-delete"))
                .andExpect(content().json("{\"slug\":\"main\",\"postId\":\"99\"}"))
                .andRespond(withSuccess("{\"postId\":\"99\"}", MediaType.APPLICATION_JSON));

        operations.deletePost(creds(), "99");

        server.verify();
    }

    @Test
    void deletePost_失敗時は例外を投げる() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/post-delete"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"投稿の削除に失敗しました\",\"detail\":\"boom\"}"));

        assertThrows(AgentOperationException.class, () -> operations.deletePost(creds(), "99"));
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

    @Test
    void getLatestPost_参照記事を返す() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/reference-post"))
                .andExpect(content().json("{\"slug\":\"main\"}"))
                .andRespond(withSuccess(
                        "{\"found\":true,\"id\":\"1\",\"link\":\"http://wordpress/sites/main/hello-world/\","
                        + "\"title\":\"Hello World\",\"content\":\"<p>Hi</p>\"}",
                        MediaType.APPLICATION_JSON));

        ReferencePost referencePost = operations.getLatestPost(creds()).orElseThrow();

        assertEquals("1", referencePost.id());
        assertEquals("http://wordpress/sites/main/hello-world/", referencePost.link());
        assertEquals("Hello World", referencePost.title());
        assertEquals("<p>Hi</p>", referencePost.content());
        server.verify();
    }

    @Test
    void getLatestPost_参照記事が存在しない場合は空を返す() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/reference-post"))
                .andRespond(withSuccess("{\"found\":false}", MediaType.APPLICATION_JSON));

        assertTrue(operations.getLatestPost(creds()).isEmpty());
    }

    @Test
    void getLatestPost_失敗時は例外を投げる() {
        server.expect(requestTo("http://wordpress:9000/wp-cli/reference-post"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"参照記事の取得に失敗しました\",\"detail\":\"boom\"}"));

        assertThrows(AgentOperationException.class, () -> operations.getLatestPost(creds()));
    }
}
