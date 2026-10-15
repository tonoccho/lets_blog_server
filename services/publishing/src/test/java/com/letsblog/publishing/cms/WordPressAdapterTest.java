package com.letsblog.publishing.cms;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.publishing.cms.agent.WordPressAgentOperations;
import com.letsblog.publishing.cms.ssh.WordPressSshOperations;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WordPressAdapterの回帰テスト(issue #1814で書き直し)。SSH(wp-cli)/managed(agent)のいずれかの
 * トランスポートへ委譲することを検証する(REST(wp-json)呼び出しはissue #518で廃止済み)。
 *
 * <p>自前のクラス(WordPressSshOperations / WordPressAgentOperations / WordPressBulkManagementClient)は
 * モックせず実物を使い、外部との通信だけを差し替える。SSHは{@link FakeSshCommandExecutor}
 * (SshCommandExecutorの手書きフェイク)、HTTPは{@link FakeProvisionAgent}(サーバー側のフェイク)。
 * 「どちらのトランスポートへ委譲したか」は、受けた側の記録(実行したコマンド/受けたHTTPパス)で確かめる。
 */
class WordPressAdapterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SHA = "a".repeat(64);

    private FakeSshCommandExecutor ssh;
    private FakeProvisionAgent agent;
    private WordPressAdapter adapter;

    @BeforeEach
    void setUp() {
        ssh = new FakeSshCommandExecutor();
        agent = new FakeProvisionAgent();
        adapter = new WordPressAdapter(
                new WordPressSshOperations(ssh, MAPPER),
                new WordPressAgentOperations(RestClient.builder(), agent.baseUrl(), "agent-token", 5, 5),
                new WordPressBulkManagementClient(agent.baseUrl(), "agent-token", 5, 5));
    }

    @AfterEach
    void tearDown() {
        agent.close();
    }

    private CmsCredentials.WordPressCredentials sshCredentials() {
        return new CmsCredentials.WordPressCredentials(
                "https://example.com", null,
                "SSH", "ssh.example.com", 22, "deploy", "/var/www/html", "PRIVATE-KEY-PEM", null, null);
    }

    private CmsCredentials.WordPressCredentials agentCredentials() {
        return new CmsCredentials.WordPressCredentials(
                "http://wordpress/sites/main", "admin",
                "AGENT", null, null, null, null, null, null, "main");
    }

    private CmsCredentials.WordPressCredentials unsupportedCredentials() {
        return new CmsCredentials.WordPressCredentials(
                "http://example.com", "admin",
                "FTP", null, null, null, null, null, null, null);
    }

    /** 文字列をJSONの文字列リテラル(エスケープ済み・引用符つき)にする。エージェントのstdout欄に使う。 */
    private static String lit(String text) {
        try {
            return MAPPER.writeValueAsString(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String agentCommandResult(int exitCode, String stdout, String stderr) {
        return "{\"exitCode\":" + exitCode + ",\"stdout\":" + lit(stdout) + ",\"stderr\":" + lit(stderr) + "}";
    }

    private void assertNoAgentCall() {
        assertTrue(agent.requests.isEmpty(), "agentへ呼び出しが行かないこと: " + agent.paths());
    }

    private void assertNoSshCall() {
        assertTrue(ssh.commands.isEmpty(), "SSHでコマンドが実行されないこと: " + ssh.commands);
    }

    private static final String INSTALLED_STATUS_JSON = "{\"plugin_version\":\"1.0.0\",\"protocol_version\":1}";

    private LetsblogPluginStatus installedStatus() {
        return new LetsblogPluginStatus(LetsblogPluginStatus.State.INSTALLED, "1.0.0",
                LetsblogPluginStatus.SUPPORTED_PROTOCOL_VERSION);
    }

    // ---- supportedType / testConnection ----

    @Test
    void testSupportedType() {
        assertEquals(CmsType.WORDPRESS, adapter.supportedType());
    }

    @Test
    void testTestConnection_SSHトランスポートはwp_cliで疎通を確かめる() {
        ssh.whenOk("echo ok", "ok").whenOk("core version", "6.4.2");

        ConnectionCheckResult result = adapter.testConnection(sshCredentials());

        assertTrue(result.ok());
        assertEquals("wp core version: 6.4.2", result.detail());
        assertEquals("SHA256:observed", result.observedHostKeyFingerprint());
        assertNoAgentCall();
    }

    @Test
    void testTestConnection_AGENTトランスポートはagentの_wp_cli_core_versionを呼ぶ() {
        agent.respond("/wp-cli/core-version", "{\"version\":\"6.4.2\"}");

        ConnectionCheckResult result = adapter.testConnection(agentCredentials());

        assertTrue(result.ok());
        assertEquals("wp core version: 6.4.2", result.detail());
        assertEquals(List.of("/wp-cli/core-version"), agent.paths());
        assertEquals("agent-token", agent.last().token());
        assertNoSshCall();
    }

    @Test
    void testTestConnection_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class, () -> adapter.testConnection(unsupportedCredentials()));
        assertNoSshCall();
        assertNoAgentCall();
    }

    // ---- generateAuthCookie ----

    @Test
    void testGenerateAuthCookie_SSHトランスポートはwp_evalでCookieを発行する() {
        ssh.whenOk("eval", "{\"name\":\"wordpress_logged_in_x\",\"value\":\"value\"}");

        assertEquals(new AuthCookie("wordpress_logged_in_x", "value"), adapter.generateAuthCookie(sshCredentials()));
        assertNoAgentCall();
    }

    @Test
    void testGenerateAuthCookie_AGENTトランスポートはagentへユーザー名を渡してCookieを発行する() {
        agent.respond("/wp-cli/generate-auth-cookie", "{\"name\":\"wordpress_logged_in_x\",\"value\":\"value\"}");

        assertEquals(new AuthCookie("wordpress_logged_in_x", "value"), adapter.generateAuthCookie(agentCredentials()));
        assertTrue(agent.last().body().contains("\"userLogin\":\"admin\""));
        assertNoSshCall();
    }

    @Test
    void testGenerateAuthCookie_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class, () -> adapter.generateAuthCookie(unsupportedCredentials()));
        assertNoSshCall();
        assertNoAgentCall();
    }

    // ---- createOrUpdatePost ----

    private PostContent content() {
        return new PostContent("Test Title", "test-slug", "<p>HTML</p>", "draft", null, null, null, null);
    }

    private void stubSshPostCreation() {
        ssh.whenOk("post create", "123")
                .whenOk("post get", "{\"guid\":\"https://example.com/posts/test\",\"post_status\":\"draft\"}");
    }

    @Test
    void testCreateOrUpdatePost_SSHトランスポートは投稿前にletsblogプラグインの導入を確かめてから投稿する() {
        ssh.whenOk("test -f", "");
        stubSshPostCreation();

        PostResult result = adapter.createOrUpdatePost(sshCredentials(), content(), null);

        assertEquals(new PostResult("123", "https://example.com/posts/test", "draft"), result);
        assertTrue(ssh.indexOf("test -f") >= 0 && ssh.indexOf("test -f") < ssh.indexOf("post create"),
                "プラグイン確認が投稿作成より先: " + ssh.commands);
        String createCommand = ssh.commands.get(ssh.indexOf("post create"));
        assertEquals("<p>HTML</p>", new String(ssh.stdins.get(createCommand)));
        assertNoAgentCall();
    }

    @Test
    void testCreateOrUpdatePost_SSHでプラグイン導入に失敗しても投稿は続行する() {
        ssh.whenFail("test -f", "")
                .whenOk("mkdir -p", "")
                .whenFail("plugin activate", "activate failed")
                .whenOk("rm -f", "");
        stubSshPostCreation();

        PostResult result = adapter.createOrUpdatePost(sshCredentials(), content(), null);

        assertEquals("123", result.id());
        assertTrue(ssh.ran("rm -f"), "有効化に失敗した配置は取り消される");
        assertTrue(ssh.ran("post create"));
    }

    @Test
    void testCreateOrUpdatePost_AGENTトランスポートはagentの_wp_cli_postを呼ぶ() {
        agent.respond("/wp-cli/post", "{\"postId\":\"123\",\"guid\":\"https://example.com/posts/test\",\"status\":\"draft\"}");

        PostResult result = adapter.createOrUpdatePost(agentCredentials(), content(), null);

        assertEquals(new PostResult("123", "https://example.com/posts/test", "draft"), result);
        assertEquals(List.of("/wp-cli/post"), agent.paths());
        assertNoSshCall();
    }

    @Test
    void testCreateOrUpdatePost_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class,
                () -> adapter.createOrUpdatePost(unsupportedCredentials(), content(), null));
    }

    // ---- postExists / mediaExists ----

    @Test
    void testPostExists_SSHトランスポートは実在しない投稿をfalseにする() {
        ssh.whenFail("post get", "Error: Invalid post ID.");

        assertFalse(adapter.postExists(sshCredentials(), "42"));
        assertNoAgentCall();
    }

    @Test
    void testPostExists_SSHトランスポートは実在する投稿をtrueにする() {
        ssh.whenOk("post get", "42");

        assertTrue(adapter.postExists(sshCredentials(), "42"));
    }

    @Test
    void testPostExists_AGENTトランスポートはagentの_post_existsを呼ぶ() {
        agent.respond("/wp-cli/post-exists", "{\"exists\":false}");

        assertFalse(adapter.postExists(agentCredentials(), "42"));
        assertTrue(agent.last().body().contains("\"postId\":\"42\""));
        assertNoSshCall();
    }

    @Test
    void testPostExists_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class, () -> adapter.postExists(unsupportedCredentials(), "42"));
    }

    @Test
    void testMediaExists_SSHトランスポートは投稿と同じ実在確認を使う() {
        ssh.whenFail("post get", "Error: Invalid post ID.");

        assertFalse(adapter.mediaExists(sshCredentials(), "11"));
        assertTrue(ssh.commands.get(0).contains("post get '11' --field=ID"));
        assertNoAgentCall();
    }

    @Test
    void testMediaExists_AGENTトランスポートはagentの_post_existsを呼ぶ() {
        agent.respond("/wp-cli/post-exists", "{\"exists\":true}");

        assertTrue(adapter.mediaExists(agentCredentials(), "11"));
        assertEquals(List.of("/wp-cli/post-exists"), agent.paths());
        assertNoSshCall();
    }

    @Test
    void testMediaExists_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class, () -> adapter.mediaExists(unsupportedCredentials(), "11"));
    }

    // ---- issue #1431: スラッグでの既存投稿照会 ----

    @Test
    void findPostIdsBySlug_SSHはWordPressの保存形へ正規化したスラッグで照会する() {
        ssh.whenOk("post list", "[{\"ID\":\"42\",\"post_name\":\"%e6%97%a5%e6%9c%ac-abc\"},"
                + "{\"ID\":\"43\",\"post_name\":\"other\"}]");

        assertEquals(List.of("42"), adapter.findPostIdsBySlug(sshCredentials(), "日本 ABC"));
        assertTrue(ssh.commands.get(0).contains("--name='%e6%97%a5%e6%9c%ac-abc'"), ssh.commands.get(0));
        assertNoAgentCall();
    }

    @Test
    void findPostIdsBySlug_managedはagentへ正規化したスラッグで照会する() {
        agent.respond("/wp-cli/post-list", "{\"posts\":[{\"id\":\"7\",\"slug\":\"my-slug\"},{\"id\":\"8\",\"slug\":\"x\"}]}");

        assertEquals(List.of("7"), adapter.findPostIdsBySlug(agentCredentials(), "my-slug"));
        assertTrue(agent.last().body().contains("\"postName\":\"my-slug\""));
        assertNoSshCall();
    }

    @Test
    void findPostIdsBySlug_正規化後に空になるスラッグは照会せず空を返す() {
        assertEquals(List.of(), adapter.findPostIdsBySlug(sshCredentials(), "!!!"));
        assertNoSshCall();
        assertNoAgentCall();
    }

    @Test
    void findPostIdsBySlug_未対応トランスポートは例外を投げる() {
        assertThrows(IllegalStateException.class, () -> adapter.findPostIdsBySlug(unsupportedCredentials(), "my-slug"));
    }

    // ---- issue #1432: 内容ハッシュによるメディア照会 ----

    @Test
    void findMediaBySha256_SSHはwp_evalで照会する() {
        ssh.whenOk("eval", "[{\"id\":\"5\",\"guid\":\"u\",\"sha256\":\"" + SHA + "\"}]");

        assertEquals(Map.of(SHA, new MediaUploadResult("5", "u")),
                adapter.findMediaBySha256(sshCredentials(), Set.of(SHA)));
        assertNoAgentCall();
    }

    @Test
    void findMediaBySha256_managedはagentへ照会する() {
        agent.respond("/wp-cli/media-find-by-hash",
                "{\"media\":[{\"id\":\"5\",\"guid\":\"u\",\"sha256\":\"" + SHA + "\"}]}");

        assertEquals(Map.of(SHA, new MediaUploadResult("5", "u")),
                adapter.findMediaBySha256(agentCredentials(), Set.of(SHA)));
        assertNoSshCall();
    }

    @Test
    void findMediaBySha256_未対応トランスポートは例外を投げる() {
        assertThrows(IllegalStateException.class,
                () -> adapter.findMediaBySha256(unsupportedCredentials(), Set.of(SHA)));
    }

    // ---- uploadMedia ----

    @Test
    void testUploadMedia_SSHトランスポートはSFTPで転送してwp_media_importで取り込む() {
        byte[] imageData = {1, 2, 3};
        ssh.whenOk("test -f", "")
                .whenOk("media import", "456")
                .whenOk("post get", "{\"guid\":\"https://example.com/uploads/image.png\"}")
                .whenOk("post meta update", "");

        MediaUploadResult result = adapter.uploadMedia(sshCredentials(), "image.png", "image/png", imageData);

        assertEquals(new MediaUploadResult("456", "https://example.com/uploads/image.png"), result);
        String remotePath = ssh.uploaded.keySet().iterator().next();
        assertTrue(remotePath.startsWith("/tmp/letsblog-media-") && remotePath.endsWith("-image.png"), remotePath);
        assertEquals(List.of(remotePath), ssh.removed);
        assertNoAgentCall();
    }

    @Test
    void testUploadMedia_AGENTトランスポートはagentへmultipartで送る() {
        agent.respond("/wp-cli/media-upload", "{\"mediaId\":\"456\",\"guid\":\"https://example.com/uploads/image.png\"}");

        MediaUploadResult result = adapter.uploadMedia(agentCredentials(), "image.png", "image/png", new byte[]{1, 2, 3});

        assertEquals(new MediaUploadResult("456", "https://example.com/uploads/image.png"), result);
        assertTrue(agent.last().body().contains("filename=\"image.png\""), agent.last().body());
        assertNoSshCall();
    }

    @Test
    void testUploadMedia_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class,
                () -> adapter.uploadMedia(unsupportedCredentials(), "image.png", "image/png", new byte[]{1}));
    }

    // ---- resolveCategories / resolveTags / provisionDefault* ----

    @Test
    void testResolveCategories_SSHトランスポートは既存カテゴリのIDを返す() {
        ssh.whenOk("term list", "[{\"name\":\"technology\",\"term_id\":\"1\"}]");

        assertEquals(List.of("1"), adapter.resolveCategories(sshCredentials(), List.of("Technology")));
        assertTrue(ssh.commands.get(0).contains("term list 'category' --search='Technology'"));
        assertNoAgentCall();
    }

    @Test
    void testResolveCategories_AGENTトランスポートはtaxonomyにcategoryを指定する() {
        agent.respond("/wp-cli/resolve-terms", "{\"ids\":[\"3\"]}");

        assertEquals(List.of("3"), adapter.resolveCategories(agentCredentials(), List.of("Technology")));
        assertTrue(agent.last().body().contains("\"taxonomy\":\"category\""));
    }

    @Test
    void testResolveCategories_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class,
                () -> adapter.resolveCategories(unsupportedCredentials(), List.of("Technology")));
    }

    @Test
    void testResolveTags_SSHトランスポートはpost_tagで検索する() {
        ssh.whenOk("term list", "[{\"name\":\"java\",\"term_id\":\"9\"}]");

        assertEquals(List.of("9"), adapter.resolveTags(sshCredentials(), List.of("java")));
        assertTrue(ssh.commands.get(0).contains("term list 'post_tag'"));
    }

    @Test
    void testResolveTags_AGENTトランスポートはtaxonomyにpost_tagを指定する() {
        agent.respond("/wp-cli/resolve-terms", "{\"ids\":[\"9\"]}");

        assertEquals(List.of("9"), adapter.resolveTags(agentCredentials(), List.of("java")));
        assertTrue(agent.last().body().contains("\"taxonomy\":\"post_tag\""));
    }

    @Test
    void testResolveTags_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class,
                () -> adapter.resolveTags(unsupportedCredentials(), List.of("java")));
    }

    @Test
    void provisionDefaultCategory_Uncategorizedを解決して先頭のIDを返す() {
        ssh.whenOk("term list", "[{\"name\":\"Uncategorized\",\"term_id\":\"1\"}]");

        assertEquals("1", adapter.provisionDefaultCategory(sshCredentials()));
        assertTrue(ssh.commands.get(0).contains("--search='Uncategorized'"));
    }

    @Test
    void provisionDefaultTag_既定タグを解決して先頭のIDを返す() {
        agent.respond("/wp-cli/resolve-terms", "{\"ids\":[\"9\"]}");

        assertEquals("9", adapter.provisionDefaultTag(agentCredentials()));
        assertTrue(agent.last().body().contains("Let's Blog"));
    }

    // ---- provisionAuthor / hasAuthorProvisioningCapability / findAuthorIdByEmail ----

    @Test
    void testProvisionAuthor_SSHトランスポートは未登録のメールなら作成してプロフィールを更新する() {
        ssh.whenOk("user list", "[]").whenOk("user create", "7").whenOk("user update", "");

        String authorId = adapter.provisionAuthor(sshCredentials(), AuthorProvisioningRequest.of("author@example.com"));

        assertEquals("7", authorId);
        assertTrue(ssh.commands.get(ssh.indexOf("user update")).contains("user update '7'"));
        assertNoAgentCall();
    }

    @Test
    void testProvisionAuthor_AGENTトランスポートはagentの_provision_authorを呼ぶ() {
        agent.respond("/wp-cli/provision-author", "{\"userId\":\"12\"}");

        assertEquals("12", adapter.provisionAuthor(agentCredentials(), AuthorProvisioningRequest.of("author@example.com")));
        assertTrue(agent.last().body().contains("\"email\":\"author@example.com\""));
        assertNoSshCall();
    }

    @Test
    void testProvisionAuthor_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class,
                () -> adapter.provisionAuthor(unsupportedCredentials(), AuthorProvisioningRequest.of("a@example.com")));
    }

    @Test
    void testHasAuthorProvisioningCapability_SSHは疎通できればtrue_できなければfalse() {
        ssh.whenOk("echo ok", "ok").whenOk("core version", "6.4.2");
        assertTrue(adapter.hasAuthorProvisioningCapability(sshCredentials()));

        FakeSshCommandExecutor broken = new FakeSshCommandExecutor().whenFail("echo ok", "denied");
        WordPressAdapter brokenAdapter = new WordPressAdapter(new WordPressSshOperations(broken, MAPPER), null, null);
        assertFalse(brokenAdapter.hasAuthorProvisioningCapability(sshCredentials()));
    }

    @Test
    void testHasAuthorProvisioningCapability_AGENTは疎通できればtrue() {
        agent.respond("/wp-cli/core-version", "{\"version\":\"6.4.2\"}");

        assertTrue(adapter.hasAuthorProvisioningCapability(agentCredentials()));
        assertNoSshCall();
    }

    @Test
    void testHasAuthorProvisioningCapability_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class,
                () -> adapter.hasAuthorProvisioningCapability(unsupportedCredentials()));
    }

    @Test
    void testFindAuthorIdByEmail_SSHトランスポートはwp_user_listで検索する() {
        ssh.whenOk("user list", "[{\"ID\":\"9\"}]");

        assertEquals(Optional.of("9"), adapter.findAuthorIdByEmail(sshCredentials(), "author@example.com"));
        assertNoAgentCall();
    }

    @Test
    void testFindAuthorIdByEmail_AGENTトランスポートはagentの_find_authorを呼ぶ() {
        agent.respond("/wp-cli/find-author", "{\"userId\":\"9\"}");

        assertEquals(Optional.of("9"), adapter.findAuthorIdByEmail(agentCredentials(), "author@example.com"));
        assertNoSshCall();
    }

    @Test
    void testFindAuthorIdByEmail_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class,
                () -> adapter.findAuthorIdByEmail(unsupportedCredentials(), "author@example.com"));
    }

    // ---- 一覧(カテゴリ/タグ) ----

    @Test
    void testListCategoryNames_SSHトランスポートはwp_term_listの名前を返す() {
        ssh.whenOk("term list",
                "[{\"term_id\":\"1\",\"name\":\"お知らせ\",\"slug\":\"news\",\"parent\":\"0\",\"description\":\"\"}]");

        assertEquals(List.of("お知らせ"), adapter.listCategoryNames(sshCredentials()));
    }

    @Test
    void testListCategoryNames_AGENTトランスポートは一括管理エージェントのカテゴリ一覧を返す() {
        agent.respond("/categories",
                "{\"categories\":[{\"name\":\"技術\",\"slug\":\"tech\",\"parentSlug\":null,\"description\":\"\"}]}");

        assertEquals(List.of("技術"), adapter.listCategoryNames(agentCredentials()));
        assertTrue(agent.last().body().contains("\"slug\":\"main\""));
        assertNoSshCall();
    }

    @Test
    void testListCategoryNames_取得失敗時は空リストを返す() {
        ssh.whenFail("term list", "boom");

        assertEquals(List.of(), adapter.listCategoryNames(sshCredentials()));
    }

    @Test
    void testListCategoryNames_agentがエラー応答を返したときも空リストを返す() {
        agent.respond("/categories", 500, "{\"error\":\"boom\"}");

        assertEquals(List.of(), adapter.listCategoryNames(agentCredentials()));
    }

    @Test
    void testListCategoryNames_未対応トランスポートは例外を握りつぶして空リストを返す() {
        assertEquals(List.of(), adapter.listCategoryNames(unsupportedCredentials()));
    }

    @Test
    void testListTagNames_SSHトランスポートはwp_term_listの名前を返す() {
        ssh.whenOk("term list",
                "[{\"term_id\":\"1\",\"name\":\"Java\",\"slug\":\"java\",\"parent\":\"0\",\"description\":\"\"}]");

        assertEquals(List.of("Java"), adapter.listTagNames(sshCredentials()));
        assertTrue(ssh.commands.get(0).contains("term list 'post_tag'"));
    }

    @Test
    void testListTagNames_AGENTトランスポートは一括管理エージェントのタグ一覧を返す() {
        agent.respond("/tags", "{\"tags\":[{\"name\":\"AWS\",\"slug\":\"aws\",\"parentSlug\":null,\"description\":\"\"}]}");

        assertEquals(List.of("AWS"), adapter.listTagNames(agentCredentials()));
    }

    @Test
    void testListTagNames_取得失敗と未対応トランスポートは空リストを返す() {
        ssh.whenFail("term list", "boom");

        assertEquals(List.of(), adapter.listTagNames(sshCredentials()));
        assertEquals(List.of(), adapter.listTagNames(unsupportedCredentials()));
    }

    @Test
    void testListCategoriesWithParents_SSHトランスポートはparentSlugから親カテゴリ名を解決する() {
        ssh.whenOk("term list", "["
                + "{\"term_id\":\"1\",\"name\":\"技術\",\"slug\":\"tech\",\"parent\":\"0\",\"description\":\"\"},"
                + "{\"term_id\":\"2\",\"name\":\"Java\",\"slug\":\"java\",\"parent\":\"1\",\"description\":\"\"}]");

        assertEquals(List.of(
                new CmsAdapter.CategoryOption("技術", null),
                new CmsAdapter.CategoryOption("Java", "技術")), adapter.listCategoriesWithParents(sshCredentials()));
    }

    @Test
    void testListCategoriesWithParents_AGENTトランスポートはparentSlugから親カテゴリ名を解決する() {
        agent.respond("/categories", "{\"categories\":["
                + "{\"name\":\"技術\",\"slug\":\"tech\",\"parentSlug\":null,\"description\":\"\"},"
                + "{\"name\":\"Java\",\"slug\":\"java\",\"parentSlug\":\"tech\",\"description\":\"\"}]}");

        assertEquals(List.of(
                new CmsAdapter.CategoryOption("技術", null),
                new CmsAdapter.CategoryOption("Java", "技術")), adapter.listCategoriesWithParents(agentCredentials()));
    }

    @Test
    void testListCategoriesWithParents_取得失敗と未対応トランスポートは空リストを返す() {
        ssh.whenFail("term list", "boom");

        assertEquals(List.of(), adapter.listCategoriesWithParents(sshCredentials()));
        assertEquals(List.of(), adapter.listCategoriesWithParents(unsupportedCredentials()));
    }

    // ---- deletePost ----

    @Test
    void testDeletePost_SSHトランスポートは存在確認のあとwp_post_deleteを実行する() {
        ssh.whenOk("--field=post_status", "publish").whenOk("post delete", "");

        adapter.deletePost(sshCredentials(), "123");

        assertTrue(ssh.ran("post delete '123'"));
        assertNoAgentCall();
    }

    @Test
    void testDeletePost_AGENTトランスポートはagentの_post_deleteを呼ぶ() {
        agent.respond("/wp-cli/post-delete", "{}");

        adapter.deletePost(agentCredentials(), "123");

        assertEquals(List.of("/wp-cli/post-delete"), agent.paths());
        assertNoSshCall();
    }

    @Test
    void testDeletePost_postTypeあり版はpostId版と同じ削除を行う() {
        ssh.whenOk("--field=post_status", "publish").whenOk("post delete", "");

        adapter.deletePost(sshCredentials(), "123", "post");

        assertTrue(ssh.ran("post delete '123'"));
    }

    @Test
    void testDeletePost_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class, () -> adapter.deletePost(unsupportedCredentials(), "123"));
    }

    // ---- 投稿/ページ・メディアの一覧と操作 ----

    @Test
    void listPosts_SSHはwp_post_listの結果を返す() {
        ssh.whenOk("post list",
                "[{\"ID\":\"1\",\"post_title\":\"T\",\"post_name\":\"t\",\"post_status\":\"publish\"}]");

        assertEquals(List.of(new CmsPostSummary("1", "T", "t", "publish", "page")),
                adapter.listPosts(sshCredentials(), "page"));
        assertNoAgentCall();
    }

    @Test
    void listPosts_AGENTはagentの_post_listの結果を返す() {
        agent.respond("/wp-cli/post-list",
                "{\"posts\":[{\"id\":\"1\",\"title\":\"T\",\"slug\":\"t\",\"status\":\"publish\"}]}");

        assertEquals(List.of(new CmsPostSummary("1", "T", "t", "publish", "post")),
                adapter.listPosts(agentCredentials(), "post"));
        assertNoSshCall();
    }

    @Test
    void listPosts_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class, () -> adapter.listPosts(unsupportedCredentials(), "post"));
    }

    @Test
    void updatePostStatus_SSHはpostTypeを使わずIDでステータスを更新する() {
        ssh.whenOk("post update", "");

        adapter.updatePostStatus(sshCredentials(), "5", "post", "private");

        assertTrue(ssh.ran("post update '5' --post_status='private'"));
        assertNoAgentCall();
    }

    @Test
    void updatePostStatus_AGENTはagentの_post_status_updateを呼ぶ() {
        agent.respond("/wp-cli/post-status-update", "{}");

        adapter.updatePostStatus(agentCredentials(), "5", "post", "private");

        assertTrue(agent.last().body().contains("\"status\":\"private\""));
        assertNoSshCall();
    }

    @Test
    void updatePostStatus_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class,
                () -> adapter.updatePostStatus(unsupportedCredentials(), "5", "post", "private"));
    }

    @Test
    void listMedia_SSHはwp_post_listの結果を返す() {
        ssh.whenOk("post list", "[{\"ID\":\"5\",\"guid\":\"u\",\"post_title\":\"t\","
                + "\"post_mime_type\":\"image/png\",\"post_date\":\"2026-01-01\"}]");

        assertEquals(List.of(new CmsMediaSummary("5", "u", "t", "image/png", "2026-01-01")),
                adapter.listMedia(sshCredentials()));
    }

    @Test
    void listMedia_AGENTはagentの_media_listの結果を返す() {
        agent.respond("/wp-cli/media-list", "{\"media\":[{\"id\":\"5\",\"guid\":\"u\",\"title\":\"t\","
                + "\"mimeType\":\"image/png\",\"uploadedAt\":\"2026-01-01\"}]}");

        assertEquals(List.of(new CmsMediaSummary("5", "u", "t", "image/png", "2026-01-01")),
                adapter.listMedia(agentCredentials()));
    }

    @Test
    void listMedia_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class, () -> adapter.listMedia(unsupportedCredentials()));
    }

    @Test
    void scanMediaReferences_SSHはwp_evalの結果を返す() {
        ssh.whenOk("eval", "{\"posts\":[{\"id\":\"1\",\"postType\":\"post\",\"status\":\"publish\","
                + "\"content\":\"c\",\"thumbnailId\":\"5\"}],\"settings\":{\"site_icon\":\"3\"}}");

        CmsMediaReferenceScan scan = adapter.scanMediaReferences(sshCredentials());

        assertEquals(List.of(new CmsPostContentSummary("1", "post", "publish", "c", "5")), scan.posts());
        assertEquals("3", scan.settingsMediaIds().get("site_icon"));
    }

    @Test
    void scanMediaReferences_AGENTはagentの_media_reference_scanの結果を返す() {
        agent.respond("/wp-cli/media-reference-scan", "{\"posts\":[],\"settings\":{\"custom_logo\":\"4\"}}");

        CmsMediaReferenceScan scan = adapter.scanMediaReferences(agentCredentials());

        assertEquals(List.of(), scan.posts());
        assertEquals("4", scan.settingsMediaIds().get("custom_logo"));
    }

    @Test
    void scanMediaReferences_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class, () -> adapter.scanMediaReferences(unsupportedCredentials()));
    }

    @Test
    void deleteMedia_SSHは存在確認のあと_force付きで削除する() {
        ssh.whenOk("--field=ID", "5").whenOk("post delete", "");

        adapter.deleteMedia(sshCredentials(), "5");

        assertTrue(ssh.ran("post delete '5' --force"));
        assertNoAgentCall();
    }

    @Test
    void deleteMedia_AGENTはagentの_media_deleteを呼ぶ() {
        agent.respond("/wp-cli/media-delete", "{}");

        adapter.deleteMedia(agentCredentials(), "5");

        assertEquals(List.of("/wp-cli/media-delete"), agent.paths());
        assertNoSshCall();
    }

    @Test
    void deleteMedia_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class, () -> adapter.deleteMedia(unsupportedCredentials(), "5"));
    }

    // ---- installWpCli ----

    @Test
    void testInstallWpCli_SSHは未導入ならインストールして結果を返す() {
        ssh.whenFail("command -v wp", "").whenOk("curl -fsSL", "WP-CLI 2.9.0");

        WpCliInstallResult result = adapter.installWpCli(sshCredentials());

        assertTrue(result.message().contains("WP-CLI 2.9.0"));
    }

    @Test
    void testInstallWpCli_SSH以外は例外() {
        assertThrows(IllegalStateException.class, () -> adapter.installWpCli(agentCredentials()));
        assertNoAgentCall();
    }

    // ---- issue #1557: letsblogプラグインの導入状態 ----

    @Test
    void letsblogPluginStatus_SSHはwp_letsblog_statusの出力から判定する() {
        ssh.whenOk("letsblog status", INSTALLED_STATUS_JSON);

        assertEquals(installedStatus(), adapter.letsblogPluginStatus(sshCredentials()));
        assertNoAgentCall();
    }

    @Test
    void letsblogPluginStatus_AGENTはagentの_letsblog_statusの出力から判定する() {
        agent.respond("/wp-cli/letsblog-status", agentCommandResult(1, "", "Error: 'letsblog' is not a registered wp command."));

        assertEquals(LetsblogPluginStatus.notInstalled(), adapter.letsblogPluginStatus(agentCredentials()));
        assertNoSshCall();
    }

    @Test
    void letsblogPluginStatus_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class, () -> adapter.letsblogPluginStatus(unsupportedCredentials()));
    }

    @Test
    void installLetsblogPlugin_SSHは配置して有効化し導入後の状態を返す() {
        ssh.whenFail("cat ", "")
                .whenOk("mkdir -p", "")
                .whenOk("plugin activate", "")
                .whenOk("letsblog status", INSTALLED_STATUS_JSON);

        assertEquals(installedStatus(), adapter.installLetsblogPlugin(sshCredentials()));
        assertTrue(ssh.uploaded.keySet().stream().anyMatch(p -> p.endsWith("/plugins/letsblog/letsblog.php")));
        assertNoAgentCall();
    }

    @Test
    void installLetsblogPlugin_AGENTはagentへ導入を依頼して導入後の状態を返す() {
        agent.respond("/wp-cli/letsblog-install", "{}")
                .respond("/wp-cli/letsblog-status", agentCommandResult(0, INSTALLED_STATUS_JSON, ""));

        assertEquals(installedStatus(), adapter.installLetsblogPlugin(agentCredentials()));
        assertEquals(List.of("/wp-cli/letsblog-install", "/wp-cli/letsblog-status"), agent.paths());
        assertNoSshCall();
    }

    @Test
    void installLetsblogPlugin_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class, () -> adapter.installLetsblogPlugin(unsupportedCredentials()));
    }

    @Test
    void requireLetsblogPlugin_導入済みなら何も投げない() {
        agent.respond("/wp-cli/letsblog-status", agentCommandResult(0, INSTALLED_STATUS_JSON, ""));

        adapter.requireLetsblogPlugin(agentCredentials());
    }

    @Test
    void requireLetsblogPlugin_未導入なら再導入を案内する例外を投げる() {
        ssh.whenFail("letsblog status", "Error: 'letsblog' is not a registered wp command.");

        LetsblogPluginUnavailableException e = assertThrows(LetsblogPluginUnavailableException.class,
                () -> adapter.requireLetsblogPlugin(sshCredentials()));
        assertTrue(e.getMessage().contains("再導入"));
    }

    @Test
    void requireLetsblogPlugin_要更新でも例外を投げる() {
        agent.respond("/wp-cli/letsblog-status",
                agentCommandResult(0, "{\"plugin_version\":\"0.9.0\",\"protocol_version\":0}", ""));

        LetsblogPluginUnavailableException e = assertThrows(LetsblogPluginUnavailableException.class,
                () -> adapter.requireLetsblogPlugin(agentCredentials()));
        assertEquals(LetsblogPluginStatus.State.NEEDS_UPDATE, e.getStatus().state());
    }

    // ---- issue #1558 / #1561 / #1574 ----

    @Test
    void syncLetsblogPlugin_SSHはwp_letsblog_syncで送り保存されたハッシュを返す() {
        ssh.whenOk("letsblog sync", "{\"sync_hash\":\"h1\"}").whenOk("rm -f", "");

        assertEquals("h1", adapter.syncLetsblogPlugin(sshCredentials(), "{}", "h1"));
        assertNoAgentCall();
    }

    @Test
    void syncLetsblogPlugin_AGENTはagentの_letsblog_syncで送り保存されたハッシュを返す() {
        agent.respond("/wp-cli/letsblog-sync", agentCommandResult(0, "{\"sync_hash\":\"h1\"}", ""));

        assertEquals("h1", adapter.syncLetsblogPlugin(agentCredentials(), "{}", "h1"));
        assertNoSshCall();
    }

    @Test
    void syncLetsblogPlugin_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class,
                () -> adapter.syncLetsblogPlugin(unsupportedCredentials(), "{}", "h1"));
    }

    @Test
    void createSignedPreview_SSHは有効期限つきでwp_letsblog_previewを実行する() {
        ssh.whenOk("letsblog preview", "{\"url\":\"https://x/?letsblog_preview=t\",\"expires_at\":1}")
                .whenOk("rm -f", "");

        assertEquals(new SignedPreview("https://x/?letsblog_preview=t", 1L),
                adapter.createSignedPreview(sshCredentials(), "{}", 600));
        assertTrue(ssh.ran("--ttl=600"));
        assertNoAgentCall();
    }

    @Test
    void createSignedPreview_AGENTは有効期限を省略してagentへ依頼する() {
        agent.respond("/wp-cli/letsblog-preview",
                agentCommandResult(0, "{\"url\":\"https://x/?letsblog_preview=t\",\"expires_at\":1}", ""));

        assertEquals(new SignedPreview("https://x/?letsblog_preview=t", 1L),
                adapter.createSignedPreview(agentCredentials(), "{}", null));
        assertFalse(agent.last().body().contains("\"ttl\""));
        assertNoSshCall();
    }

    @Test
    void createSignedPreview_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class,
                () -> adapter.createSignedPreview(unsupportedCredentials(), "{}", null));
    }

    @Test
    void runLetsblogSns_SSHはwp_letsblog_sns_statusを実行する() {
        ssh.whenOk("letsblog sns status", "{}\n");

        assertEquals("{}", adapter.runLetsblogSns(sshCredentials(), LetsblogSnsCommand.STATUS, "x", null));
        assertNoAgentCall();
    }

    @Test
    void runLetsblogSns_AGENTはagentの_letsblog_snsへ標準入力を渡す() {
        agent.respond("/wp-cli/letsblog-sns", agentCommandResult(0, "ok", ""));

        assertEquals("ok", adapter.runLetsblogSns(agentCredentials(), LetsblogSnsCommand.CONFIG_SET, "x", "{\"k\":1}"));
        assertTrue(agent.last().body().contains("\"command\":\"config-set\""));
        assertTrue(agent.last().body().contains("\"stdin\":\"{\\\"k\\\":1}\""), agent.last().body());
        assertNoSshCall();
    }

    @Test
    void runLetsblogSns_未対応トランスポートは例外() {
        assertThrows(IllegalStateException.class,
                () -> adapter.runLetsblogSns(unsupportedCredentials(), LetsblogSnsCommand.STATUS, "x", null));
    }
}
