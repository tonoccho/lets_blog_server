package com.letsblog.api.cms.rest;

import com.letsblog.api.cms.CmsCredentials.WordPressCredentials;
import com.letsblog.api.cms.ssh.WordPressSshOperations.SshApplyResult;
import com.letsblog.api.domain.BulkOperationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpMethod.PUT;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * WordPressRestBulkManagementOperationsの回帰テスト。RestClient.Builderは単体で生成し、
 * MockRestServiceServerでHTTP通信を検証する(Springコンテキスト起動・DB接続は不要)。
 */
class WordPressRestBulkManagementOperationsTest {

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer server;
    private WordPressRestBulkManagementOperations operations;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        operations = new WordPressRestBulkManagementOperations(restClientBuilder);
    }

    private WordPressCredentials creds() {
        return new WordPressCredentials("https://example.com", "admin", "app-pass",
                "REST", null, null, null, null, null, null, null);
    }

    @Test
    void listCategories_1ページで全件取得できる場合は1回のGETで済む() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/categories")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(
                        "[{\"id\":1,\"name\":\"News\",\"slug\":\"news\",\"parent\":0,\"description\":\"\"},"
                                + "{\"id\":2,\"name\":\"Sub\",\"slug\":\"sub-news\",\"parent\":1,\"description\":\"d\"}]",
                        MediaType.APPLICATION_JSON)
                        .header("X-WP-TotalPages", "1"));

        List<WordPressRestBulkManagementOperations.CategoryInfo> result = operations.listCategories(creds());

        assertEquals(2, result.size());
        assertEquals(null, result.get(0).parentSlug());
        assertEquals("news", result.get(1).parentSlug());
        server.verify();
    }

    @Test
    void listCategories_複数ページはX_WP_TotalPagesを見て全ページ取得する() {
        server.expect(requestTo(containsString("page=1")))
                .andRespond(withSuccess("[{\"id\":1,\"name\":\"A\",\"slug\":\"a\",\"parent\":0,\"description\":\"\"}]",
                        MediaType.APPLICATION_JSON).header("X-WP-TotalPages", "2"));
        server.expect(requestTo(containsString("page=2")))
                .andRespond(withSuccess("[{\"id\":2,\"name\":\"B\",\"slug\":\"b\",\"parent\":0,\"description\":\"\"}]",
                        MediaType.APPLICATION_JSON).header("X-WP-TotalPages", "2"));

        List<WordPressRestBulkManagementOperations.CategoryInfo> result = operations.listCategories(creds());

        assertEquals(2, result.size());
        server.verify();
    }

    @Test
    void listCategories_取得に失敗したら例外() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/categories")))
                .andRespond(withServerError());

        org.junit.jupiter.api.Assertions.assertThrows(com.letsblog.api.cms.CmsApiException.class,
                () -> operations.listCategories(creds()));
    }

    @Test
    void listPlugins_pluginフィールドからslugを抽出しstatusを正規化する() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/plugins")))
                .andRespond(withSuccess(
                        "[{\"plugin\":\"akismet/akismet.php\",\"status\":\"active\"},"
                                + "{\"plugin\":\"hello/hello.php\",\"status\":\"inactive\"}]",
                        MediaType.APPLICATION_JSON));

        List<WordPressRestBulkManagementOperations.PluginThemeInfo> result = operations.listPlugins(creds());

        assertEquals("akismet", result.get(0).name());
        assertEquals("active", result.get(0).status());
        assertEquals("hello", result.get(1).name());
        assertEquals("inactive", result.get(1).status());
    }

    @Test
    void listThemes_stylesheetをそのままslugとして使う() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/themes")))
                .andRespond(withSuccess(
                        "[{\"stylesheet\":\"twentytwentyfour\",\"status\":\"active\"}]", MediaType.APPLICATION_JSON));

        List<WordPressRestBulkManagementOperations.PluginThemeInfo> result = operations.listThemes(creds());

        assertEquals("twentytwentyfour", result.get(0).name());
    }

    @Test
    void applyTerm_既存slugがあればskippedを返しPOSTしない() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/categories")))
                .andRespond(withSuccess("[{\"id\":1,\"name\":\"News\",\"slug\":\"news\",\"parent\":0,\"description\":\"\"}]",
                        MediaType.APPLICATION_JSON).header("X-WP-TotalPages", "1"));

        SshApplyResult result = operations.applyTerm(
                creds(), BulkOperationType.CATEGORY_CREATE, "News", "news", null, null, null);

        assertEquals("SKIPPED", result.status());
        server.verify();
    }

    @Test
    void applyTerm_新規作成はPOSTする() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/categories")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON).header("X-WP-TotalPages", "1"));
        server.expect(requestTo(containsString("/wp-json/wp/v2/categories")))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("\"slug\":\"news\"")))
                .andRespond(withSuccess("{\"id\":9}", MediaType.APPLICATION_JSON));

        SshApplyResult result = operations.applyTerm(
                creds(), BulkOperationType.CATEGORY_CREATE, "News", "news", null, "desc", null);

        assertEquals("SUCCESS", result.status());
        server.verify();
    }

    @Test
    void applyTerm_編集は対象idへPUTする() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/tags")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":3,\"name\":\"Old\",\"slug\":\"old\",\"parent\":0,\"description\":\"\"}]",
                        MediaType.APPLICATION_JSON).header("X-WP-TotalPages", "1"));
        server.expect(requestTo(containsString("/wp-json/wp/v2/tags/3")))
                .andExpect(method(POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        SshApplyResult result = operations.applyTerm(
                creds(), BulkOperationType.TAG_EDIT, "New", "new-slug", null, null, "old");

        assertEquals("SUCCESS", result.status());
        server.verify();
    }

    @Test
    void applyTerm_編集対象が見つからなければfailed() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/tags")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON).header("X-WP-TotalPages", "1"));

        SshApplyResult result = operations.applyTerm(
                creds(), BulkOperationType.TAG_EDIT, "New", "new-slug", null, null, "missing");

        assertEquals("FAILED", result.status());
        assertEquals(true, result.errorMessage().contains("見つかりません"));
    }

    @Test
    void applyTerm_削除は対象idへforce付きDELETEする() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/categories")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":4,\"name\":\"X\",\"slug\":\"x\",\"parent\":0,\"description\":\"\"}]",
                        MediaType.APPLICATION_JSON).header("X-WP-TotalPages", "1"));
        server.expect(requestTo(containsString("/wp-json/wp/v2/categories/4")))
                .andExpect(method(DELETE))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        SshApplyResult result = operations.applyTerm(
                creds(), BulkOperationType.CATEGORY_DELETE, null, null, null, null, "x");

        assertEquals("SUCCESS", result.status());
        server.verify();
    }

    @Test
    void applyTerm_削除対象が存在しなければskipped() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/categories")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON).header("X-WP-TotalPages", "1"));

        SshApplyResult result = operations.applyTerm(
                creds(), BulkOperationType.CATEGORY_DELETE, null, null, null, null, "already-gone");

        assertEquals("SKIPPED", result.status());
    }

    @Test
    void applyPlugin_未インストールならPOSTでinstallする() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/plugins")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/wp-json/wp/v2/plugins")))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("\"slug\":\"akismet\"")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        SshApplyResult result = operations.applyPlugin(creds(), BulkOperationType.PLUGIN_INSTALL, "akismet");

        assertEquals("SUCCESS", result.status());
        server.verify();
    }

    @Test
    void applyPlugin_インストール済みならskipped() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/plugins")))
                .andRespond(withSuccess("[{\"plugin\":\"akismet/akismet.php\",\"status\":\"inactive\"}]",
                        MediaType.APPLICATION_JSON));

        SshApplyResult result = operations.applyPlugin(creds(), BulkOperationType.PLUGIN_INSTALL, "akismet");

        assertEquals("SKIPPED", result.status());
    }

    @Test
    void applyPlugin_有効化はplugin識別子へPUTする() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/plugins")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess("[{\"plugin\":\"akismet/akismet.php\",\"status\":\"inactive\"}]",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/wp-json/wp/v2/plugins/akismet/akismet.php")))
                .andExpect(method(PUT))
                .andExpect(content().string(containsString("\"status\":\"active\"")))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        SshApplyResult result = operations.applyPlugin(creds(), BulkOperationType.PLUGIN_ACTIVATE, "akismet");

        assertEquals("SUCCESS", result.status());
        server.verify();
    }

    @Test
    void applyPlugin_対象が見つからなければfailed() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/plugins")))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        SshApplyResult result = operations.applyPlugin(creds(), BulkOperationType.PLUGIN_ACTIVATE, "missing");

        assertEquals("FAILED", result.status());
    }

    @Test
    void applyPlugin_失敗しても例外を投げずfailedを返す() {
        server.expect(requestTo(containsString("/wp-json/wp/v2/plugins")))
                .andRespond(withServerError());

        SshApplyResult result = operations.applyPlugin(creds(), BulkOperationType.PLUGIN_INSTALL, "akismet");

        assertEquals("FAILED", result.status());
    }
}
