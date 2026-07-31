package com.letsblog.api.cms.ssh;

import com.letsblog.api.cms.AuthorProvisioningRequest;
import com.letsblog.api.cms.CmsCredentials.WordPressCredentials;
import com.letsblog.api.cms.ConnectionCheckResult;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.cms.PostContent;
import com.letsblog.api.cms.PostResult;
import com.letsblog.api.cms.ssh.SshCommandExecutor.SshCommandResult;
import com.letsblog.api.cms.ssh.SshCommandExecutor.SshConnectionParams;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SSH+wp-cli経由でWordPressを操作する実装。WordPressAdapterからtransport=SSHの
 * サイトについて委譲される。REST APIを使わないため、Cloudflare等のHTTPレベルの
 * ボット対策の影響を受けない。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WordPressSshOperations {

    private final SshCommandExecutor executor;

    public ConnectionCheckResult testConnection(WordPressCredentials creds) {
        try {
            SshCommandResult result = exec(creds, wpCli(creds, "option get siteurl --format=json"));
            if (result.ok()) {
                return ConnectionCheckResult.success(result.observedHostKeyFingerprint());
            }
            return ConnectionCheckResult.failure(firstLine(result.stderr(), result.stdout()));
        } catch (SshOperationException e) {
            log.warn("WordPress(SSH)疎通確認に失敗しました (sshHost={}, sshUser={}, wpPath={}): {}",
                    creds.sshHost(), creds.sshUser(), creds.wpPath(), e.getMessage());
            return ConnectionCheckResult.failure(e.getMessage());
        }
    }

    /**
     * SSH経由のwp-cli実行はOSユーザーとしてシェルに直接アクセスできることを意味し、
     * WordPress REST APIのロール権限(create_users capability)という概念が存在しない。
     * そのため、疎通確認が成功する = 著者作成等の管理操作も行えるとみなす
     * (ProjectUserSyncServiceがこの戻り値をハードゲートとして使うため、
     * SSH接続できるサイトを誤ってブロックしないようにする)。
     */
    public boolean hasAuthorProvisioningCapability(WordPressCredentials creds) {
        return testConnection(creds).ok();
    }

    public List<String> resolveCategories(WordPressCredentials creds, List<String> names) {
        throw new UnsupportedOperationException("SSH transport: resolveCategories is not yet implemented");
    }

    public List<String> resolveTags(WordPressCredentials creds, List<String> names) {
        throw new UnsupportedOperationException("SSH transport: resolveTags is not yet implemented");
    }

    public String provisionAuthor(WordPressCredentials creds, AuthorProvisioningRequest request) {
        throw new UnsupportedOperationException("SSH transport: provisionAuthor is not yet implemented");
    }

    public PostResult createOrUpdatePost(WordPressCredentials creds, PostContent content, String existingPostId) {
        throw new UnsupportedOperationException("SSH transport: createOrUpdatePost is not yet implemented");
    }

    public MediaUploadResult uploadMedia(WordPressCredentials creds, String filename, String contentType,
            byte[] data) {
        throw new UnsupportedOperationException("SSH transport: uploadMedia is not yet implemented");
    }

    private SshCommandResult exec(WordPressCredentials creds, String command) {
        return executor.exec(connectionParams(creds), command, null);
    }

    private SshConnectionParams connectionParams(WordPressCredentials creds) {
        int port = creds.sshPort() != null ? creds.sshPort() : 22;
        return new SshConnectionParams(creds.sshHost(), port, creds.sshUser(), creds.sshPrivateKeyPem(),
                creds.sshHostKeyFingerprint());
    }

    private String wpCli(WordPressCredentials creds, String subcommand) {
        return "wp --path=" + ShellQuote.single(creds.wpPath()) + " " + subcommand;
    }

    private String firstLine(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                int newline = candidate.indexOf('\n');
                return (newline >= 0 ? candidate.substring(0, newline) : candidate).strip();
            }
        }
        return "SSH経由のwp-cli実行に失敗しました";
    }
}
