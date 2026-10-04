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

/** SNS 告知(issue #1573)。認証情報(秘密は暗号化)と告知履歴を保存する option 名、履歴の上限。 */
const LETSBLOG_OPTION_SNS_CONFIG = 'letsblog_sns_config';
const LETSBLOG_OPTION_SNS_LOG = 'letsblog_sns_log';
const LETSBLOG_SNS_LOG_LIMIT = 100;
/** X のトークンは、期限のこの秒数前から更新する(送信中に切れないように)。 */
const LETSBLOG_SNS_TOKEN_SKEW = 60;
const LETSBLOG_SNS_STATUS_UNSET = '未設定';
const LETSBLOG_SNS_STATUS_CONNECTED = '接続済み';
const LETSBLOG_SNS_STATUS_RECONNECT = '要再接続';

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

// ---- 同期済み CSS の読み込みと、本文の囲みクラスの付け直し(issue #1559) ----
// 保存済みの内容(wp letsblog sync、issue #1558)だけを使い、Lets Blog サーバーとは通信しない。
// どちらもこのプラグインのフック経由なので、プラグインを停止すれば CSS は読み込まれない。

/** 保存済みの同期内容(JSON オブジェクト)を配列で返す。未同期・壊れている場合は null。 */
function letsblog_synced_payload(): ?array
{
    $raw = get_option(LETSBLOG_OPTION_SYNC_PAYLOAD, null);
    if (!is_string($raw)) {
        return null;
    }
    $decoded = json_decode($raw, true);
    return is_array($decoded) && !array_is_list($decoded) ? $decoded : null;
}

/** 表側の画面で、同期済みの統合 CSS(組み込みタグのデザイン CSS を含む)を読み込む。 */
function letsblog_enqueue_synced_css(): void
{
    $payload = letsblog_synced_payload();
    $css = $payload['cssBundle'] ?? null;
    if (!is_string($css) || trim($css) === '') {
        return;
    }
    // インライン CSS から <style> を抜け出せないようにする。
    $css = str_ireplace('</style', '<\/style', $css);
    $hash = get_option(LETSBLOG_OPTION_SYNC_HASH, false);
    wp_register_style('letsblog-synced', false, [], is_string($hash) ? $hash : false);
    wp_enqueue_style('letsblog-synced');
    wp_add_inline_style('letsblog-synced', $css);
}

/**
 * 本文の囲み(lets-blog-rendered)のプレフィックスクラスを、保存済みの現在の cssSelectorPrefix へ付け直す。
 * プレフィックスを変えても、投稿に書き込まれた古いクラスのまま CSS が効かなくなることを防ぐ。
 */
function letsblog_rewrite_wrapper_class($content)
{
    if (!is_string($content)) {
        return $content;
    }
    $payload = letsblog_synced_payload();
    if ($payload === null || !array_key_exists('cssSelectorPrefix', $payload) || !is_string($payload['cssSelectorPrefix'])) {
        return $content;
    }
    $prefix = trim($payload['cssSelectorPrefix']);
    if ($prefix !== '' && preg_match('/^[A-Za-z0-9_-]+(?: [A-Za-z0-9_-]+)*$/', $prefix) !== 1) {
        return $content;
    }
    $class = 'lets-blog-rendered' . ($prefix !== '' ? ' ' . $prefix : '');
    return preg_replace(
        '/<div class="lets-blog-rendered(?: [^"]*)?"/',
        '<div class="' . $class . '"',
        $content
    );
}

if (function_exists('add_action') && function_exists('add_filter')) {
    add_action('wp_enqueue_scripts', 'letsblog_enqueue_synced_css');
    add_filter('the_content', 'letsblog_rewrite_wrapper_class');
}

// ---- SNS 告知の土台(issue #1573)。設定は wp-cli で受け取り、REST ルートは作らず、Let's Blog へは通信しない ----

/**
 * SNS ごとの処理の登録簿。SNS を足すときは letsblog_sns_register_sender() で登録する。
 * 定義: secret_fields(暗号化して保存する項目)、required(必須の項目)、optional(任意の項目)、int_fields(整数の項目)、
 * send(callable(array $cred, string $text, int $now): array{ok: bool, error: ?string, cred: array, needs_reconnect?: bool})。
 * account_name(アカウントの表示名)はどの SNS でも任意で受け取る。
 */
function letsblog_sns_senders(?string $sns = null, ?array $definition = null): array
{
    static $registry = [];
    if ($sns !== null && $definition !== null) {
        $registry[$sns] = $definition;
    }
    return $registry;
}

function letsblog_sns_register_sender(string $sns, array $definition): void
{
    letsblog_sns_senders($sns, $definition);
}

/** 秘密を暗号化する鍵。wp-config.php の salt から作るので、サイトごとに異なり DB だけでは復号できない。 */
function letsblog_sns_key(): string
{
    return hash('sha256', 'letsblog-sns|' . wp_salt('auth') . '|' . wp_salt('secure_auth'), true);
}

function letsblog_sns_encrypt(array $secrets): string
{
    $iv = random_bytes(12);
    $tag = '';
    $cipher = openssl_encrypt(json_encode($secrets), 'aes-256-gcm', letsblog_sns_key(), OPENSSL_RAW_DATA, $iv, $tag);
    return 'v1:' . base64_encode($iv . $tag . $cipher);
}

/** 復号できない(鍵が違う・壊れている)ときは null。 */
function letsblog_sns_decrypt(string $blob): ?array
{
    if (!str_starts_with($blob, 'v1:')) {
        return null;
    }
    $raw = base64_decode(substr($blob, 3), true);
    if ($raw === false || strlen($raw) < 28) {
        return null;
    }
    $plain = openssl_decrypt(substr($raw, 28), 'aes-256-gcm', letsblog_sns_key(), OPENSSL_RAW_DATA, substr($raw, 0, 12), substr($raw, 12, 16));
    if ($plain === false) {
        return null;
    }
    $decoded = json_decode($plain, true);
    return is_array($decoded) ? $decoded : null;
}

function letsblog_sns_config_all(): array
{
    $all = get_option(LETSBLOG_OPTION_SNS_CONFIG, []);
    return is_array($all) ? $all : [];
}

/** @return string 未設定 / 接続済み / 要再接続 */
function letsblog_sns_state(string $sns): string
{
    $entry = letsblog_sns_config_all()[$sns] ?? null;
    if (!is_array($entry)) {
        return LETSBLOG_SNS_STATUS_UNSET;
    }
    if (!empty($entry['reconnect']) || letsblog_sns_load_credentials($sns) === null) {
        return LETSBLOG_SNS_STATUS_RECONNECT;
    }
    return LETSBLOG_SNS_STATUS_CONNECTED;
}

/** 復号した認証情報(秘密と公開の項目)。未設定・復号できないときは null。 */
function letsblog_sns_load_credentials(string $sns): ?array
{
    $entry = letsblog_sns_config_all()[$sns] ?? null;
    if (!is_array($entry) || !is_string($entry['secret'] ?? null)) {
        return null;
    }
    $secrets = letsblog_sns_decrypt($entry['secret']);
    if ($secrets === null) {
        return null;
    }
    return array_merge(is_array($entry['public'] ?? null) ? $entry['public'] : [], $secrets);
}

/** 認証情報を保存する。秘密の項目だけを暗号化し、それ以外は option にそのまま持つ。 */
function letsblog_sns_save_credentials(string $sns, array $cred, bool $reconnect = false): void
{
    $definition = letsblog_sns_senders()[$sns];
    $secretFields = $definition['secret_fields'] ?? [];
    $secrets = array_intersect_key($cred, array_flip($secretFields));
    $public = array_diff_key($cred, array_flip($secretFields));
    $all = letsblog_sns_config_all();
    $all[$sns] = ['public' => $public, 'secret' => letsblog_sns_encrypt($secrets), 'reconnect' => $reconnect];
    update_option(LETSBLOG_OPTION_SNS_CONFIG, $all, false);
}

/**
 * config set の入力(JSON のオブジェクト)を検証して、保存する項目だけに絞る。未知の項目(api_base_url など)は捨てる。
 *
 * @return array{0: ?array, 1: ?string} [認証情報, エラー]
 */
function letsblog_sns_normalize_input(array $decoded): array
{
    $sns = $decoded['sns'] ?? null;
    $senders = letsblog_sns_senders();
    if (!is_string($sns) || !isset($senders[$sns])) {
        return [null, 'sns が未対応です(対応: ' . implode(', ', array_keys($senders)) . ')'];
    }
    $definition = $senders[$sns];
    $intFields = $definition['int_fields'] ?? [];
    $allowed = array_merge($definition['required'], $definition['optional'] ?? [], $definition['secret_fields'] ?? [], ['account_name']);
    $cred = [];
    foreach (array_unique($allowed) as $field) {
        if (!array_key_exists($field, $decoded)) {
            continue;
        }
        $value = $decoded[$field];
        if (in_array($field, $intFields, true)) {
            if (!is_int($value) || $value < 0) {
                return [null, "{$field} は 0 以上の整数にしてください"];
            }
        } elseif (!is_string($value)) {
            return [null, "{$field} は文字列にしてください"];
        }
        $cred[$field] = $value;
    }
    foreach ($definition['required'] as $field) {
        if (!isset($cred[$field]) || $cred[$field] === '') {
            return [null, "{$field} が必要です"];
        }
    }
    return [['sns' => $sns, 'cred' => $cred], null];
}

/** 告知履歴へ1件足す。上限を超えたら古いものから消す。 */
function letsblog_sns_log_append(array $entry): void
{
    $log = get_option(LETSBLOG_OPTION_SNS_LOG, []);
    $log = is_array($log) ? array_values($log) : [];
    $log[] = $entry;
    if (count($log) > LETSBLOG_SNS_LOG_LIMIT) {
        $log = array_slice($log, -LETSBLOG_SNS_LOG_LIMIT);
    }
    update_option(LETSBLOG_OPTION_SNS_LOG, $log, false);
}

function letsblog_sns_log_entries(): array
{
    $log = get_option(LETSBLOG_OPTION_SNS_LOG, []);
    return is_array($log) ? array_values($log) : [];
}

/**
 * 1件を送り、結果を告知履歴に残す。更新されたトークンは保存する。未設定の SNS へは送らず、履歴にも残さない。
 *
 * @return array{ok: bool, error: ?string, entry: ?array}
 */
function letsblog_sns_announce(string $sns, string $kind, ?int $postId, string $text, int $now): array
{
    $senders = letsblog_sns_senders();
    if (!isset($senders[$sns])) {
        return ['ok' => false, 'error' => "未対応の SNS です: {$sns}", 'entry' => null];
    }
    $entry = (array_key_exists($sns, letsblog_sns_config_all()))
        ? ['sns' => $sns, 'kind' => $kind, 'post_id' => $postId, 'at' => gmdate('c', $now)]
        : null;
    if ($entry === null) {
        return ['ok' => false, 'error' => "{$sns} は" . LETSBLOG_SNS_STATUS_UNSET . 'です', 'entry' => null];
    }
    $cred = letsblog_sns_load_credentials($sns);
    if ($cred === null || !empty(letsblog_sns_config_all()[$sns]['reconnect'])) {
        $result = ['ok' => false, 'error' => "{$sns} は" . LETSBLOG_SNS_STATUS_RECONNECT . 'です(認証情報を使えません)'];
    } else {
        $result = ($senders[$sns]['send'])($cred, $text, $now);
        $newCred = $result['cred'] ?? $cred;
        if ($newCred !== $cred || !empty($result['needs_reconnect'])) {
            letsblog_sns_save_credentials($sns, $newCred, !empty($result['needs_reconnect']));
        }
    }
    $entry['success'] = (bool) $result['ok'];
    $entry['error'] = $result['ok'] ? null : (string) ($result['error'] ?? '不明なエラー');
    letsblog_sns_log_append($entry);
    return ['ok' => $entry['success'], 'error' => $entry['error'], 'entry' => $entry];
}

// ---- X(OAuth 2.0 の confidential client。更新は Basic 認証で client_id:client_secret を送る) ----

/** API のベース URL。変えられるのは wp-config.php の定数だけ(e2e でスタブへ向けるため)。アプリから送る設定では変えられない。 */
function letsblog_x_api_base(): string
{
    return rtrim(defined('LETSBLOG_X_API_BASE_URL') ? (string) LETSBLOG_X_API_BASE_URL : 'https://api.x.com', '/');
}

/** 失敗の理由として履歴に残す短い説明。秘密は含まない(応答の本文の error / detail だけを使う)。 */
function letsblog_x_describe_failure(string $what, $response): string
{
    if (is_wp_error($response)) {
        return "X に接続できません({$what})";
    }
    $code = (int) wp_remote_retrieve_response_code($response);
    $body = json_decode((string) wp_remote_retrieve_body($response), true);
    $parts = [];
    if (is_array($body)) {
        foreach (['error', 'error_description', 'detail', 'title'] as $key) {
            if (isset($body[$key]) && is_string($body[$key]) && !in_array($body[$key], $parts, true)) {
                $parts[] = $body[$key];
            }
        }
    }
    $detail = $parts === [] ? '' : ': ' . mb_substr(implode(' / ', $parts), 0, 200);
    return "X の{$what}に失敗しました(HTTP {$code}{$detail})";
}

/** @return array{ok: bool, error: ?string, cred: array, needs_reconnect: bool} */
function letsblog_x_refresh(array $cred, int $now): array
{
    $response = wp_remote_request(letsblog_x_api_base() . '/2/oauth2/token', [
        'method' => 'POST',
        'timeout' => 15,
        'headers' => [
            'Authorization' => 'Basic ' . base64_encode($cred['client_id'] . ':' . $cred['client_secret']),
            'Content-Type' => 'application/x-www-form-urlencoded',
        ],
        'body' => http_build_query(['grant_type' => 'refresh_token', 'refresh_token' => $cred['refresh_token']]),
    ]);
    $code = is_wp_error($response) ? 0 : (int) wp_remote_retrieve_response_code($response);
    $body = is_wp_error($response) ? null : json_decode((string) wp_remote_retrieve_body($response), true);
    if ($code !== 200 || !is_array($body) || !is_string($body['access_token'] ?? null) || $body['access_token'] === '') {
        return [
            'ok' => false,
            'error' => letsblog_x_describe_failure('トークン更新', $response),
            'cred' => $cred,
            'needs_reconnect' => $code === 400 || $code === 401,
        ];
    }
    // refresh token は使うたびに入れ替わる。返ってきた値を必ず保存する(返らなければ今の値を使い続ける)。
    $cred['access_token'] = $body['access_token'];
    if (is_string($body['refresh_token'] ?? null) && $body['refresh_token'] !== '') {
        $cred['refresh_token'] = $body['refresh_token'];
    }
    $cred['expires_at'] = $now + (int) ($body['expires_in'] ?? 7200);
    return ['ok' => true, 'error' => null, 'cred' => $cred, 'needs_reconnect' => false];
}

function letsblog_x_post_tweet(array $cred, string $text)
{
    return wp_remote_request(letsblog_x_api_base() . '/2/tweets', [
        'method' => 'POST',
        'timeout' => 15,
        'headers' => ['Authorization' => 'Bearer ' . $cred['access_token'], 'Content-Type' => 'application/json'],
        'body' => json_encode(['text' => $text]),
    ]);
}

/** X へ投稿する。期限が切れていれば先に更新し、投稿が 401 なら一度だけ更新してやり直す。 */
function letsblog_x_send(array $cred, string $text, int $now): array
{
    $refreshed = false;
    if ((int) ($cred['expires_at'] ?? 0) <= $now + LETSBLOG_SNS_TOKEN_SKEW) {
        $refresh = letsblog_x_refresh($cred, $now);
        $cred = $refresh['cred'];
        if (!$refresh['ok']) {
            return $refresh;
        }
        $refreshed = true;
    }
    $response = letsblog_x_post_tweet($cred, $text);
    if (!is_wp_error($response) && (int) wp_remote_retrieve_response_code($response) === 401 && !$refreshed) {
        $refresh = letsblog_x_refresh($cred, $now);
        $cred = $refresh['cred'];
        if (!$refresh['ok']) {
            return $refresh;
        }
        $response = letsblog_x_post_tweet($cred, $text);
    }
    if (is_wp_error($response) || (int) wp_remote_retrieve_response_code($response) !== 201) {
        return ['ok' => false, 'error' => letsblog_x_describe_failure('投稿', $response), 'cred' => $cred, 'needs_reconnect' => false];
    }
    return ['ok' => true, 'error' => null, 'cred' => $cred, 'needs_reconnect' => false];
}

letsblog_sns_register_sender('x', [
    'secret_fields' => ['client_secret', 'access_token', 'refresh_token'],
    'required' => ['client_id', 'client_secret', 'access_token', 'refresh_token'],
    'optional' => ['expires_at'],
    'int_fields' => ['expires_at'],
    'send' => 'letsblog_x_send',
]);

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

        /**
         * SNS 告知の設定・状態・テスト投稿・履歴。秘密情報は引数では受け取らず、標準入力の JSON で渡す。
         *
         * ## OPTIONS
         *
         * [<args>...]
         * : `config set`(標準入力に JSON: sns, client_id, client_secret, access_token, refresh_token, expires_at, account_name)、
         *   `config clear [<sns>]`、`status`、`test <sns>`、`log --format=json`。
         *
         * [--format=<format>]
         * : `log` の出力形式。json のみ。
         *
         * ## EXAMPLES
         *
         *     wp letsblog sns config set < x.json
         *     wp letsblog sns status
         *     wp letsblog sns test x
         *     wp letsblog sns log --format=json
         *     wp letsblog sns config clear
         */
        public function sns($args, $assoc_args)
        {
            $sub = $args[0] ?? '';
            if ($sub === 'config') {
                $this->sns_config($args[1] ?? '', array_slice($args, 2), $assoc_args);
            } elseif ($sub === 'status') {
                $status = [];
                foreach (array_keys(letsblog_sns_senders()) as $sns) {
                    $status[$sns] = [
                        'status' => letsblog_sns_state($sns),
                        'account_name' => letsblog_sns_config_all()[$sns]['public']['account_name'] ?? null,
                    ];
                }
                WP_CLI::line(json_encode($status, JSON_UNESCAPED_UNICODE));
            } elseif ($sub === 'test') {
                $sns = $args[1] ?? '';
                if ($sns === '') {
                    WP_CLI::error('SNS を指定してください(例: wp letsblog sns test x)');
                    return;
                }
                $now = time();
                $result = letsblog_sns_announce($sns, 'test', null, 'Let\'s Blog の接続テストです(' . gmdate('Y-m-d H:i:s', $now) . ' UTC)', $now);
                if (!$result['ok']) {
                    WP_CLI::error((string) $result['error']);
                    return;
                }
                WP_CLI::line(json_encode($result['entry'], JSON_UNESCAPED_UNICODE));
            } elseif ($sub === 'log') {
                if (($assoc_args['format'] ?? 'json') !== 'json') {
                    WP_CLI::error('--format は json だけです');
                    return;
                }
                WP_CLI::line(json_encode(letsblog_sns_log_entries(), JSON_UNESCAPED_UNICODE));
            } else {
                WP_CLI::error('サブコマンドは config set / config clear / status / test <sns> / log のどれかです');
            }
        }

        private function sns_config(string $action, array $rest, array $assoc_args): void
        {
            if ($action === 'set') {
                if ($assoc_args !== []) {
                    WP_CLI::error('秘密情報を引数では受け取りません。JSON を標準入力で渡してください');
                    return;
                }
                $decoded = json_decode($this->read_stdin(), true);
                if (!is_array($decoded) || array_is_list($decoded)) {
                    WP_CLI::error('標準入力が JSON オブジェクトではありません');
                    return;
                }
                [$input, $error] = letsblog_sns_normalize_input($decoded);
                if ($input === null) {
                    WP_CLI::error((string) $error);
                    return;
                }
                letsblog_sns_save_credentials($input['sns'], $input['cred']);
                WP_CLI::line(json_encode(['sns' => $input['sns'], 'status' => letsblog_sns_state($input['sns'])], JSON_UNESCAPED_UNICODE));
            } elseif ($action === 'clear') {
                $target = $rest[0] ?? null;
                $all = letsblog_sns_config_all();
                if ($target === null) {
                    $all = [];
                } else {
                    unset($all[$target]);
                }
                if ($all === []) {
                    delete_option(LETSBLOG_OPTION_SNS_CONFIG);
                } else {
                    update_option(LETSBLOG_OPTION_SNS_CONFIG, $all, false);
                }
                WP_CLI::line(json_encode(['cleared' => $target ?? 'all']));
            } else {
                WP_CLI::error('config のサブコマンドは set か clear です');
            }
        }

        protected function read_stdin(): string
        {
            $raw = file_get_contents('php://stdin');
            return is_string($raw) ? $raw : '';
        }
    }

    WP_CLI::add_command('letsblog', 'Letsblog_CLI_Command');
}
