package com.letsblog.publishing.cms.ssh;

import com.letsblog.publishing.cms.agent.PostNotFoundException;
import com.letsblog.publishing.cms.AuthCookie;
import com.letsblog.publishing.cms.AuthorProvisioningRequest;
import com.letsblog.publishing.cms.WpCliInstallResult;
import com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials;
import com.letsblog.publishing.cms.CmsMediaReferenceScan;
import com.letsblog.publishing.cms.CmsMediaSummary;
import com.letsblog.publishing.cms.CmsPostContentSummary;
import com.letsblog.publishing.cms.CmsPostSummary;
import com.letsblog.publishing.cms.ConnectionCheckResult;
import com.letsblog.publishing.cms.MediaContentHash;
import com.letsblog.publishing.cms.LetsblogPluginStatus;
import com.letsblog.publishing.cms.LetsblogPluginUnavailableException;
import com.letsblog.publishing.cms.LetsblogSnsCommand;
import com.letsblog.publishing.cms.SignedPreview;
import com.letsblog.publishing.cms.MediaUploadResult;
import com.letsblog.publishing.cms.PostContent;
import com.letsblog.publishing.cms.PostResult;
import com.letsblog.publishing.cms.ReferencePost;
import com.letsblog.publishing.cms.ssh.SshCommandExecutor.SshCommandResult;
import com.letsblog.publishing.cms.ssh.SshCommandExecutor.SshConnectionParams;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.common.util.StackTraceUtil;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

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

    /**
     * PHP本体が出力する診断行(テーマ/プラグインのWarning/Notice等)の判定パターン。
     * 「Warning: ... in /path/file.php on line 123」形式(先頭に「[日時] PHP 」が付く場合もある)に一致する。
     * wp-cli自身のエラー(例:「Warning: 無効な投稿 ID です。」)はこの形式を取らないため一致しない。
     */
    private static final Pattern PHP_DIAGNOSTIC_LINE = Pattern.compile(
            "^(?:\\[[^\\]]*\\]\\s*)?(?:PHP\\s+)?(?:Warning|Notice|Deprecated|Strict Standards):.* in .+ on line \\d+");

    /** provision-agent(managed)側のletsblog-allow-svg-upload.phpと同内容(issue #489)。 */
    private static final String SVG_UPLOAD_MU_PLUGIN = """
            <?php
            add_filter('upload_mimes', function ($mimes) {
                $mimes['svg'] = 'image/svg+xml';
                return $mimes;
            });
            add_filter('wp_check_filetype_and_ext', function ($data, $file, $filename, $mimes) {
                if (empty($data['type'])) {
                    $check = wp_check_filetype($filename, $mimes);
                    $data['ext'] = $check['ext'];
                    $data['type'] = $check['type'];
                }
                return $data;
            }, 10, 4);
            """;

    /**
     * 疎通確認を「1. SSH接続」「2. wp core versionの実行」の2段階で行い、
     * どちらの段階で失敗したかをfailureReasonに含めて返す。
     */
    public ConnectionCheckResult testConnection(WordPressCredentials creds) {
        SshCommandResult connectResult;
        try {
            connectResult = exec(creds, "echo ok");
        } catch (SshOperationException e) {
            log.warn("SSH接続に失敗しました (sshHost={}, sshUser={}): {}",
                    creds.sshHost(), creds.sshUser(), e.getMessage());
            return ConnectionCheckResult.failure("SSH接続に失敗しました: " + e.getMessage());
        }
        if (!connectResult.ok()) {
            return ConnectionCheckResult.failure("SSH接続に失敗しました: "
                    + firstLine(connectResult.stderr(), connectResult.stdout()));
        }

        try {
            SshCommandResult versionResult = exec(creds, wpCli(creds, "core version"));
            if (!versionResult.ok()) {
                return ConnectionCheckResult.failure("wp core versionの実行に失敗しました: "
                        + firstLine(versionResult.stderr(), versionResult.stdout()));
            }
            return ConnectionCheckResult.success(connectResult.observedHostKeyFingerprint(),
                    "wp core version: " + versionResult.stdout().strip());
        } catch (SshOperationException e) {
            log.warn("wp core versionの実行に失敗しました (sshHost={}, sshUser={}, wpPath={}): {}",
                    creds.sshHost(), creds.sshUser(), creds.wpPath(), e.getMessage());
            return ConnectionCheckResult.failure("wp core versionの実行に失敗しました: " + e.getMessage());
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
                "term list " + ShellQuote.single(taxonomy) + " --search=" + ShellQuote.single(name)
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
                "term create " + ShellQuote.single(taxonomy) + " " + ShellQuote.single(name) + " --porcelain"));
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

    /**
     * `command -v wp`でインストール済みかを確認し、未インストールならwp-cli公式pharを
     * `$HOME/bin/wp`へ配置する(sudoは使わない。パスワード入力待ちで非対話実行がハングする
     * リスクを避けるため)。`/usr/local/bin`等の共有PATHへの配置はSSHユーザーの権限に依存するため、
     * このメソッドでは行わない。そのため`$HOME/bin`がリモート側のPATHに含まれていない場合、
     * 以後のwp-cli呼び出し(投稿作成等、本クラスの他メソッド)はこのインストールだけでは
     * 解決しないことがあり、その旨を結果メッセージに含める。
     */
    public WpCliInstallResult installWpCli(WordPressCredentials creds) {
        SshCommandResult check = exec(creds, "command -v wp");
        if (check.ok() && !check.stdout().isBlank()) {
            throw new IllegalStateException("wp-cliは既にインストールされています(場所: "
                    + check.stdout().strip() + ")");
        }

        String installScript = "mkdir -p \"$HOME/bin\" && "
                + "curl -fsSL -o \"$HOME/bin/wp\" "
                + "https://raw.githubusercontent.com/wp-cli/builds/gh-pages/phar/wp-cli.phar && "
                + "chmod +x \"$HOME/bin/wp\" && "
                + "\"$HOME/bin/wp\" --version";
        SshCommandResult result = exec(creds, installScript);
        if (!result.ok()) {
            throw new SshOperationException("wp-cliのインストールに失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
        return new WpCliInstallResult(
                "wp-cliを $HOME/bin/wp にインストールしました(" + firstLine(result.stdout()) + ")。"
                        + "リモートサーバーの $HOME/bin がPATHに含まれていない場合、"
                        + "投稿作成等の他のwp-cli操作が引き続き失敗することがあります。"
                        + "その場合はリモート側でPATHに追加してください。");
    }

    /**
     * インストール済みプラグイン/テーマの一覧(name+status)を取得する
     * (比較テーブルに使用。managed環境向けのWordPressBulkManagementClient#listPlugins/listThemesのSSH版)。
     */
    public List<PluginThemeInfo> listPlugins(WordPressCredentials creds) {
        return listPluginsOrThemes(creds, "plugin");
    }

    public List<PluginThemeInfo> listThemes(WordPressCredentials creds) {
        return listPluginsOrThemes(creds, "theme");
    }

    private List<PluginThemeInfo> listPluginsOrThemes(WordPressCredentials creds, String type) {
        SshCommandResult result = exec(creds, wpCli(creds, pluginThemeListCommand(type)));
        return parsePluginThemeList(result, type.equals("plugin") ? "プラグイン" : "テーマ");
    }

    private String pluginThemeListCommand(String type) {
        return ShellQuote.single(type) + " list --fields=name,status --format=json";
    }

    private List<PluginThemeInfo> parsePluginThemeList(SshCommandResult result, String label) {
        if (!result.ok()) {
            throw new SshOperationException(label + "一覧の取得に失敗しました: " + firstLine(result.stderr(), result.stdout()));
        }
        List<JsonNode> items = parseJsonArray(result.stdout());
        return items.stream()
                .map(node -> new PluginThemeInfo(node.path("name").asText(), node.path("status").asText()))
                .toList();
    }

    /**
     * プラグイン/テーマのインストール・有効化・無効化・削除をwp-cli経由で行う。
     * provision-agent(infra/wordpress/provision-agent/index.php)の/bulk-managementハンドラと同じ挙動
     * (インストール済みならskipped、失敗時は例外を投げずfailed()を返す)に揃える。
     */
    public SshApplyResult applyPluginTheme(WordPressCredentials creds, BulkOperationType type, String slug) {
        try {
            return switch (type) {
                case PLUGIN_INSTALL -> installIfMissing(creds, "plugin", slug);
                case THEME_INSTALL -> installIfMissing(creds, "theme", slug);
                case PLUGIN_ACTIVATE -> runWpCli(creds, "plugin activate " + ShellQuote.single(slug), "プラグインの有効化");
                case PLUGIN_DEACTIVATE ->
                        runWpCli(creds, "plugin deactivate " + ShellQuote.single(slug), "プラグインの無効化");
                case PLUGIN_DELETE -> {
                    // 有効化されている場合に備え先に無効化を試みる(未有効化時のエラーは無視してよい)
                    exec(creds, wpCli(creds, "plugin deactivate " + ShellQuote.single(slug)));
                    yield runWpCli(creds, "plugin delete " + ShellQuote.single(slug), "プラグインの削除");
                }
                case THEME_ACTIVATE -> runWpCli(creds, "theme activate " + ShellQuote.single(slug), "テーマの有効化");
                case THEME_DELETE -> runWpCli(creds, "theme delete " + ShellQuote.single(slug), "テーマの削除");
                default -> throw new IllegalArgumentException("SSH経由ではサポートされていない操作です: " + type);
            };
        } catch (SshOperationException e) {
            return SshApplyResult.failed(e);
        }
    }

    /**
     * zipアップロードによるプラグイン/テーマインストール(managed環境向けの
     * WordPressBulkManagementClient#applyZipのSSH版)。SFTPでリモートの一時パスへ転送してから
     * `wp plugin/theme install <path> --force`を実行する(uploadMediaと同じ転送パターン)。
     * provision-agentと同じく、zipの中身(実際のslug)は展開するまで確定しないため事前の
     * 存在チェックは行わず、常に--forceで上書きインストールする。
     */
    public SshApplyResult applyZip(WordPressCredentials creds, BulkOperationType type, byte[] zipBytes, String filename) {
        SshConnectionParams params = connectionParams(creds);
        String remotePath = "/tmp/letsblog-bulk-" + UUID.randomUUID() + "-" + sanitizeFilename(filename);
        String wpType = type == BulkOperationType.PLUGIN_INSTALL ? "plugin" : "theme";
        try {
            executor.putFile(params, zipBytes, remotePath);
        } catch (SshOperationException e) {
            return SshApplyResult.failed(e);
        }
        try {
            return runWpCli(creds, ShellQuote.single(wpType) + " install " + ShellQuote.single(remotePath) + " --force",
                    (wpType.equals("plugin") ? "プラグイン" : "テーマ") + "のインストール");
        } catch (SshOperationException e) {
            return SshApplyResult.failed(e);
        } finally {
            executor.removeFile(params, remotePath);
        }
    }

    private SshApplyResult installIfMissing(WordPressCredentials creds, String type, String slug) {
        List<PluginThemeInfo> installed = listPluginsOrThemes(creds, type);
        if (installed.stream().anyMatch(info -> info.name().equals(slug))) {
            return SshApplyResult.skipped();
        }
        return runWpCli(creds, ShellQuote.single(type) + " install " + ShellQuote.single(slug),
                (type.equals("plugin") ? "プラグイン" : "テーマ") + "のインストール");
    }

    private SshApplyResult runWpCli(WordPressCredentials creds, String subcommand, String actionLabel) {
        SshCommandResult result = exec(creds, wpCli(creds, subcommand));
        if (!result.ok()) {
            throw new SshOperationException(actionLabel + "に失敗しました: " + firstLine(result.stderr(), result.stdout()));
        }
        return SshApplyResult.success();
    }

    /**
     * カテゴリ/タグ一覧を取得する(比較テーブルに使用。managed環境向けの
     * WordPressBulkManagementClient#listCategories/listTagsのSSH版)。parent(term_id)は
     * 同一取得結果内でslugへ解決する(provision-agentのfetchTerms()と同じ方針)。
     */
    public List<CategoryInfo> listCategories(WordPressCredentials creds) {
        return listTerms(creds, "category");
    }

    public List<CategoryInfo> listTags(WordPressCredentials creds) {
        return listTerms(creds, "post_tag");
    }

    private List<CategoryInfo> listTerms(WordPressCredentials creds, String taxonomy) {
        SshCommandResult result = exec(creds, wpCli(creds, termListCommand(taxonomy)));
        return parseTerms(result, taxonomy.equals("category") ? "カテゴリ" : "タグ");
    }

    private String termListCommand(String taxonomy) {
        return "term list " + ShellQuote.single(taxonomy)
                + " --fields=term_id,name,slug,parent,description --format=json";
    }

    private List<CategoryInfo> parseTerms(SshCommandResult result, String label) {
        if (!result.ok()) {
            throw new SshOperationException(label + "一覧の取得に失敗しました: " + firstLine(result.stderr(), result.stdout()));
        }
        List<JsonNode> terms = parseJsonArray(result.stdout());
        Map<String, String> slugByTermId = new HashMap<>();
        for (JsonNode term : terms) {
            slugByTermId.put(term.path("term_id").asText(), term.path("slug").asText());
        }
        return terms.stream()
                .map(term -> {
                    String parentId = term.path("parent").asText("0");
                    String parentSlug = !"0".equals(parentId) ? slugByTermId.get(parentId) : null;
                    return new CategoryInfo(term.path("term_id").asText(), term.path("name").asText(),
                            term.path("slug").asText(), parentSlug, term.path("description").asText());
                })
                .toList();
    }

    /**
     * 複数環境が同一SSHホストを使っている場合に、1回の接続(環境ごとに別セッション)で
     * まとめてカテゴリ/タグ一覧を取得する。credsByEnvironmentの値群はhost/port/user/鍵が
     * 同一であることを前提とする(呼び出し元がホスト単位でグルーピングして渡す。wpPathだけ
     * 環境ごとに異なってよい)。接続自体が失敗した場合は全環境に同じエラーを設定し、
     * 1コマンド分だけの失敗(その環境のwp-cli実行エラー)はその環境だけをエラーにする。
     */
    public EnvironmentFetchResult<CategoryInfo> fetchTermsForEnvironments(
            String taxonomy, Map<String, WordPressCredentials> credsByEnvironment) {
        String label = taxonomy.equals("category") ? "カテゴリ" : "タグ";
        return fetchForEnvironments(credsByEnvironment,
                creds -> termListCommand(taxonomy),
                result -> parseTerms(result, label));
    }

    /**
     * {@link #fetchTermsForEnvironments}のプラグイン/テーマ版。
     */
    public EnvironmentFetchResult<PluginThemeInfo> fetchPluginsOrThemesForEnvironments(
            String type, Map<String, WordPressCredentials> credsByEnvironment) {
        String label = type.equals("plugin") ? "プラグイン" : "テーマ";
        return fetchForEnvironments(credsByEnvironment,
                creds -> pluginThemeListCommand(type),
                result -> parsePluginThemeList(result, label));
    }

    private <T> EnvironmentFetchResult<T> fetchForEnvironments(
            Map<String, WordPressCredentials> credsByEnvironment,
            Function<WordPressCredentials, String> commandBuilder,
            Function<SshCommandResult, List<T>> parser) {
        List<String> environments = new ArrayList<>(credsByEnvironment.keySet());
        List<String> commands = environments.stream()
                .map(env -> wpCli(credsByEnvironment.get(env), commandBuilder.apply(credsByEnvironment.get(env))))
                .toList();

        List<SshCommandResult> results;
        try {
            results = executor.execAll(connectionParams(credsByEnvironment.get(environments.get(0))), commands);
        } catch (SshOperationException e) {
            Map<String, String> errors = new LinkedHashMap<>();
            Map<String, String> stackTraces = new LinkedHashMap<>();
            String stackTrace = StackTraceUtil.toString(e);
            environments.forEach(env -> {
                errors.put(env, e.getMessage());
                stackTraces.put(env, stackTrace);
            });
            return new EnvironmentFetchResult<>(Map.of(), errors, stackTraces);
        }

        Map<String, List<T>> byEnvironment = new LinkedHashMap<>();
        Map<String, String> errorByEnvironment = new LinkedHashMap<>();
        Map<String, String> stackTraceByEnvironment = new LinkedHashMap<>();
        for (int i = 0; i < environments.size(); i++) {
            String environment = environments.get(i);
            try {
                byEnvironment.put(environment, parser.apply(results.get(i)));
            } catch (SshOperationException e) {
                errorByEnvironment.put(environment, e.getMessage());
                stackTraceByEnvironment.put(environment, StackTraceUtil.toString(e));
            }
        }
        return new EnvironmentFetchResult<>(byEnvironment, errorByEnvironment, stackTraceByEnvironment);
    }

    public record EnvironmentFetchResult<T>(
            Map<String, List<T>> byEnvironment,
            Map<String, String> errorByEnvironment,
            Map<String, String> stackTraceByEnvironment) {
    }

    /**
     * カテゴリ/タグの作成・編集・削除をwp-cli経由で行う。provision-agentの/bulk-management
     * ハンドラ(category_create/category_edit/category_delete、tagはtaxonomy=post_tagで共用)と
     * 同じ挙動(作成済みならskipped、削除対象が既に無ければskipped、失敗時はfailed()を返す)に揃える。
     */
    public SshApplyResult applyTerm(
            WordPressCredentials creds, BulkOperationType type, String value, String slug, String parentSlug,
            String description, String targetSlug) {
        try {
            String taxonomy = (type == BulkOperationType.CATEGORY_CREATE || type == BulkOperationType.CATEGORY_EDIT
                    || type == BulkOperationType.CATEGORY_DELETE) ? "category" : "post_tag";
            return switch (type) {
                case CATEGORY_CREATE, TAG_CREATE -> createTerm(creds, taxonomy, value, slug, parentSlug, description);
                case CATEGORY_EDIT, TAG_EDIT ->
                        updateTerm(creds, taxonomy, value, slug, parentSlug, description, targetSlug);
                case CATEGORY_DELETE, TAG_DELETE -> deleteTerm(creds, taxonomy, targetSlug);
                default -> throw new IllegalArgumentException("SSH経由ではサポートされていない操作です: " + type);
            };
        } catch (SshOperationException e) {
            return SshApplyResult.failed(e);
        }
    }

    private SshApplyResult createTerm(
            WordPressCredentials creds, String taxonomy, String value, String slug, String parentSlug,
            String description) {
        List<CategoryInfo> terms = listTerms(creds, taxonomy);
        if (findBySlug(terms, slug) != null) {
            return SshApplyResult.skipped();
        }
        StringBuilder command = new StringBuilder("term create ").append(ShellQuote.single(taxonomy)).append(" ")
                .append(ShellQuote.single(value)).append(" --slug=").append(ShellQuote.single(slug))
                .append(" --porcelain");
        if (description != null && !description.isBlank()) {
            command.append(" --description=").append(ShellQuote.single(description));
        }
        if ("category".equals(taxonomy) && parentSlug != null && !parentSlug.isBlank()) {
            CategoryInfo parent = findBySlug(terms, parentSlug);
            if (parent == null) {
                throw new SshOperationException("親カテゴリ(slug: " + parentSlug + ")が見つかりません");
            }
            command.append(" --parent=").append(ShellQuote.single(parent.termId()));
        }
        SshCommandResult result = exec(creds, wpCli(creds, command.toString()));
        if (!result.ok()) {
            throw new SshOperationException("作成に失敗しました: " + firstLine(result.stderr(), result.stdout()));
        }
        return SshApplyResult.success();
    }

    private SshApplyResult updateTerm(
            WordPressCredentials creds, String taxonomy, String value, String slug, String parentSlug,
            String description, String targetSlug) {
        List<CategoryInfo> terms = listTerms(creds, taxonomy);
        CategoryInfo target = findBySlug(terms, targetSlug);
        if (target == null) {
            throw new SshOperationException("対象(slug: " + targetSlug + ")が見つかりません");
        }
        StringBuilder command = new StringBuilder("term update ").append(ShellQuote.single(taxonomy)).append(" ")
                .append(ShellQuote.single(target.termId()))
                .append(" --name=").append(ShellQuote.single(value))
                .append(" --slug=").append(ShellQuote.single(slug));
        if (description != null && !description.isBlank()) {
            command.append(" --description=").append(ShellQuote.single(description));
        }
        if ("category".equals(taxonomy) && parentSlug != null && !parentSlug.isBlank()) {
            CategoryInfo parent = findBySlug(terms, parentSlug);
            if (parent == null) {
                throw new SshOperationException("親カテゴリ(slug: " + parentSlug + ")が見つかりません");
            }
            if (parent.termId().equals(target.termId())) {
                throw new SshOperationException("親カテゴリに自分自身は指定できません");
            }
            command.append(" --parent=").append(ShellQuote.single(parent.termId()));
        }
        SshCommandResult result = exec(creds, wpCli(creds, command.toString()));
        if (!result.ok()) {
            throw new SshOperationException("更新に失敗しました: " + firstLine(result.stderr(), result.stdout()));
        }
        return SshApplyResult.success();
    }

    private SshApplyResult deleteTerm(WordPressCredentials creds, String taxonomy, String targetSlug) {
        List<CategoryInfo> terms = listTerms(creds, taxonomy);
        CategoryInfo target = findBySlug(terms, targetSlug);
        if (target == null) {
            // 既に存在しない = 目的達成済みとみなす(provision-agentの/bulk-managementと同じ方針)
            return SshApplyResult.skipped();
        }
        SshCommandResult result = exec(creds, wpCli(creds, "term delete " + ShellQuote.single(taxonomy) + " " + ShellQuote.single(target.termId())));
        if (!result.ok()) {
            throw new SshOperationException("削除に失敗しました: " + firstLine(result.stderr(), result.stdout()));
        }
        return SshApplyResult.success();
    }

    private CategoryInfo findBySlug(List<CategoryInfo> terms, String slug) {
        return terms.stream().filter(t -> t.slug().equalsIgnoreCase(slug)).findFirst().orElse(null);
    }

    public record CategoryInfo(String termId, String name, String slug, String parentSlug, String description) {
    }

    public record PluginThemeInfo(String name, String status) {
    }

    /** {@link #exportDatabase}の戻り値。tablePrefixは同期元のWordPressテーブルプレフィックス。 */
    public record DatabaseExport(String tablePrefix, byte[] dump) {
    }

    public record SshApplyResult(String status, String errorMessage, String stackTrace) {
        public static SshApplyResult success() {
            return new SshApplyResult("SUCCESS", null, null);
        }

        public static SshApplyResult skipped() {
            return new SshApplyResult("SKIPPED", null, null);
        }

        public static SshApplyResult failed(Throwable cause) {
            return new SshApplyResult("FAILED", cause.getMessage(), StackTraceUtil.toString(cause));
        }
    }

    /** メールアドレスに一致する既存WordPressユーザーIDを検索する(作成は行わない、読み取り専用)。 */
    public java.util.Optional<String> findAuthorIdByEmail(WordPressCredentials creds, String email) {
        return java.util.Optional.ofNullable(findExistingAuthorId(creds, email));
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
        StringBuilder command = new StringBuilder("user update ").append(ShellQuote.single(userId));
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
        log.info("SSH投稿コマンド組み立て: existingPostId={}, featuredMediaId={}, args={}",
                existingPostId, content.featuredMediaId(), fields);

        // API側(lets_blog.posts)が記憶しているWordPress投稿IDは、WordPress側で当該投稿が
        // 削除される等で実在しなくなることがある。その状態で`post update`すると
        // 「無効な投稿 ID です」で失敗し投稿自体ができなくなるため、実在確認して
        // 存在しなければ新規作成にフォールバックする(issue #491。managed側は#487で対応済み)。
        String targetPostId = existingPostId;
        if (targetPostId != null && !postExists(creds, targetPostId)) {
            log.info("existingPostId={} はWordPress側に存在しないため新規作成として扱います", targetPostId);
            targetPostId = null;
        }

        String subcommand = targetPostId == null
                ? "post create - " + fields + " --porcelain"
                : "post update " + ShellQuote.single(targetPostId) + " - " + fields + " --porcelain";

        SshCommandResult result = exec(creds, wpCli(creds, subcommand), stdin);
        if (!result.ok()) {
            throw new SshOperationException("WordPress投稿の作成/更新に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
        String postId = targetPostId != null ? targetPostId : result.stdout().strip();
        if (content.featuredMediaId() != null) {
            setFeaturedMedia(creds, postId, content.featuredMediaId());
        }
        return fetchPostResult(creds, postId);
    }

    /**
     * 指定IDの投稿がWordPress側に実在するかを`wp post get`の終了ステータスで判定する。
     * WordPressAdapter.postExists(issue #493)からも呼ばれるためpublic。メディア(添付ファイル)も
     * post_type=attachmentのwp_postsレコードのため、WordPressAdapter.mediaExists(issue #495)
     * からも同じ判定として再利用される。
     *
     * コマンドが失敗した場合、「投稿IDが無効(=実在しない)」と断定できるのは`wp post get`が
     * その旨のエラーを返したときだけである。一時的なSSH/wp-cliの不調など、実在しないと断定
     * できない失敗まで「実在しない」(false)扱いにすると、createOrUpdatePostが本来更新すべき
     * 投稿を新規作成してしまい、同じスラッグの記事が再投稿のたびに重複投稿されてしまう
     * (issue #529)。REST版(WordPressAdapter#postExists)・managed版
     * (WordPressAgentOperations#postExists)は既にこの安全側(true=実在するとみなす)の方針を
     * 採っており、SSH版も揃える。
     */
    public boolean postExists(WordPressCredentials creds, String postId) {
        SshCommandResult result = exec(creds, wpCli(creds, "post get " + ShellQuote.single(postId) + " --field=ID"));
        if (result.ok()) {
            return true;
        }
        if (isPostNotFoundError(result)) {
            return false;
        }
        log.warn("投稿の実在確認が実在しないと断定できない理由で失敗したため、安全側(実在する)とみなします: "
                + "postId={}, exitStatus={}, detail={}",
                postId, result.exitStatus(), firstLine(result.stderr(), result.stdout()));
        return true;
    }

    private static final Pattern POST_NOT_FOUND_PATTERN = Pattern.compile(
            "Invalid post ID|Could not find the post|無効な投稿\\s*ID\\s*です", Pattern.CASE_INSENSITIVE);

    /** `wp post get`が「指定IDの投稿が存在しない」ことを理由に失敗したかどうかを判定する。 */
    private boolean isPostNotFoundError(SshCommandResult result) {
        return POST_NOT_FOUND_PATTERN.matcher(result.stderr() + "\n" + result.stdout()).find();
    }

    /**
     * `wp post create/update`の`--post_thumbnail`は wp_insert_post() の認識するフィールドではなく
     * 黙って無視される(_thumbnail_id postmetaが更新されない)ため、投稿作成/更新後に
     * `wp post meta update` で明示的にアイキャッチ(_thumbnail_id)を設定する。
     */
    private void setFeaturedMedia(WordPressCredentials creds, String postId, String mediaId) {
        SshCommandResult result = exec(creds, wpCli(creds,
                "post meta update " + ShellQuote.single(postId) + " _thumbnail_id " + ShellQuote.single(mediaId)));
        if (!result.ok()) {
            throw new SshOperationException("アイキャッチ(featured media)の設定に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
        log.info("投稿{}のアイキャッチをmediaId={}に設定しました", postId, mediaId);
    }

    private String postFieldsArgs(PostContent content) {
        StringBuilder args = new StringBuilder();
        args.append("--post_title=").append(ShellQuote.single(content.title()));
        args.append(" --post_status=").append(ShellQuote.single(content.status()));
        if (content.publishScheduledAt() != null) {
            // wp-cliはUTCの日時を --post_date_gmt で受け取る(status=futureと組で予約投稿になる)。
            // `post update` は未指定フィールドを既存投稿の値のまま引き継ぐため、--post_date_gmt
            // だけを送ると post_date(サイトのローカル時刻。wp-adminや投稿画面はこちらを表示する)が
            // 更新前の値に取り残され、予約日時を変更したのに画面上は変わって見えないままになる
            // (このアプリはサイトのタイムゾーン設定を扱っていないため、--post_dateも同じUTC値で送る)。
            String scheduledAt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .format(content.publishScheduledAt().atOffset(ZoneOffset.UTC));
            args.append(" --post_date=").append(ShellQuote.single(scheduledAt));
            args.append(" --post_date_gmt=").append(ShellQuote.single(scheduledAt));
        }
        if (content.slug() != null && !content.slug().isBlank()) {
            args.append(" --post_name=").append(ShellQuote.single(content.slug()));
        }
        if (content.categoryIds() != null) {
            // 空リストも明示的に送る(frontmatterでカテゴリを全て外した変更を反映するため。issue #467)。
            // 引数自体を省略するとwp-cliは既存のカテゴリをそのまま残してしまう。
            args.append(" --post_category=").append(ShellQuote.single(String.join(",", content.categoryIds())));
        }
        if (content.tagIds() != null) {
            // 同上(issue #467)。空リストでもタグをクリアする意図として送る。
            String tagIds = String.join(",", content.tagIds());
            args.append(" --tax_input=").append(ShellQuote.single("{\"post_tag\":[" + tagIds + "]}"));
        }
        if (content.authorId() != null) {
            args.append(" --post_author=").append(ShellQuote.single(content.authorId()));
        }
        return args.toString();
    }

    private PostResult fetchPostResult(WordPressCredentials creds, String postId) {
        SshCommandResult result = exec(creds, wpCli(creds,
                "post get " + ShellQuote.single(postId) + " --fields=guid,post_status --format=json"));
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

    /**
     * `wp post delete`を`--force`なしで実行する(ゴミ箱対応の投稿タイプはWordPressコアの
     * `wp_delete_post()`既定挙動でゴミ箱へ移動される。REST API版(forceパラメータなし)と
     * 同じ挙動になる想定)。
     *
     * <p><b>issue #1411: 削除前に`post_status`を見る。</b>agent transportの
     * `/wp-cli/post-delete`は#1070で存在確認を、#1326でゴミ箱判定を入れたが、
     * どちらもSSH transportには適用されていなかった。SSH管理サイトは実運用の顧客サイトであり、
     * 未対応のままだと次の2つが残る:
     *
     * <ul>
     *   <li>存在しないIDの削除が一律502({@link SshOperationException})になり、
     *       呼び出し側の入力ミスとインフラ障害が区別できない(#1070相当)</li>
     *   <li>`--force`なしの1回目でゴミ箱へ移動した投稿へ削除要求が再送されると、
     *       WordPressコアは既に`trash`の投稿への`wp_delete_post()`を恒久削除として扱うため、
     *       **記事が復旧不能に失われる**(#1326相当)。タイムアウト後のリトライで起こりうる</li>
     * </ul>
     *
     * <p><b>一時的な失敗を404に化けさせない(issue #529)。</b>{@link #postExists}が
     * 同じ理由で安全側へ倒しているのと同じ判断である。`wp post get`の失敗のうち
     * 「投稿が無い」と断定できるのは{@link #isPostNotFoundError}が真のときだけで、
     * SSHやwp-cliの一時的な不調まで404にすると、呼び出し側は「消すものが無い」という
     * 確定的な答えを受け取ってそれ以上追わなくなる。断定できない失敗は502のままにする。
     *
     * <p>この経路からゴミ箱の恒久削除はできなくなるが、それはWordPress管理画面の役目であり、
     * 本APIの削除は「ゴミ箱へ送る」までを責務とする(`--force`を付けない既存の選択と一貫する)。
     */
    public void deletePost(WordPressCredentials creds, String postId) {
        SshCommandResult status = exec(creds,
                wpCli(creds, "post get " + ShellQuote.single(postId) + " --field=post_status"));
        if (!status.ok()) {
            if (isPostNotFoundError(status)) {
                throw new PostNotFoundException("投稿 '" + postId + "' が見つかりません");
            }
            // 断定できない失敗はここで止める。`postExists`が同じ分岐でwarnを出しているのと
            // 同じ理由で、ここも記録する(issue #1411のレビュー指摘)。`isPostNotFoundError`は
            // wp-cliのエラー文言の正規表現なので、ロケールやバージョンで文言が変われば
            // 「本当は存在しない」ケースがこの分岐へ落ちる。そのとき無言だと、404にならない
            // 理由が誰にも分からない。
            log.warn("投稿の存在確認が実在しないと断定できない理由で失敗したため、削除を中止します: "
                    + "postId={}, exitStatus={}, detail={}",
                    postId, status.exitStatus(), firstLine(status.stderr(), status.stdout()));
            throw new SshOperationException("WordPress投稿の存在確認に失敗しました: "
                    + firstLine(status.stderr(), status.stdout()));
        }
        if ("trash".equals(status.stdout().trim())) {
            throw new PostNotFoundException("投稿 '" + postId + "' は既に削除済み(ゴミ箱)です");
        }

        SshCommandResult result = exec(creds, wpCli(creds, "post delete " + ShellQuote.single(postId)));
        if (!result.ok()) {
            throw new SshOperationException("WordPress投稿の削除に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
    }

    /**
     * 記事プレビュー(非公開投稿の実表示)向けに、サイト管理者としてログイン済みと同等のCookieを発行する。
     * managed(agent)サイトのWordPressAgentOperations#generateAuthCookieと同じ仕組み(`wp eval`経由での
     * wp_generate_auth_cookie()呼び出し)を、SSH経由のwp-cli実行で行う。
     */
    public AuthCookie generateAuthCookie(WordPressCredentials creds) {
        String phpCode = "$u = get_user_by('login', " + phpSingleQuote(creds.username()) + "); "
                + "if (!$u) { echo json_encode(['error' => 'user_not_found']); exit; } "
                + "echo json_encode(['name' => LOGGED_IN_COOKIE, "
                + "'value' => wp_generate_auth_cookie($u->ID, time() + 3600, 'logged_in')]);";
        SshCommandResult result = exec(creds, wpCli(creds, "eval " + ShellQuote.single(phpCode)));
        if (!result.ok()) {
            throw new SshOperationException("認証Cookieの発行に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
        JsonNode body = parseJsonObject(result.stdout());
        if (body.has("error") || !body.hasNonNull("name") || !body.hasNonNull("value")) {
            throw new SshOperationException("ユーザー '" + creds.username() + "' が見つかりません");
        }
        return new AuthCookie(body.path("name").asText(), body.path("value").asText());
    }

    /**
     * 記事プレビュー(ArticlePreviewService)のテーマCSS/DOM取得向けに、サイト内の最新公開記事を
     * 「参照記事」として返す(読み取り専用)。従来はArticlePreviewServiceが認証なしのWordPress
     * REST APIを直接叩いていたが、SSH管理サイトも他の全操作と同じくwp-cli経由へ揃える(issue #519)。
     * title/contentはREST版のtitle.rendered/content.rendered相当(the_title/the_contentフィルタ
     * 適用後)になるよう、wp-cliのpost系コマンドではなくwp evalでWordPressコアのAPI
     * (get_posts/get_permalink/apply_filters)を直接呼び出す(generateAuthCookieと同じ方針)。
     * 参照記事が存在しない場合は空を返す。
     */
    public java.util.Optional<ReferencePost> getLatestPost(WordPressCredentials creds) {
        String phpCode = "$posts = get_posts(['numberposts' => 1, 'post_status' => 'publish', "
                + "'orderby' => 'date', 'order' => 'DESC']); "
                + "if (empty($posts)) { echo json_encode(['found' => false]); exit; } "
                + "$post = $posts[0]; "
                + "echo json_encode(['found' => true, 'id' => (string) $post->ID, "
                + "'link' => get_permalink($post->ID), "
                + "'title' => apply_filters('the_title', $post->post_title, $post->ID), "
                + "'content' => apply_filters('the_content', $post->post_content)]);";
        SshCommandResult result = exec(creds, wpCli(creds, "eval " + ShellQuote.single(phpCode)));
        if (!result.ok()) {
            throw new SshOperationException("参照記事の取得に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
        JsonNode body = parseJsonObject(result.stdout());
        if (!body.path("found").asBoolean(false)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new ReferencePost(
                body.path("id").asText(), body.path("link").asText(),
                body.path("title").asText(), body.path("content").asText()));
    }

    /**
     * テーマCSS取得(issue #1368)でURLからリモートのファイルパスを解決するための、サイトの配置情報。
     * home/siteurl/content_urlはwp-cliで取得したURL(末尾スラッシュなし)、abspath/contentDirは
     * ABSPATH/WP_CONTENT_DIR。
     */
    public record SiteFileLayout(String home, String siteurl, String contentUrl, String abspath, String contentDir) {

        /** urlがこのサイト(home/siteurl/content_urlのいずれか)のホストに属するか。 */
        public boolean owns(String url) {
            String authority = authorityOf(url);
            return authority != null
                    && (authority.equals(authorityOf(home)) || authority.equals(authorityOf(siteurl))
                    || authority.equals(authorityOf(contentUrl)));
        }
    }

    private static String authorityOf(String url) {
        try {
            String authority = java.net.URI.create(url).getAuthority();
            return authority == null ? null : authority.toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * テーマCSS取得向けに、home/siteurl/WP_CONTENT_URLとABSPATH/WP_CONTENT_DIRをwp evalで取得する。
     * URL→ファイルパス解決({@link #readStylesheetFile})の基準になる。
     */
    public SiteFileLayout fetchSiteFileLayout(WordPressCredentials creds) {
        String phpCode = "echo json_encode(['home' => home_url(), 'siteurl' => site_url(), "
                + "'content_url' => content_url(), 'abspath' => ABSPATH, 'content_dir' => WP_CONTENT_DIR]);";
        SshCommandResult result = exec(creds, wpCli(creds, "eval " + ShellQuote.single(phpCode)));
        if (!result.ok()) {
            throw new SshOperationException("サイト配置情報の取得に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
        JsonNode body = parseJsonObject(result.stdout());
        for (String field : List.of("home", "siteurl", "content_url", "abspath", "content_dir")) {
            if (!body.hasNonNull(field) || body.path(field).asText().isBlank()) {
                throw new SshOperationException("サイト配置情報に" + field + "がありません");
            }
        }
        return new SiteFileLayout(
                stripTrailingSlash(body.path("home").asText()), stripTrailingSlash(body.path("siteurl").asText()),
                stripTrailingSlash(body.path("content_url").asText()), body.path("abspath").asText(),
                body.path("content_dir").asText());
    }

    /**
     * リモートホスト自身から(サイトのWordPressコアのHTTP APIで)ページのHTMLを取得する(issue #1368)。
     *
     * <p><b>採用した手段: {@code wp eval}で{@code wp_remote_get()}を呼ぶ。</b>WordPressは常に何らかの
     * HTTPトランスポート(cURL/streams)を持つため、リモートに{@code curl}/{@code wget}バイナリが
     * 入っている保証が要らない。取得元がサイト自身のホストなので、APIコンテナ→公開URLの経路で起きる
     * WAF/ボット対策(#245)・IP制限・到達性の問題を避けられる。本文はbase64で返すため、テーマ/プラグインが
     * 出力するPHP診断行や不正なUTF-8でJSONが壊れない。
     *
     * <p><b>採らなかった手段。</b>(1){@code curl}/{@code wget}をSSHで直接実行 — 共有ホスティングでは
     * 入っていない/制限されていることがあり、前提が最も厚い。(2){@code wp eval}でテンプレートを直接
     * 描画({@code load_template}/{@code the_content}等) — {@code wp_head}/{@code wp_enqueue_scripts}が
     * 通常のリクエスト(is_single()等のクエリ状態、テーマの条件分岐)を前提とするため、CLIコンテキストでは
     * enqueueされるstylesheetが実サイトと変わり、CSS取得の目的(実際に読み込まれるものを知る)に反する。
     * (3){@code wp eval}での単純な{@code file_get_contents} — allow_url_fopenが無効なホストがある。
     * 実際のHTTPリクエストとして描画させる{@code wp_remote_get}が、前提が最も薄く実サイトに最も忠実。
     *
     * <p>SSRF防止のため、URLのホストがサイト自身のもの({@link SiteFileLayout#owns})でなければ実行しない。
     * リダイレクトも追従しない(redirection=0)。追従すると、サイト内URLからサイト外ホストへの
     * 302でこの検査を迂回できてしまうため(リダイレクトはHTTP 3xxとして例外になり、HTTP経路へ落ちる)。
     * 自己署名/不一致の証明書を持つホストでも自分自身のHTMLは取れるよう、sslverifyは無効にしている
     * (取得するのは自サイトの公開ページで、結果はCSS抽出にしか使わない)。
     */
    public String fetchPageHtml(WordPressCredentials creds, SiteFileLayout layout, String url) {
        if (!layout.owns(url)) {
            throw new SshOperationException("サイト外のURLはリモートから取得できません: " + url);
        }
        String phpCode = "$r = wp_remote_get(" + phpSingleQuote(url) + ", ['timeout' => 20, 'redirection' => 0, "
                + "'sslverify' => false, 'user-agent' => 'Mozilla/5.0 (compatible; LetsBlogPreview)']); "
                + "if (is_wp_error($r)) { echo json_encode(['error' => $r->get_error_message()]); exit; } "
                + "echo json_encode(['status' => wp_remote_retrieve_response_code($r), "
                + "'body_b64' => base64_encode(wp_remote_retrieve_body($r))]);";
        SshCommandResult result = exec(creds, wpCli(creds, "eval " + ShellQuote.single(phpCode)));
        if (!result.ok()) {
            throw new SshOperationException("ページの取得に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
        JsonNode body = parseJsonObject(result.stdout());
        if (body.has("error")) {
            throw new SshOperationException("リモートからのページ取得に失敗しました: " + body.path("error").asText());
        }
        int status = body.path("status").asInt(0);
        if (status < 200 || status >= 300) {
            throw new SshOperationException("リモートからのページ取得がHTTP " + status + "を返しました: " + url);
        }
        return new String(Base64.getDecoder().decode(body.path("body_b64").asText("")), StandardCharsets.UTF_8);
    }

    /**
     * サイト内stylesheetのURLをリモートのファイルパスへ解決し、SFTPで読む(issue #1368)。
     * 解決は、より具体的なWP_CONTENT_URL→WP_CONTENT_DIR、siteurl→ABSPATH、home→ABSPATHの順。
     *
     * <p>読まない(空を返す)のは次の場合: サイト外のURL(呼び出し側がHTTPで取得する)、解決したパスが
     * {@code wpPath}配下から外れる(パストラバーサル防止。URLの{@code %2e%2e}等は復号後に判定し、
     * 外れた場合はログに残す)、{@code .css}以外(wp-config.phpのような任意ファイルを、細工された
     * リンクで読み出させないため)、空ファイル。
     */
    public java.util.Optional<String> readStylesheetFile(WordPressCredentials creds, SiteFileLayout layout, String url) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url);
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
        if (!layout.owns(url)) {
            return java.util.Optional.empty();
        }
        String path = uri.getPath();
        String resolved = null;
        String[][] candidates = {
                {layout.contentUrl(), layout.contentDir()},
                {layout.siteurl(), layout.abspath()},
                {layout.home(), layout.abspath()},
        };
        for (String[] candidate : candidates) {
            String rel = relativePath(candidate[0], authorityOf(url), path);
            if (rel != null) {
                resolved = stripTrailingSlash(candidate[1]) + "/" + rel;
                break;
            }
        }
        if (resolved == null) {
            return java.util.Optional.empty();
        }
        java.nio.file.Path normalized = java.nio.file.Paths.get(resolved).normalize();
        java.nio.file.Path root = java.nio.file.Paths.get(creds.wpPath()).normalize();
        if (!normalized.startsWith(root)) {
            log.warn("wpPath外を指すstylesheetパスはSFTPで読みません (url={}, resolved={}, wpPath={})",
                    url, normalized, root);
            return java.util.Optional.empty();
        }
        if (!normalized.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".css")) {
            log.warn("CSS以外のパスはSFTPで読みません (url={}, resolved={})", url, normalized);
            return java.util.Optional.empty();
        }
        byte[] bytes = executor.getFile(connectionParams(creds), normalized.toString());
        if (bytes == null || bytes.length == 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new String(bytes, StandardCharsets.UTF_8));
    }

    /**
     * baseUrl配下のパスならbaseからの相対パス(先頭スラッシュなし)、配下でなければnull。
     * pathは{@link java.net.URI#getPath()}で復号済み。
     */
    private static String relativePath(String baseUrl, String authority, String path) {
        if (!java.util.Objects.equals(authorityOf(baseUrl), authority)) {
            return null;
        }
        // authorityを持つ階層URIのgetPath()は(空文字はあっても)nullにならない
        String basePath = stripTrailingSlash(java.net.URI.create(baseUrl).getPath());
        if (!path.startsWith(basePath + "/")) {
            return null;
        }
        return path.substring(basePath.length() + 1);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** PHPのシングルクォート文字列リテラルとして安全に埋め込むためのエスケープ(`\`と`'`のみ特殊)。 */
    private String phpSingleQuote(String value) {
        if (value == null) {
            return "''";
        }
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /**
     * 投稿/固定ページの一覧を取得する(ポスト/ページ管理タブの環境間比較に使用)。
     * `wp post list --post_type=post|page` はIDベースで投稿種別を問わず動作するwp-cliの標準コマンド。
     */
    public List<CmsPostSummary> listPosts(WordPressCredentials creds, String postType) {
        SshCommandResult result = exec(creds, wpCli(creds,
                "post list --post_type=" + ShellQuote.single(postType)
                        + " --fields=ID,post_title,post_name,post_status --format=json"));
        if (!result.ok()) {
            throw new SshOperationException("投稿/ページ一覧の取得に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
        return parseJsonArray(result.stdout()).stream()
                .map(item -> new CmsPostSummary(
                        item.path("ID").asText(), item.path("post_title").asText(),
                        item.path("post_name").asText(), item.path("post_status").asText(), postType))
                .toList();
    }

    /**
     * 指定スラッグ(WordPressの保存形へ正規化済み)の投稿(post_type=post)のIDを返す(issue #1431)。
     * ステータスは公開済み・下書き・非公開・予約・レビュー待ちに明示し、ゴミ箱は含めない
     * (wp-cliの既定値には依存しない)。`--name`での絞り込みに加え、`post_name`の完全一致で確認する。
     * 失敗時は例外を投げる(呼び出し側が新規作成へ進んで重複を作らないため)。
     */
    public List<String> findPostIdsBySlug(WordPressCredentials creds, String slug) {
        SshCommandResult result = exec(creds, wpCli(creds,
                "post list --post_type=" + ShellQuote.single("post")
                        + " --post_status=publish,draft,private,future,pending"
                        + " --name=" + ShellQuote.single(slug)
                        + " --fields=ID,post_name --format=json"));
        if (!result.ok()) {
            throw new SshOperationException("スラッグによる既存投稿の照会に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
        return parseJsonArray(result.stdout()).stream()
                .filter(item -> slug.equalsIgnoreCase(item.path("post_name").asText()))
                .map(item -> item.path("ID").asText())
                .toList();
    }

    /** `wp post update <id> --post_status=` はIDベースで投稿種別を問わず動作する。 */
    public void updatePostStatus(WordPressCredentials creds, String postId, String status) {
        SshCommandResult result = exec(creds, wpCli(creds,
                "post update " + ShellQuote.single(postId) + " --post_status=" + ShellQuote.single(status)));
        if (!result.ok()) {
            throw new SshOperationException("投稿/ページのステータス変更に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
    }

    /**
     * ガベージコレクション画面(issue #500)向けにメディアライブラリの一覧を取得する。
     * 添付ファイルもpost_type=attachmentのwp_postsレコードのため`wp post list`で取得できる。
     * ゴミ箱にあるメディアも「蓄積した不要メディア」の掃除対象に含めるため、
     * 既定(inherit)に加えprivate/trashも明示的に対象とする。
     */
    public List<CmsMediaSummary> listMedia(WordPressCredentials creds) {
        SshCommandResult result = exec(creds, wpCli(creds,
                "post list --post_type=attachment --post_status=inherit,private,trash"
                        + " --fields=ID,post_title,guid,post_mime_type,post_date --format=json"));
        if (!result.ok()) {
            throw new SshOperationException("メディア一覧の取得に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
        return parseJsonArray(result.stdout()).stream()
                .map(item -> new CmsMediaSummary(
                        item.path("ID").asText(), item.path("guid").asText(), item.path("post_title").asText(),
                        item.path("post_mime_type").asText(), item.path("post_date").asText()))
                .toList();
    }

    /**
     * ガベージコレクション画面(issue #500)向けに、公開投稿タイプ全件の本文/アイキャッチと、
     * 主要なサイト設定(サイトアイコン・カスタムロゴ・ヘッダー/背景画像)が参照する添付ファイルIDを
     * 1回のwp eval呼び出しでまとめて取得する(getLatestPost/generateAuthCookieと同じ「PHP文字列
     * 組み立て→eval→JSON parse」方式)。投稿タイプを['post','page']に固定せず動的に取得するのは、
     * カスタム投稿タイプに埋め込まれたメディアを誤って「未参照」と判定し削除してしまう
     * (=データ消失)リスクを避けるため。
     */
    public CmsMediaReferenceScan scanMediaReferences(WordPressCredentials creds) {
        String phpCode = "$types = get_post_types(['public' => true], 'names'); "
                + "unset($types['attachment']); $types = array_values($types); "
                + "$posts = get_posts(['post_type' => $types, "
                + "'post_status' => ['publish','future','draft','pending','private'], 'numberposts' => -1]); "
                + "$items = array_map(function($p) { return ['id' => (string) $p->ID, "
                + "'postType' => $p->post_type, 'status' => $p->post_status, 'content' => $p->post_content, "
                + "'thumbnailId' => (string) get_post_thumbnail_id($p->ID)]; }, $posts); "
                + "$headerData = get_theme_mod('header_image_data'); "
                + "$headerUrl = get_theme_mod('header_image'); "
                + "$headerId = (is_object($headerData) && isset($headerData->attachment_id)) "
                + "? (string) $headerData->attachment_id "
                + ": ($headerUrl ? (string) attachment_url_to_postid($headerUrl) : ''); "
                + "$bgUrl = get_theme_mod('background_image'); "
                + "$bgId = $bgUrl ? (string) attachment_url_to_postid($bgUrl) : ''; "
                + "$settings = ['site_icon' => (string) get_option('site_icon'), "
                + "'custom_logo' => (string) get_theme_mod('custom_logo'), "
                + "'header_image' => $headerId, 'background_image' => $bgId]; "
                + "echo json_encode(['posts' => $items, 'settings' => $settings]);";
        SshCommandResult result = exec(creds, wpCli(creds, "eval " + ShellQuote.single(phpCode)));
        if (!result.ok()) {
            throw new SshOperationException("メディア参照スキャンに失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
        JsonNode body = parseJsonObject(result.stdout());
        List<CmsPostContentSummary> posts = new ArrayList<>();
        body.path("posts").forEach(item -> posts.add(new CmsPostContentSummary(
                item.path("id").asText(), item.path("postType").asText(), item.path("status").asText(),
                item.path("content").asText(), item.path("thumbnailId").asText())));
        Map<String, String> settings = extractSettingsMediaIds(body.path("settings"));
        return new CmsMediaReferenceScan(posts, settings);
    }

    private static final List<String> SETTINGS_MEDIA_KEYS =
            List.of("site_icon", "custom_logo", "header_image", "background_image");

    private Map<String, String> extractSettingsMediaIds(JsonNode settingsNode) {
        Map<String, String> settings = new LinkedHashMap<>();
        for (String key : SETTINGS_MEDIA_KEYS) {
            settings.put(key, settingsNode.path(key).asText(""));
        }
        return settings;
    }

    /**
     * メディア(添付ファイル)を完全に削除する(issue #500)。添付ファイルもpost_type=attachmentの
     * wp_postsレコードのため`wp post delete`で削除できるが、{@link #deletePost}と異なり
     * `--force`を付けてゴミ箱を経由せず物理削除する(アップロード済みファイルも合わせて削除される)。
     */
    public void deleteMedia(WordPressCredentials creds, String mediaId) {
        // issue #1411: agent側の`/wp-cli/media-delete`(#1070)と同じく、削除前に存在を確かめて
        // 対象なしを404で区別する。`--force`付きなので#1326のゴミ箱問題は起こらない
        // (添付ファイルはそもそもゴミ箱を持たない)が、存在しないIDが502になる点は同じだった。
        // #529と同じ理由で、実在しないと断定できない失敗は404にせず502のままにする。
        SshCommandResult exists = exec(creds,
                wpCli(creds, "post get " + ShellQuote.single(mediaId) + " --field=ID"));
        if (!exists.ok()) {
            if (isPostNotFoundError(exists)) {
                throw new PostNotFoundException("メディア '" + mediaId + "' が見つかりません");
            }
            log.warn("メディアの存在確認が実在しないと断定できない理由で失敗したため、削除を中止します: "
                    + "mediaId={}, exitStatus={}, detail={}",
                    mediaId, exists.exitStatus(), firstLine(exists.stderr(), exists.stdout()));
            throw new SshOperationException("メディアの存在確認に失敗しました: "
                    + firstLine(exists.stderr(), exists.stdout()));
        }

        SshCommandResult result = exec(creds,
                wpCli(creds, "post delete " + ShellQuote.single(mediaId) + " --force"));
        if (!result.ok()) {
            throw new SshOperationException("メディアの削除に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
    }

    /**
     * SFTPでリモートの一時パスへ転送してから`wp media import`で取り込む
     * (wp-cliにバイト列を直接渡す手段がないため)。取り込み後は一時ファイルを削除する
     * (削除失敗はSshCommandExecutor.removeFileの方針通り無視してログのみ)。
     */
    public MediaUploadResult uploadMedia(WordPressCredentials creds, String filename, String contentType,
            byte[] data) {
        SshConnectionParams params = connectionParams(creds);
        ensureSvgUploadMuPlugin(creds, params);
        String remotePath = "/tmp/letsblog-media-" + UUID.randomUUID() + "-" + sanitizeFilename(filename);
        executor.putFile(params, data, remotePath);
        try {
            SshCommandResult importResult = exec(creds, wpCli(creds,
                    "media import " + ShellQuote.single(remotePath) + " --porcelain"));
            if (!importResult.ok()) {
                throw new SshOperationException("WordPressメディアのアップロードに失敗しました: "
                        + firstLine(importResult.stderr(), importResult.stdout()));
            }
            String mediaId = importResult.stdout().strip();

            SshCommandResult getResult = exec(creds, wpCli(creds,
                    "post get " + ShellQuote.single(mediaId) + " --fields=guid --format=json"));
            if (!getResult.ok()) {
                throw new SshOperationException("アップロードしたメディアの情報取得に失敗しました: "
                        + firstLine(getResult.stderr(), getResult.stdout()));
            }
            JsonNode media = parseJsonObject(getResult.stdout());
            recordContentHash(creds, mediaId, data);
            return new MediaUploadResult(mediaId, media.path("guid").asText());
        } finally {
            executor.removeFile(params, remotePath);
        }
    }

    /**
     * アップロードしたバイト列のsha256をメディアのpost metaへ記録する(issue #1432)。以後の投稿で
     * 同一内容の画像を{@link #findMediaBySha256}で同定するための印。記録に失敗してもメディア自体は
     * 作成済みで投稿は続行できる(次回その画像が再アップロードされるだけ)ため、警告に留める。
     */
    private void recordContentHash(WordPressCredentials creds, String mediaId, byte[] data) {
        SshCommandResult result = exec(creds, wpCli(creds,
                "post meta update " + ShellQuote.single(mediaId) + " "
                        + ShellQuote.single(MediaContentHash.META_KEY) + " "
                        + ShellQuote.single(MediaContentHash.sha256Hex(data))));
        if (!result.ok()) {
            log.warn("メディア(id={})のsha256の記録に失敗しました(次回は再アップロードされます): {}",
                    mediaId, firstLine(result.stderr(), result.stdout()));
        }
    }

    /**
     * 内容ハッシュ(sha256)を記録したメディアを1回のwp evalでまとめて照会する(issue #1432)。
     * ゴミ箱(trash)のメディアは対象外(post_statusはinherit/privateのみ)。ハッシュは16進64桁に
     * 検証済みのものだけをPHPリテラルへ埋め込む。同一ハッシュが複数あればIDの小さいものを採る。
     */
    public Map<String, MediaUploadResult> findMediaBySha256(WordPressCredentials creds,
                                                           java.util.Collection<String> sha256s) {
        List<String> valid = sha256s.stream().filter(MediaContentHash::isValid).distinct().toList();
        if (valid.isEmpty()) {
            return Map.of();
        }
        String literal = valid.stream().map(h -> "'" + h + "'").collect(java.util.stream.Collectors.joining(","));
        String phpCode = "$h = [" + literal + "]; "
                + "$ids = get_posts(['post_type' => 'attachment', 'post_status' => ['inherit','private'], "
                + "'numberposts' => -1, 'fields' => 'ids', 'orderby' => 'ID', 'order' => 'ASC', "
                + "'meta_query' => [['key' => '" + MediaContentHash.META_KEY + "', 'value' => $h, "
                + "'compare' => 'IN']]]); "
                + "echo json_encode(array_map(function($id) { return ['id' => (string) $id, "
                + "'guid' => get_post_field('guid', $id), "
                + "'sha256' => get_post_meta($id, '" + MediaContentHash.META_KEY + "', true)]; }, $ids));";
        SshCommandResult result = exec(creds, wpCli(creds, "eval " + ShellQuote.single(phpCode)));
        if (!result.ok()) {
            throw new SshOperationException("内容ハッシュによるメディアの照会に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
        Map<String, MediaUploadResult> found = new LinkedHashMap<>();
        parseJsonArray(result.stdout()).forEach(item -> found.putIfAbsent(item.path("sha256").asText(),
                new MediaUploadResult(item.path("id").asText(), item.path("guid").asText())));
        return found;
    }

    /**
     * WordPressコアはデフォルトでSVG(image/svg+xml)をupload_mimesに含めないため、SVG画像
     * (記事投稿時にImageResizeServiceがImageIOでデコードできない形式として無変換で送ってくる)を
     * `wp media import`で取り込もうとすると"Sorry, you are not allowed to upload this file
     * type."(このファイルタイプをアップロードする権限がありません)で失敗する(issue #489。
     * managed/provision-agent向けには#485で同様の対応済み)。他形式の挙動は変えずSVGのみ許可する
     * mu-pluginをリモートへ配置する(未配置の場合のみ)。
     */
    private void ensureSvgUploadMuPlugin(WordPressCredentials creds, SshConnectionParams params) {
        String muPluginPath = creds.wpPath() + "/wp-content/mu-plugins/letsblog-allow-svg-upload.php";
        SshCommandResult checkResult = exec(creds, "test -f " + ShellQuote.single(muPluginPath));
        if (checkResult.ok()) {
            return;
        }
        exec(creds, "mkdir -p " + ShellQuote.single(creds.wpPath() + "/wp-content/mu-plugins"));
        executor.putFile(params, SVG_UPLOAD_MU_PLUGIN.getBytes(StandardCharsets.UTF_8), muPluginPath);
    }

    /**
     * letsblogプラグインをリモートへ配置して有効化する(issue #1556)。配置済みなら何もしない(冪等)。
     * 有効化に失敗したら配置を取り消す(配置だけが残ると次回以降「導入済み」と見なされ有効化されないため)。
     */
    public void ensureLetsblogPlugin(WordPressCredentials creds) {
        String pluginDir = creds.wpPath() + "/wp-content/plugins/letsblog";
        String pluginPath = pluginDir + "/letsblog.php";
        if (exec(creds, "test -f " + ShellQuote.single(pluginPath)).ok()) {
            return;
        }
        exec(creds, "mkdir -p " + ShellQuote.single(pluginDir));
        executor.putFile(connectionParams(creds), letsblogPluginSource(), pluginPath);
        SshCommandResult activate = exec(creds, wpCli(creds, "plugin activate letsblog"));
        if (!activate.ok()) {
            exec(creds, "rm -f " + ShellQuote.single(pluginPath));
            throw new SshOperationException("letsblogプラグインの有効化に失敗しました: "
                    + firstLine(activate.stderr(), activate.stdout()));
        }
    }

    /**
     * `wp letsblog status` で letsblog プラグインの導入状態を判定する(issue #1557)。
     * 「`letsblog` は登録されたコマンドではない」(プラグインが無い・停止している)だけが未導入。
     * それ以外の失敗(wpPathの誤り・wp-cli未導入・PHPの致命的エラー・JSONでない出力)は、未導入と
     * 取り違えないよう、stderrをログに残して例外にする。
     */
    public LetsblogPluginStatus letsblogPluginStatus(WordPressCredentials creds) {
        SshCommandResult result = exec(creds, wpCli(creds, "letsblog status"));
        if (!result.ok()) {
            if (LetsblogPluginStatus.isCommandMissing(result.stderr())
                    || LetsblogPluginStatus.isCommandMissing(result.stdout())) {
                return LetsblogPluginStatus.notInstalled();
            }
            log.warn("wp letsblog statusが失敗しました (sshHost={}, wpPath={}): {}",
                    creds.sshHost(), creds.wpPath(), result.stderr());
            throw new SshOperationException("wp letsblog statusの実行に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
        try {
            return LetsblogPluginStatus.fromStatusOutput(result.stdout());
        } catch (IllegalArgumentException e) {
            log.warn("wp letsblog statusの出力を解釈できません (sshHost={}, wpPath={}): {}",
                    creds.sshHost(), creds.wpPath(), result.stderr());
            throw new SshOperationException(e.getMessage(), e);
        }
    }

    /**
     * letsblog プラグインを配置し直して有効化し、導入後の状態を返す(issue #1557)。
     * {@link #ensureLetsblogPlugin}と違い、配置済みでも有効化し直す(停止したサイトを戻すため)。
     * 有効化に失敗したら配置を取り消す。ただし導入済みだったファイルは消さず、元の内容へ戻す。
     */
    public LetsblogPluginStatus installLetsblogPlugin(WordPressCredentials creds) {
        String pluginDir = creds.wpPath() + "/wp-content/plugins/letsblog";
        String pluginPath = pluginDir + "/letsblog.php";
        SshCommandResult existing = exec(creds, "cat " + ShellQuote.single(pluginPath));
        exec(creds, "mkdir -p " + ShellQuote.single(pluginDir));
        executor.putFile(connectionParams(creds), letsblogPluginSource(), pluginPath);
        SshCommandResult activate = exec(creds, wpCli(creds, "plugin activate letsblog"));
        if (!activate.ok()) {
            if (existing.ok()) {
                executor.putFile(connectionParams(creds),
                        existing.stdout().getBytes(StandardCharsets.UTF_8), pluginPath);
            } else {
                exec(creds, "rm -f " + ShellQuote.single(pluginPath));
            }
            throw new SshOperationException("letsblogプラグインの有効化に失敗しました: "
                    + firstLine(activate.stderr(), activate.stdout()));
        }
        return letsblogPluginStatus(creds);
    }

    /**
     * タグ定義・統合CSS等を `wp letsblog sync` でプラグインへ渡す(issue #1558)。送信は wp-cli だけで行う
     * (REST APIは使わない)。内容は一時ファイルへ置いて `--file` で渡し、終わったら必ず消す。
     * 導入処理は走らせない(未導入のサイトへは送らない)。
     *
     * @return プラグインが保存した内容のハッシュ。期待ハッシュと違えば例外
     */
    public String syncLetsblogPlugin(WordPressCredentials creds, String payload, String expectedHash) {
        if (expectedHash == null || !expectedHash.matches("[A-Za-z0-9]{1,128}")) {
            throw new IllegalArgumentException("ハッシュの形式が不正です");
        }
        String remotePath = "/tmp/letsblog-sync-" + UUID.randomUUID() + ".json";
        executor.putFile(connectionParams(creds), payload.getBytes(StandardCharsets.UTF_8), remotePath);
        SshCommandResult result;
        try {
            result = exec(creds, wpCli(creds,
                    "letsblog sync --file=" + ShellQuote.single(remotePath) + " --hash=" + expectedHash));
        } finally {
            exec(creds, "rm -f " + ShellQuote.single(remotePath));
        }
        if (!result.ok()) {
            log.warn("wp letsblog syncが失敗しました (sshHost={}, wpPath={}): {}",
                    creds.sshHost(), creds.wpPath(), result.stderr());
            throw new SshOperationException("wp letsblog syncの実行に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
        String savedHash;
        try {
            savedHash = LetsblogPluginStatus.syncHashFromSyncOutput(result.stdout());
        } catch (IllegalArgumentException e) {
            throw new SshOperationException(e.getMessage(), e);
        }
        if (!savedHash.equals(expectedHash)) {
            throw new SshOperationException("プラグインが保存したハッシュ(" + savedHash
                    + ")が送った内容のハッシュ(" + expectedHash + ")と一致しません");
        }
        return savedHash;
    }

    /**
     * 内容を一時ファイルへ置き、`wp letsblog preview --file=`(wp-cliだけ。REST APIは使わない)でプラグインへ渡して、
     * 投稿を作らない署名付きプレビューURLを返す(issue #1561)。一時ファイルは成否にかかわらず消す。
     *
     * @param ttlSeconds 有効期限(秒、1〜86400)。nullならプラグインの規定値
     */
    public SignedPreview createSignedPreview(WordPressCredentials creds, String payload, Integer ttlSeconds) {
        if (ttlSeconds != null && (ttlSeconds < 1 || ttlSeconds > 86400)) {
            throw new IllegalArgumentException("有効期限は1〜86400秒で指定してください");
        }
        String remotePath = "/tmp/letsblog-preview-" + UUID.randomUUID() + ".json";
        executor.putFile(connectionParams(creds), payload.getBytes(StandardCharsets.UTF_8), remotePath);
        SshCommandResult result;
        try {
            result = exec(creds, wpCli(creds, "letsblog preview --file=" + ShellQuote.single(remotePath)
                    + (ttlSeconds == null ? "" : " --ttl=" + ttlSeconds)));
        } finally {
            exec(creds, "rm -f " + ShellQuote.single(remotePath));
        }
        if (!result.ok()) {
            log.warn("wp letsblog previewが失敗しました (sshHost={}, wpPath={}): {}",
                    creds.sshHost(), creds.wpPath(), result.stderr());
            if (LetsblogPluginStatus.isSubcommandMissing(result.stderr())) {
                throw new LetsblogPluginUnavailableException(LetsblogPluginStatus.needsUpdate());
            }
            throw new SshOperationException("wp letsblog previewの実行に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
        try {
            return SignedPreview.fromPreviewOutput(result.stdout());
        } catch (IllegalArgumentException e) {
            throw new SshOperationException(e.getMessage(), e);
        }
    }

    /**
     * `wp letsblog sns ...` を実行して標準出力を返す(issue #1574)。秘密を含む `config set` のJSONはコマンドラインに
     * 載せず、SSHの標準入力で渡す(リモートの `ps` や履歴に残さない)。SNS名は形式を検証してからシェルへ渡す。
     * 失敗したときの例外には標準入力を含めない。
     *
     * @param sns テスト投稿では必須。設定の削除では省略可(省略するとすべて消す)。それ以外では使わない
     */
    public String letsblogSns(WordPressCredentials creds, LetsblogSnsCommand command, String sns, String stdin) {
        boolean snsRequired = command == LetsblogSnsCommand.TEST;
        boolean snsUsed = snsRequired || command == LetsblogSnsCommand.CONFIG_CLEAR;
        if (snsUsed && sns != null && !sns.matches("[a-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("SNS名の形式が不正です");
        }
        if (snsRequired && sns == null) {
            throw new IllegalArgumentException("SNS名を指定してください");
        }
        String subcommand = switch (command) {
            case CONFIG_SET -> "letsblog sns config set";
            case CONFIG_CLEAR -> "letsblog sns config clear" + (sns == null ? "" : " " + sns);
            case STATUS -> "letsblog sns status";
            case TEST -> "letsblog sns test " + sns;
            case LOG -> "letsblog sns log --format=json";
        };
        byte[] input = command.requiresStdin() && stdin != null ? stdin.getBytes(StandardCharsets.UTF_8) : null;
        SshCommandResult result = exec(creds, wpCli(creds, subcommand), input);
        if (!result.ok()) {
            log.warn("wp letsblog sns {} が失敗しました (sshHost={}, wpPath={}): {}",
                    command.wire(), creds.sshHost(), creds.wpPath(), result.stderr());
            throw new SshOperationException("wp letsblog sns " + command.wire() + " の実行に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
        return result.stdout().strip();
    }

    private byte[] letsblogPluginSource() {
        try {
            return new org.springframework.core.io.ClassPathResource("wordpress-plugin/letsblog.php")
                    .getContentAsByteArray();
        } catch (java.io.IOException e) {
            throw new SshOperationException("letsblogプラグインのソースを読めません: " + e.getMessage(), e);
        }
    }

    /**
     * `wp db export`でリモートに書き出したダンプをSFTPでダウンロードする
     * (環境同期の同期元がSSH管理サイトの場合に使用。issue #511)。各環境の管理者/プロジェクトメンバー
     * アカウント(wp_users/wp_usermeta)はProjectUserSyncServiceが環境ごとに個別管理しているため、
     * provision-agentの環境同期(/sync)と同様にダンプ対象から除外する。
     * <p>
     * テーブルプレフィックス(WordPressのインストーラがセキュリティのためランダム生成することがあり、
     * 同期元と同期先で異なりうる。例: {@code jI7_} vs {@code wp_})を戻り値に含める。
     * ダンプのCREATE TABLE/INSERT INTO等は同期元のプレフィックスのまま書き出されるため、
     * 呼び出し元(provision-agentの/db-import)で同期先のwp-config.phpのtable_prefixを
     * このプレフィックスへ合わせないと、同期先のWordPressがインポートされたテーブルを
     * 読まないままになってしまう(issue #511のバグ対応)。
     */
    public DatabaseExport exportDatabase(WordPressCredentials creds) {
        SshConnectionParams params = connectionParams(creds);
        String prefix = tablePrefix(creds);
        String remotePath = "/tmp/letsblog-dbexport-" + UUID.randomUUID() + ".sql";
        SshCommandResult exportResult = exec(creds, wpCli(creds,
                "db export " + ShellQuote.single(remotePath)
                        + " --exclude_tables=" + ShellQuote.single(prefix + "users," + prefix + "usermeta")));
        if (!exportResult.ok()) {
            throw new SshOperationException("DBのエクスポートに失敗しました: "
                    + firstLine(exportResult.stderr(), exportResult.stdout()));
        }
        try {
            return new DatabaseExport(prefix, executor.getFile(params, remotePath));
        } finally {
            executor.removeFile(params, remotePath);
        }
    }

    private String tablePrefix(WordPressCredentials creds) {
        SshCommandResult result = exec(creds, wpCli(creds, "config get table_prefix"));
        String prefix = result.ok() ? result.stdout().strip() : "";
        return prefix.isEmpty() ? "wp_" : prefix;
    }

    /**
     * wp-content/uploadsをリモートでtar.gzにまとめSFTPでダウンロードする
     * (環境同期の同期元がSSH管理サイトの場合に使用。issue #511)。uploadsディレクトリが存在しない
     * (メディア未アップロード)場合は空バイト列を返し、呼び出し元でインポートをスキップする想定。
     */
    public byte[] exportMedia(WordPressCredentials creds) {
        return exportContentDirectory(creds, "uploads", "メディア");
    }

    /**
     * wp-content/themesをリモートでtar.gzにまとめSFTPでダウンロードする
     * (環境同期の同期元がSSH管理サイトの場合に使用。issue #511)。プラグインは対象外
     * (ProjectEnvironmentSyncService参照)。themesディレクトリが存在しない場合は空バイト列を返す。
     */
    public byte[] exportThemes(WordPressCredentials creds) {
        return exportContentDirectory(creds, "themes", "テーマ");
    }

    private byte[] exportContentDirectory(WordPressCredentials creds, String dirName, String label) {
        SshConnectionParams params = connectionParams(creds);
        String contentPath = creds.wpPath() + "/wp-content";
        String targetPath = contentPath + "/" + dirName;
        SshCommandResult checkResult = exec(creds, "test -d " + ShellQuote.single(targetPath));
        if (!checkResult.ok()) {
            return new byte[0];
        }
        String remotePath = "/tmp/letsblog-" + dirName + "-" + UUID.randomUUID() + ".tar.gz";
        SshCommandResult tarResult = exec(creds, "tar -czf " + ShellQuote.single(remotePath)
                + " -C " + ShellQuote.single(contentPath) + " " + ShellQuote.single(dirName));
        if (!tarResult.ok()) {
            throw new SshOperationException(label + "のエクスポートに失敗しました: "
                    + firstLine(tarResult.stderr(), tarResult.stdout()));
        }
        try {
            return executor.getFile(params, remotePath);
        } finally {
            executor.removeFile(params, remotePath);
        }
    }

    private String sanitizeFilename(String filename) {
        String base = filename != null ? filename : "upload";
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        return base.replaceAll("[^A-Za-z0-9._-]", "_");
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

    /**
     * エラー表示用にstderr/stdoutから最初の「意味のある」1行を取り出す。
     * テーマ/プラグインが出力するPHPのWarning/Notice等は本来のwp-cliエラーより先に大量に出力され、
     * 単純に先頭1行を取るとエラーの原因が完全に隠れてしまう(issue #491。実際に
     * 「このファイルタイプをアップロードする権限がありません」「無効な投稿 ID です」が
     * テーマのWarningに覆い隠され原因調査が難航した)。PHPが出力する診断行は
     * 「... in /path/to/file.php on line 123」形式である一方、wp-cli自身のエラーは
     * その形式を取らないため、これを手掛かりに読み飛ばす。
     * 全行がPHP診断行だった場合は情報を失わないよう従来どおり先頭行を返す。
     */
    private String firstLine(String... candidates) {
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            for (String line : candidate.split("\n")) {
                String stripped = line.strip();
                if (!stripped.isEmpty() && !PHP_DIAGNOSTIC_LINE.matcher(stripped).find()) {
                    return stripped;
                }
            }
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                int newline = candidate.indexOf('\n');
                return (newline >= 0 ? candidate.substring(0, newline) : candidate).strip();
            }
        }
        return "SSH経由のwp-cli実行に失敗しました";
    }
}
