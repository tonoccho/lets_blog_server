package com.letsblog.publishing.cms.ssh;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.publishing.cms.AuthCookie;
import com.letsblog.publishing.cms.AuthorProvisioningRequest;
import com.letsblog.publishing.cms.CmsCredentials.WordPressCredentials;
import com.letsblog.publishing.cms.ConnectionCheckResult;
import com.letsblog.publishing.cms.FakeSshCommandExecutor;
import com.letsblog.publishing.cms.LetsblogSnsCommand;
import com.letsblog.publishing.cms.PostContent;
import com.letsblog.publishing.cms.PostResult;
import com.letsblog.publishing.cms.WpCliInstallResult;
import com.letsblog.publishing.cms.ssh.SshCommandExecutor.SshCommandResult;
import com.letsblog.publishing.cms.ssh.WordPressSshOperations.CategoryInfo;
import com.letsblog.publishing.cms.ssh.WordPressSshOperations.EnvironmentFetchResult;
import com.letsblog.publishing.cms.ssh.WordPressSshOperations.PluginThemeInfo;
import com.letsblog.publishing.cms.ssh.WordPressSshOperations.SshApplyResult;
import com.letsblog.publishing.domain.BulkOperationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WordPressSshOperationsの分岐を埋める補完テスト(issue #1814)。SSHの境界は{@link FakeSshCommandExecutor}
 * (SshCommandExecutorの手書きフェイク)で差し替え、WordPressSshOperations自身は実物で動かす。
 * 既存のWordPressSshOperationsTestが通していない分岐(エラー経路・省略可能な引数・
 * 列挙値ごとの分岐)を、それぞれ出力(結果/実行したコマンド/例外)を検証して通す。
 */
class WordPressSshOperationsBranchTest {

    private static final String TERMS = "["
            + "{\"term_id\":\"1\",\"name\":\"News\",\"slug\":\"news\",\"parent\":\"0\",\"description\":\"\"},"
            + "{\"term_id\":\"2\",\"name\":\"Sub\",\"slug\":\"sub\",\"parent\":\"1\",\"description\":\"d\"}]";

    private FakeSshCommandExecutor ssh;
    private WordPressSshOperations operations;

    @BeforeEach
    void setUp() {
        ssh = new FakeSshCommandExecutor();
        operations = new WordPressSshOperations(ssh, new ObjectMapper());
    }

    private WordPressCredentials creds() {
        return new WordPressCredentials("https://example.com", "admin", "SSH", "203.0.113.5", 22, "deploy",
                "/var/www/html", "PRIVATE-KEY-PEM", "SHA256:pinned", null);
    }

    // ---- 名前解決(カテゴリ/タグ) ----

    @Test
    void resolveCategories_名前がnullまたは空ならコマンドを実行せず空を返す() {
        assertEquals(List.of(), operations.resolveCategories(creds(), null));
        assertEquals(List.of(), operations.resolveTags(creds(), List.of()));
        assertTrue(ssh.commands.isEmpty());
    }

    @Test
    void resolveTags_検索結果に同名が無ければ作成してそのIDを返す() {
        ssh.whenOk("term list", "[{\"name\":\"other\",\"term_id\":\"9\"}]").whenOk("term create", "5\n");

        assertEquals(List.of("5"), operations.resolveTags(creds(), List.of("java")));
        assertTrue(ssh.ran("term create 'post_tag' 'java' --porcelain"));
    }

    @Test
    void resolveTags_検索結果が配列でなければ同名なしとして作成する() {
        ssh.whenOk("term list", "{}").whenOk("term create", "6");

        assertEquals(List.of("6"), operations.resolveTags(creds(), List.of("java")));
    }

    @Test
    void resolveTags_作成に失敗したら例外() {
        ssh.whenOk("term list", "[]").whenFail("term create", "boom");

        SshOperationException e = assertThrows(SshOperationException.class,
                () -> operations.resolveTags(creds(), List.of("java")));
        assertTrue(e.getMessage().contains("作成に失敗しました: boom"));
    }

    @Test
    void resolveTags_検索に失敗したら例外() {
        ssh.whenFail("term list", "denied");

        SshOperationException e = assertThrows(SshOperationException.class,
                () -> operations.resolveTags(creds(), List.of("java")));
        assertTrue(e.getMessage().contains("検索に失敗しました: denied"));
    }

    @Test
    void listCategories_出力がJSONとして不正なら例外() {
        ssh.whenOk("term list", "not json {");

        SshOperationException e = assertThrows(SshOperationException.class, () -> operations.listCategories(creds()));
        assertTrue(e.getMessage().contains("解析に失敗しました"));
    }

    @Test
    void listTags_出力が配列でなければ空を返す() {
        ssh.whenOk("term list", "{}");

        assertEquals(List.of(), operations.listTags(creds()));
    }

    @Test
    void listCategories_親IDをslugへ解決して返す() {
        ssh.whenOk("term list", TERMS);

        List<CategoryInfo> categories = operations.listCategories(creds());

        assertEquals(new CategoryInfo("1", "News", "news", null, ""), categories.get(0));
        assertEquals(new CategoryInfo("2", "Sub", "sub", "news", "d"), categories.get(1));
    }

    // ---- installWpCli ----

    @Test
    void installWpCli_command_vが成功しても出力が空なら未導入として導入する() {
        ssh.whenOk("command -v wp", "  \n").whenOk("curl -fsSL", "WP-CLI 2.9.0");

        WpCliInstallResult result = operations.installWpCli(creds());

        assertTrue(result.message().contains("WP-CLI 2.9.0"));
    }

    // ---- applyPluginTheme ----

    @Test
    void applyPluginTheme_PLUGIN_INSTALLは導入済みならskippedで導入コマンドを実行しない() {
        ssh.whenOk("'plugin' list", "[{\"name\":\"seo\",\"status\":\"active\"}]");

        SshApplyResult result = operations.applyPluginTheme(creds(), BulkOperationType.PLUGIN_INSTALL, "seo");

        assertEquals("SKIPPED", result.status());
        assertFalse(ssh.ran("'plugin' install"));
    }

    @Test
    void applyPluginTheme_PLUGIN_INSTALLは未導入なら導入する() {
        ssh.whenOk("'plugin' list", "[]").whenOk("'plugin' install", "");

        assertEquals("SUCCESS", operations.applyPluginTheme(creds(), BulkOperationType.PLUGIN_INSTALL, "seo").status());
        assertTrue(ssh.ran("'plugin' install 'seo'"));
    }

    @Test
    void applyPluginTheme_THEME_INSTALLは導入に失敗したらテーマの文言で失敗を返す() {
        ssh.whenOk("'theme' list", "[{\"name\":\"other\",\"status\":\"inactive\"}]").whenFail("'theme' install", "no space");

        SshApplyResult result = operations.applyPluginTheme(creds(), BulkOperationType.THEME_INSTALL, "twenty");

        assertEquals("FAILED", result.status());
        assertEquals("テーマのインストールに失敗しました: no space", result.errorMessage());
        assertNotNull(result.stackTrace());
    }

    @Test
    void applyPluginTheme_THEME_INSTALLは未導入なら導入し_導入済みならskipped() {
        ssh.whenSeq("'theme' list", FakeSshCommandExecutor.ok("[]"),
                FakeSshCommandExecutor.ok("[{\"name\":\"twenty\",\"status\":\"active\"}]"))
                .whenOk("'theme' install", "");

        assertEquals("SUCCESS", operations.applyPluginTheme(creds(), BulkOperationType.THEME_INSTALL, "twenty").status());
        assertTrue(ssh.ran("'theme' install 'twenty'"));
        assertEquals("SKIPPED", operations.applyPluginTheme(creds(), BulkOperationType.THEME_INSTALL, "twenty").status());
    }

    @Test
    void applyPluginTheme_PLUGIN_INSTALLの導入失敗はプラグインの文言() {
        ssh.whenOk("'plugin' list", "[]").whenFail("'plugin' install", "no space");

        assertEquals("プラグインのインストールに失敗しました: no space",
                operations.applyPluginTheme(creds(), BulkOperationType.PLUGIN_INSTALL, "seo").errorMessage());
    }

    @Test
    void applyPluginTheme_有効化_無効化_テーマ操作はそれぞれのwp_cliを実行する() {
        ssh.whenOk("wp", "");

        assertEquals("SUCCESS", operations.applyPluginTheme(creds(), BulkOperationType.PLUGIN_ACTIVATE, "a").status());
        assertEquals("SUCCESS", operations.applyPluginTheme(creds(), BulkOperationType.PLUGIN_DEACTIVATE, "b").status());
        assertEquals("SUCCESS", operations.applyPluginTheme(creds(), BulkOperationType.THEME_ACTIVATE, "c").status());
        assertEquals("SUCCESS", operations.applyPluginTheme(creds(), BulkOperationType.THEME_DELETE, "d").status());

        assertTrue(ssh.commands.get(0).endsWith("plugin activate 'a'"));
        assertTrue(ssh.commands.get(1).endsWith("plugin deactivate 'b'"));
        assertTrue(ssh.commands.get(2).endsWith("theme activate 'c'"));
        assertTrue(ssh.commands.get(3).endsWith("theme delete 'd'"));
    }

    @Test
    void applyPluginTheme_有効化と無効化とテーマ操作の失敗は操作ごとの文言で失敗を返す() {
        ssh.whenFail("wp", "denied");

        assertEquals("プラグインの有効化に失敗しました: denied",
                operations.applyPluginTheme(creds(), BulkOperationType.PLUGIN_ACTIVATE, "a").errorMessage());
        assertEquals("プラグインの無効化に失敗しました: denied",
                operations.applyPluginTheme(creds(), BulkOperationType.PLUGIN_DEACTIVATE, "a").errorMessage());
        assertEquals("テーマの有効化に失敗しました: denied",
                operations.applyPluginTheme(creds(), BulkOperationType.THEME_ACTIVATE, "a").errorMessage());
        assertEquals("テーマの削除に失敗しました: denied",
                operations.applyPluginTheme(creds(), BulkOperationType.THEME_DELETE, "a").errorMessage());
    }

    @Test
    void applyPluginTheme_PLUGIN_DELETEは先に無効化を試み_無効化の失敗は無視して削除する() {
        ssh.whenFail("plugin deactivate", "not active").whenOk("plugin delete", "");

        assertEquals("SUCCESS", operations.applyPluginTheme(creds(), BulkOperationType.PLUGIN_DELETE, "a").status());
        assertTrue(ssh.indexOf("plugin deactivate") < ssh.indexOf("plugin delete"));
    }

    @Test
    void applyPluginTheme_PLUGIN_DELETEの削除失敗は失敗を返す() {
        ssh.whenOk("plugin deactivate", "").whenFail("plugin delete", "locked");

        assertEquals("プラグインの削除に失敗しました: locked",
                operations.applyPluginTheme(creds(), BulkOperationType.PLUGIN_DELETE, "a").errorMessage());
    }

    @Test
    void applyPluginTheme_SSH経由で扱わない操作は例外() {
        assertThrows(IllegalArgumentException.class,
                () -> operations.applyPluginTheme(creds(), BulkOperationType.CATEGORY_CREATE, "a"));
    }

    @Test
    void applyZip_転送に失敗したら失敗を返しwp_cliは実行しない() {
        SshCommandExecutor failingPut = new FakeSshCommandExecutor() {
            @Override
            public void putFile(SshConnectionParams params, byte[] data, String remotePath) {
                throw new SshOperationException("sftp down");
            }
        };

        SshApplyResult result = new WordPressSshOperations(failingPut, new ObjectMapper())
                .applyZip(creds(), BulkOperationType.PLUGIN_INSTALL, new byte[]{1}, "p.zip");

        assertEquals("FAILED", result.status());
        assertEquals("sftp down", result.errorMessage());
    }

    @Test
    void applyZip_プラグインzipはwp_plugin_install_forceで導入し_失敗時はプラグインの文言で一時ファイルを消す() {
        ssh.whenFail("'plugin' install", "bad zip");

        SshApplyResult result = operations.applyZip(creds(), BulkOperationType.PLUGIN_INSTALL, new byte[]{1}, "../p q.zip");

        assertEquals("プラグインのインストールに失敗しました: bad zip", result.errorMessage());
        String remote = ssh.uploaded.keySet().iterator().next();
        assertTrue(remote.endsWith("-p_q.zip"), remote);
        assertEquals(List.of(remote), ssh.removed);
    }

    @Test
    void applyZip_テーマzipはtheme_installで導入する() {
        ssh.whenOk("'theme' install", "");

        assertEquals("SUCCESS", operations.applyZip(creds(), BulkOperationType.THEME_INSTALL, new byte[]{1}, null).status());
        assertTrue(ssh.ran("'theme' install"));
        assertTrue(ssh.uploaded.keySet().iterator().next().endsWith("-upload"));
    }

    // ---- 複数環境の一括取得 ----

    @Test
    void fetchTermsForEnvironments_タグはpost_tagで取得し環境ごとの失敗はその環境だけをエラーにする() {
        ssh.whenSeq("term list 'post_tag'", FakeSshCommandExecutor.ok("[{\"term_id\":\"1\",\"name\":\"Java\",\"slug\":\"java\"}]"),
                FakeSshCommandExecutor.fail("denied"));
        Map<String, WordPressCredentials> envs = new LinkedHashMap<>();
        envs.put("dev", creds());
        envs.put("prod", creds());

        EnvironmentFetchResult<CategoryInfo> result = operations.fetchTermsForEnvironments("post_tag", envs);

        assertEquals("Java", result.byEnvironment().get("dev").get(0).name());
        assertEquals("タグ一覧の取得に失敗しました: denied", result.errorByEnvironment().get("prod"));
        assertNotNull(result.stackTraceByEnvironment().get("prod"));
    }

    @Test
    void fetchPluginsOrThemesForEnvironments_テーマはtheme_listで取得する() {
        ssh.whenOk("'theme' list", "[{\"name\":\"twenty\",\"status\":\"active\"}]");
        Map<String, WordPressCredentials> envs = Map.of("dev", creds());

        EnvironmentFetchResult<PluginThemeInfo> result = operations.fetchPluginsOrThemesForEnvironments("theme", envs);

        assertEquals(List.of(new PluginThemeInfo("twenty", "active")), result.byEnvironment().get("dev"));
    }

    // ---- applyTerm ----

    @Test
    void applyTerm_カテゴリ作成は説明と親を付けて作成する() {
        ssh.whenOk("term list", TERMS).whenOk("term create", "3");

        SshApplyResult result = operations.applyTerm(creds(), BulkOperationType.CATEGORY_CREATE,
                "Tech", "tech", "news", "about tech", null);

        assertEquals("SUCCESS", result.status());
        String command = ssh.commands.get(ssh.indexOf("term create"));
        assertTrue(command.contains("--slug='tech'") && command.contains("--description='about tech'")
                && command.contains("--parent='1'"), command);
    }

    @Test
    void applyTerm_カテゴリ作成は説明と親が空白なら付けない() {
        ssh.whenOk("term list", TERMS).whenOk("term create", "3");

        operations.applyTerm(creds(), BulkOperationType.CATEGORY_CREATE, "Tech", "tech", " ", " ", null);

        String command = ssh.commands.get(ssh.indexOf("term create"));
        assertFalse(command.contains("--description") || command.contains("--parent"), command);
    }

    @Test
    void applyTerm_カテゴリ作成は説明と親がnullなら付けない() {
        ssh.whenOk("term list", TERMS).whenOk("term create", "3");

        operations.applyTerm(creds(), BulkOperationType.CATEGORY_CREATE, "Tech", "tech", null, null, null);

        String command = ssh.commands.get(ssh.indexOf("term create"));
        assertFalse(command.contains("--description") || command.contains("--parent"), command);
    }

    @Test
    void applyTerm_タグ作成は親を指定されても付けない() {
        ssh.whenOk("term list", TERMS).whenOk("term create", "3");

        operations.applyTerm(creds(), BulkOperationType.TAG_CREATE, "Java", "java", "news", "", null);

        String command = ssh.commands.get(ssh.indexOf("term create"));
        assertTrue(command.contains("'post_tag'") && !command.contains("--parent"), command);
    }

    @Test
    void applyTerm_作成済みのslugならskipped() {
        ssh.whenOk("term list", TERMS);

        assertEquals("SKIPPED", operations.applyTerm(creds(), BulkOperationType.CATEGORY_CREATE,
                "News", "NEWS", null, null, null).status());
        assertFalse(ssh.ran("term create"));
    }

    @Test
    void applyTerm_作成で親が見つからなければ失敗() {
        ssh.whenOk("term list", TERMS);

        SshApplyResult result = operations.applyTerm(creds(), BulkOperationType.CATEGORY_CREATE,
                "Tech", "tech", "missing", null, null);

        assertEquals("FAILED", result.status());
        assertEquals("親カテゴリ(slug: missing)が見つかりません", result.errorMessage());
    }

    @Test
    void applyTerm_作成コマンドが失敗したら失敗() {
        ssh.whenOk("term list", TERMS).whenFail("term create", "exists");

        assertEquals("作成に失敗しました: exists", operations.applyTerm(creds(), BulkOperationType.CATEGORY_CREATE,
                "Tech", "tech", null, null, null).errorMessage());
    }

    @Test
    void applyTerm_カテゴリ編集は名前_slug_説明_親を更新する() {
        ssh.whenOk("term list", TERMS).whenOk("term update", "");

        SshApplyResult result = operations.applyTerm(creds(), BulkOperationType.CATEGORY_EDIT,
                "Sub2", "sub2", "news", "desc", "sub");

        assertEquals("SUCCESS", result.status());
        String command = ssh.commands.get(ssh.indexOf("term update"));
        assertTrue(command.contains("term update 'category' '2' --name='Sub2' --slug='sub2'")
                && command.contains("--description='desc'") && command.contains("--parent='1'"), command);
    }

    @Test
    void applyTerm_カテゴリ編集は説明と親が空なら付けない() {
        ssh.whenOk("term list", TERMS).whenOk("term update", "");

        operations.applyTerm(creds(), BulkOperationType.CATEGORY_EDIT, "Sub2", "sub2", "", "", "sub");
        operations.applyTerm(creds(), BulkOperationType.CATEGORY_EDIT, "Sub2", "sub2", null, null, "sub");

        for (String command : ssh.commands) {
            if (command.contains("term update")) {
                assertFalse(command.contains("--description") || command.contains("--parent"), command);
            }
        }
    }

    @Test
    void applyTerm_タグ編集は親を指定されても付けない() {
        ssh.whenOk("term list", TERMS).whenOk("term update", "");

        assertEquals("SUCCESS", operations.applyTerm(creds(), BulkOperationType.TAG_EDIT,
                "Java2", "java2", "news", null, "news").status());
        assertFalse(ssh.commands.get(ssh.indexOf("term update")).contains("--parent"));
    }

    @Test
    void applyTerm_編集で対象が見つからなければ失敗() {
        ssh.whenOk("term list", TERMS);

        assertEquals("対象(slug: nope)が見つかりません", operations.applyTerm(creds(),
                BulkOperationType.CATEGORY_EDIT, "x", "x", null, null, "nope").errorMessage());
    }

    @Test
    void applyTerm_編集で親が見つからなければ失敗() {
        ssh.whenOk("term list", TERMS);

        assertEquals("親カテゴリ(slug: nope)が見つかりません", operations.applyTerm(creds(),
                BulkOperationType.CATEGORY_EDIT, "x", "x", "nope", null, "sub").errorMessage());
    }

    @Test
    void applyTerm_編集で親が自分自身なら失敗() {
        ssh.whenOk("term list", TERMS);

        assertEquals("親カテゴリに自分自身は指定できません", operations.applyTerm(creds(),
                BulkOperationType.CATEGORY_EDIT, "x", "x", "sub", null, "sub").errorMessage());
        assertFalse(ssh.ran("term update"));
    }

    @Test
    void applyTerm_編集コマンドが失敗したら失敗() {
        ssh.whenOk("term list", TERMS).whenFail("term update", "locked");

        assertEquals("更新に失敗しました: locked", operations.applyTerm(creds(), BulkOperationType.CATEGORY_EDIT,
                "x", "x", null, null, "sub").errorMessage());
    }

    @Test
    void applyTerm_削除は対象がすでに無ければskippedで_あれば削除する() {
        ssh.whenOk("term list", TERMS).whenOk("term delete", "");

        assertEquals("SKIPPED", operations.applyTerm(creds(), BulkOperationType.CATEGORY_DELETE,
                null, null, null, null, "gone").status());
        assertFalse(ssh.ran("term delete"));
        assertEquals("SUCCESS", operations.applyTerm(creds(), BulkOperationType.TAG_DELETE,
                null, null, null, null, "sub").status());
        assertTrue(ssh.ran("term delete 'post_tag' '2'"));
    }

    @Test
    void applyTerm_削除コマンドが失敗したら失敗() {
        ssh.whenOk("term list", TERMS).whenFail("term delete", "in use");

        assertEquals("削除に失敗しました: in use", operations.applyTerm(creds(), BulkOperationType.CATEGORY_DELETE,
                null, null, null, null, "sub").errorMessage());
    }

    @Test
    void applyTerm_term操作ではない種別は例外() {
        assertThrows(IllegalArgumentException.class, () -> operations.applyTerm(creds(),
                BulkOperationType.PLUGIN_INSTALL, null, null, null, null, null));
    }

    // ---- 著者 ----

    @Test
    void provisionAuthor_検索コマンドが失敗したら未登録として作成し_指定のroleと項目で更新する() {
        ssh.whenFail("user list", "boom").whenOk("user create", "7").whenOk("user update", "");
        AuthorProvisioningRequest request = new AuthorProvisioningRequest("a@example.com", "editor",
                "First", "Last", "Disp", "https://a.example", "bio", null);

        assertEquals("7", operations.provisionAuthor(creds(), request));

        assertTrue(ssh.commands.get(ssh.indexOf("user create")).contains("--role='editor'"));
        String update = ssh.commands.get(ssh.indexOf("user update"));
        assertTrue(update.contains("--first_name='First'") && update.contains("--last_name='Last'")
                && update.contains("--display_name='Disp'") && update.contains("--user_url='https://a.example'")
                && update.contains("--description='bio'") && update.contains("--role='editor'"), update);
    }

    @Test
    void provisionAuthor_作成に失敗しても同時作成された著者がいれば更新して返す() {
        ssh.whenSeq("user list", FakeSshCommandExecutor.ok("[]"), FakeSshCommandExecutor.ok("[{\"ID\":\"8\"}]"))
                .whenFail("user create", "exists").whenOk("user update", "");

        assertEquals("8", operations.provisionAuthor(creds(), AuthorProvisioningRequest.of("a@example.com")));
    }

    @Test
    void provisionAuthor_作成に失敗し著者もいなければ例外() {
        ssh.whenOk("user list", "[]").whenFail("user create", "denied");

        SshOperationException e = assertThrows(SshOperationException.class,
                () -> operations.provisionAuthor(creds(), AuthorProvisioningRequest.of("a@example.com")));
        assertEquals("WordPress著者の作成に失敗しました: denied", e.getMessage());
    }

    @Test
    void provisionAuthor_更新に失敗したら例外() {
        ssh.whenOk("user list", "[{\"ID\":\"8\"}]").whenFail("user update", "denied");

        SshOperationException e = assertThrows(SshOperationException.class,
                () -> operations.provisionAuthor(creds(), AuthorProvisioningRequest.of("a@example.com")));
        assertEquals("WordPress著者の更新に失敗しました: denied", e.getMessage());
    }

    @Test
    void provisionAuthor_roleが無ければauthorで更新する() {
        ssh.whenOk("user list", "[{\"ID\":\"8\"}]").whenOk("user update", "");
        AuthorProvisioningRequest request = new AuthorProvisioningRequest("a@example.com", null, null, null, null, null, null, null);

        operations.provisionAuthor(creds(), request);

        String update = ssh.commands.get(ssh.indexOf("user update"));
        assertTrue(update.contains("--role='author'") && !update.contains("--first_name"), update);
    }

    // ---- 投稿 ----

    private void stubPostRoundTrip() {
        ssh.whenOk("post create", "10\n")
                .whenOk("post update", "")
                .whenOk("--fields=guid,post_status", "{\"guid\":\"g\",\"post_status\":\"publish\"}");
    }

    @Test
    void createOrUpdatePost_本文もスラッグもカテゴリも無ければ最小の引数で作成する() {
        stubPostRoundTrip();
        PostContent content = new PostContent("T", null, null, "publish", null, null, null, null);

        PostResult result = operations.createOrUpdatePost(creds(), content, null);

        assertEquals(new PostResult("10", "g", "publish"), result);
        String create = ssh.commands.get(ssh.indexOf("post create"));
        assertEquals("wp --path='/var/www/html' post create - --post_title='T' --post_status='publish' --porcelain", create);
        assertEquals(0, ssh.stdins.get(create).length);
    }

    @Test
    void createOrUpdatePost_スラッグが空白なら_post_nameを付けない() {
        stubPostRoundTrip();

        operations.createOrUpdatePost(creds(), new PostContent("T", "  ", "<p>x</p>", "publish", null, null, null, null), null);

        assertFalse(ssh.commands.get(ssh.indexOf("post create")).contains("--post_name"));
    }

    @Test
    void createOrUpdatePost_全項目を指定すると全引数を付け_予約日時はUTCで送る() {
        stubPostRoundTrip();
        PostContent content = new PostContent("T", "slug", "<p>x</p>", "future", List.of("1", "2"), List.of("3"),
                null, "9", Instant.parse("2026-10-16T01:02:03Z"));

        operations.createOrUpdatePost(creds(), content, null);

        String create = ssh.commands.get(ssh.indexOf("post create"));
        assertTrue(create.contains("--post_date='2026-10-16 01:02:03'")
                && create.contains("--post_date_gmt='2026-10-16 01:02:03'")
                && create.contains("--post_name='slug'") && create.contains("--post_category='1,2'")
                && create.contains("--tax_input='{\"post_tag\":[3]}'") && create.contains("--post_author='9'"), create);
    }

    @Test
    void createOrUpdatePost_カテゴリとタグが空リストでも明示的に送る() {
        stubPostRoundTrip();

        operations.createOrUpdatePost(creds(), new PostContent("T", "s", "x", "draft", List.of(), List.of(), null, null), null);

        String create = ssh.commands.get(ssh.indexOf("post create"));
        assertTrue(create.contains("--post_category=''") && create.contains("--tax_input='{\"post_tag\":[]}'"), create);
    }

    @Test
    void createOrUpdatePost_既存IDが実在すれば更新しアイキャッチを設定する() {
        stubPostRoundTrip();
        ssh.whenOk("--field=ID", "55").whenOk("post meta update", "");

        PostResult result = operations.createOrUpdatePost(creds(),
                new PostContent("T", "s", "x", "publish", null, null, "77", null), "55");

        assertEquals("55", result.id());
        assertTrue(ssh.ran("post update '55' -"));
        assertTrue(ssh.ran("post meta update '55' _thumbnail_id '77'"));
    }

    @Test
    void createOrUpdatePost_既存IDが実在しなければ新規作成にフォールバックする() {
        stubPostRoundTrip();
        ssh.whenFail("--field=ID", "Error: Invalid post ID.");

        PostResult result = operations.createOrUpdatePost(creds(),
                new PostContent("T", "s", "x", "publish", null, null, null, null), "55");

        assertEquals("10", result.id());
        assertFalse(ssh.ran("post update"));
    }

    @Test
    void createOrUpdatePost_アイキャッチの設定に失敗したら例外() {
        stubPostRoundTrip();
        ssh.whenFail("post meta update", "denied");

        SshOperationException e = assertThrows(SshOperationException.class, () -> operations.createOrUpdatePost(creds(),
                new PostContent("T", "s", "x", "publish", null, null, "77", null), null));
        assertTrue(e.getMessage().contains("アイキャッチ(featured media)の設定に失敗しました: denied"));
    }

    @Test
    void createOrUpdatePost_作成コマンドが失敗したら例外() {
        ssh.whenFail("post create", "denied");

        SshOperationException e = assertThrows(SshOperationException.class, () -> operations.createOrUpdatePost(creds(),
                new PostContent("T", "s", "x", "publish", null, null, null, null), null));
        assertTrue(e.getMessage().contains("作成/更新に失敗しました: denied"));
    }

    @Test
    void createOrUpdatePost_作成後の情報取得に失敗したら例外() {
        ssh.whenOk("post create", "10").whenFail("--fields=guid,post_status", "gone");

        SshOperationException e = assertThrows(SshOperationException.class, () -> operations.createOrUpdatePost(creds(),
                new PostContent("T", "s", "x", "publish", null, null, null, null), null));
        assertTrue(e.getMessage().contains("情報取得に失敗しました: gone"));
    }

    @Test
    void createOrUpdatePost_作成後の情報が空なら例外() {
        ssh.whenOk("post create", "10").whenOk("--fields=guid,post_status", "");

        SshOperationException e = assertThrows(SshOperationException.class, () -> operations.createOrUpdatePost(creds(),
                new PostContent("T", "s", "x", "publish", null, null, null, null), null));
        assertEquals("wp-cliの出力(JSON)が空です", e.getMessage());
    }

    @Test
    void createOrUpdatePost_作成後の情報がJSONとして不正なら例外() {
        ssh.whenOk("post create", "10").whenOk("--fields=guid,post_status", "{bad");

        SshOperationException e = assertThrows(SshOperationException.class, () -> operations.createOrUpdatePost(creds(),
                new PostContent("T", "s", "x", "publish", null, null, null, null), null));
        assertTrue(e.getMessage().contains("解析に失敗しました"));
    }

    @Test
    void deletePost_削除コマンドが失敗したら例外() {
        ssh.whenOk("--field=post_status", "publish").whenFail("post delete", "locked");

        SshOperationException e = assertThrows(SshOperationException.class, () -> operations.deletePost(creds(), "5"));
        assertEquals("WordPress投稿の削除に失敗しました: locked", e.getMessage());
    }

    // ---- 認証Cookie・ページ取得 ----

    @Test
    void generateAuthCookie_errorを含む応答_nameなし_valueなしはいずれもユーザー未検出の例外() {
        ssh.whenSeq("eval", FakeSshCommandExecutor.ok("{\"error\":\"user_not_found\"}"),
                FakeSshCommandExecutor.ok("{\"value\":\"v\"}"), FakeSshCommandExecutor.ok("{\"name\":\"n\"}"));

        for (int i = 0; i < 3; i++) {
            SshOperationException e = assertThrows(SshOperationException.class, () -> operations.generateAuthCookie(creds()));
            assertEquals("ユーザー 'admin' が見つかりません", e.getMessage());
        }
    }

    @Test
    void generateAuthCookie_正常応答はCookieを返す() {
        ssh.whenOk("eval", "{\"name\":\"n\",\"value\":\"v\"}");

        assertEquals(new AuthCookie("n", "v"), operations.generateAuthCookie(creds()));
    }

    private static String pageResponse(int status) {
        return "{\"status\":" + status + ",\"body_b64\":\"PGh0bWw+\"}";
    }

    private WordPressSshOperations.SiteFileLayout layout() {
        return new WordPressSshOperations.SiteFileLayout("https://example.com", "https://example.com",
                "https://example.com/wp-content", "/var/www/html/", "/var/www/html/wp-content");
    }

    @Test
    void fetchPageHtml_2xx以外のステータスは例外_2xxは本文を返す() {
        ssh.whenSeq("eval", FakeSshCommandExecutor.ok(pageResponse(199)), FakeSshCommandExecutor.ok(pageResponse(300)),
                FakeSshCommandExecutor.ok("{\"body_b64\":\"\"}"), FakeSshCommandExecutor.ok(pageResponse(200)));

        for (int i = 0; i < 3; i++) {
            assertThrows(SshOperationException.class,
                    () -> operations.fetchPageHtml(creds(), layout(), "https://example.com/p"));
        }
        assertEquals("<html>", operations.fetchPageHtml(creds(), layout(), "https://example.com/p"));
    }

    @Test
    void fetchPageHtml_本文が無い2xxは空文字を返す() {
        ssh.whenOk("eval", "{\"status\":204}");

        assertEquals("", operations.fetchPageHtml(creds(), layout(), "https://example.com/p"));
    }

    // ---- 一覧・更新・削除の失敗系 ----

    @Test
    void 一覧取得_更新_メディア操作が失敗したら操作ごとの例外() {
        ssh.whenFail("post list", "denied").whenFail("post update", "denied").whenFail("eval", "denied")
                .whenFail("--field=ID", "denied");

        assertTrue(assertThrows(SshOperationException.class, () -> operations.listPosts(creds(), "post"))
                .getMessage().startsWith("投稿/ページ一覧の取得に失敗しました"));
        assertTrue(assertThrows(SshOperationException.class, () -> operations.findPostIdsBySlug(creds(), "s"))
                .getMessage().startsWith("スラッグによる既存投稿の照会に失敗しました"));
        assertTrue(assertThrows(SshOperationException.class, () -> operations.updatePostStatus(creds(), "1", "draft"))
                .getMessage().startsWith("投稿/ページのステータス変更に失敗しました"));
        assertTrue(assertThrows(SshOperationException.class, () -> operations.listMedia(creds()))
                .getMessage().startsWith("メディア一覧の取得に失敗しました"));
        assertTrue(assertThrows(SshOperationException.class, () -> operations.scanMediaReferences(creds()))
                .getMessage().startsWith("メディア参照スキャンに失敗しました"));
        assertTrue(assertThrows(SshOperationException.class, () -> operations.deleteMedia(creds(), "1"))
                .getMessage().startsWith("メディアの存在確認に失敗しました"));
    }

    @Test
    void deleteMedia_削除コマンドが失敗したら例外() {
        ssh.whenOk("--field=ID", "5").whenFail("post delete", "locked");

        assertEquals("メディアの削除に失敗しました: locked",
                assertThrows(SshOperationException.class, () -> operations.deleteMedia(creds(), "5")).getMessage());
    }

    // ---- letsblog sns ----

    @Test
    void letsblogSns_標準入力が必要なコマンドは渡された標準入力をstdinで送る() {
        ssh.whenOk("letsblog", "done\n");

        assertEquals("done", operations.letsblogSns(creds(), LetsblogSnsCommand.CONFIG_SET, null, "{\"k\":\"v\"}"));

        String command = ssh.commands.get(0);
        assertTrue(command.endsWith("letsblog sns config set"), command);
        assertEquals("{\"k\":\"v\"}", new String(ssh.stdins.get(command)));
    }

    @Test
    void letsblogSns_標準入力が必要でも渡されなければstdinは送らない_不要なコマンドに渡されても送らない() {
        ssh.whenOk("letsblog", "");

        operations.letsblogSns(creds(), LetsblogSnsCommand.CONFIG_SET, null, null);
        operations.letsblogSns(creds(), LetsblogSnsCommand.STATUS, null, "ignored");

        assertTrue(ssh.stdins.isEmpty());
    }

    @Test
    void letsblogSns_コマンドごとのサブコマンドを組み立てる() {
        ssh.whenOk("letsblog", "");
        Map<LetsblogSnsCommand, String> expected = new LinkedHashMap<>();
        expected.put(LetsblogSnsCommand.CONFIG_CLEAR, "letsblog sns config clear x");
        expected.put(LetsblogSnsCommand.STATUS, "letsblog sns status");
        expected.put(LetsblogSnsCommand.TEST, "letsblog sns test x");
        expected.put(LetsblogSnsCommand.LOG, "letsblog sns log --format=json");
        expected.put(LetsblogSnsCommand.PV_CONFIG_SET, "letsblog pv config set");
        expected.put(LetsblogSnsCommand.PV_CONFIG_CLEAR, "letsblog pv config clear");
        expected.put(LetsblogSnsCommand.PV_STATUS, "letsblog pv status");
        expected.put(LetsblogSnsCommand.PV_RULES_SET, "letsblog pv rules set");
        expected.put(LetsblogSnsCommand.TEMPLATES_SET, "letsblog sns templates set");

        expected.forEach((command, subcommand) -> {
            ssh.commands.clear();
            operations.letsblogSns(creds(), command, "x", "{}");
            assertTrue(ssh.commands.get(0).endsWith(subcommand), command + ": " + ssh.commands.get(0));
        });
    }

    @Test
    void letsblogSns_設定削除はSNS名を省略するとすべて消す() {
        ssh.whenOk("letsblog", "");

        operations.letsblogSns(creds(), LetsblogSnsCommand.CONFIG_CLEAR, null, null);

        assertTrue(ssh.commands.get(0).endsWith("letsblog sns config clear"));
    }

    @Test
    void letsblogSns_SNS名の形式が不正なら例外_テスト投稿でSNS名が無くても例外() {
        assertThrows(IllegalArgumentException.class,
                () -> operations.letsblogSns(creds(), LetsblogSnsCommand.TEST, "X; rm", null));
        assertThrows(IllegalArgumentException.class,
                () -> operations.letsblogSns(creds(), LetsblogSnsCommand.CONFIG_CLEAR, "bad name", null));
        assertThrows(IllegalArgumentException.class,
                () -> operations.letsblogSns(creds(), LetsblogSnsCommand.TEST, null, null));
        assertTrue(ssh.commands.isEmpty());
    }

    @Test
    void letsblogSns_SNS名を使わないコマンドでは名前の形式を検証しない() {
        ssh.whenOk("letsblog", "ok");

        assertEquals("ok", operations.letsblogSns(creds(), LetsblogSnsCommand.STATUS, "X; rm", null));
    }

    @Test
    void letsblogSns_失敗したらstderrを含む例外で標準入力は含めない() {
        ssh.whenFail("letsblog", "token rejected");

        SshOperationException e = assertThrows(SshOperationException.class,
                () -> operations.letsblogSns(creds(), LetsblogSnsCommand.CONFIG_SET, null, "{\"secret\":\"s3cr3t\"}"));
        assertEquals("wp letsblog sns config-set の実行に失敗しました: token rejected", e.getMessage());
        assertFalse(e.getMessage().contains("s3cr3t"));
    }

    // ---- 接続情報・ファイル名・エラー文言 ----

    @Test
    void ポートが未設定なら22番で接続し_アップロード名のパス区切りと不正文字を除く() {
        WordPressCredentials noPort = new WordPressCredentials("https://example.com", "admin", "SSH", "h", null, "u",
                "/var/www/html", "KEY", null, null);
        ssh.whenOk("test -f", "").whenOk("media import", "5").whenOk("post get", "{\"guid\":\"g\"}")
                .whenOk("post meta update", "");

        operations.uploadMedia(noPort, "C:\\dir\\my pic.png", "image/png", new byte[]{1});
        operations.uploadMedia(noPort, "a/b/ok.png", "image/png", new byte[]{1});
        operations.uploadMedia(noPort, null, "image/png", new byte[]{1});

        List<String> paths = List.copyOf(ssh.uploaded.keySet());
        assertTrue(paths.get(0).endsWith("-my_pic.png"), paths.get(0));
        assertTrue(paths.get(1).endsWith("-ok.png"), paths.get(1));
        assertTrue(paths.get(2).endsWith("-upload"), paths.get(2));
    }

    private String versionFailureReason(SshCommandResult failure) {
        ssh.whenOk("echo ok", "ok").when("core version", failure);
        ConnectionCheckResult result = operations.testConnection(creds());
        String prefix = "wp core versionの実行に失敗しました: ";
        assertTrue(result.failureReason().startsWith(prefix));
        return result.failureReason().substring(prefix.length());
    }

    @Test
    void エラー表示はPHPの診断行を読み飛ばして最初の意味のある行を出す() {
        assertEquals("Error: real",
                versionFailureReason(new SshCommandResult(1, "", "PHP Warning:  x in /a.php on line 1\n\n  \nError: real\n", null)));
    }

    @Test
    void エラー表示はstderrが空ならstdoutから取る() {
        assertEquals("from stdout", versionFailureReason(new SshCommandResult(1, "from stdout", "  ", null)));
    }

    @Test
    void エラー表示はstderrがnullでもstdoutから取る() {
        assertEquals("from stdout", versionFailureReason(new SshCommandResult(1, "from stdout", null, null)));
    }

    @Test
    void エラー表示は全行がPHP診断行なら先頭行を出す() {
        assertEquals("Warning: a in /a.php on line 1", versionFailureReason(new SshCommandResult(1, "",
                "Warning: a in /a.php on line 1\nNotice: b in /b.php on line 2", null)));
    }

    @Test
    void エラー表示は診断行1行だけで改行が無くてもそのまま出す() {
        assertEquals("Warning: a in /a.php on line 1", versionFailureReason(new SshCommandResult(1, "",
                "Warning: a in /a.php on line 1", null)));
    }

    @Test
    void エラー表示は診断行だけのstderrの後に意味のあるstdoutがあればそれを出す() {
        assertEquals("real stdout", versionFailureReason(new SshCommandResult(1, "real stdout",
                "Warning: a in /a.php on line 1", null)));
    }

    @Test
    void エラー表示は出力がすべて空なら定型文を出す() {
        assertEquals("SSH経由のwp-cli実行に失敗しました", versionFailureReason(new SshCommandResult(1, "", "", null)));
    }

    @Test
    void エラー表示はstderrが空白でstdoutが診断行だけならstdoutの先頭行を出す() {
        assertEquals("Notice: a in /a.php on line 1", versionFailureReason(new SshCommandResult(1,
                "Notice: a in /a.php on line 1\nWarning: b in /b.php on line 2", "  ", null)));
    }

    @Test
    void エラー表示はstderrがnullでstdoutが診断行だけならstdoutの先頭行を出す() {
        assertEquals("Notice: a in /a.php on line 1", versionFailureReason(new SshCommandResult(1,
                "Notice: a in /a.php on line 1", null, null)));
    }
}
