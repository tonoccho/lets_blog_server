package com.letsblog.api.cms.ssh;

import com.letsblog.api.cms.AuthorProvisioningRequest;
import com.letsblog.api.cms.CmsCredentials.WordPressCredentials;
import com.letsblog.api.cms.ConnectionCheckResult;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.cms.PostContent;
import com.letsblog.api.cms.PostResult;
import com.letsblog.api.cms.ssh.SshCommandExecutor.SshCommandResult;
import com.letsblog.api.cms.ssh.SshCommandExecutor.SshConnectionParams;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
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
    private final ObjectMapper objectMapper;

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
        return resolveTerms(creds, "category", names);
    }

    public List<String> resolveTags(WordPressCredentials creds, List<String> names) {
        return resolveTerms(creds, "post_tag", names);
    }

    /**
     * REST版(WordPressAdapter.resolveTerms)と同じく、名前の完全一致(大文字小文字無視)で
     * 既存タームを探し、なければ作成する。
     */
    private List<String> resolveTerms(WordPressCredentials creds, String taxonomy, List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (String name : names) {
            ids.add(findOrCreateTerm(creds, taxonomy, name));
        }
        return ids;
    }

    private String findOrCreateTerm(WordPressCredentials creds, String taxonomy, String name) {
        SshCommandResult searchResult = exec(creds, wpCli(creds,
                "term list " + taxonomy + " --search=" + ShellQuote.single(name)
                        + " --fields=name,term_id --format=json"));
        if (!searchResult.ok()) {
            throw new SshOperationException("カテゴリ/タグ '" + name + "' の検索に失敗しました: "
                    + firstLine(searchResult.stderr(), searchResult.stdout()));
        }
        for (JsonNode term : parseJsonArray(searchResult.stdout())) {
            if (term.path("name").asText().equalsIgnoreCase(name)) {
                return term.path("term_id").asText();
            }
        }

        SshCommandResult createResult = exec(creds, wpCli(creds,
                "term create " + taxonomy + " " + ShellQuote.single(name) + " --porcelain"));
        if (!createResult.ok()) {
            throw new SshOperationException("カテゴリ/タグ '" + name + "' の作成に失敗しました: "
                    + firstLine(createResult.stderr(), createResult.stdout()));
        }
        return createResult.stdout().strip();
    }

    private List<JsonNode> parseJsonArray(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            List<JsonNode> result = new ArrayList<>();
            if (node != null && node.isArray()) {
                node.forEach(result::add);
            }
            return result;
        } catch (IOException e) {
            throw new SshOperationException("wp-cliの出力(JSON)の解析に失敗しました: " + e.getMessage(), e);
        }
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
