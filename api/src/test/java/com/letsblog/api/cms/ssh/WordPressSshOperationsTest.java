package com.letsblog.api.cms.ssh;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.cms.AuthorProvisioningRequest;
import com.letsblog.api.cms.CmsCredentials.WordPressCredentials;
import com.letsblog.api.cms.ConnectionCheckResult;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.cms.PostContent;
import com.letsblog.api.cms.PostResult;
import com.letsblog.api.cms.ssh.SshCommandExecutor.SshCommandResult;
import com.letsblog.api.cms.ssh.SshCommandExecutor.SshConnectionParams;
import com.letsblog.api.domain.BulkOperationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WordPressSshOperationsTest {

    @Mock
    private SshCommandExecutor executor;

    private WordPressSshOperations operations;

    @BeforeEach
    void setUp() {
        operations = new WordPressSshOperations(executor, new ObjectMapper());
    }

    private WordPressCredentials creds() {
        return creds("/var/www/html");
    }

    private WordPressCredentials creds(String wpPath) {
        return new WordPressCredentials(
                "https://example.com", null, null,
                "SSH", "203.0.113.5", 22, "deploy", wpPath, "PRIVATE-KEY-PEM", "SHA256:pinned", null);
    }

    private WordPressCredentials credsWithUsername(String username) {
        return new WordPressCredentials(
                "https://example.com", username, null,
                "SSH", "203.0.113.5", 22, "deploy", "/var/www/html", "PRIVATE-KEY-PEM", "SHA256:pinned", null);
    }

    private SshCommandResult ok(String stdout) {
        return new SshCommandResult(0, stdout, "", "SHA256:observed");
    }

    private SshCommandResult fail(String stderr) {
        return new SshCommandResult(1, "", stderr, null);
    }

    @Test
    void testConnection_成功時にfingerprintとwp_core_versionの応答を返す() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("ok"))
                .thenReturn(ok("6.4.2"));

        ConnectionCheckResult result = operations.testConnection(creds());

        assertEquals(true, result.ok());
        assertEquals("SHA256:observed", result.observedHostKeyFingerprint());
        assertEquals("wp core version: 6.4.2", result.detail());

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals("echo ok", commandCaptor.getAllValues().get(0));
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("core version"));
    }

    @Test
    void testConnection_SSH接続コマンドが失敗すればSSH接続失敗として返す() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(fail("Permission denied"));

        ConnectionCheckResult result = operations.testConnection(creds());

        assertEquals(false, result.ok());
        assertEquals(true, result.failureReason().startsWith("SSH接続に失敗しました: "));
        verify(executor, times(1)).exec(any(SshConnectionParams.class), any(), isNull());
    }

    @Test
    void testConnection_SSH接続例外時はSSH接続失敗として返す() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenThrow(new SshOperationException("接続がタイムアウトしました"));

        ConnectionCheckResult result = operations.testConnection(creds());

        assertEquals(false, result.ok());
        assertEquals(true, result.failureReason().startsWith("SSH接続に失敗しました: "));
        verify(executor, times(1)).exec(any(SshConnectionParams.class), any(), isNull());
    }

    @Test
    void testConnection_wp_core_versionが失敗すればwp_cli実行失敗として返す() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("ok"))
                .thenReturn(fail("wp-cli: command not found"));

        ConnectionCheckResult result = operations.testConnection(creds());

        assertEquals(false, result.ok());
        assertEquals(true, result.failureReason().startsWith("wp core versionの実行に失敗しました: "));
    }

    @Test
    void hasAuthorProvisioningCapability_疎通確認が成功すればtrue() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(ok("{}"));

        assertEquals(true, operations.hasAuthorProvisioningCapability(creds()));
    }

    @Test
    void hasAuthorProvisioningCapability_疎通確認が失敗すればfalse() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(fail("error"));

        assertEquals(false, operations.hasAuthorProvisioningCapability(creds()));
    }

    @Test
    void resolveCategories_既存タームが見つかればそれを返す() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("[{\"name\":\"News\",\"term_id\":\"5\"}]"));

        List<String> ids = operations.resolveCategories(creds(), List.of("News"));

        assertEquals(List.of("5"), ids);
        verify(executor, times(1)).exec(any(SshConnectionParams.class), any(), isNull());
    }

    @Test
    void resolveCategories_見つからなければ作成する() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("[]"))
                .thenReturn(ok("42\n"));

        List<String> ids = operations.resolveCategories(creds(), List.of("New Category"));

        assertEquals(List.of("42"), ids);

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getAllValues().get(0).contains("term list category"));
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("term create category"));
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("--porcelain"));
    }

    @Test
    void resolveTags_タクソノミにpost_tagを使う() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("[{\"name\":\"Tech\",\"term_id\":\"7\"}]"));

        List<String> ids = operations.resolveTags(creds(), List.of("Tech"));

        assertEquals(List.of("7"), ids);
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getValue().contains("term list post_tag"));
    }

    @Test
    void resolveCategories_検索コマンドが失敗したら例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(fail("wp: command not found"));

        assertThrows(SshOperationException.class, () -> operations.resolveCategories(creds(), List.of("News")));
    }

    @Test
    void resolveCategories_作成コマンドが失敗したら例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("[]"))
                .thenReturn(fail("term already exists"));

        assertThrows(SshOperationException.class, () -> operations.resolveCategories(creds(), List.of("Dup")));
    }

    @Test
    void resolveCategories_空リストはコマンドを実行せず空を返す() {
        List<String> ids = operations.resolveCategories(creds(), List.of());

        assertEquals(List.of(), ids);
        verify(executor, never()).exec(any(), any(), any());
    }

    @Test
    void provisionAuthor_既存ユーザーが見つかればプロフィール更新のみ実行する() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("[{\"ID\":\"11\"}]"))
                .thenReturn(ok(""));

        String userId = operations.provisionAuthor(creds(), AuthorProvisioningRequest.of("author@example.com"));

        assertEquals("11", userId);
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getAllValues().get(0).contains("user list --search="));
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("user update 11"));
    }

    @Test
    void provisionAuthor_見つからなければ作成してからプロフィールを更新する() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("[]"))
                .thenReturn(ok("23\n"))
                .thenReturn(ok(""));

        String userId = operations.provisionAuthor(creds(), AuthorProvisioningRequest.of("new-author@example.com"));

        assertEquals("23", userId);
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(3)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("user create"));
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("--porcelain"));
        assertEquals(true, commandCaptor.getAllValues().get(2).contains("user update 23"));
    }

    @Test
    void provisionAuthor_作成が失敗しても再検索で見つかれば更新にフォールバックする() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("[]"))
                .thenReturn(fail("A user with that email already exists."))
                .thenReturn(ok("[{\"ID\":\"31\"}]"))
                .thenReturn(ok(""));

        String userId = operations.provisionAuthor(creds(), AuthorProvisioningRequest.of("race@example.com"));

        assertEquals("31", userId);
        verify(executor, times(4)).exec(any(SshConnectionParams.class), any(), isNull());
    }

    @Test
    void provisionAuthor_作成が失敗し再検索でも見つからなければ例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("[]"))
                .thenReturn(fail("unexpected error"))
                .thenReturn(ok("[]"));

        assertThrows(SshOperationException.class,
                () -> operations.provisionAuthor(creds(), AuthorProvisioningRequest.of("broken@example.com")));
    }

    @Test
    void provisionAuthor_プロフィール更新が失敗したら例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("[{\"ID\":\"11\"}]"))
                .thenReturn(fail("update failed"));

        assertThrows(SshOperationException.class,
                () -> operations.provisionAuthor(creds(), AuthorProvisioningRequest.of("author@example.com")));
    }

    private PostContent postContent() {
        return new PostContent("Title", "my-slug", "<p>Hello</p>", "publish", List.of("5"), List.of("7"), null, null);
    }

    @Test
    void createOrUpdatePost_新規作成時はpost_createをstdin経由で実行する() {
        when(executor.exec(any(SshConnectionParams.class), any(), notNull())).thenReturn(ok("99\n"));
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("{\"guid\":\"https://example.com/?p=99\",\"post_status\":\"publish\"}"));

        PostResult result = operations.createOrUpdatePost(creds(), postContent(), null);

        assertEquals("99", result.id());
        assertEquals("https://example.com/?p=99", result.link());
        assertEquals("publish", result.status());

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> stdinCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), commandCaptor.capture(), stdinCaptor.capture());
        String createCommand = commandCaptor.getAllValues().get(0);
        assertEquals(true, createCommand.contains("post create -"));
        assertEquals(true, createCommand.contains("--post_category='5'"));
        assertEquals(true, createCommand.contains("post_tag"));
        assertEquals("<p>Hello</p>", new String(stdinCaptor.getAllValues().get(0), StandardCharsets.UTF_8));
    }

    @Test
    void createOrUpdatePost_authorId指定時はpost_authorを付与する() {
        when(executor.exec(any(SshConnectionParams.class), any(), notNull())).thenReturn(ok("99\n"));
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("{\"guid\":\"https://example.com/?p=99\",\"post_status\":\"publish\"}"));
        PostContent contentWithAuthor = new PostContent(
                "Title", "my-slug", "<p>Hello</p>", "publish", List.of("5"), List.of("7"), null, "42");

        operations.createOrUpdatePost(creds(), contentWithAuthor, null);

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), commandCaptor.capture(), any());
        assertEquals(true, commandCaptor.getAllValues().get(0).contains("--post_author='42'"));
    }

    @Test
    void createOrUpdatePost_featuredMediaId指定時はpost_thumbnailではなくpost_meta_updateでアイキャッチを設定する() {
        when(executor.exec(any(SshConnectionParams.class), any(), notNull())).thenReturn(ok("99\n"));
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("")) // post meta update
                .thenReturn(ok("{\"guid\":\"https://example.com/?p=99\",\"post_status\":\"publish\"}")); // post get
        PostContent contentWithFeaturedMedia = new PostContent(
                "Title", "my-slug", "<p>Hello</p>", "publish", List.of("5"), List.of("7"), "55", null);

        operations.createOrUpdatePost(creds(), contentWithFeaturedMedia, null);

        ArgumentCaptor<String> createCommandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(1)).exec(any(SshConnectionParams.class), createCommandCaptor.capture(), notNull());
        // wp-cliの post create/update は --post_thumbnail を認識しないため付与しない
        assertEquals(false, createCommandCaptor.getValue().contains("post_thumbnail"));

        ArgumentCaptor<String> readonlyCommandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), readonlyCommandCaptor.capture(), isNull());
        assertEquals(true, readonlyCommandCaptor.getAllValues().get(0).contains("post meta update 99 _thumbnail_id '55'"));
    }

    @Test
    void createOrUpdatePost_アイキャッチ設定コマンドが失敗したら例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), notNull())).thenReturn(ok("99\n"));
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(fail("meta update failed"));
        PostContent contentWithFeaturedMedia = new PostContent(
                "Title", "my-slug", "<p>Hello</p>", "publish", List.of("5"), List.of("7"), "55", null);

        assertThrows(SshOperationException.class,
                () -> operations.createOrUpdatePost(creds(), contentWithFeaturedMedia, null));
    }

    @Test
    void findAuthorIdByEmail_見つかればIDを返す() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("[{\"ID\":\"11\"}]"));

        assertEquals("11", operations.findAuthorIdByEmail(creds(), "author@example.com").orElse(null));
    }

    @Test
    void findAuthorIdByEmail_見つからなければ空を返す() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(ok("[]"));

        assertEquals(true, operations.findAuthorIdByEmail(creds(), "unknown@example.com").isEmpty());
    }

    @Test
    void createOrUpdatePost_既存投稿は更新コマンドを実行する() {
        when(executor.exec(any(SshConnectionParams.class), any(), notNull())).thenReturn(ok(""));
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("{\"guid\":\"https://example.com/?p=42\",\"post_status\":\"draft\"}"));

        PostResult result = operations.createOrUpdatePost(creds(), postContent(), "42");

        assertEquals("42", result.id());
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).exec(any(SshConnectionParams.class), commandCaptor.capture(), notNull());
        assertEquals(true, commandCaptor.getValue().contains("post update 42 -"));
    }

    @Test
    void createOrUpdatePost_カテゴリとタグを空リストにした更新は明示的にクリアするコマンドを送る() {
        when(executor.exec(any(SshConnectionParams.class), any(), notNull())).thenReturn(ok(""));
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("{\"guid\":\"https://example.com/?p=42\",\"post_status\":\"draft\"}"));
        PostContent clearedContent = new PostContent(
                "Title", "my-slug", "<p>Hello</p>", "publish", List.of(), List.of(), null, null);

        operations.createOrUpdatePost(creds(), clearedContent, "42");

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).exec(any(SshConnectionParams.class), commandCaptor.capture(), notNull());
        String updateCommand = commandCaptor.getValue();
        assertEquals(true, updateCommand.contains("--post_category="));
        assertEquals(true, updateCommand.contains("--tax_input="));
    }

    @Test
    void createOrUpdatePost_作成コマンドが失敗したら例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), notNull())).thenReturn(fail("wp-cli error"));

        assertThrows(SshOperationException.class,
                () -> operations.createOrUpdatePost(creds(), postContent(), null));
    }

    @Test
    void createOrUpdatePost_情報取得コマンドが失敗したら例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), notNull())).thenReturn(ok("99\n"));
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(fail("post not found"));

        assertThrows(SshOperationException.class,
                () -> operations.createOrUpdatePost(creds(), postContent(), null));
    }

    @Test
    void deletePost_forceなしでpost_deleteを実行する() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(ok(""));

        operations.deletePost(creds(), "99");

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getValue().contains("post delete 99 --yes"));
        assertEquals(false, commandCaptor.getValue().contains("--force"));
    }

    @Test
    void deletePost_失敗したら例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(fail("post not found"));

        assertThrows(SshOperationException.class, () -> operations.deletePost(creds(), "99"));
    }

    @Test
    void generateAuthCookie_wp_evalの結果からCookieを組み立てる() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("{\"name\":\"wordpress_logged_in_abc\",\"value\":\"admin|123|token|hash\"}"));

        com.letsblog.api.cms.AuthCookie cookie = operations.generateAuthCookie(credsWithUsername("admin"));

        assertEquals("wordpress_logged_in_abc", cookie.name());
        assertEquals("admin|123|token|hash", cookie.value());
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getValue().contains("eval"));
        assertEquals(true, commandCaptor.getValue().contains("get_user_by"));
    }

    @Test
    void generateAuthCookie_ユーザーが見つからない場合は例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("{\"error\":\"user_not_found\"}"));

        assertThrows(SshOperationException.class,
                () -> operations.generateAuthCookie(credsWithUsername("nobody")));
    }

    @Test
    void generateAuthCookie_wp_eval失敗時は例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(fail("eval error"));

        assertThrows(SshOperationException.class,
                () -> operations.generateAuthCookie(credsWithUsername("admin")));
    }

    @Test
    void uploadMedia_成功時はSFTP転送してmedia_importで取り込み一時ファイルを削除する() {
        byte[] data = "image-bytes".getBytes(StandardCharsets.UTF_8);
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok(""))
                .thenReturn(ok("55\n"))
                .thenReturn(ok("{\"guid\":\"https://example.com/wp-content/uploads/photo.png\"}"));

        MediaUploadResult result = operations.uploadMedia(creds(), "photo.png", "image/png", data);

        assertEquals("55", result.id());
        assertEquals("https://example.com/wp-content/uploads/photo.png", result.url());

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).putFile(any(SshConnectionParams.class), eq(data), pathCaptor.capture());
        String remotePath = pathCaptor.getValue();
        assertEquals(true, remotePath.contains("photo.png"));

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(3)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getAllValues().get(0).contains("test -f"));
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("media import"));
        assertEquals(true, commandCaptor.getAllValues().get(2).contains("post get 55"));

        verify(executor).removeFile(any(SshConnectionParams.class), eq(remotePath));
    }

    @Test
    void uploadMedia_SVGアップロード許可mu_pluginが未配置なら配置してから取り込む() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(fail("No such file or directory"))
                .thenReturn(ok("55\n"))
                .thenReturn(ok("{\"guid\":\"https://example.com/wp-content/uploads/icon.svg\"}"));

        operations.uploadMedia(creds(), "icon.svg", "image/svg+xml", new byte[]{1});

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(4)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getAllValues().get(0).contains("test -f"));
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("mkdir -p"));
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("mu-plugins"));

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(executor, times(2)).putFile(any(SshConnectionParams.class), dataCaptor.capture(), pathCaptor.capture());
        int muPluginIndex = pathCaptor.getAllValues().indexOf(
                "/var/www/html/wp-content/mu-plugins/letsblog-allow-svg-upload.php");
        assertEquals(true, muPluginIndex >= 0);
        String muPluginContent = new String(dataCaptor.getAllValues().get(muPluginIndex), StandardCharsets.UTF_8);
        assertEquals(true, muPluginContent.contains("upload_mimes"));
        assertEquals(true, muPluginContent.contains("image/svg+xml"));
    }

    @Test
    void uploadMedia_SVGアップロード許可mu_pluginが配置済みなら再配置しない() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok(""))
                .thenReturn(ok("55\n"))
                .thenReturn(ok("{\"guid\":\"https://example.com/wp-content/uploads/icon.svg\"}"));

        operations.uploadMedia(creds(), "icon.svg", "image/svg+xml", new byte[]{1});

        verify(executor, times(1)).putFile(any(SshConnectionParams.class), any(), any());
    }

    @Test
    void uploadMedia_import失敗時も一時ファイルを削除してから例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(fail("import failed"));

        assertThrows(SshOperationException.class,
                () -> operations.uploadMedia(creds(), "photo.png", "image/png", new byte[]{1}));

        verify(executor).removeFile(any(SshConnectionParams.class), any());
    }

    @Test
    void uploadMedia_情報取得失敗時も一時ファイルを削除してから例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok(""))
                .thenReturn(ok("55\n"))
                .thenReturn(fail("not found"));

        assertThrows(SshOperationException.class,
                () -> operations.uploadMedia(creds(), "photo.png", "image/png", new byte[]{1}));

        verify(executor).removeFile(any(SshConnectionParams.class), any());
    }

    @Test
    void applyZip_成功時はSFTP転送してinstall_forceを実行し一時ファイルを削除する() {
        byte[] zip = "zip-bytes".getBytes(StandardCharsets.UTF_8);
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(ok(""));

        WordPressSshOperations.SshApplyResult result =
                operations.applyZip(creds(), BulkOperationType.THEME_INSTALL, zip, "custom-theme.zip");

        assertEquals("SUCCESS", result.status());

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).putFile(any(SshConnectionParams.class), eq(zip), pathCaptor.capture());
        String remotePath = pathCaptor.getValue();
        assertEquals(true, remotePath.contains("custom-theme.zip"));

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getValue().contains("theme install"));
        assertEquals(true, commandCaptor.getValue().contains("--force"));
        assertEquals(true, commandCaptor.getValue().contains(remotePath));

        verify(executor).removeFile(any(SshConnectionParams.class), eq(remotePath));
    }

    @Test
    void applyZip_plugin_installはplugin_installコマンドを実行する() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(ok(""));

        operations.applyZip(creds(), BulkOperationType.PLUGIN_INSTALL, new byte[]{1}, "custom-plugin.zip");

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getValue().contains("plugin install"));
    }

    @Test
    void applyZip_インストール失敗時も一時ファイルを削除してFAILEDを返す() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(fail("install failed"));

        WordPressSshOperations.SshApplyResult result =
                operations.applyZip(creds(), BulkOperationType.THEME_INSTALL, new byte[]{1}, "custom-theme.zip");

        assertEquals("FAILED", result.status());
        verify(executor).removeFile(any(SshConnectionParams.class), any());
    }

    @Test
    void applyZip_転送失敗時は一時ファイル削除を試みずFAILEDを返す() {
        doThrow(new SshOperationException("put failed"))
                .when(executor).putFile(any(SshConnectionParams.class), any(byte[].class), any());

        WordPressSshOperations.SshApplyResult result =
                operations.applyZip(creds(), BulkOperationType.THEME_INSTALL, new byte[]{1}, "custom-theme.zip");

        assertEquals("FAILED", result.status());
        verify(executor, never()).removeFile(any(), any());
    }

    @Test
    void uploadMedia_ファイル名のパス区切り文字は除去される() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok(""))
                .thenReturn(ok("55\n"))
                .thenReturn(ok("{\"guid\":\"https://example.com/x.png\"}"));

        operations.uploadMedia(creds(), "../../etc/passwd.png", "image/png", new byte[]{1});

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).putFile(any(SshConnectionParams.class), any(), pathCaptor.capture());
        assertEquals(false, pathCaptor.getValue().contains("/etc/"));
        assertEquals(false, pathCaptor.getValue().contains(".."));
    }

    @Test
    void listPlugins_name_statusの一覧を返す() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("[{\"name\":\"akismet\",\"status\":\"active\"},{\"name\":\"hello\",\"status\":\"inactive\"}]"));

        List<WordPressSshOperations.PluginThemeInfo> infos = operations.listPlugins(creds());

        assertEquals(2, infos.size());
        assertEquals("akismet", infos.get(0).name());
        assertEquals("active", infos.get(0).status());
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getValue().contains("plugin list --fields=name,status --format=json"));
    }

    @Test
    void listThemes_取得に失敗したら例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(fail("wp: command not found"));

        assertThrows(SshOperationException.class, () -> operations.listThemes(creds()));
    }

    @Test
    void applyPluginTheme_未インストールのプラグインはインストールする() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("[]"))
                .thenReturn(ok(""));

        WordPressSshOperations.SshApplyResult result =
                operations.applyPluginTheme(creds(), BulkOperationType.PLUGIN_INSTALL, "akismet");

        assertEquals("SUCCESS", result.status());
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("plugin install 'akismet'"));
    }

    @Test
    void applyPluginTheme_インストール済みのプラグインはskippedを返しinstallを実行しない() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("[{\"name\":\"akismet\",\"status\":\"inactive\"}]"));

        WordPressSshOperations.SshApplyResult result =
                operations.applyPluginTheme(creds(), BulkOperationType.PLUGIN_INSTALL, "akismet");

        assertEquals("SKIPPED", result.status());
        verify(executor, times(1)).exec(any(SshConnectionParams.class), any(), isNull());
    }

    @Test
    void applyPluginTheme_有効化_無効化はそれぞれのwp_cliコマンドを実行する() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(ok(""));

        operations.applyPluginTheme(creds(), BulkOperationType.PLUGIN_ACTIVATE, "akismet");
        operations.applyPluginTheme(creds(), BulkOperationType.PLUGIN_DEACTIVATE, "akismet");
        operations.applyPluginTheme(creds(), BulkOperationType.THEME_ACTIVATE, "twentytwentyfour");

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(3)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getAllValues().get(0).contains("plugin activate 'akismet'"));
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("plugin deactivate 'akismet'"));
        assertEquals(true, commandCaptor.getAllValues().get(2).contains("theme activate 'twentytwentyfour'"));
    }

    @Test
    void applyPluginTheme_プラグイン削除は先に無効化を試みてから削除する() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(fail("not active"))
                .thenReturn(ok(""));

        WordPressSshOperations.SshApplyResult result =
                operations.applyPluginTheme(creds(), BulkOperationType.PLUGIN_DELETE, "akismet");

        assertEquals("SUCCESS", result.status());
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getAllValues().get(0).contains("plugin deactivate"));
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("plugin delete 'akismet'"));
    }

    @Test
    void applyPluginTheme_失敗しても例外を投げずfailedを返す() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(fail("permission denied"));

        WordPressSshOperations.SshApplyResult result =
                operations.applyPluginTheme(creds(), BulkOperationType.THEME_DELETE, "twentytwentyfour");

        assertEquals("FAILED", result.status());
        assertEquals(true, result.errorMessage().contains("permission denied"));
    }

    @Test
    void listCategories_parentのterm_idをparentSlugへ解決する() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(ok(
                "[{\"term_id\":\"1\",\"name\":\"News\",\"slug\":\"news\",\"parent\":\"0\",\"description\":\"\"},"
                        + "{\"term_id\":\"2\",\"name\":\"Sub\",\"slug\":\"sub-news\",\"parent\":\"1\",\"description\":\"d\"}]"));

        List<WordPressSshOperations.CategoryInfo> infos = operations.listCategories(creds());

        assertEquals(2, infos.size());
        assertEquals(null, infos.get(0).parentSlug());
        assertEquals("news", infos.get(1).parentSlug());
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getValue().contains("term list category"));
    }

    @Test
    void applyTerm_未作成のカテゴリは作成しparentを解決する() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("[{\"term_id\":\"1\",\"name\":\"News\",\"slug\":\"news\",\"parent\":\"0\",\"description\":\"\"}]"))
                .thenReturn(ok(""));

        WordPressSshOperations.SshApplyResult result = operations.applyTerm(
                creds(), BulkOperationType.CATEGORY_CREATE, "Sub News", "sub-news", "news", "desc", null);

        assertEquals("SUCCESS", result.status());
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        String createCommand = commandCaptor.getAllValues().get(1);
        assertEquals(true, createCommand.contains("term create category"));
        assertEquals(true, createCommand.contains("--parent=1"));
    }

    @Test
    void applyTerm_既に同じslugが存在すればskippedを返す() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("[{\"term_id\":\"1\",\"name\":\"News\",\"slug\":\"news\",\"parent\":\"0\",\"description\":\"\"}]"));

        WordPressSshOperations.SshApplyResult result = operations.applyTerm(
                creds(), BulkOperationType.TAG_CREATE, "News", "news", null, null, null);

        assertEquals("SKIPPED", result.status());
        verify(executor, times(1)).exec(any(SshConnectionParams.class), any(), isNull());
    }

    @Test
    void applyTerm_編集は対象slugのterm_idをupdateする() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("[{\"term_id\":\"1\",\"name\":\"News\",\"slug\":\"news\",\"parent\":\"0\",\"description\":\"\"}]"))
                .thenReturn(ok(""));

        WordPressSshOperations.SshApplyResult result = operations.applyTerm(
                creds(), BulkOperationType.TAG_EDIT, "Updated", "updated-news", null, null, "news");

        assertEquals("SUCCESS", result.status());
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("term update post_tag 1"));
    }

    @Test
    void applyTerm_編集対象が見つからなければfailedを返す() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(ok("[]"));

        WordPressSshOperations.SshApplyResult result = operations.applyTerm(
                creds(), BulkOperationType.CATEGORY_EDIT, "Updated", "updated", null, null, "missing");

        assertEquals("FAILED", result.status());
        assertEquals(true, result.errorMessage().contains("見つかりません"));
    }

    @Test
    void applyTerm_削除対象が存在しなければskippedを返す() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(ok("[]"));

        WordPressSshOperations.SshApplyResult result = operations.applyTerm(
                creds(), BulkOperationType.CATEGORY_DELETE, null, null, null, null, "already-gone");

        assertEquals("SKIPPED", result.status());
    }

    @Test
    void fetchTermsForEnvironments_複数環境分を1回のexecAllで取得する() {
        when(executor.execAll(any(SshConnectionParams.class), any())).thenReturn(List.of(
                ok("[{\"term_id\":\"1\",\"name\":\"News\",\"slug\":\"news\",\"parent\":\"0\",\"description\":\"\"}]"),
                ok("[{\"term_id\":\"5\",\"name\":\"Others\",\"slug\":\"others\",\"parent\":\"0\",\"description\":\"\"}]")));
        Map<String, WordPressCredentials> credsByEnvironment = new LinkedHashMap<>();
        credsByEnvironment.put("test", creds("/var/www/html/test"));
        credsByEnvironment.put("production", creds("/var/www/html/production"));

        WordPressSshOperations.EnvironmentFetchResult<WordPressSshOperations.CategoryInfo> result =
                operations.fetchTermsForEnvironments("category", credsByEnvironment);

        assertEquals(true, result.errorByEnvironment().isEmpty());
        assertEquals("news", result.byEnvironment().get("test").get(0).slug());
        assertEquals("others", result.byEnvironment().get("production").get(0).slug());
        ArgumentCaptor<List<String>> commandsCaptor = ArgumentCaptor.forClass(List.class);
        verify(executor, times(1)).execAll(any(SshConnectionParams.class), commandsCaptor.capture());
        assertEquals(2, commandsCaptor.getValue().size());
        verify(executor, never()).exec(any(), any(), any());
    }

    @Test
    void fetchTermsForEnvironments_接続自体が失敗したら全環境にエラーを設定する() {
        when(executor.execAll(any(SshConnectionParams.class), any()))
                .thenThrow(new SshOperationException("Connection refused"));
        Map<String, WordPressCredentials> credsByEnvironment = new LinkedHashMap<>();
        credsByEnvironment.put("test", creds("/var/www/html/test"));
        credsByEnvironment.put("production", creds("/var/www/html/production"));

        WordPressSshOperations.EnvironmentFetchResult<WordPressSshOperations.CategoryInfo> result =
                operations.fetchTermsForEnvironments("category", credsByEnvironment);

        assertEquals(true, result.byEnvironment().isEmpty());
        assertEquals(true, result.errorByEnvironment().get("test").contains("Connection refused"));
        assertEquals(true, result.errorByEnvironment().get("production").contains("Connection refused"));
    }

    @Test
    void fetchTermsForEnvironments_1環境分だけ取得コマンドが失敗してもその環境だけエラーになる() {
        when(executor.execAll(any(SshConnectionParams.class), any())).thenReturn(List.of(
                ok("[{\"term_id\":\"1\",\"name\":\"News\",\"slug\":\"news\",\"parent\":\"0\",\"description\":\"\"}]"),
                fail("wp: command not found")));
        Map<String, WordPressCredentials> credsByEnvironment = new LinkedHashMap<>();
        credsByEnvironment.put("test", creds("/var/www/html/test"));
        credsByEnvironment.put("production", creds("/var/www/html/production"));

        WordPressSshOperations.EnvironmentFetchResult<WordPressSshOperations.CategoryInfo> result =
                operations.fetchTermsForEnvironments("category", credsByEnvironment);

        assertEquals(1, result.byEnvironment().get("test").size());
        assertEquals(false, result.byEnvironment().containsKey("production"));
        assertEquals(true, result.errorByEnvironment().containsKey("production"));
    }

    @Test
    void fetchPluginsOrThemesForEnvironments_複数環境分を1回のexecAllで取得する() {
        when(executor.execAll(any(SshConnectionParams.class), any())).thenReturn(List.of(
                ok("[{\"name\":\"akismet\",\"status\":\"active\"}]"),
                ok("[{\"name\":\"akismet\",\"status\":\"inactive\"}]")));
        Map<String, WordPressCredentials> credsByEnvironment = new LinkedHashMap<>();
        credsByEnvironment.put("test", creds("/var/www/html/test"));
        credsByEnvironment.put("production", creds("/var/www/html/production"));

        WordPressSshOperations.EnvironmentFetchResult<WordPressSshOperations.PluginThemeInfo> result =
                operations.fetchPluginsOrThemesForEnvironments("plugin", credsByEnvironment);

        assertEquals("active", result.byEnvironment().get("test").get(0).status());
        assertEquals("inactive", result.byEnvironment().get("production").get(0).status());
        verify(executor, times(1)).execAll(any(SshConnectionParams.class), any());
    }
}
