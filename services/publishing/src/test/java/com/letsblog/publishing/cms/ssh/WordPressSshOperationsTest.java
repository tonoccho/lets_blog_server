package com.letsblog.publishing.cms.ssh;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.publishing.cms.AuthorProvisioningRequest;
import com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials;
import com.letsblog.publishing.cms.ConnectionCheckResult;
import com.letsblog.publishing.cms.MediaUploadResult;
import com.letsblog.publishing.cms.PostContent;
import com.letsblog.publishing.cms.PostResult;
import com.letsblog.publishing.cms.WpCliInstallResult;
import com.letsblog.publishing.cms.ssh.SshCommandExecutor.SshCommandResult;
import com.letsblog.publishing.cms.ssh.SshCommandExecutor.SshConnectionParams;
import com.letsblog.publishing.domain.BulkOperationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.letsblog.publishing.cms.agent.PostNotFoundException;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeastOnce;
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
                "https://example.com", null,
                "SSH", "203.0.113.5", 22, "deploy", wpPath, "PRIVATE-KEY-PEM", "SHA256:pinned", null);
    }

    private WordPressCredentials credsWithUsername(String username) {
        return new WordPressCredentials(
                "https://example.com", username,
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

    // ------------------------------------------------------------------------------------
    // installWpCli(issue #1169 / AT-5-5、AC2: SSHトランスポートのサイトへのwp-cli導入と、
    // 導入後の一括管理操作(applyPluginTheme)の成功。managed(AGENT)サイトはwp-cliが
    // Dockerイメージへビルド時導入済みで対象外(installWpCliはSSHのみ対応、
    // WordPressAdapter#installWpCli参照)なので、実インフラにSSHサーバが無いこのリポジトリでは
    // E2Eで再現できない(issue #1197)。SshCommandExecutorをモックした本テストが
    // 唯一の検証経路になる。
    // ------------------------------------------------------------------------------------

    @Test
    void installWpCli_未導入なら導入コマンドを実行し成功結果を返す() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(fail("wp: command not found")) // command -v wp
                .thenReturn(ok("WP-CLI 2.9.0")); // インストールスクリプト(--versionの出力)

        WpCliInstallResult result = operations.installWpCli(creds());

        assertEquals(true, result.message().contains("インストールしました"));
        assertEquals(true, result.message().contains("WP-CLI 2.9.0"));
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals("command -v wp", commandCaptor.getAllValues().get(0));
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("curl -fsSL -o \"$HOME/bin/wp\""));
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("wp-cli.phar"));
    }

    @Test
    void installWpCli_既にインストール済みなら例外を投げ導入コマンドは実行しない() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("/home/deploy/bin/wp"));

        assertThrows(IllegalStateException.class, () -> operations.installWpCli(creds()));
        verify(executor, times(1)).exec(any(SshConnectionParams.class), any(), isNull());
    }

    @Test
    void installWpCli_導入コマンドが失敗したら例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(fail("")) // command -v wp: 未導入
                .thenReturn(fail("curl: command not found"));

        SshOperationException exception =
                assertThrows(SshOperationException.class, () -> operations.installWpCli(creds()));
        assertEquals(true, exception.getMessage().contains("curl: command not found"));
    }

    @Test
    void installWpCli_導入成功後に一括管理操作のプラグイン有効化が実行できる() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(fail("wp: command not found")) // command -v wp
                .thenReturn(ok("WP-CLI 2.9.0")) // インストールスクリプト
                .thenReturn(ok("")); // 一括管理操作(plugin activate)

        WpCliInstallResult installResult = operations.installWpCli(creds());
        assertEquals(true, installResult.message().contains("インストールしました"));

        WordPressSshOperations.SshApplyResult applyResult =
                operations.applyPluginTheme(creds(), BulkOperationType.PLUGIN_ACTIVATE, "akismet");

        assertEquals("SUCCESS", applyResult.status());
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(3)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getAllValues().get(2).contains("plugin activate 'akismet'"));
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
        assertEquals(true, commandCaptor.getAllValues().get(0).contains("term list 'category'"));
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("term create 'category'"));
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
        assertEquals(true, commandCaptor.getValue().contains("term list 'post_tag'"));
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
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("user update '11'"));
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
        assertEquals(true, commandCaptor.getAllValues().get(2).contains("user update '23'"));
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
        assertEquals(true, readonlyCommandCaptor.getAllValues().get(0).contains("post meta update '99' _thumbnail_id '55'"));
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
        assertEquals(true, commandCaptor.getValue().contains("post update '42' -"));
    }

    @Test
    void createOrUpdatePost_publishScheduledAt指定時はpost_dateとpost_date_gmtの両方を送る() {
        // `post update`は未指定フィールドを既存投稿の値のまま引き継ぐため、--post_date_gmtだけを
        // 送ると post_date(サイトのローカル時刻。wp-admin等が表示する値)が更新されずに取り残される
        // (issue #504)。両方が同じ日時で送られることを確認する。
        when(executor.exec(any(SshConnectionParams.class), any(), notNull())).thenReturn(ok(""));
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("{\"guid\":\"https://example.com/?p=42\",\"post_status\":\"future\"}"));
        PostContent scheduled = new PostContent(
                "Title", "my-slug", "<p>Hello</p>", "future", List.of("5"), List.of("7"), null, null,
                Instant.parse("2026-12-25T09:00:00Z"));

        operations.createOrUpdatePost(creds(), scheduled, "42");

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).exec(any(SshConnectionParams.class), commandCaptor.capture(), notNull());
        assertEquals(true, commandCaptor.getValue().contains("--post_date='2026-12-25 09:00:00'"));
        assertEquals(true, commandCaptor.getValue().contains("--post_date_gmt='2026-12-25 09:00:00'"));
    }

    @Test
    void createOrUpdatePost_existingPostIdがWordPress側に無ければ新規作成へフォールバックする() {
        // 1回目のisNull()呼び出し = 実在確認(post get)を失敗させ、投稿が消えている状況を再現する。
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(fail("Error: Invalid post ID."))
                .thenReturn(ok("{\"guid\":\"https://example.com/?p=99\",\"post_status\":\"publish\"}"));
        when(executor.exec(any(SshConnectionParams.class), any(), notNull())).thenReturn(ok("99\n"));

        PostResult result = operations.createOrUpdatePost(creds(), postContent(), "38");

        assertEquals("99", result.id());
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).exec(any(SshConnectionParams.class), commandCaptor.capture(), notNull());
        assertEquals(true, commandCaptor.getValue().contains("post create -"));
        assertEquals(false, commandCaptor.getValue().contains("post update"));
    }

    @Test
    void createOrUpdatePost_実在確認が投稿不在と断定できない理由で失敗しても更新コマンドを実行する() {
        // issue #529: 一時的なSSH/wp-cliの不調など「投稿が実在しない」と断定できない理由で
        // 実在確認(post get)が失敗した場合にfalse(実在しない)扱いにすると、本来更新すべき
        // 投稿が新規作成され、同じスラッグの記事が再投稿のたびに重複投稿されてしまう。
        // 実在しないと断定できない失敗は安全側(実在する)とみなし、post updateを実行すべき。
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(fail("Error: The site is currently being updated."))
                .thenReturn(ok("{\"guid\":\"https://example.com/?p=42\",\"post_status\":\"draft\"}"));
        when(executor.exec(any(SshConnectionParams.class), any(), notNull())).thenReturn(ok(""));

        PostResult result = operations.createOrUpdatePost(creds(), postContent(), "42");

        assertEquals("42", result.id());
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).exec(any(SshConnectionParams.class), commandCaptor.capture(), notNull());
        assertEquals(true, commandCaptor.getValue().contains("post update '42' -"));
    }

    @Test
    void postExists_無効な投稿IDの日本語エラーは実在しないと判定する() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(fail("Warning: 無効な投稿 ID です。"));

        assertEquals(false, operations.postExists(creds(), "38"));
    }

    @Test
    void createOrUpdatePost_テーマのPHP警告に隠れたwp_cliのエラーを抽出して例外メッセージにする() {
        String themeNoise = """
                Warning: Trying to access array offset on null in /home4/x/public_html/wp-content/themes/jinr/a.php on line 401
                [18-Aug-2026 21:41:28 UTC] PHP Warning:  Trying to access array offset on null in /home4/x/b.php on line 1190
                Warning: 無効な投稿 ID です。""";
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(ok("38"));
        when(executor.exec(any(SshConnectionParams.class), any(), notNull()))
                .thenReturn(new SshCommandResult(1, "", themeNoise, null));

        SshOperationException thrown = assertThrows(SshOperationException.class,
                () -> operations.createOrUpdatePost(creds(), postContent(), "38"));

        assertEquals(true, thrown.getMessage().contains("無効な投稿 ID です。"));
        assertEquals(false, thrown.getMessage().contains("box-design-setting"));
        assertEquals(false, thrown.getMessage().contains("on line 401"));
    }

    @Test
    void createOrUpdatePost_全てPHP警告なら情報を失わず先頭行を返す() {
        String onlyNoise = "Warning: Trying to access array offset on null in /home4/x/a.php on line 401\n"
                + "Warning: Trying to access array offset on null in /home4/x/a.php on line 406";
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(ok("38"));
        when(executor.exec(any(SshConnectionParams.class), any(), notNull()))
                .thenReturn(new SshCommandResult(1, "", onlyNoise, null));

        SshOperationException thrown = assertThrows(SshOperationException.class,
                () -> operations.createOrUpdatePost(creds(), postContent(), "38"));

        assertEquals(true, thrown.getMessage().contains("on line 401"));
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

    /**
     * issue #1411 で存在確認が1回増えたため、`post delete` は2本目のコマンドになった。
     * 検証内容(`--force`/`--yes` を付けない)は変えていない。IDは同Issueで
     * ShellQuote を通すようにしたので `'99'` を見る。
     */
    @Test
    void deletePost_forceなしでpost_deleteを実行する() {
        when(executor.exec(any(SshConnectionParams.class), contains("post get"), isNull()))
                .thenReturn(ok("publish\n"));
        when(executor.exec(any(SshConnectionParams.class), contains("post delete"), isNull()))
                .thenReturn(ok(""));

        operations.deletePost(creds(), "99");

        // 1本目は存在確認、2本目が削除(issue #1411)。検証対象は2本目。
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        String deleteCommand = commandCaptor.getAllValues().get(1);
        assertEquals(true, deleteCommand.contains("post delete '99'"));
        assertEquals(false, deleteCommand.contains("--force"));
        assertEquals(false, deleteCommand.contains("--yes"));
    }

    @Test
    void deletePost_失敗したら例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(fail("post not found"));

        assertThrows(SshOperationException.class, () -> operations.deletePost(creds(), "99"));
    }

    /** issue #1411 で存在確認が増えた分の追従。検証内容は変えていない。 */
    @Test
    void deleteMedia_forceありpost_deleteを実行する() {
        when(executor.exec(any(SshConnectionParams.class), contains("post get"), isNull()))
                .thenReturn(ok("123\n"));
        when(executor.exec(any(SshConnectionParams.class), contains("post delete"), isNull()))
                .thenReturn(ok(""));

        operations.deleteMedia(creds(), "123");

        // 1本目は存在確認、2本目が削除(issue #1411)。検証対象は2本目。
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        String deleteCommand = commandCaptor.getAllValues().get(1);
        assertEquals(true, deleteCommand.contains("post delete '123'"));
        assertEquals(true, deleteCommand.contains("--force"));
        assertEquals(false, deleteCommand.contains("--yes"));
    }

    /**
     * issue #1411: SSH transport の削除を agent transport(#1070 / #1326 適用後)に揃える。
     *
     * <p>agent側の {@code /wp-cli/post-delete} は削除前に
     * {@code wp post get --field=post_status} で存在を確かめ、対象なしと
     * **ゴミ箱(trash)** をどちらも404で返す。SSH側にはその確認が一切無く、
     *
     * <ul>
     *   <li>存在しないIDの削除が一律502({@link SshOperationException})になり、
     *       呼び出し側の入力ミスとインフラ障害が区別できない(#1070相当)</li>
     *   <li>`--force`なしの1回目でゴミ箱へ移動した投稿に削除要求が再送されると、
     *       WordPressコアが恒久削除として扱うため**記事が復旧不能に失われる**(#1326相当)</li>
     * </ul>
     *
     * が残っていた。SSH管理サイトは実運用の顧客サイトである。
     */
    @Test
    void deletePost_存在しないIDは502ではなく404として区別する() {
        when(executor.exec(any(SshConnectionParams.class), contains("post get"), isNull()))
                .thenReturn(fail("Error: Could not find the post with ID 99."));

        assertThrows(PostNotFoundException.class, () -> operations.deletePost(creds(), "99"));

        verify(executor, never()).exec(any(SshConnectionParams.class), contains("post delete"), isNull());
    }

    @Test
    void deletePost_ゴミ箱の投稿への再削除は404にし恒久削除しない() {
        when(executor.exec(any(SshConnectionParams.class), contains("post get"), isNull()))
                .thenReturn(ok("trash\n"));

        assertThrows(PostNotFoundException.class, () -> operations.deletePost(creds(), "99"));

        // `wp post delete` を一度も実行しないことが、記事がゴミ箱に残る根拠である。
        verify(executor, never()).exec(any(SshConnectionParams.class), contains("post delete"), isNull());
    }

    /**
     * issue #529 の教訓を削除経路にも効かせる。同ファイルの {@code postExists} は
     * 「実在しないと断定できない失敗」を実在扱い(安全側)にしている。削除でも同じで、
     * 一時的なSSH/wp-cliの不調を404(=対象なし)に化けさせてはならない。
     * 404は「消すものが無い」という確定的な答えであり、呼び出し側はそれ以上追わない。
     */
    @Test
    void deletePost_実在しないと断定できない失敗は404ではなく502のままにする() {
        when(executor.exec(any(SshConnectionParams.class), contains("post get"), isNull()))
                .thenReturn(fail("ssh: connect to host example.com port 22: Connection timed out"));

        assertThrows(SshOperationException.class, () -> operations.deletePost(creds(), "99"));

        verify(executor, never()).exec(any(SshConnectionParams.class), contains("post delete"), isNull());
    }

    @Test
    void deletePost_存在確認のIDはシェルクォートを通す() {
        when(executor.exec(any(SshConnectionParams.class), contains("post get"), isNull()))
                .thenReturn(ok("publish\n"));
        when(executor.exec(any(SshConnectionParams.class), contains("post delete"), isNull()))
                .thenReturn(ok(""));

        operations.deletePost(creds(), "99");

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        for (String command : commandCaptor.getAllValues()) {
            assertEquals(true, command.contains("'99'"),
                    "動的な値はShellQuoteを通すこと(ShellQuoteのjavadoc、issue #1416): " + command);
        }
    }

    @Test
    void deleteMedia_存在しないIDは502ではなく404として区別する() {
        when(executor.exec(any(SshConnectionParams.class), contains("post get"), isNull()))
                .thenReturn(fail("Error: Could not find the post with ID 123."));

        assertThrows(PostNotFoundException.class, () -> operations.deleteMedia(creds(), "123"));

        verify(executor, never()).exec(any(SshConnectionParams.class), contains("post delete"), isNull());
    }

    @Test
    void deleteMedia_失敗したら例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(fail("delete failed"));

        assertThrows(SshOperationException.class, () -> operations.deleteMedia(creds(), "123"));
    }

    /**
     * issue #1416: 動的な値は必ず {@code ShellQuote} を通す。
     *
     * <p>{@link ShellQuote} の javadoc がこのファイル群の規約を定めている:
     * 「SSHのexecはリモートシェルが解釈する1本の文字列を送るだけなので、コマンド文字列へ
     * 埋め込む動的な値は**必ずこれを通す(コマンドインジェクション対策)**」。
     * 45箇所で守られている一方、いくつかの箇所が素通しになっていた。
     *
     * <p>{@code wpPostId} は {@code DELETE /api/posts/{site}/{wpPostId}} の
     * {@code @PathVariable String} で、経路上どこでも数値検証されずに
     * {@code WordPressSshOperations} まで届く。**SSH管理サイトは実運用の顧客サーバーである。**
     *
     * <p>ここではメタ文字を含むIDを渡し、組み立てられたコマンド文字列の中で
     * **メタ文字がクォートの外に出ていない**ことを確かめる。実際にリモートで実行はしない
     * (コマンド文字列がどうなるかが、リモートシェルが何を解釈するかを決める)。
     */
    private static final String METACHARACTER_ID = "1; touch /tmp/pwned";

    private static void assertNoUnquotedMetacharacters(String command) {
        // ShellQuote.single は値全体を ' で囲む。メタ文字がその中にあれば、
        // 直前に必ず ' が来る形(= "'1; touch /tmp/pwned'")になる。
        assertEquals(true, command.contains("'" + METACHARACTER_ID + "'"),
                "動的な値がShellQuoteを通っていない(ShellQuoteのjavadoc、issue #1416): " + command);
    }

    @Test
    void deletePost_メタ文字を含むIDでもクォートの外に出さない() {
        when(executor.exec(any(SshConnectionParams.class), contains("post get"), isNull()))
                .thenReturn(ok("publish\n"));
        when(executor.exec(any(SshConnectionParams.class), contains("post delete"), isNull()))
                .thenReturn(ok(""));

        operations.deletePost(creds(), METACHARACTER_ID);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), captor.capture(), isNull());
        captor.getAllValues().forEach(WordPressSshOperationsTest::assertNoUnquotedMetacharacters);
    }

    @Test
    void deleteMedia_メタ文字を含むIDでもクォートの外に出さない() {
        when(executor.exec(any(SshConnectionParams.class), contains("post get"), isNull()))
                .thenReturn(ok("1\n"));
        when(executor.exec(any(SshConnectionParams.class), contains("post delete"), isNull()))
                .thenReturn(ok(""));

        operations.deleteMedia(creds(), METACHARACTER_ID);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), captor.capture(), isNull());
        captor.getAllValues().forEach(WordPressSshOperationsTest::assertNoUnquotedMetacharacters);
    }

    @Test
    void postExists_メタ文字を含むIDでもクォートの外に出さない() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(ok("1\n"));

        operations.postExists(creds(), METACHARACTER_ID);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(executor).exec(any(SshConnectionParams.class), captor.capture(), isNull());
        assertNoUnquotedMetacharacters(captor.getValue());
    }

    /**
     * 更新経路。`existingPostId` は公開APIの `wpPostId` リクエストパラメータ由来で、
     * `post update <id>` に素通しで埋め込まれていた。
     */
    @Test
    void createOrUpdatePost_メタ文字を含む既存IDでもクォートの外に出さない() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("1\n"));
        when(executor.exec(any(SshConnectionParams.class), any(), notNull()))
                .thenReturn(ok("1\n"));

        try {
            operations.createOrUpdatePost(creds(), postContent(), METACHARACTER_ID);
        } catch (RuntimeException ignored) {
            // 後続のJSON解析まで通す必要は無い。組み立てたコマンド文字列だけを見る。
        }

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(executor, atLeastOnce()).exec(any(SshConnectionParams.class), captor.capture(), any());
        String update = captor.getAllValues().stream()
                .filter(c -> c.contains("post update"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "post update コマンドが組み立てられていない: " + captor.getAllValues()));
        assertNoUnquotedMetacharacters(update);
    }

    @Test
    void generateAuthCookie_wp_evalの結果からCookieを組み立てる() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("{\"name\":\"wordpress_logged_in_abc\",\"value\":\"admin|123|token|hash\"}"));

        com.letsblog.publishing.cms.AuthCookie cookie = operations.generateAuthCookie(credsWithUsername("admin"));

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
    void getLatestPost_wp_evalの結果から参照記事を組み立てる() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("{\"found\":true,\"id\":\"1\",\"link\":\"https://example.com/hello-world/\","
                        + "\"title\":\"Hello World\",\"content\":\"<p>Hi</p>\"}"));

        com.letsblog.publishing.cms.ReferencePost referencePost = operations.getLatestPost(creds()).orElseThrow();

        assertEquals("1", referencePost.id());
        assertEquals("https://example.com/hello-world/", referencePost.link());
        assertEquals("Hello World", referencePost.title());
        assertEquals("<p>Hi</p>", referencePost.content());
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getValue().contains("eval"));
        assertEquals(true, commandCaptor.getValue().contains("get_posts"));
    }

    @Test
    void getLatestPost_公開記事が無い場合は空を返す() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("{\"found\":false}"));

        assertEquals(true, operations.getLatestPost(creds()).isEmpty());
    }

    @Test
    void getLatestPost_wp_eval失敗時は例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(fail("eval error"));

        assertThrows(SshOperationException.class, () -> operations.getLatestPost(creds()));
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
        assertEquals(true, commandCaptor.getAllValues().get(2).contains("post get '55'"));

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
        assertEquals(true, commandCaptor.getValue().contains("'theme' install"));
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
        assertEquals(true, commandCaptor.getValue().contains("'plugin' install"));
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
        assertEquals(true, commandCaptor.getValue().contains("'plugin' list --fields=name,status --format=json"));
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
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("'plugin' install 'akismet'"));
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
        assertEquals(true, commandCaptor.getValue().contains("term list 'category'"));
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
        assertEquals(true, createCommand.contains("term create 'category'"));
        assertEquals(true, createCommand.contains("--parent='1'"));
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
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("term update 'post_tag' '1'"));
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

    @Test
    void exportDatabase_成功時はテーブルプレフィックスを除外指定してdb_exportしダウンロード後に一時ファイルを削除する() {
        byte[] dump = "-- dump --".getBytes(StandardCharsets.UTF_8);
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("wp_"))
                .thenReturn(ok(""));
        when(executor.getFile(any(SshConnectionParams.class), any())).thenReturn(dump);

        WordPressSshOperations.DatabaseExport result = operations.exportDatabase(creds());

        assertEquals("wp_", result.tablePrefix());
        assertArrayEquals(dump, result.dump());

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("db export"));
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("--exclude_tables='wp_users,wp_usermeta'"));

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).getFile(any(SshConnectionParams.class), pathCaptor.capture());
        verify(executor).removeFile(any(SshConnectionParams.class), eq(pathCaptor.getValue()));
    }

    @Test
    void exportDatabase_プレフィックス取得に失敗した場合はwp_をデフォルトに使う() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(fail("config not found"))
                .thenReturn(ok(""));
        when(executor.getFile(any(SshConnectionParams.class), any())).thenReturn(new byte[0]);

        operations.exportDatabase(creds());

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("--exclude_tables='wp_users,wp_usermeta'"));
    }

    @Test
    void exportDatabase_エクスポート失敗時は例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok("wp_"))
                .thenReturn(fail("export failed"));

        assertThrows(SshOperationException.class, () -> operations.exportDatabase(creds()));
        verify(executor, never()).getFile(any(), any());
    }

    @Test
    void exportMedia_uploadsディレクトリが無ければ空バイト列を返しダウンロードしない() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(fail("not found"));

        byte[] result = operations.exportMedia(creds());

        assertArrayEquals(new byte[0], result);
        verify(executor, never()).getFile(any(), any());
        verify(executor, never()).removeFile(any(), any());
    }

    @Test
    void exportMedia_成功時はuploadsをtarで固めてダウンロード後に一時ファイルを削除する() {
        byte[] archive = "tar-gz-bytes".getBytes(StandardCharsets.UTF_8);
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok(""))
                .thenReturn(ok(""));
        when(executor.getFile(any(SshConnectionParams.class), any())).thenReturn(archive);

        byte[] result = operations.exportMedia(creds());

        assertArrayEquals(archive, result);

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("tar -czf"));
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("uploads"));

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).getFile(any(SshConnectionParams.class), pathCaptor.capture());
        verify(executor).removeFile(any(SshConnectionParams.class), eq(pathCaptor.getValue()));
    }

    @Test
    void exportMedia_tar失敗時は例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok(""))
                .thenReturn(fail("tar failed"));

        assertThrows(SshOperationException.class, () -> operations.exportMedia(creds()));
        verify(executor, never()).getFile(any(), any());
    }

    @Test
    void exportThemes_themesディレクトリが無ければ空バイト列を返しダウンロードしない() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(fail("not found"));

        byte[] result = operations.exportThemes(creds());

        assertArrayEquals(new byte[0], result);
        verify(executor, never()).getFile(any(), any());
        verify(executor, never()).removeFile(any(), any());
    }

    @Test
    void exportThemes_成功時はthemesをtarで固めてダウンロード後に一時ファイルを削除する() {
        byte[] archive = "themes-tar-gz-bytes".getBytes(StandardCharsets.UTF_8);
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok(""))
                .thenReturn(ok(""));
        when(executor.getFile(any(SshConnectionParams.class), any())).thenReturn(archive);

        byte[] result = operations.exportThemes(creds());

        assertArrayEquals(archive, result);

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor, times(2)).exec(any(SshConnectionParams.class), commandCaptor.capture(), isNull());
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("tar -czf"));
        assertEquals(true, commandCaptor.getAllValues().get(1).contains("themes"));

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        verify(executor).getFile(any(SshConnectionParams.class), pathCaptor.capture());
        verify(executor).removeFile(any(SshConnectionParams.class), eq(pathCaptor.getValue()));
    }

    @Test
    void exportThemes_tar失敗時は例外() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenReturn(ok(""))
                .thenReturn(fail("tar failed"));

        assertThrows(SshOperationException.class, () -> operations.exportThemes(creds()));
        verify(executor, never()).getFile(any(), any());
    }
}
