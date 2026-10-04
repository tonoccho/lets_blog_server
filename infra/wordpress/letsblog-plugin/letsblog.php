<?php
/**
 * Plugin Name: Lets Blog
 * Description: Lets Blog とやり取りするためのプラグイン。通信は wp-cli(wp letsblog)だけで行い、REST API のルートは登録しない。
 * Version: 1.0.0
 * License: GPL-2.0-or-later
 * License URI: https://www.gnu.org/licenses/gpl-2.0.html
 * Text Domain: letsblog
 */

if (!defined('ABSPATH') && !defined('LETSBLOG_PLUGIN_TESTING')) {
    exit;
}

/** プラグイン自体のバージョン(上のプラグインヘッダの Version と揃える)。 */
const LETSBLOG_PLUGIN_VERSION = '1.0.0';

/** アプリとプラグインのやり取りの取り決めのバージョン。互換性のない変更をしたときだけ上げる。 */
const LETSBLOG_PROTOCOL_VERSION = 1;

/** 受け取った内容(JSON)とそのハッシュを保存する WordPress の option 名。 */
const LETSBLOG_OPTION_SYNC_PAYLOAD = 'letsblog_sync_payload';
const LETSBLOG_OPTION_SYNC_HASH = 'letsblog_sync_hash';

/** 署名付きプレビュー(issue #1561)。URL のクエリ名・一時データの名前・期限切れを掃除するための索引。 */
const LETSBLOG_PREVIEW_QUERY_VAR = 'letsblog_preview';
const LETSBLOG_PREVIEW_TRANSIENT_PREFIX = 'letsblog_preview_';
const LETSBLOG_OPTION_PREVIEW_INDEX = 'letsblog_preview_index';
/** 有効期限の規定値と上限(秒)。 */
const LETSBLOG_PREVIEW_DEFAULT_TTL = 900;
const LETSBLOG_PREVIEW_MAX_TTL = 86400;
/**
 * 表示用の仮の投稿 ID。wp_posts の行ではない。WordPress は 0 以下の ID を投稿として扱わない(get_post が失敗する)ため、
 * 正の値で、実在の投稿の ID が届かない大きな値にする。表示中は ID だけでなくオブジェクトキャッシュにも載せ、DB は引かない。
 */
const LETSBLOG_PREVIEW_POST_ID = 2147481561;

/** 署名の対象は「id.期限」。鍵は WordPress の salt なので、サイトごとに異なり外から推測できない。 */
function letsblog_preview_sign(string $id, int $expires): string
{
    return hash_hmac('sha256', $id . '.' . $expires, wp_salt('auth'));
}

/** 発行済みプレビューの索引(id => 期限)。期限切れの一時データを開かれなくても消すために持つ。 */
function letsblog_preview_index(): array
{
    $index = get_option(LETSBLOG_OPTION_PREVIEW_INDEX, []);
    return is_array($index) ? $index : [];
}

/** 1件分の一時データと索引の項目を消す。 */
function letsblog_preview_forget(string $id): void
{
    delete_transient(LETSBLOG_PREVIEW_TRANSIENT_PREFIX . $id);
    $index = letsblog_preview_index();
    if (array_key_exists($id, $index)) {
        unset($index[$id]);
        update_option(LETSBLOG_OPTION_PREVIEW_INDEX, $index, false);
    }
}

/** 期限が切れた一時データをすべて消す。 */
function letsblog_preview_purge(int $now): void
{
    foreach (letsblog_preview_index() as $id => $expires) {
        if ((int) $expires <= $now) {
            letsblog_preview_forget((string) $id);
        }
    }
}

/**
 * 内容を一時データとして保存し、署名付きトークンを含む URL を返す。投稿(wp_posts)は作らない。
 *
 * @return array{url: string, expires_at: int, token: string}
 */
function letsblog_preview_issue(array $data, int $ttl, int $now): array
{
    letsblog_preview_purge($now);
    $id = bin2hex(random_bytes(16));
    $expires = $now + $ttl;
    set_transient(LETSBLOG_PREVIEW_TRANSIENT_PREFIX . $id, $data, $ttl);
    $index = letsblog_preview_index();
    $index[$id] = $expires;
    update_option(LETSBLOG_OPTION_PREVIEW_INDEX, $index, false);
    $token = $id . '.' . $expires . '.' . letsblog_preview_sign($id, $expires);
    return [
        'url' => add_query_arg(LETSBLOG_PREVIEW_QUERY_VAR, $token, home_url('/')),
        'expires_at' => $expires,
        'token' => $token,
    ];
}

/**
 * トークンを検証して内容を返す。形式・署名が不正なら 403、期限切れ・保存されていなければ 404(本文は返さない)。
 * 期限切れや保存されていないトークンを見つけたときは、残っている一時データも消す。
 *
 * @return array{status: int, data: ?array}
 */
function letsblog_preview_resolve(string $token, int $now): array
{
    if (preg_match('/^([0-9a-f]{32})\.(\d{1,12})\.([0-9a-f]{64})$/', $token, $m) !== 1) {
        return ['status' => 403, 'data' => null];
    }
    [, $id, $expiresText, $signature] = $m;
    $expires = (int) $expiresText;
    if (!hash_equals(letsblog_preview_sign($id, $expires), $signature)) {
        return ['status' => 403, 'data' => null];
    }
    if ($expires <= $now) {
        letsblog_preview_forget($id);
        return ['status' => 404, 'data' => null];
    }
    $data = get_transient(LETSBLOG_PREVIEW_TRANSIENT_PREFIX . $id);
    if (!is_array($data)) {
        letsblog_preview_forget($id);
        return ['status' => 404, 'data' => null];
    }
    return ['status' => 200, 'data' => $data];
}

/** アイキャッチとして受け付けるのは、画像の data URI か http(s) の URL だけ(HTML に埋め込むため)。 */
function letsblog_preview_is_safe_image(string $value): bool
{
    return preg_match('#^data:image/(png|jpeg|gif|webp);base64,[A-Za-z0-9+/=]+$#', $value) === 1
        || preg_match('#^https?://[^\s"\'<>]+$#', $value) === 1;
}

/** @return array{0: ?array, 1: ?string} [正規化した内容, エラー] */
function letsblog_preview_normalize(array $decoded): array
{
    $title = $decoded['title'] ?? null;
    $content = $decoded['content'] ?? null;
    if (!is_string($title) || trim($title) === '') {
        return [null, 'title(タイトル)が必要です'];
    }
    if (!is_string($content)) {
        return [null, 'content(本文 HTML)が必要です'];
    }
    $lists = [];
    foreach (['categories', 'tags'] as $key) {
        $value = $decoded[$key] ?? [];
        if (!is_array($value) || !array_is_list($value) || count(array_filter($value, 'is_string')) !== count($value)) {
            return [null, "{$key} は文字列の配列にしてください"];
        }
        $lists[$key] = $value;
    }
    $image = $decoded['featured_image'] ?? null;
    if ($image !== null && $image !== '') {
        if (!is_string($image) || !letsblog_preview_is_safe_image($image)) {
            return [null, 'featured_image は画像の data URI か http(s) の URL にしてください'];
        }
    } else {
        $image = null;
    }
    return [[
        'title' => $title,
        'content' => $content,
        'categories' => $lists['categories'],
        'tags' => $lists['tags'],
        'featured_image' => $image,
    ], null];
}

// ---- 署名付きプレビューの表示(テーマの単一記事テンプレートで。投稿は作らない) ----

/** このリクエストで表示するプレビューの内容。検証に成功したときだけ入る。 */
function letsblog_preview_current(?array $set = null): ?array
{
    static $current = null;
    if ($set !== null) {
        $current = $set;
    }
    return $current;
}

function letsblog_preview_is_active(): bool
{
    return letsblog_preview_current() !== null;
}

function letsblog_preview_matches_post($post_id): bool
{
    return letsblog_preview_is_active() && (int) $post_id === LETSBLOG_PREVIEW_POST_ID;
}

function letsblog_preview_on_parse_request($wp): void
{
    $token = $_GET[LETSBLOG_PREVIEW_QUERY_VAR] ?? null;
    if (!is_string($token)) {
        return;
    }
    $result = letsblog_preview_resolve(wp_unslash($token), time());
    nocache_headers();
    header('X-Robots-Tag: noindex, nofollow, noarchive');
    if ($result['status'] !== 200) {
        $message = $result['status'] === 403
            ? 'プレビューの URL が正しくありません。'
            : 'プレビューの期限が切れているか、見つかりません。';
        wp_die($message, '', ['response' => $result['status']]);
        return;
    }
    letsblog_preview_current($result['data']);
}

function letsblog_preview_build_post(array $data): WP_Post
{
    $now = current_time('mysql');
    $gmt = current_time('mysql', true);
    return new WP_Post((object) [
        'ID' => LETSBLOG_PREVIEW_POST_ID,
        'post_author' => 0,
        'post_date' => $now,
        'post_date_gmt' => $gmt,
        'post_content' => $data['content'],
        'post_title' => $data['title'],
        'post_excerpt' => '',
        'post_status' => 'publish',
        'comment_status' => 'closed',
        'ping_status' => 'closed',
        'post_password' => '',
        'post_name' => 'letsblog-preview',
        'to_ping' => '',
        'pinged' => '',
        'post_modified' => $now,
        'post_modified_gmt' => $gmt,
        'post_content_filtered' => '',
        'post_parent' => 0,
        'guid' => home_url('/'),
        'menu_order' => 0,
        'post_type' => 'post',
        'post_mime_type' => '',
        'comment_count' => 0,
        'filter' => 'raw',
    ]);
}

/** メインクエリを、DB を読まずに「仮の1件の単一記事」へ差し替える。 */
function letsblog_preview_posts_pre_query($posts, $query)
{
    $data = letsblog_preview_current();
    if ($data === null || !$query->is_main_query()) {
        return $posts;
    }
    $post = letsblog_preview_build_post($data);
    $query->is_single = true;
    $query->is_singular = true;
    $query->is_home = false;
    $query->is_archive = false;
    $query->is_404 = false;
    $query->queried_object = $post;
    $query->queried_object_id = $post->ID;
    // テーマが get_post(仮の ID) や get_the_terms(仮の ID) で引いても DB を読まず、この仮の投稿が返るようにする。
    wp_cache_set($post->ID, $post, 'posts', 60);
    $query->found_posts = 1;
    $query->max_num_pages = 1;
    status_header(200);
    return [$post];
}

function letsblog_preview_template_include($template)
{
    if (!letsblog_preview_is_active()) {
        return $template;
    }
    // 単一記事テンプレートが決まらなかった場合だけ、テーマの index に任せる。
    return is_string($template) && $template !== '' ? $template : get_index_template();
}

function letsblog_preview_robots($robots)
{
    return letsblog_preview_is_active()
        ? array_merge((array) $robots, ['noindex' => true, 'nofollow' => true, 'noarchive' => true])
        : $robots;
}

function letsblog_preview_terms($terms, $post_id, $taxonomy)
{
    if (!letsblog_preview_matches_post($post_id) || !in_array($taxonomy, ['category', 'post_tag'], true)) {
        return $terms;
    }
    $data = letsblog_preview_current();
    $names = $taxonomy === 'category' ? $data['categories'] : $data['tags'];
    $result = [];
    foreach (array_values($names) as $i => $name) {
        $id = -(($taxonomy === 'category' ? 1000 : 2000) + $i + 1);
        $result[] = new WP_Term((object) [
            'term_id' => $id,
            'name' => $name,
            'slug' => sanitize_title($name),
            'term_group' => 0,
            'term_taxonomy_id' => $id,
            'taxonomy' => $taxonomy,
            'description' => '',
            'parent' => 0,
            'count' => 1,
            'filter' => 'raw',
        ]);
    }
    return $result === [] ? $terms : $result;
}

function letsblog_preview_thumbnail_meta($value, $object_id, $meta_key)
{
    if ($meta_key === '_thumbnail_id' && letsblog_preview_matches_post($object_id)
        && (letsblog_preview_current()['featured_image'] ?? null) !== null) {
        return LETSBLOG_PREVIEW_POST_ID;
    }
    return $value;
}

function letsblog_preview_thumbnail_html($html, $post_id)
{
    if (!letsblog_preview_matches_post($post_id)) {
        return $html;
    }
    $image = letsblog_preview_current()['featured_image'] ?? null;
    return $image === null ? $html : '<img src="' . esc_attr($image) . '" alt="" class="wp-post-image" />';
}

function letsblog_preview_permalink($url, $post_id = 0)
{
    return letsblog_preview_matches_post($post_id) ? home_url('/') : $url;
}

function letsblog_preview_comments_open($open, $post_id)
{
    return letsblog_preview_matches_post($post_id) ? false : $open;
}

if (function_exists('add_action') && function_exists('add_filter')) {
    add_action('parse_request', 'letsblog_preview_on_parse_request');
    add_filter('posts_pre_query', 'letsblog_preview_posts_pre_query', 10, 2);
    add_filter('template_include', 'letsblog_preview_template_include', 99);
    add_filter('wp_robots', 'letsblog_preview_robots');
    add_filter('redirect_canonical', fn($redirect) => letsblog_preview_is_active() ? false : $redirect);
    add_filter('get_the_terms', 'letsblog_preview_terms', 10, 3);
    add_filter('get_post_metadata', 'letsblog_preview_thumbnail_meta', 10, 3);
    add_filter('post_thumbnail_html', 'letsblog_preview_thumbnail_html', 10, 2);
    add_filter('post_link', 'letsblog_preview_permalink', 10, 2);
    add_filter('comments_open', 'letsblog_preview_comments_open', 10, 2);
    add_filter('pings_open', 'letsblog_preview_comments_open', 10, 2);
}

if (defined('WP_CLI') && WP_CLI) {
    /**
     * Lets Blog 用の wp-cli コマンド。
     */
    class Letsblog_CLI_Command
    {
        /**
         * プラグインのバージョンとプロトコルのバージョンを JSON で返す。
         *
         * ## EXAMPLES
         *
         *     wp letsblog status
         */
        public function status($args, $assoc_args)
        {
            WP_CLI::line(json_encode([
                'plugin_version' => LETSBLOG_PLUGIN_VERSION,
                'protocol_version' => LETSBLOG_PROTOCOL_VERSION,
                'sync_hash' => get_option(LETSBLOG_OPTION_SYNC_HASH, null),
            ]));
        }

        /**
         * Lets Blog から受け取ったタグ定義・統合 CSS・プレフィックス等(JSON)を DB に保存する。
         * 記事の表示時は保存済みの内容だけを使い、Lets Blog サーバーとは通信しない。
         *
         * ## OPTIONS
         *
         * --file=<path>
         * : 内容(JSON オブジェクト)を書いたファイル。
         *
         * [--hash=<sha256>]
         * : 内容の SHA-256(16進)。指定したとき、受け取った内容と食い違えば保存せず失敗する。
         *
         * ## EXAMPLES
         *
         *     wp letsblog sync --file=/tmp/payload.json --hash=3b4c...
         */
        public function sync($args, $assoc_args)
        {
            $file = $assoc_args['file'] ?? null;
            if (!is_string($file) || $file === '') {
                WP_CLI::error('--file を指定してください');
                return;
            }
            $payload = is_readable($file) ? file_get_contents($file) : false;
            if (!is_string($payload)) {
                WP_CLI::error("ファイルを読めません: $file");
                return;
            }
            $expected = $assoc_args['hash'] ?? null;
            $hash = hash('sha256', $payload);
            if (is_string($expected) && $expected !== '' && !hash_equals($expected, $hash)) {
                WP_CLI::error("ハッシュが一致しません(期待 {$expected}、実際 {$hash})");
                return;
            }
            $decoded = json_decode($payload, true);
            if (!is_array($decoded) || array_is_list($decoded)) {
                WP_CLI::error('内容が JSON オブジェクトではありません');
                return;
            }
            update_option(LETSBLOG_OPTION_SYNC_PAYLOAD, $payload, false);
            update_option(LETSBLOG_OPTION_SYNC_HASH, $hash, false);
            WP_CLI::line(json_encode(['sync_hash' => $hash]));
        }

        /**
         * 投稿を作らずに実テーマで表示する、期限付きの署名付きプレビュー URL を発行する。
         * 内容(JSON)は一時データとして保存し、wp_posts には行を作らない。URL は JSON で出力する。
         *
         * ## OPTIONS
         *
         * --file=<path>
         * : 内容(JSON オブジェクト: title, content, categories, tags, featured_image)を書いたファイル。
         *
         * [--ttl=<seconds>]
         * : 有効期限(秒)。1 から 86400。省略すると 900。
         *
         * ## EXAMPLES
         *
         *     wp letsblog preview --file=/tmp/preview.json --ttl=600
         */
        public function preview($args, $assoc_args)
        {
            $file = $assoc_args['file'] ?? null;
            if (!is_string($file) || $file === '') {
                WP_CLI::error('--file を指定してください');
                return;
            }
            $ttl = LETSBLOG_PREVIEW_DEFAULT_TTL;
            if (array_key_exists('ttl', $assoc_args)) {
                $ttlText = (string) $assoc_args['ttl'];
                if (preg_match('/^\d{1,6}$/', $ttlText) !== 1 || (int) $ttlText < 1 || (int) $ttlText > LETSBLOG_PREVIEW_MAX_TTL) {
                    WP_CLI::error('--ttl は 1 から ' . LETSBLOG_PREVIEW_MAX_TTL . ' の整数で指定してください');
                    return;
                }
                $ttl = (int) $ttlText;
            }
            $raw = is_readable($file) ? file_get_contents($file) : false;
            if (!is_string($raw)) {
                WP_CLI::error("ファイルを読めません: $file");
                return;
            }
            $decoded = json_decode($raw, true);
            if (!is_array($decoded) || array_is_list($decoded)) {
                WP_CLI::error('内容が JSON オブジェクトではありません');
                return;
            }
            [$data, $error] = letsblog_preview_normalize($decoded);
            if ($data === null) {
                WP_CLI::error((string) $error);
                return;
            }
            $issued = letsblog_preview_issue($data, $ttl, time());
            WP_CLI::line(json_encode(['url' => $issued['url'], 'expires_at' => $issued['expires_at']], JSON_UNESCAPED_SLASHES));
        }
    }

    WP_CLI::add_command('letsblog', 'Letsblog_CLI_Command');
}
