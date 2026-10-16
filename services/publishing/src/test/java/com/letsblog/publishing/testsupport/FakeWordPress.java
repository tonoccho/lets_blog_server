package com.letsblog.publishing.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.letsblog.publishing.cms.FakeSshCommandExecutor;
import com.letsblog.publishing.cms.ssh.SshOperationException;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SSH境界の向こうにあるWordPress(wp-cli)の、状態を持つフェイク(issue #1815)。
 * {@link FakeSshCommandExecutor}を土台に、wp-cliのコマンドを解釈して投稿・メディア・ターム・ユーザー・
 * プラグイン・テーマの状態を更新・参照する。自前のクラス({@code WordPressAdapter}・{@code WordPressSshOperations})は
 * モックせず実物を使い、このフェイクへ実際のコマンド文字列を流す。
 *
 * <p>WordPressのインストールは(ホスト, wpPath)ごとに別の{@link Site}として持つ(local/test/production環境のように
 * 同じホストに複数のサイトが同居する場合も、別ホストの場合も表せる)。何も指定しなければ
 * {@link #DEFAULT_HOST}・{@link #DEFAULT_PATH}の{@link #site()}を使い、{@link #posts}などはその別名。
 *
 * <p>優先順位: ① {@link #when}系で登録した規則(失敗の注入や固定応答) → ② このクラスの解釈 → ③ 解釈できない
 * コマンドは終了ステータス1(登録漏れがテストの成功に化けない)。{@link #rejectHost}したホストへの接続は
 * {@link SshOperationException}(接続できない)になる。
 */
public final class FakeWordPress extends FakeSshCommandExecutor {

    public static final String DEFAULT_HOST = "ssh.example.com";
    public static final String DEFAULT_PATH = "/var/www/html";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern WP_PREFIX = Pattern.compile("^wp --path='([^']*)' (.*)$", Pattern.DOTALL);

    /** WordPressの投稿(post_type=postやpage)。 */
    public static final class Post {
        public String id;
        public String type = "post";
        public String title = "";
        public String slug = "";
        public String status = "draft";
        public String link;
        public byte[] content = new byte[0];
        public String categories;
        public String taxInput;
        public String author;
        public String thumbnailId;
        public String dateGmt;
    }

    /** WordPressのメディア(添付ファイル)。 */
    public static final class Media {
        public String id;
        public String url;
        public byte[] bytes;
        public String sha256;
        public String status = "inherit";
    }

    /** カテゴリ・タグ。 */
    public static final class Term {
        public String id;
        public String taxonomy;
        public String name;
        public String slug;
        public String parentId = "0";
        public String description = "";
    }

    /** 1つのWordPressインストール(ホスト+wpPath)。 */
    public static final class Site {
        public final Map<String, Post> posts = new LinkedHashMap<>();
        public final Map<String, Media> media = new LinkedHashMap<>();
        /** 「タクソノミー:名前」 → term_id。 */
        public final Map<String, String> terms = new LinkedHashMap<>();
        public final Map<String, Term> termsById = new LinkedHashMap<>();
        /** メールアドレス → ユーザーID。 */
        public final Map<String, String> users = new LinkedHashMap<>();
        /** プラグイン・テーマ名 → 状態(active / inactive)。 */
        public final Map<String, String> plugins = new LinkedHashMap<>();
        public final Map<String, String> themes = new LinkedHashMap<>();
        /** {@code wp letsblog status}の標準出力。nullなら「未導入」(letsblogコマンドが無い)。 */
        public String letsblogStatusOutput = "{\"plugin_version\":\"1.0.0\",\"protocol_version\":1}";
        private int nextId = 101;

        public Site nextId(int id) {
            this.nextId = id;
            return this;
        }

        /** 既存のカテゴリ・タグを置く。{@code taxonomy}は{@code category}または{@code post_tag}。 */
        public Term addTerm(String taxonomy, String id, String name, String slug, String parentId,
                String description) {
            Term term = new Term();
            term.id = id;
            term.taxonomy = taxonomy;
            term.name = name;
            term.slug = slug;
            term.parentId = parentId == null ? "0" : parentId;
            term.description = description == null ? "" : description;
            termsById.put(id, term);
            terms.put(taxonomy + ":" + name, id);
            return term;
        }

        /** 既存の投稿を置く。 */
        public Post addPost(String id, String slug, String status) {
            Post post = new Post();
            post.id = id;
            post.slug = slug;
            post.status = status;
            post.link = "https://example.com/?p=" + id;
            posts.put(id, post);
            return post;
        }

        /** 既存の投稿/ページを置く(post_type指定、タイトル付き)。 */
        public Post addPost(String id, String type, String title, String slug, String status) {
            Post post = addPost(id, slug, status);
            post.type = type;
            post.title = title;
            return post;
        }

        /** 既存のメディアを置く。 */
        public Media addMedia(String id, String url, byte[] bytes) {
            Media m = new Media();
            m.id = id;
            m.url = url;
            m.bytes = bytes;
            m.sha256 = bytes == null ? null : com.letsblog.publishing.cms.MediaContentHash.sha256Hex(bytes);
            media.put(id, m);
            return m;
        }

        /** 保存した投稿本文(HTML)。 */
        public String contentOf(String postId) {
            return new String(posts.get(postId).content, StandardCharsets.UTF_8);
        }

        private String newId() {
            return String.valueOf(nextId++);
        }
    }

    private final Map<String, Site> sites = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> rejectedHosts = new LinkedHashSet<>();
    private final Site defaultSite = site(DEFAULT_HOST, DEFAULT_PATH);

    // 既定のサイトの別名(最初から使えるように)
    public final Map<String, Post> posts = defaultSite.posts;
    public final Map<String, Media> media = defaultSite.media;
    public final Map<String, String> terms = defaultSite.terms;
    public final Map<String, String> users = defaultSite.users;
    /** 配置済み(putFileされた)リモートファイル。ホストを問わずパスで引く。 */
    public final Map<String, byte[]> files = new LinkedHashMap<>();

    /** 既定のサイト({@link #DEFAULT_HOST}・{@link #DEFAULT_PATH})。 */
    public Site site() {
        return defaultSite;
    }

    /** 指定のホスト・wpPathのサイト。無ければ作る。 */
    public Site site(String host, String wpPath) {
        return sites.computeIfAbsent(host + "|" + wpPath, k -> new Site());
    }

    /** このホストへは接続できない(SSHの接続失敗)ことにする。 */
    public FakeWordPress rejectHost(String host) {
        rejectedHosts.add(host);
        return this;
    }

    /** 既定のサイトに既存の投稿を置く。 */
    public Post addPost(String id, String slug, String status) {
        return defaultSite.addPost(id, slug, status);
    }

    /** 既定のサイトに既存のメディアを置く。 */
    public Media addMedia(String id, String url, byte[] bytes) {
        return defaultSite.addMedia(id, url, bytes);
    }

    /** 既定のサイトの投稿本文。 */
    public String contentOf(String postId) {
        return defaultSite.contentOf(postId);
    }

    /** コマンドを実行する直前に呼ぶ処理(複数環境への並列実行が重なることの観測や、実行の遅延に使う)。 */
    public FakeWordPress onCommand(String needle, java.util.function.Consumer<SshConnectionParams> hook) {
        hooks.put(needle, hook);
        return this;
    }

    /** ファイル転送(SFTP)が、想定外の実行時例外で失敗することにする。 */
    public FakeWordPress failPutFile(RuntimeException failure) {
        this.putFileFailure = failure;
        return this;
    }

    private final Map<String, java.util.function.Consumer<SshConnectionParams>> hooks = new LinkedHashMap<>();
    private volatile RuntimeException putFileFailure;
    /** {@link #files}に無いパスをダウンロードしたときに返すバイト列(リモートで作られる一時ファイルのパスは毎回違うため)。 */
    public volatile byte[] downloadFallback;

    private void runHooks(SshConnectionParams params, String command) {
        hooks.forEach((needle, hook) -> {
            if (command.contains(needle)) {
                hook.accept(params);
            }
        });
    }

    // ---- SshCommandExecutor ----
    // 環境ごとの並列実行から同時に呼ばれうるので、状態を触る部分は排他する(フックは排他の外で呼ぶ)。

    @Override
    public SshCommandResult exec(SshConnectionParams params, String command, byte[] stdin) {
        connect(params);
        runHooks(params, command);
        synchronized (this) {
            commands.add(command);
            if (stdin != null) {
                stdins.put(command, stdin);
            }
            return run(params, command, stdin);
        }
    }

    /** {@link #execAll}の呼び出し(1回の接続でまとめて実行した単位)ごとの、実行したコマンド。 */
    public final List<List<String>> batches = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public List<SshCommandResult> execAll(SshConnectionParams params, List<String> commandList) {
        connect(params);
        batches.add(List.copyOf(commandList));
        List<SshCommandResult> results = new ArrayList<>();
        for (String command : commandList) {
            runHooks(params, command);
            synchronized (this) {
                commands.add(command);
                results.add(run(params, command, null));
            }
        }
        return results;
    }

    @Override
    public synchronized void putFile(SshConnectionParams params, byte[] data, String remotePath) {
        connect(params);
        if (putFileFailure != null) {
            throw putFileFailure;
        }
        super.putFile(params, data, remotePath);
        files.put(remotePath, data);
    }

    @Override
    public synchronized byte[] getFile(SshConnectionParams params, String remotePath) {
        connect(params);
        byte[] found = files.get(remotePath);
        return found != null ? found : downloadFallback;
    }

    @Override
    public synchronized void removeFile(SshConnectionParams params, String remotePath) {
        super.removeFile(params, remotePath);
        files.remove(remotePath);
    }

    private void connect(SshConnectionParams params) {
        if (rejectedHosts.contains(params.host())) {
            throw new SshOperationException("SSH接続に失敗しました: " + params.host());
        }
    }

    // ---- 解釈 ----

    private SshCommandResult run(SshConnectionParams params, String command, byte[] stdin) {
        SshCommandResult overridden = matchRule(command);
        if (overridden != null) {
            return overridden;
        }
        if (command.equals("echo ok")) {
            return ok("ok");
        }
        if (command.startsWith("test -f ")) {
            return files.containsKey(unquote(command.substring("test -f ".length()))) ? ok("") : fail("");
        }
        if (command.startsWith("mkdir -p ") || command.startsWith("rm -f ")) {
            return ok("");
        }
        Matcher wp = WP_PREFIX.matcher(command);
        if (!wp.matches()) {
            return fail("FakeWordPress: 解釈できないコマンドです: " + command);
        }
        Site site = site(params.host(), wp.group(1));
        String sub = wp.group(2);
        SshCommandResult result = runWp(site, sub, stdin);
        return result != null ? result : fail("FakeWordPress: 解釈できないwp-cliコマンドです: " + command);
    }

    private SshCommandResult runWp(Site site, String sub, byte[] stdin) {
        if (sub.equals("core version")) {
            return ok("6.4.2");
        }
        if (sub.equals("letsblog status")) {
            return site.letsblogStatusOutput == null
                    ? fail("Error: 'letsblog' is not a registered wp command.") : ok(site.letsblogStatusOutput);
        }
        if (sub.equals("plugin activate letsblog")) {
            site.letsblogStatusOutput = "{\"plugin_version\":\"1.0.0\",\"protocol_version\":1}";
            return ok("Plugin 'letsblog' activated.");
        }
        if (sub.startsWith("post get ")) {
            return postGet(site, sub);
        }
        if (sub.startsWith("post create - ")) {
            return postCreate(site, sub, stdin);
        }
        if (sub.startsWith("post update ")) {
            return postUpdate(site, sub, stdin);
        }
        if (sub.startsWith("post delete ")) {
            return postDelete(site, sub);
        }
        if (sub.startsWith("post meta update ")) {
            return postMetaUpdate(site, sub);
        }
        if (sub.startsWith("post list ")) {
            return postList(site, sub);
        }
        if (sub.startsWith("media import ")) {
            return mediaImport(site, sub);
        }
        if (sub.startsWith("term list ")) {
            return termList(site, sub);
        }
        if (sub.startsWith("term create ")) {
            return termCreate(site, sub);
        }
        if (sub.startsWith("term update ")) {
            return termUpdate(site, sub);
        }
        if (sub.startsWith("term delete ")) {
            return termDelete(site, sub);
        }
        if (sub.startsWith("user list ")) {
            return userList(site, sub);
        }
        if (sub.startsWith("user create ")) {
            return userCreate(site, sub);
        }
        if (sub.startsWith("user update ")) {
            return ok("Success: Updated user " + quotedArgs(sub).get(0) + ".");
        }
        if (sub.startsWith("'plugin' ") || sub.startsWith("plugin ")) {
            return extension(site.plugins, "plugin", sub);
        }
        if (sub.startsWith("'theme' ") || sub.startsWith("theme ")) {
            return extension(site.themes, "theme", sub);
        }
        if (sub.startsWith("eval ") && sub.contains("_letsblog_sha256")) {
            return findMediaBySha(site, sub);
        }
        return null;
    }

    // ---- 投稿 ----

    private SshCommandResult postGet(Site site, String sub) {
        String id = quotedArgs(sub).get(0);
        Post post = site.posts.get(id);
        Media m = site.media.get(id);
        if (post == null && m == null) {
            return fail("Error: Invalid post ID.");
        }
        if (sub.endsWith("--field=ID")) {
            return ok(id);
        }
        String status = post != null ? post.status : m.status;
        if (sub.endsWith("--field=post_status")) {
            return ok(status);
        }
        String guid = post != null ? post.link : m.url;
        ObjectNode node = MAPPER.createObjectNode().put("guid", guid);
        if (sub.contains("--fields=guid,post_status")) {
            node.put("post_status", status);
        }
        return ok(node.toString());
    }

    private SshCommandResult postCreate(Site site, String sub, byte[] stdin) {
        Post post = new Post();
        post.id = site.newId();
        post.link = "https://example.com/?p=" + post.id;
        applyFields(post, sub, stdin);
        site.posts.put(post.id, post);
        return ok(post.id);
    }

    private SshCommandResult postUpdate(Site site, String sub, byte[] stdin) {
        String id = quotedArgs(sub).get(0);
        Post post = site.posts.get(id);
        if (post == null) {
            return fail("Error: Invalid post ID.");
        }
        applyFields(post, sub, stdin);
        return ok(sub.contains("--porcelain") ? id : "Success: Updated post " + id + ".");
    }

    private void applyFields(Post post, String sub, byte[] stdin) {
        if (stdin != null) {
            post.content = stdin;
        }
        String title = option(sub, "post_title");
        if (title != null) {
            post.title = title;
        }
        String status = option(sub, "post_status");
        if (status != null) {
            post.status = status;
        }
        String name = option(sub, "post_name");
        if (name != null) {
            post.slug = name;
        }
        String category = option(sub, "post_category");
        if (category != null) {
            post.categories = category;
        }
        String taxInput = option(sub, "tax_input");
        if (taxInput != null) {
            post.taxInput = taxInput;
        }
        String author = option(sub, "post_author");
        if (author != null) {
            post.author = author;
        }
        String dateGmt = option(sub, "post_date_gmt");
        if (dateGmt != null) {
            post.dateGmt = dateGmt;
        }
    }

    private SshCommandResult postDelete(Site site, String sub) {
        String id = quotedArgs(sub).get(0);
        boolean force = sub.endsWith("--force");
        Post post = site.posts.get(id);
        if (post != null) {
            if (force || "trash".equals(post.status)) {
                site.posts.remove(id);
            } else {
                post.status = "trash";
            }
            return ok("Success: Trashed post " + id + ".");
        }
        if (site.media.remove(id) != null) {
            return ok("Success: Deleted post " + id + ".");
        }
        return fail("Error: Invalid post ID.");
    }

    private SshCommandResult postMetaUpdate(Site site, String sub) {
        List<String> args = quotedArgs(sub);
        String id = args.get(0);
        if (args.size() >= 3 && "_letsblog_sha256".equals(args.get(1)) && site.media.containsKey(id)) {
            site.media.get(id).sha256 = args.get(2);
        }
        // _thumbnail_idだけはクォートされずに渡される(`post meta update 'ID' _thumbnail_id 'mediaId'`)。
        if (sub.contains(" _thumbnail_id ") && args.size() >= 2 && site.posts.containsKey(id)) {
            site.posts.get(id).thumbnailId = args.get(1);
        }
        return ok("Success: Updated custom field.");
    }

    private SshCommandResult postList(Site site, String sub) {
        ArrayNode array = MAPPER.createArrayNode();
        String postType = optionQuoted(sub, "post_type");
        if (sub.contains("--name=")) {
            String slug = optionQuoted(sub, "name");
            for (Post post : site.posts.values()) {
                if (post.type.equals(postType) && post.slug.equals(slug) && !"trash".equals(post.status)) {
                    array.addObject().put("ID", Integer.parseInt(post.id)).put("post_name", post.slug);
                }
            }
            return ok(array.toString());
        }
        if ("attachment".equals(postType)) {
            for (Media m : site.media.values()) {
                array.addObject().put("ID", Integer.parseInt(m.id)).put("guid", m.url).put("post_title", m.id)
                        .put("post_mime_type", "image/png").put("post_date", "2026-01-01 00:00:00");
            }
            return ok(array.toString());
        }
        for (Post post : site.posts.values()) {
            if (post.type.equals(postType)) {
                array.addObject().put("ID", Integer.parseInt(post.id)).put("post_title", post.title)
                        .put("post_name", post.slug).put("post_status", post.status);
            }
        }
        return ok(array.toString());
    }

    private SshCommandResult mediaImport(Site site, String sub) {
        String remotePath = quotedArgs(sub).get(0);
        byte[] bytes = files.get(remotePath);
        if (bytes == null) {
            return fail("Error: Unable to import file " + remotePath + ". Reason: File doesn't exist.");
        }
        Media m = new Media();
        m.id = site.newId();
        String filename = remotePath.substring(remotePath.lastIndexOf('/') + 1);
        m.url = "https://example.com/wp-content/uploads/" + m.id + "-" + filename;
        m.bytes = bytes;
        site.media.put(m.id, m);
        return ok(m.id);
    }

    // ---- カテゴリ・タグ ----

    private SshCommandResult termList(Site site, String sub) {
        String taxonomy = quotedArgs(sub).get(0);
        String search = optionQuoted(sub, "search");
        ArrayNode array = MAPPER.createArrayNode();
        for (Term term : site.termsById.values()) {
            if (!term.taxonomy.equals(taxonomy)) {
                continue;
            }
            if (sub.contains("--fields=term_id,name,slug,parent,description")) {
                array.addObject().put("term_id", Integer.parseInt(term.id)).put("name", term.name)
                        .put("slug", term.slug).put("parent", Integer.parseInt(term.parentId))
                        .put("description", term.description);
            } else if (search == null || term.name.toLowerCase().contains(search.toLowerCase())) {
                array.addObject().put("name", term.name).put("term_id", Integer.parseInt(term.id));
            }
        }
        return ok(array.toString());
    }

    private SshCommandResult termCreate(Site site, String sub) {
        List<String> args = quotedArgs(sub);
        String taxonomy = args.get(0);
        String name = args.get(1);
        String slug = option(sub, "slug");
        Term term = site.addTerm(taxonomy, site.newId(), name,
                slug != null ? slug : name.toLowerCase().replace(' ', '-'),
                option(sub, "parent"), option(sub, "description"));
        return ok(term.id);
    }

    private SshCommandResult termUpdate(Site site, String sub) {
        List<String> args = quotedArgs(sub);
        Term term = site.termsById.get(args.get(1));
        if (term == null) {
            return fail("Error: Term doesn't exist.");
        }
        site.terms.remove(term.taxonomy + ":" + term.name);
        String name = option(sub, "name");
        if (name != null) {
            term.name = name;
        }
        String slug = option(sub, "slug");
        if (slug != null) {
            term.slug = slug;
        }
        String parent = option(sub, "parent");
        term.parentId = parent == null ? "0" : parent;
        String description = option(sub, "description");
        if (description != null) {
            term.description = description;
        }
        site.terms.put(term.taxonomy + ":" + term.name, term.id);
        return ok("Success: Term updated.");
    }

    private SshCommandResult termDelete(Site site, String sub) {
        List<String> args = quotedArgs(sub);
        Term term = site.termsById.remove(args.get(1));
        if (term == null) {
            return fail("Error: Term doesn't exist.");
        }
        site.terms.remove(term.taxonomy + ":" + term.name);
        return ok("Success: Deleted term " + term.id + ".");
    }

    // ---- ユーザー ----

    private SshCommandResult userList(Site site, String sub) {
        String email = optionQuoted(sub, "search");
        ArrayNode array = MAPPER.createArrayNode();
        String id = site.users.get(email);
        if (id != null) {
            array.addObject().put("ID", Integer.parseInt(id));
        }
        return ok(array.toString());
    }

    private SshCommandResult userCreate(Site site, String sub) {
        List<String> args = quotedArgs(sub);
        String id = site.newId();
        site.users.put(args.get(1), id);
        return ok(id);
    }

    // ---- プラグイン・テーマ ----

    private SshCommandResult extension(Map<String, String> installed, String type, String sub) {
        String rest = sub.startsWith("'" + type + "' ") ? sub.substring(type.length() + 3) : sub.substring(type.length() + 1);
        if (rest.startsWith("list ")) {
            ArrayNode array = MAPPER.createArrayNode();
            installed.forEach((name, status) -> array.addObject().put("name", name).put("status", status));
            return ok(array.toString());
        }
        List<String> args = quotedArgs(rest);
        if (rest.startsWith("install ")) {
            String target = args.get(0);
            if (rest.contains("--force") && target.endsWith(".zip")) {
                String file = target.substring(target.lastIndexOf('/') + 1);
                // /tmp/letsblog-bulk-<uuid>-<元のファイル名>.zip の元のファイル名(拡張子なし)を名前にする。
                String name = file.replaceFirst("^letsblog-bulk-[0-9a-f-]{36}-", "").replaceFirst("\\.zip$", "");
                installed.put(name, "inactive");
            } else {
                installed.put(target, "inactive");
            }
            return ok("Success: Installed 1 of 1 " + type + "s.");
        }
        String name = args.get(0);
        if (!installed.containsKey(name)) {
            return fail("Error: The '" + name + "' " + type + " could not be found.");
        }
        if (rest.startsWith("activate ")) {
            installed.put(name, "active");
            return ok("Success: Activated.");
        }
        if (rest.startsWith("deactivate ")) {
            installed.put(name, "inactive");
            return ok("Success: Deactivated.");
        }
        if (rest.startsWith("delete ")) {
            installed.remove(name);
            return ok("Success: Deleted.");
        }
        return null;
    }

    private SshCommandResult findMediaBySha(Site site, String sub) {
        Matcher hashes = Pattern.compile("\\$h = \\[([^\\]]*)\\]").matcher(sub);
        List<String> wanted = new ArrayList<>();
        if (hashes.find()) {
            Matcher each = Pattern.compile("'([0-9a-f]{64})'").matcher(hashes.group(1));
            while (each.find()) {
                wanted.add(each.group(1));
            }
        }
        ArrayNode array = MAPPER.createArrayNode();
        for (Media m : site.media.values()) {
            if (m.sha256 != null && wanted.contains(m.sha256)) {
                array.addObject().put("id", m.id).put("guid", m.url).put("sha256", m.sha256);
            }
        }
        return ok(array.toString());
    }

    // ---- 文字列の取り出し ----

    /** シングルクォートで囲まれた引数を出現順に取り出す({@code '\''}は{@code '}に戻す)。 */
    private static List<String> quotedArgs(String sub) {
        List<String> result = new ArrayList<>();
        Matcher m = Pattern.compile("'((?:[^']|'\\\\'')*)'").matcher(sub);
        while (m.find()) {
            result.add(m.group(1).replace("'\\''", "'"));
        }
        return result;
    }

    /** {@code --name='value'}の値。無ければnull。 */
    private static String option(String sub, String name) {
        return optionQuoted(sub, name);
    }

    private static String optionQuoted(String sub, String name) {
        Matcher m = Pattern.compile("--" + name + "=(?:'((?:[^']|'\\\\'')*)'|(\\S+))").matcher(sub);
        if (!m.find()) {
            return null;
        }
        return m.group(1) != null ? m.group(1).replace("'\\''", "'") : m.group(2);
    }

    private static String unquote(String quoted) {
        String s = quoted.strip();
        if (s.length() >= 2 && s.startsWith("'") && s.endsWith("'")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("'\\''", "'");
    }
}
