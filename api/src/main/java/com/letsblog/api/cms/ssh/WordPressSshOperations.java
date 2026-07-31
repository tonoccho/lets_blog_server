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
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
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

    /**
     * REST版(WordPressAdapter.provisionAuthor)と同じくメールアドレスで既存ユーザーを探し、
     * 存在すればプロフィール更新、なければ新規作成する。作成コマンドが失敗した場合、
     * 既に他のプロセスが同時作成した可能性を考慮して再検索しフォールバックする。
     */
    public String provisionAuthor(WordPressCredentials creds, AuthorProvisioningRequest request) {
        String email = request.email();
        String existingId = findExistingAuthorId(creds, email);
        if (existingId != null) {
            return updateAuthor(creds, existingId, request);
        }

        String username = email.substring(0, email.indexOf('@'));
        SshCommandResult createResult = exec(creds, wpCli(creds,
                "user create " + ShellQuote.single(username) + " " + ShellQuote.single(email)
                        + " --role=" + ShellQuote.single(resolveRole(request))
                        + " --user_pass=" + ShellQuote.single(generateRandomPassword())
                        + " --porcelain"));
        if (!createResult.ok()) {
            String fallbackId = findExistingAuthorId(creds, email);
            if (fallbackId != null) {
                return updateAuthor(creds, fallbackId, request);
            }
            throw new SshOperationException(authorErrorMessage("作成", createResult));
        }
        return updateAuthor(creds, createResult.stdout().strip(), request);
    }

    private String findExistingAuthorId(WordPressCredentials creds, String email) {
        SshCommandResult result = exec(creds, wpCli(creds,
                "user list --search=" + ShellQuote.single(email) + " --fields=ID --format=json"));
        if (!result.ok()) {
            return null;
        }
        List<JsonNode> users = parseJsonArray(result.stdout());
        return users.isEmpty() ? null : users.get(0).path("ID").asText();
    }

    private String updateAuthor(WordPressCredentials creds, String userId, AuthorProvisioningRequest request) {
        StringBuilder command = new StringBuilder("user update ").append(userId);
        appendFieldIfPresent(command, "user_email", request.email());
        appendFieldIfPresent(command, "display_name", request.displayName());
        appendFieldIfPresent(command, "first_name", request.firstName());
        appendFieldIfPresent(command, "last_name", request.lastName());
        appendFieldIfPresent(command, "user_url", request.websiteUrl());
        appendFieldIfPresent(command, "description", request.bio());
        // localeはREST版と同じ理由(未インストール言語だと失敗しうる)で送信しない
        command.append(" --role=").append(ShellQuote.single(resolveRole(request)));

        SshCommandResult result = exec(creds, wpCli(creds, command.toString()));
        if (!result.ok()) {
            throw new SshOperationException(authorErrorMessage("更新", result));
        }
        return userId;
    }

    private void appendFieldIfPresent(StringBuilder command, String field, String value) {
        if (value != null) {
            command.append(" --").append(field).append("=").append(ShellQuote.single(value));
        }
    }

    private String resolveRole(AuthorProvisioningRequest request) {
        return request.wpRole() != null ? request.wpRole() : "author";
    }

    private String authorErrorMessage(String action, SshCommandResult result) {
        return "WordPress著者の" + action + "に失敗しました: " + firstLine(result.stderr(), result.stdout());
    }

    private String generateRandomPassword() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * `wp post create`/`wp post update`はporcelain出力でID以外の情報を返さないため、
     * 作成/更新後に`wp post get`で改めてlink(guid)・statusを取得してPostResultを組み立てる。
     * 本文(htmlContent)は`-`(標準入力読み込み)経由で渡す(シェル引数に展開すると
     * 特殊文字・サイズ上限の問題があるため)。
     */
    public PostResult createOrUpdatePost(WordPressCredentials creds, PostContent content, String existingPostId) {
        byte[] stdin = (content.htmlContent() != null ? content.htmlContent() : "").getBytes(StandardCharsets.UTF_8);
        String fields = postFieldsArgs(content);

        String subcommand = existingPostId == null
                ? "post create - " + fields + " --porcelain"
                : "post update " + existingPostId + " - " + fields + " --porcelain";

        SshCommandResult result = exec(creds, wpCli(creds, subcommand), stdin);
        if (!result.ok()) {
            throw new SshOperationException("WordPress投稿の作成/更新に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
        String postId = existingPostId != null ? existingPostId : result.stdout().strip();
        return fetchPostResult(creds, postId);
    }

    private String postFieldsArgs(PostContent content) {
        StringBuilder args = new StringBuilder();
        args.append("--post_title=").append(ShellQuote.single(content.title()));
        args.append(" --post_status=").append(ShellQuote.single(content.status()));
        if (content.slug() != null && !content.slug().isBlank()) {
            args.append(" --post_name=").append(ShellQuote.single(content.slug()));
        }
        if (content.categoryIds() != null && !content.categoryIds().isEmpty()) {
            args.append(" --post_category=").append(ShellQuote.single(String.join(",", content.categoryIds())));
        }
        if (content.tagIds() != null && !content.tagIds().isEmpty()) {
            String tagIds = String.join(",", content.tagIds());
            args.append(" --tax_input=").append(ShellQuote.single("{\"post_tag\":[" + tagIds + "]}"));
        }
        return args.toString();
    }

    private PostResult fetchPostResult(WordPressCredentials creds, String postId) {
        SshCommandResult result = exec(creds, wpCli(creds,
                "post get " + postId + " --fields=guid,post_status --format=json"));
        if (!result.ok()) {
            throw new SshOperationException("作成/更新した投稿の情報取得に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
        JsonNode post = parseJsonObject(result.stdout());
        return new PostResult(postId, post.path("guid").asText(), post.path("post_status").asText());
    }

    private JsonNode parseJsonObject(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || node.isMissingNode()) {
                throw new SshOperationException("wp-cliの出力(JSON)が空です");
            }
            return node;
        } catch (IOException e) {
            throw new SshOperationException("wp-cliの出力(JSON)の解析に失敗しました: " + e.getMessage(), e);
        }
    }

    public MediaUploadResult uploadMedia(WordPressCredentials creds, String filename, String contentType,
            byte[] data) {
        throw new UnsupportedOperationException("SSH transport: uploadMedia is not yet implemented");
    }

    private SshCommandResult exec(WordPressCredentials creds, String command) {
        return executor.exec(connectionParams(creds), command, null);
    }

    private SshCommandResult exec(WordPressCredentials creds, String command, byte[] stdin) {
        return executor.exec(connectionParams(creds), command, stdin);
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
