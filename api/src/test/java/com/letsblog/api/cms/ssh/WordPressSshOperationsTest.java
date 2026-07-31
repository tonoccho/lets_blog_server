package com.letsblog.api.cms.ssh;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.cms.AuthorProvisioningRequest;
import com.letsblog.api.cms.CmsCredentials.WordPressCredentials;
import com.letsblog.api.cms.ConnectionCheckResult;
import com.letsblog.api.cms.PostContent;
import com.letsblog.api.cms.PostResult;
import com.letsblog.api.cms.ssh.SshCommandExecutor.SshCommandResult;
import com.letsblog.api.cms.ssh.SshCommandExecutor.SshConnectionParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
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
        return new WordPressCredentials(
                "https://example.com", null, null,
                "SSH", "203.0.113.5", 22, "deploy", "/var/www/html", "PRIVATE-KEY-PEM", "SHA256:pinned");
    }

    private SshCommandResult ok(String stdout) {
        return new SshCommandResult(0, stdout, "", "SHA256:observed");
    }

    private SshCommandResult fail(String stderr) {
        return new SshCommandResult(1, "", stderr, null);
    }

    @Test
    void testConnection_成功時にfingerprintを伝播する() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(ok("{\"siteurl\":\"https://example.com\"}"));

        ConnectionCheckResult result = operations.testConnection(creds());

        assertEquals(true, result.ok());
        assertEquals("SHA256:observed", result.observedHostKeyFingerprint());
    }

    @Test
    void testConnection_失敗時は理由を返す() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull())).thenReturn(fail("wp-cli: command not found"));

        ConnectionCheckResult result = operations.testConnection(creds());

        assertEquals(false, result.ok());
        assertEquals("wp-cli: command not found", result.failureReason());
    }

    @Test
    void testConnection_SSH接続例外時は失敗として返す() {
        when(executor.exec(any(SshConnectionParams.class), any(), isNull()))
                .thenThrow(new SshOperationException("接続がタイムアウトしました"));

        ConnectionCheckResult result = operations.testConnection(creds());

        assertEquals(false, result.ok());
        assertEquals("接続がタイムアウトしました", result.failureReason());
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
        return new PostContent("Title", "my-slug", "<p>Hello</p>", "publish", List.of("5"), List.of("7"));
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
}
