package com.letsblog.api.cms.ssh;

import com.letsblog.api.cms.AuthorProvisioningRequest;
import com.letsblog.api.cms.WpCliInstallResult;
import com.letsblog.api.cms.CmsCredentials.WordPressCredentials;
import com.letsblog.api.cms.CmsPostSummary;
import com.letsblog.api.cms.ConnectionCheckResult;
import com.letsblog.api.cms.MediaUploadResult;
import com.letsblog.api.cms.PostContent;
import com.letsblog.api.cms.PostResult;
import com.letsblog.api.cms.ssh.SshCommandExecutor.SshCommandResult;
import com.letsblog.api.cms.ssh.SshCommandExecutor.SshConnectionParams;
import com.letsblog.api.domain.BulkOperationType;
import com.letsblog.api.util.StackTraceUtil;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

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
        return type + " list --fields=name,status --format=json";
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
     * provision-agent(wordpress/provision-agent/index.php)の/bulk-managementハンドラと同じ挙動
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
            return runWpCli(creds, wpType + " install " + ShellQuote.single(remotePath) + " --force",
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
        return runWpCli(creds, type + " install " + ShellQuote.single(slug),
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
        return "term list " + taxonomy + " --fields=term_id,name,slug,parent,description --format=json";
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
        StringBuilder command = new StringBuilder("term create ").append(taxonomy).append(" ")
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
            command.append(" --parent=").append(parent.termId());
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
        StringBuilder command = new StringBuilder("term update ").append(taxonomy).append(" ").append(target.termId())
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
            command.append(" --parent=").append(parent.termId());
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
        SshCommandResult result = exec(creds, wpCli(creds, "term delete " + taxonomy + " " + target.termId()));
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
        log.info("SSH投稿コマンド組み立て: existingPostId={}, featuredMediaId={}, args={}",
                existingPostId, content.featuredMediaId(), fields);

        String subcommand = existingPostId == null
                ? "post create - " + fields + " --porcelain"
                : "post update " + existingPostId + " - " + fields + " --porcelain";

        SshCommandResult result = exec(creds, wpCli(creds, subcommand), stdin);
        if (!result.ok()) {
            throw new SshOperationException("WordPress投稿の作成/更新に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
        String postId = existingPostId != null ? existingPostId : result.stdout().strip();
        if (content.featuredMediaId() != null) {
            setFeaturedMedia(creds, postId, content.featuredMediaId());
        }
        return fetchPostResult(creds, postId);
    }

    /**
     * `wp post create/update`の`--post_thumbnail`は wp_insert_post() の認識するフィールドではなく
     * 黙って無視される(_thumbnail_id postmetaが更新されない)ため、投稿作成/更新後に
     * `wp post meta update` で明示的にアイキャッチ(_thumbnail_id)を設定する。
     */
    private void setFeaturedMedia(WordPressCredentials creds, String postId, String mediaId) {
        SshCommandResult result = exec(creds, wpCli(creds,
                "post meta update " + postId + " _thumbnail_id " + ShellQuote.single(mediaId)));
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
        if (content.authorId() != null) {
            args.append(" --post_author=").append(ShellQuote.single(content.authorId()));
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

    /**
     * `wp post delete`を`--force`なしで実行する(ゴミ箱対応の投稿タイプはWordPressコアの
     * `wp_delete_post()`既定挙動でゴミ箱へ移動される。REST API版(forceパラメータなし)と
     * 同じ挙動になる想定)。
     */
    public void deletePost(WordPressCredentials creds, String postId) {
        SshCommandResult result = exec(creds, wpCli(creds, "post delete " + postId + " --yes"));
        if (!result.ok()) {
            throw new SshOperationException("WordPress投稿の削除に失敗しました: "
                    + firstLine(result.stderr(), result.stdout()));
        }
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

    /** `wp post update <id> --post_status=` はIDベースで投稿種別を問わず動作する。 */
    public void updatePostStatus(WordPressCredentials creds, String postId, String status) {
        SshCommandResult result = exec(creds, wpCli(creds,
                "post update " + postId + " --post_status=" + ShellQuote.single(status)));
        if (!result.ok()) {
            throw new SshOperationException("投稿/ページのステータス変更に失敗しました: "
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
                    "post get " + mediaId + " --fields=guid --format=json"));
            if (!getResult.ok()) {
                throw new SshOperationException("アップロードしたメディアの情報取得に失敗しました: "
                        + firstLine(getResult.stderr(), getResult.stdout()));
            }
            JsonNode media = parseJsonObject(getResult.stdout());
            return new MediaUploadResult(mediaId, media.path("guid").asText());
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
