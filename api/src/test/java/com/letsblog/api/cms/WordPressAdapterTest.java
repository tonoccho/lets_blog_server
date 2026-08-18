package com.letsblog.api.cms;

import com.letsblog.api.cms.agent.WordPressAgentOperations;
import com.letsblog.api.cms.ssh.WordPressSshOperations;
import com.letsblog.api.provisioning.WordPressBulkManagementClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.http.HttpStatus;

/**
 * WordPressAdapterの回帰テスト。RestClient.Builderは単体で生成し、
 * MockRestServiceServerでHTTP通信を検証する(Springコンテキスト起動・DB接続は不要)。
 */
class WordPressAdapterTest {

    private RestClient.Builder restClientBuilder;
    private WordPressAdapter adapter;
    private MockRestServiceServer server;
    private WordPressSshOperations sshOperations;
    private WordPressAgentOperations agentOperations;
    private WordPressBulkManagementClient bulkManagementClient;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        sshOperations = mock(WordPressSshOperations.class);
        agentOperations = mock(WordPressAgentOperations.class);
        bulkManagementClient = mock(WordPressBulkManagementClient.class);
        adapter = new WordPressAdapter(restClientBuilder, sshOperations, agentOperations, bulkManagementClient);
    }

    private CmsCredentials.WordPressCredentials sshCredentials() {
        return new CmsCredentials.WordPressCredentials(
                "https://example.com", null, null,
                "SSH", "ssh.example.com", 22, "deploy", "/var/www/html", "PRIVATE-KEY-PEM", null, null);
    }

    private CmsCredentials.WordPressCredentials agentCredentials() {
        return new CmsCredentials.WordPressCredentials(
                "http://wordpress/sites/main", "admin", "app-pass",
                "AGENT", null, null, null, null, null, null, "main");
    }

    @Test
    void testTestConnection_SSHトランスポートはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.testConnection(creds)).thenReturn(ConnectionCheckResult.success());

        ConnectionCheckResult result = adapter.testConnection(creds);

        assertEquals(true, result.ok());
        verify(sshOperations).testConnection(creds);
    }

    @Test
    void testTestConnection_AGENTトランスポートはWordPressAgentOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        when(agentOperations.testConnection(creds)).thenReturn(ConnectionCheckResult.success());

        ConnectionCheckResult result = adapter.testConnection(creds);

        assertEquals(true, result.ok());
        verify(agentOperations).testConnection(creds);
        verify(sshOperations, never()).testConnection(any());
    }

    @Test
    void testTestConnection_RESTトランスポートはWordPressSshOperationsを呼ばない() {
        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");
        server.expect(requestTo(containsString("/wp-json/wp/v2/users/me")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        adapter.testConnection(creds);

        verify(sshOperations, never()).testConnection(any());
        verify(agentOperations, never()).testConnection(any());
    }

    @Test
    void testSupportedType() {
        assertEquals(CmsType.WORDPRESS, adapter.supportedType());
    }

    @Test
    void testGenerateAuthCookie_SSHトランスポートはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        AuthCookie cookie = new AuthCookie("wordpress_logged_in_x", "value");
        when(sshOperations.generateAuthCookie(creds)).thenReturn(cookie);

        AuthCookie result = adapter.generateAuthCookie(creds);

        assertEquals(cookie, result);
        verify(sshOperations).generateAuthCookie(creds);
        verify(agentOperations, never()).generateAuthCookie(any());
    }

    @Test
    void testGenerateAuthCookie_AGENTトランスポートはWordPressAgentOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        AuthCookie cookie = new AuthCookie("wordpress_logged_in_x", "value");
        when(agentOperations.generateAuthCookie(creds)).thenReturn(cookie);

        AuthCookie result = adapter.generateAuthCookie(creds);

        assertEquals(cookie, result);
        verify(agentOperations).generateAuthCookie(creds);
        verify(sshOperations, never()).generateAuthCookie(any());
    }

    @Test
    void testGenerateAuthCookie_RESTトランスポートは未対応で例外() {
        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        assertThrows(UnsupportedOperationException.class, () -> adapter.generateAuthCookie(creds));
        verify(sshOperations, never()).generateAuthCookie(any());
        verify(agentOperations, never()).generateAuthCookie(any());
    }

    @Test
    void testCreatePost() {
        server.expect(requestTo("http://example.com/wp-json/wp/v2/posts"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"id\":123,\"link\":\"http://example.com/posts/test\",\"status\":\"draft\"}",
                        MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");
        PostContent content = new PostContent("Test Title", "test-slug", "<p>HTML</p>", "draft", null, null, null, null);

        PostResult result = adapter.createOrUpdatePost(creds, content, null);

        assertEquals("123", result.id());
        assertEquals("http://example.com/posts/test", result.link());
        assertEquals("draft", result.status());
        server.verify();
    }

    @Test
    void testUpdatePost() {
        server.expect(requestTo("http://example.com/wp-json/wp/v2/posts/123"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"id\":123,\"link\":\"http://example.com/posts/test\",\"status\":\"publish\"}",
                        MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");
        PostContent content = new PostContent("Updated Title", "test-slug", "<p>Updated</p>", "publish", null, null, null, null);

        PostResult result = adapter.createOrUpdatePost(creds, content, "123");

        assertEquals("123", result.id());
        assertEquals("publish", result.status());
        server.verify();
    }

    @Test
    void testUpdatePost_カテゴリとタグを空リストにすると明示的な空配列を送る() {
        server.expect(requestTo("http://example.com/wp-json/wp/v2/posts/123"))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("\"categories\":[]")))
                .andExpect(content().string(containsString("\"tags\":[]")))
                .andRespond(withSuccess(
                        "{\"id\":123,\"link\":\"http://example.com/posts/test\",\"status\":\"publish\"}",
                        MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");
        PostContent content = new PostContent(
                "Updated Title", "test-slug", "<p>Updated</p>", "publish", List.of(), List.of(), null, null);

        adapter.createOrUpdatePost(creds, content, "123");

        server.verify();
    }

    @Test
    void testPostExists_SSHトランスポートはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.postExists(creds, "42")).thenReturn(false);

        boolean result = adapter.postExists(creds, "42");

        assertEquals(false, result);
        verify(sshOperations).postExists(creds, "42");
        verify(agentOperations, never()).postExists(any(), any());
    }

    @Test
    void testPostExists_AGENTトランスポートはWordPressAgentOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        when(agentOperations.postExists(creds, "42")).thenReturn(true);

        boolean result = adapter.postExists(creds, "42");

        assertEquals(true, result);
        verify(agentOperations).postExists(creds, "42");
        verify(sshOperations, never()).postExists(any(), any());
    }

    @Test
    void testPostExists_RESTトランスポートは200ならtrueを返す() {
        server.expect(requestTo("http://example.com/wp-json/wp/v2/posts/42"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        assertEquals(true, adapter.postExists(creds, "42"));
        server.verify();
    }

    @Test
    void testPostExists_RESTトランスポートは404ならfalseを返す() {
        server.expect(requestTo("http://example.com/wp-json/wp/v2/posts/42"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        assertEquals(false, adapter.postExists(creds, "42"));
        server.verify();
    }

    @Test
    void testPostExists_RESTトランスポートは404以外のエラーなら判定不能としてtrueを返す() {
        server.expect(requestTo("http://example.com/wp-json/wp/v2/posts/42"))
                .andRespond(withServerError());

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        assertEquals(true, adapter.postExists(creds, "42"));
        server.verify();
    }

    @Test
    void testMediaExists_SSHトランスポートはWordPressSshOperationsのpostExistsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.postExists(creds, "11")).thenReturn(false);

        boolean result = adapter.mediaExists(creds, "11");

        assertEquals(false, result);
        verify(sshOperations).postExists(creds, "11");
        verify(agentOperations, never()).postExists(any(), any());
    }

    @Test
    void testMediaExists_AGENTトランスポートはWordPressAgentOperationsのpostExistsに委譲する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        when(agentOperations.postExists(creds, "11")).thenReturn(true);

        boolean result = adapter.mediaExists(creds, "11");

        assertEquals(true, result);
        verify(agentOperations).postExists(creds, "11");
        verify(sshOperations, never()).postExists(any(), any());
    }

    @Test
    void testMediaExists_RESTトランスポートは200ならtrueを返す() {
        server.expect(requestTo("http://example.com/wp-json/wp/v2/media/11"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        assertEquals(true, adapter.mediaExists(creds, "11"));
        server.verify();
    }

    @Test
    void testMediaExists_RESTトランスポートは404ならfalseを返す() {
        server.expect(requestTo("http://example.com/wp-json/wp/v2/media/11"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        assertEquals(false, adapter.mediaExists(creds, "11"));
        server.verify();
    }

    @Test
    void testMediaExists_RESTトランスポートは404以外のエラーなら判定不能としてtrueを返す() {
        server.expect(requestTo("http://example.com/wp-json/wp/v2/media/11"))
                .andRespond(withServerError());

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        assertEquals(true, adapter.mediaExists(creds, "11"));
        server.verify();
    }

    @Test
    void testUploadMedia() {
        server.expect(requestTo("http://example.com/wp-json/wp/v2/media"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"id\":456,\"source_url\":\"http://example.com/wp-content/uploads/image.png\"}",
                        MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");
        byte[] imageData = new byte[]{1, 2, 3};

        MediaUploadResult result = adapter.uploadMedia(creds, "image.png", "image/png", imageData);

        assertEquals("456", result.id());
        assertEquals("http://example.com/wp-content/uploads/image.png", result.url());
        server.verify();
    }

    @Test
    void testResolveCategoriesExisting() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/categories")))
                .andRespond(withSuccess("[{\"id\":1,\"name\":\"Technology\"}]", MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        List<String> ids = adapter.resolveCategories(creds, List.of("Technology"));

        assertEquals(1, ids.size());
        assertEquals("1", ids.get(0));
        server.verify();
    }

    @Test
    void testResolveCategoriesCreateNew() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/categories")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://example.com/wp-json/wp/v2/categories"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"id\":2,\"name\":\"NewCategory\"}", MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        List<String> ids = adapter.resolveCategories(creds, List.of("NewCategory"));

        assertEquals(1, ids.size());
        assertEquals("2", ids.get(0));
        server.verify();
    }

    @Test
    void testResolveTags() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/tags")))
                .andRespond(withSuccess("[{\"id\":9,\"name\":\"java\"}]", MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        List<String> ids = adapter.resolveTags(creds, List.of("java"));

        assertEquals(List.of("9"), ids);
        server.verify();
    }

    @Test
    void testCreatePost_authorId指定時はauthorフィールドを送信する() {
        server.expect(requestTo("http://example.com/wp-json/wp/v2/posts"))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("\"author\":42")))
                .andRespond(withSuccess(
                        "{\"id\":123,\"link\":\"http://example.com/posts/test\",\"status\":\"draft\"}",
                        MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");
        PostContent content = new PostContent("Test Title", "test-slug", "<p>HTML</p>", "draft", null, null, null, "42");

        adapter.createOrUpdatePost(creds, content, null);

        server.verify();
    }

    @Test
    void testFindAuthorIdByEmail_RESTトランスポートは検索結果のIDを返す() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/users?search=")))
                .andRespond(withSuccess("[{\"id\":7,\"email\":\"author@example.com\"}]", MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        java.util.Optional<String> authorId = adapter.findAuthorIdByEmail(creds, "author@example.com");

        assertEquals(true, authorId.isPresent());
        assertEquals("7", authorId.get());
        server.verify();
    }

    @Test
    void testFindAuthorIdByEmail_見つからなければ空を返す() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/users?search=")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        java.util.Optional<String> authorId = adapter.findAuthorIdByEmail(creds, "unknown@example.com");

        assertEquals(true, authorId.isEmpty());
    }

    @Test
    void testFindAuthorIdByEmail_SSHトランスポートはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.findAuthorIdByEmail(creds, "author@example.com"))
                .thenReturn(java.util.Optional.of("9"));

        java.util.Optional<String> authorId = adapter.findAuthorIdByEmail(creds, "author@example.com");

        assertEquals("9", authorId.get());
    }

    @Test
    void testListCategoryNames_RESTトランスポートは名前一覧を返す() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/categories?per_page=100")))
                .andRespond(withSuccess(
                        "[{\"id\":1,\"name\":\"お知らせ\"},{\"id\":2,\"name\":\"技術\"}]", MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        List<String> names = adapter.listCategoryNames(creds);

        assertEquals(List.of("お知らせ", "技術"), names);
        server.verify();
    }

    @Test
    void testListCategoryNames_取得失敗時は空リストを返す() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/categories")))
                .andRespond(withServerError());

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        List<String> names = adapter.listCategoryNames(creds);

        assertEquals(List.of(), names);
    }

    @Test
    void testListCategoryNames_SSHトランスポートはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.listCategories(creds)).thenReturn(List.of(
                new WordPressSshOperations.CategoryInfo("1", "お知らせ", "news", null, "")));

        List<String> names = adapter.listCategoryNames(creds);

        assertEquals(List.of("お知らせ"), names);
    }

    @Test
    void testListCategoryNames_AGENTトランスポートはWordPressBulkManagementClientに委譲する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        when(bulkManagementClient.listCategories(creds.wpSlug())).thenReturn(List.of(
                new WordPressBulkManagementClient.CategoryInfo("技術", "tech", null, "")));

        List<String> names = adapter.listCategoryNames(creds);

        assertEquals(List.of("技術"), names);
    }

    @Test
    void testListCategoriesWithParents_RESTトランスポートは親カテゴリ名を解決する() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/categories?per_page=100")))
                .andRespond(withSuccess(
                        "[{\"id\":1,\"name\":\"技術\",\"parent\":0},"
                                + "{\"id\":2,\"name\":\"Java\",\"parent\":1}]",
                        MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        List<CmsAdapter.CategoryOption> options = adapter.listCategoriesWithParents(creds);

        assertEquals(List.of(
                new CmsAdapter.CategoryOption("技術", null),
                new CmsAdapter.CategoryOption("Java", "技術")), options);
        server.verify();
    }

    @Test
    void testListCategoriesWithParents_SSHトランスポートはparentSlugから親カテゴリ名を解決する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();
        when(sshOperations.listCategories(creds)).thenReturn(List.of(
                new WordPressSshOperations.CategoryInfo("1", "技術", "tech", null, ""),
                new WordPressSshOperations.CategoryInfo("2", "Java", "java", "tech", "")));

        List<CmsAdapter.CategoryOption> options = adapter.listCategoriesWithParents(creds);

        assertEquals(List.of(
                new CmsAdapter.CategoryOption("技術", null),
                new CmsAdapter.CategoryOption("Java", "技術")), options);
    }

    @Test
    void testListCategoriesWithParents_AGENTトランスポートはparentSlugから親カテゴリ名を解決する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();
        when(bulkManagementClient.listCategories(creds.wpSlug())).thenReturn(List.of(
                new WordPressBulkManagementClient.CategoryInfo("技術", "tech", null, ""),
                new WordPressBulkManagementClient.CategoryInfo("Java", "java", "tech", "")));

        List<CmsAdapter.CategoryOption> options = adapter.listCategoriesWithParents(creds);

        assertEquals(List.of(
                new CmsAdapter.CategoryOption("技術", null),
                new CmsAdapter.CategoryOption("Java", "技術")), options);
    }

    @Test
    void testListCategoriesWithParents_取得失敗時は空リストを返す() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/categories")))
                .andRespond(withServerError());

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        List<CmsAdapter.CategoryOption> options = adapter.listCategoriesWithParents(creds);

        assertEquals(List.of(), options);
    }

    @Test
    void testDeletePost_RESTトランスポートはforceパラメータなしでDELETEする() {
        server.expect(requestTo("http://example.com/wp-json/wp/v2/posts/123"))
                .andExpect(method(DELETE))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        adapter.deletePost(creds, "123");

        server.verify();
    }

    @Test
    void testDeletePost_SSHトランスポートはWordPressSshOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = sshCredentials();

        adapter.deletePost(creds, "123");

        verify(sshOperations).deletePost(creds, "123");
    }

    @Test
    void testDeletePost_AGENTトランスポートはWordPressAgentOperationsに委譲する() {
        CmsCredentials.WordPressCredentials creds = agentCredentials();

        adapter.deletePost(creds, "123");

        verify(agentOperations).deletePost(creds, "123");
        verify(sshOperations, never()).deletePost(any(), any());
    }

    @Test
    void testApiError() {
        server.expect(requestTo("http://example.com/wp-json/wp/v2/posts"))
                .andRespond(withServerError());

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");
        PostContent content = new PostContent("Test", null, "<p>Test</p>", "draft", null, null, null, null);

        assertThrows(CmsApiException.class,
                () -> adapter.createOrUpdatePost(creds, content, null));
        server.verify();
    }

    @Test
    void testProvisionDefaultCategory_既存カテゴリを返す() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/categories")))
                .andRespond(withSuccess("[{\"id\":1,\"name\":\"Uncategorized\"}]", MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        String categoryId = adapter.provisionDefaultCategory(creds);

        assertEquals("1", categoryId);
        server.verify();
    }

    @Test
    void testProvisionDefaultTag_存在しなければ作成する() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/tags")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://example.com/wp-json/wp/v2/tags"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"id\":5,\"name\":\"Let's Blog\"}", MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        String tagId = adapter.provisionDefaultTag(creds);

        assertEquals("5", tagId);
        server.verify();
    }

    @Test
    void testProvisionAuthor_既存ユーザーが見つかればプロフィールを更新する() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/users")))
                .andRespond(withSuccess("[{\"id\":7,\"email\":\"author@example.com\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://example.com/wp-json/wp/v2/users/7"))
                .andExpect(method(PUT))
                .andRespond(withSuccess("{\"id\":7,\"email\":\"author@example.com\"}", MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        String authorId = adapter.provisionAuthor(creds, AuthorProvisioningRequest.of("author@example.com"));

        assertEquals("7", authorId);
        server.verify();
    }

    @Test
    void testProvisionAuthor_見つからなければ新規作成する() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/users?search=")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://example.com/wp-json/wp/v2/users"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"id\":8,\"email\":\"newauthor@example.com\"}", MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        String authorId = adapter.provisionAuthor(creds, AuthorProvisioningRequest.of("newauthor@example.com"));

        assertEquals("8", authorId);
        server.verify();
    }

    @Test
    void testProvisionAuthor_ロール引数指定で作成される() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/users?search=")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://example.com/wp-json/wp/v2/users"))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("\"editor\"")))
                .andRespond(withSuccess("{\"id\":9,\"email\":\"editor@example.com\"}", MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        AuthorProvisioningRequest request = new AuthorProvisioningRequest(
                "editor@example.com", "editor", "太郎", "山田", "山田太郎",
                "https://example.com", "自己紹介", "ja_JP");
        String authorId = adapter.provisionAuthor(creds, request);

        assertEquals("9", authorId);
        server.verify();
    }

    @Test
    void testProvisionAuthor_既存ユーザー更新時にプロフィール項目を送信する() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/users")))
                .andRespond(withSuccess("[{\"id\":7,\"email\":\"author@example.com\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://example.com/wp-json/wp/v2/users/7"))
                .andExpect(method(PUT))
                .andExpect(content().string(containsString("\"author\"")))
                .andExpect(content().string(containsString("山田太郎")))
                .andRespond(withSuccess("{\"id\":7,\"email\":\"author@example.com\"}", MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        AuthorProvisioningRequest request = new AuthorProvisioningRequest(
                "author@example.com", "author", "太郎", "山田", "山田太郎",
                null, null, null);
        String authorId = adapter.provisionAuthor(creds, request);

        assertEquals("7", authorId);
        server.verify();
    }

    @Test
    void testProvisionAuthor_403時は権限不足を案内するメッセージになる() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/users?search=")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://example.com/wp-json/wp/v2/users"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .body("{\"code\":\"rest_cannot_create\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        CmsApiException exception = assertThrows(CmsApiException.class,
                () -> adapter.provisionAuthor(creds, AuthorProvisioningRequest.of("newauthor@example.com")));

        org.hamcrest.MatcherAssert.assertThat(exception.getMessage(), containsString("管理者権限を持つアカウント"));
        server.verify();
    }

    @Test
    void testHasAuthorProvisioningCapability_create_users権限があればtrue() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/users/me")))
                .andRespond(withSuccess(
                        "{\"id\":1,\"capabilities\":{\"create_users\":true,\"read\":true}}", MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        assertEquals(true, adapter.hasAuthorProvisioningCapability(creds));
        server.verify();
    }

    @Test
    void testHasAuthorProvisioningCapability_create_users権限がなければfalse() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/users/me")))
                .andRespond(withSuccess(
                        "{\"id\":2,\"capabilities\":{\"create_users\":false,\"read\":true}}", MediaType.APPLICATION_JSON));

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "editor", "apppass123");

        assertEquals(false, adapter.hasAuthorProvisioningCapability(creds));
        server.verify();
    }

    @Test
    void testHasAuthorProvisioningCapability_リクエスト失敗時はfalse() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/users/me")))
                .andRespond(withServerError());

        CmsCredentials.WordPressCredentials creds = new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin", "apppass123");

        assertEquals(false, adapter.hasAuthorProvisioningCapability(creds));
        server.verify();
    }
}
