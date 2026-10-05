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
/** 公開時の告知(issue #1575)。告知済み(または告知の対象外)の記事に付ける投稿メタと、即時の告知を実行する cron のフック名。 */
const LETSBLOG_META_SNS_ANNOUNCED = '_letsblog_sns_announced';
const LETSBLOG_CRON_SNS_ANNOUNCE = 'letsblog_sns_announce_post';
/** 送信を始めた記事に付ける印。cron が二重に走っても、再試行しても二度送らない。 */
const LETSBLOG_META_SNS_SENT = '_letsblog_sns_sent';

/** GA4 の記事別 PV(issue #1576)。認証情報(秘密は暗号化)・取得済みの日別 PV・取得の状況を保存する option 名と、定期取得の cron フック名。 */
const LETSBLOG_OPTION_PV_CONFIG = 'letsblog_pv_config';
const LETSBLOG_OPTION_PV_DATA = 'letsblog_pv_data';
const LETSBLOG_OPTION_PV_STATUS = 'letsblog_pv_status';
const LETSBLOG_CRON_PV_FETCH = 'letsblog_pv_fetch';
/** 初回(と、長く取得できなかった後)に全期間を取る開始日。GA4 の公開日(2020-10-14)より前の日は存在しない。 */
const LETSBLOG_PV_BACKFILL_START = '2020-10-14';
/** 2回目以降に取り直す範囲。GA4 の集計は遅れることがあり、直近の値は後から増えるので、数日前から上書きする。GA 側のタイムゾーンで解釈される。 */
const LETSBLOG_PV_REFETCH_START = '3daysAgo';
/** 最後の成功からこの秒数を超えて空いたら、直近だけでは足りないので全期間を取り直す。 */
const LETSBLOG_PV_GAP_SECONDS = 172800;
const LETSBLOG_PV_PAGE_LIMIT = 10000;
const LETSBLOG_PV_MAX_PAGES = 50;
/** PV 達成の告知(issue #1577)。ルール・告知済み(記事×ルール)を保存する option 名と、告知履歴の種類。 */
const LETSBLOG_OPTION_PV_RULES = 'letsblog_pv_rules';
const LETSBLOG_OPTION_PV_ANNOUNCED = 'letsblog_pv_announced';
const LETSBLOG_SNS_KIND_PV = 'PV 達成';

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

// ---- カスタムタグの目印を、同期済みのテンプレートで表示時に展開し直す(issue #1560) ----
// 投稿 HTML には、アプリが `<!-- lbs:tag {JSON} -->投稿時点の展開HTML<!-- /lbs:tag -->` の形で目印を残している。
// JSON は name・attrs・content(変換済みの {{content}} の HTML)を持つ。目印は HTML コメントなので、
// プラグインを停止しても画面に出ず、投稿時点の展開 HTML がそのまま表示される。

/** 同期済みのカスタムタグ定義を、タグ名 => htmlTemplate で返す。同名は PROJECT(プロジェクト固有)を優先する。 */
function letsblog_synced_tag_templates(): array
{
    $payload = letsblog_synced_payload();
    $tags = $payload['customTags'] ?? null;
    if (!is_array($tags)) {
        return [];
    }
    $templates = [];
    $isProject = [];
    foreach ($tags as $tag) {
        if (!is_array($tag) || !is_string($tag['tagName'] ?? null) || !is_string($tag['htmlTemplate'] ?? null)) {
            continue;
        }
        $name = $tag['tagName'];
        $project = ($tag['scope'] ?? null) === 'PROJECT';
        if (!isset($templates[$name]) || ($project && !$isProject[$name])) {
            $templates[$name] = $tag['htmlTemplate'];
            $isProject[$name] = $project;
        }
    }
    return $templates;
}

/**
 * テンプレートへ本文と属性を差し込む。属性値は HTML エスケープする(XSS)。本文は変換済みの HTML なのでそのまま入れ、
 * 本文の中のプレースホルダーは置換しない(1 回の走査で置換する)。
 */
function letsblog_render_tag_template(string $template, string $content, array $attrs): string
{
    return preg_replace_callback(
        '/\{\{(content|attr:[a-zA-Z0-9_-]+)\}\}/',
        function (array $m) use ($content, $attrs): string {
            if ($m[1] === 'content') {
                return $content;
            }
            $value = $attrs[substr($m[1], 5)] ?? '';
            return is_string($value) ? htmlspecialchars($value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8') : '';
        },
        $template
    ) ?? $template;
}

/**
 * 目印 1 組を、既知のタグなら同期済みのテンプレートで展開し直す。未知・壊れている場合は null
 * (呼び出し側が投稿時点の HTML を使う)。
 */
function letsblog_expand_one_tag(string $json, array $templates): ?string
{
    $data = json_decode($json, true);
    if (!is_array($data) || !is_string($data['name'] ?? null) || !is_array($data['attrs'] ?? null) || !is_string($data['content'] ?? null)) {
        return null;
    }
    if (!isset($templates[$data['name']])) {
        return null;
    }
    // content に組み込みタグの生の記法が残っている(入れ子の中で展開されなかった)場合、展開し直すと生の記法が画面に出る。
    // その場合は投稿時点の HTML を使う(中の組み込みタグの目印は呼び出し側が展開し直す。issue #1563)。
    if (preg_match('/\[(?:blogcard|amazon|toc|recharts)\b/i', $data['content']) === 1) {
        return null;
    }
    // content には、アプリが先に展開した入れ子のタグの目印が入っている。内側も同期済みのテンプレートで展開し直す。
    $content = letsblog_expand_custom_tags($data['content']);
    return letsblog_render_tag_template($templates[$data['name']], $content, $data['attrs']);
}

/**
 * the_content(wpautop より前)で、目印付きのカスタムタグ(lbs:tag)と組み込みタグ(lbs:embed、issue #1563)を
 * 展開し直す。目印のない本文は変えない。
 */
function letsblog_expand_custom_tags($content)
{
    if (!is_string($content) || (!str_contains($content, '<!-- lbs:tag ') && !str_contains($content, '<!-- lbs:embed '))) {
        return $content;
    }
    if (preg_match_all('/<!-- lbs:(tag|embed) ([^>]*?) -->|<!-- \/lbs:(?:tag|embed) -->/', $content, $tokens, PREG_SET_ORDER | PREG_OFFSET_CAPTURE) === 0) {
        return $content;
    }
    // 入れ子を数えて、最も外側の開始・終了の組を集める。閉じのない開始と、対応のない閉じは触らない。
    $pairs = [];
    $stack = [];
    foreach ($tokens as $token) {
        if (!str_starts_with($token[0][0], '<!-- /')) {
            $stack[] = $token;
            continue;
        }
        if ($stack === []) {
            continue;
        }
        $open = array_pop($stack);
        if ($stack === []) {
            $pairs[] = [$open, $token];
        }
    }
    $templates = letsblog_synced_tag_templates();
    $out = '';
    $cursor = 0;
    foreach ($pairs as [$open, $close]) {
        $openStart = $open[0][1];
        $openEnd = $openStart + strlen($open[0][0]);
        $closeStart = $close[0][1];
        $out .= substr($content, $cursor, $openStart - $cursor);
        $expanded = $open[1][0] === 'embed'
            ? letsblog_expand_one_embed($open[2][0])
            : letsblog_expand_one_tag($open[2][0], $templates);
        if ($expanded === null) {
            $out .= $open[0][0]
                . letsblog_expand_custom_tags(substr($content, $openEnd, $closeStart - $openEnd))
                . $close[0][0];
        } else {
            $out .= $expanded;
        }
        $cursor = $closeStart + strlen($close[0][0]);
    }
    return $out . substr($content, $cursor);
}

// ---- 組み込みタグ(ブログカード・Amazon・目次)の目印を、同期済みのデザインで表示時に展開し直す(issue #1563) ----
// 投稿 HTML には、アプリが `<!-- lbs:embed {"type":..,"data":{..}} -->投稿時点の展開HTML<!-- /lbs:embed -->` の形で
// 取得したデータ(目次は見出しの構造)を残している。ここでは外部ページを取得せず、そのデータだけを使う。
// データは差し込む前に必ずエスケープし(XSS)、URL は http(s) のものだけを使う。

/** 同期済みの組み込みタグのデザインを、tagType => htmlTemplate(未設定は null)で返す。同期されていない種別は含めない。 */
function letsblog_synced_tag_designs(): array
{
    $payload = letsblog_synced_payload();
    $designs = $payload['tagDesigns'] ?? null;
    if (!is_array($designs)) {
        return [];
    }
    $templates = [];
    foreach ($designs as $design) {
        if (!is_array($design) || !is_string($design['tagType'] ?? null)) {
            continue;
        }
        $template = $design['htmlTemplate'] ?? null;
        $templates[$design['tagType']] = is_string($template) ? $template : null;
    }
    return $templates;
}

function letsblog_embed_escape(string $value): string
{
    return htmlspecialchars($value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

/** データの文字列項目を取り出す。文字列でなければ空文字。 */
function letsblog_embed_text(array $data, string $key): string
{
    return is_string($data[$key] ?? null) ? $data[$key] : '';
}

function letsblog_embed_is_http_url(string $value): bool
{
    return preg_match('#^https?://#i', trim($value)) === 1;
}

/** テンプレートの {{key}} を値(エスケープ済み)で 1 回の走査で置換する。値の中のプレースホルダーは置換しない。 */
function letsblog_embed_fill(string $template, array $values): string
{
    return preg_replace_callback(
        '/\{\{([a-zA-Z0-9_]+)\}\}/',
        function (array $m) use ($values): string {
            return $values[$m[1]] ?? '';
        },
        $template
    ) ?? $template;
}

/** ブログカード。url が http(s) でなければ null(投稿時点の HTML を使う)。 */
function letsblog_embed_blogcard(array $data, ?string $template): ?string
{
    $url = letsblog_embed_text($data, 'url');
    if (!letsblog_embed_is_http_url($url)) {
        return null;
    }
    $image = letsblog_embed_text($data, 'imageUrl');
    $image = letsblog_embed_is_http_url($image) ? $image : '';
    $v = [
        'title' => letsblog_embed_escape(letsblog_embed_text($data, 'title')),
        'description' => letsblog_embed_escape(letsblog_embed_text($data, 'description')),
        'siteName' => letsblog_embed_escape(letsblog_embed_text($data, 'siteName')),
        'url' => letsblog_embed_escape(trim($url)),
        'imageUrl' => letsblog_embed_escape(trim($image)),
    ];
    if ($template !== null) {
        return letsblog_embed_fill($template, $v);
    }
    return '<a class="lb-blogcard" href="' . $v['url'] . '" target="_blank" rel="noopener noreferrer">'
        . ($image !== '' ? '<div class="lb-blogcard-thumb" style="background-image:url(\'' . $v['imageUrl'] . '\')"></div>' : '')
        . '<div class="lb-blogcard-body"><div class="lb-blogcard-title">' . $v['title'] . '</div>'
        . '<div class="lb-blogcard-description">' . $v['description'] . '</div>'
        . '<div class="lb-blogcard-site">' . $v['siteName'] . '</div></div></a>';
}

/** Amazon。商品 URL は空(非本番サイトで投稿)か http(s)。それ以外は null。 */
function letsblog_embed_amazon(array $data, ?string $template): ?string
{
    $url = trim(letsblog_embed_text($data, 'productUrl'));
    if ($url !== '' && !letsblog_embed_is_http_url($url)) {
        return null;
    }
    $image = letsblog_embed_text($data, 'imageUrl');
    $image = letsblog_embed_is_http_url($image) ? trim($image) : '';
    $v = [
        'productName' => letsblog_embed_escape(letsblog_embed_text($data, 'productName')),
        'price' => letsblog_embed_escape(letsblog_embed_text($data, 'price')),
        'summary' => letsblog_embed_escape(letsblog_embed_text($data, 'summary')),
        'productUrl' => letsblog_embed_escape($url),
        'imageUrl' => letsblog_embed_escape($image),
        'priceTimestamp' => letsblog_embed_escape(letsblog_embed_text($data, 'priceTimestamp')),
    ];
    if ($template !== null) {
        return letsblog_embed_fill($template, $v);
    }
    $link = $url !== '';
    $tag = $link ? 'a' : 'div';
    $html = '<' . $tag . ' class="lb-amazon-card"'
        . ($link ? ' href="' . $v['productUrl'] . '" target="_blank" rel="noopener noreferrer nofollow sponsored"' : '') . '>';
    if ($image !== '') {
        $html .= '<div class="lb-amazon-card-thumb" style="background-image:url(\'' . $v['imageUrl'] . '\')"></div>';
    }
    $html .= '<div class="lb-amazon-card-body"><div class="lb-amazon-card-name">' . $v['productName'] . '</div>';
    foreach (['summary' => 'summary', 'price' => 'price', 'priceTimestamp' => 'timestamp'] as $key => $class) {
        if ($v[$key] !== '') {
            $html .= '<div class="lb-amazon-card-' . $class . '">' . $v[$key] . '</div>';
        }
    }
    return $html . '<div class="lb-amazon-card-cta">Amazonで見る</div></div></' . $tag . '>';
}

/** 目次の項目(text・href・children)から入れ子の <li> 群を組み立てる。形式が壊れていれば null。 */
function letsblog_embed_toc_items($items, int $depth)
{
    if (!is_array($items) || $items === [] || !array_is_list($items) || $depth > 6) {
        return null;
    }
    $html = '';
    foreach ($items as $item) {
        if (!is_array($item) || !is_string($item['text'] ?? null) || !is_string($item['href'] ?? null) || !str_starts_with($item['href'], '#')) {
            return null;
        }
        $children = $item['children'] ?? [];
        if (!is_array($children)) {
            return null;
        }
        $nested = '';
        if ($children !== []) {
            $inner = letsblog_embed_toc_items($children, $depth + 1);
            if ($inner === null) {
                return null;
            }
            $nested = '<ul>' . $inner . '</ul>';
        }
        $html .= '<li><a href="' . letsblog_embed_escape($item['href']) . '">' . letsblog_embed_escape($item['text']) . '</a>' . $nested . '</li>';
    }
    return $html;
}

/** 目次。{{toc}} には既定のクラスつきの <ul> 全体を入れる。構造が壊れていれば null。 */
function letsblog_embed_toc(array $data, ?string $template): ?string
{
    $list = letsblog_embed_toc_items($data['items'] ?? null, 1);
    if ($list === null) {
        return null;
    }
    $ul = '<ul class="lb-toc-list">' . $list . '</ul>';
    return $template === null ? $ul : str_replace('{{toc}}', $ul, $template);
}

/**
 * 組み込みタグの目印 1 組を、同期済みのデザインで展開し直す。デザインが同期されていない種別、
 * 知らない種別、壊れたデータは null(呼び出し側が投稿時点の HTML を使う)。
 */
function letsblog_expand_one_embed(string $json): ?string
{
    $marker = json_decode($json, true);
    if (!is_array($marker) || !is_string($marker['type'] ?? null) || !is_array($marker['data'] ?? null)) {
        return null;
    }
    $designs = letsblog_synced_tag_designs();
    if (!array_key_exists($marker['type'], $designs)) {
        return null;
    }
    $template = $designs[$marker['type']];
    switch ($marker['type']) {
        case 'BLOGCARD':
            return letsblog_embed_blogcard($marker['data'], $template);
        case 'AMAZON':
            return letsblog_embed_amazon($marker['data'], $template);
        case 'TOC':
            return letsblog_embed_toc($marker['data'], $template);
    }
    return null;
}

if (function_exists('add_filter')) {
    add_filter('the_content', 'letsblog_expand_custom_tags', 9);
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

// ---- Threads(issue #1579。長期トークン 1 本だけを持つ。更新に client_secret は要らない) ----

/** 長期トークンは、期限のこの秒数前から更新する(1 日 1 回も使われないサイトでも、期限切れの前に更新できるように)。 */
const LETSBLOG_THREADS_REFRESH_WINDOW = 7 * 86400;
/** Threads は、発行から 24 時間未満のトークンを更新できない。 */
const LETSBLOG_THREADS_MIN_REFRESH_AGE = 86400;
/** Threads の投稿の上限(文字数)。 */
const LETSBLOG_THREADS_TEXT_LIMIT = 500;

/** API のベース URL。変えられるのは wp-config.php の定数だけ(e2e でスタブへ向けるため)。アプリから送る設定では変えられない。 */
function letsblog_threads_api_base(): string
{
    return rtrim(defined('LETSBLOG_THREADS_API_BASE_URL') ? (string) LETSBLOG_THREADS_API_BASE_URL : 'https://graph.threads.net', '/');
}

/** 失敗の理由として履歴に残す短い説明。秘密は含まない(応答の本文の error.message だけを使う)。 */
function letsblog_threads_describe_failure(string $what, $response): string
{
    if (is_wp_error($response)) {
        return "Threads に接続できません({$what})";
    }
    $code = (int) wp_remote_retrieve_response_code($response);
    $body = json_decode((string) wp_remote_retrieve_body($response), true);
    $message = is_array($body) && is_array($body['error'] ?? null) && is_string($body['error']['message'] ?? null)
        ? ': ' . mb_substr($body['error']['message'], 0, 200)
        : '';
    return "Threads の{$what}に失敗しました(HTTP {$code}{$message})";
}

/**
 * 長期トークンを更新する(GET /refresh_access_token)。更新できるのは、期限前で、発行から 24 時間以上たったものだけ。
 * 呼び出し側(letsblog_threads_send)がその条件を先に確かめ、満たさなければここへ来ない。
 *
 * @return array{ok: bool, error: ?string, cred: array, needs_reconnect: bool}
 */
function letsblog_threads_refresh(array $cred, int $now): array
{
    $response = wp_remote_request(
        letsblog_threads_api_base() . '/refresh_access_token?' . http_build_query(['grant_type' => 'th_refresh_token', 'access_token' => $cred['access_token']]),
        ['method' => 'GET', 'timeout' => 15]
    );
    $code = is_wp_error($response) ? 0 : (int) wp_remote_retrieve_response_code($response);
    $body = is_wp_error($response) ? null : json_decode((string) wp_remote_retrieve_body($response), true);
    if ($code !== 200 || !is_array($body) || !is_string($body['access_token'] ?? null) || $body['access_token'] === '') {
        return [
            'ok' => false,
            'error' => letsblog_threads_describe_failure('トークン更新', $response),
            'cred' => $cred,
            'needs_reconnect' => $code === 400 || $code === 401,
        ];
    }
    $cred['access_token'] = $body['access_token'];
    $cred['issued_at'] = $now;
    $cred['expires_at'] = $now + (int) ($body['expires_in'] ?? 5184000);
    return ['ok' => true, 'error' => null, 'cred' => $cred, 'needs_reconnect' => false];
}

/** 告知文を Threads の上限に収める。超えるときは、最後の行(パーマリンク)を残して手前を「…」で切り詰める。 */
function letsblog_threads_fit_text(string $text): string
{
    if (mb_strlen($text) <= LETSBLOG_THREADS_TEXT_LIMIT) {
        return $text;
    }
    $break = mb_strrpos($text, "\n");
    $tail = $break === false ? '' : mb_substr($text, $break);
    $head = $break === false ? $text : mb_substr($text, 0, $break);
    $room = LETSBLOG_THREADS_TEXT_LIMIT - mb_strlen($tail) - 1;
    if ($break === false || $room < 1) {
        return mb_substr($text, 0, LETSBLOG_THREADS_TEXT_LIMIT);
    }
    return mb_substr($head, 0, $room) . '…' . $tail;
}

/** @return array{ok: bool, error: ?string, cred: array, needs_reconnect: bool} */
function letsblog_threads_publish(array $cred, string $text): array
{
    $headers = ['Authorization' => 'Bearer ' . $cred['access_token'], 'Content-Type' => 'application/x-www-form-urlencoded'];
    $userBase = letsblog_threads_api_base() . '/v1.0/' . rawurlencode((string) $cred['user_id']);
    $fail = function (string $what, $response) use ($cred): array {
        $code = is_wp_error($response) ? 0 : (int) wp_remote_retrieve_response_code($response);
        return ['ok' => false, 'error' => letsblog_threads_describe_failure($what, $response), 'cred' => $cred, 'needs_reconnect' => $code === 401];
    };
    $created = wp_remote_request($userBase . '/threads', [
        'method' => 'POST',
        'timeout' => 15,
        'headers' => $headers,
        'body' => http_build_query(['media_type' => 'TEXT', 'text' => letsblog_threads_fit_text($text)]),
    ]);
    $createdBody = is_wp_error($created) ? null : json_decode((string) wp_remote_retrieve_body($created), true);
    if (is_wp_error($created) || (int) wp_remote_retrieve_response_code($created) !== 200
        || !is_array($createdBody) || !is_string($createdBody['id'] ?? null) || $createdBody['id'] === '') {
        return $fail('投稿の作成', $created);
    }
    $published = wp_remote_request($userBase . '/threads_publish', [
        'method' => 'POST',
        'timeout' => 15,
        'headers' => $headers,
        'body' => http_build_query(['creation_id' => $createdBody['id']]),
    ]);
    if (is_wp_error($published) || (int) wp_remote_retrieve_response_code($published) !== 200) {
        return $fail('投稿の公開', $published);
    }
    return ['ok' => true, 'error' => null, 'cred' => $cred, 'needs_reconnect' => false];
}

/**
 * Threads へ投稿する。トークンの期限が近い(または切れている)ときは、先に延長する。
 * 延長できるのは「期限前」かつ「発行から 24 時間以上」のものだけ。満たさなければ投稿せず、理由を返す(履歴に残る)。
 */
function letsblog_threads_send(array $cred, string $text, int $now): array
{
    $expiresAt = (int) ($cred['expires_at'] ?? 0);
    if ($expiresAt <= $now) {
        return [
            'ok' => false,
            'error' => 'Threads のトークンの期限が切れています(更新できるのは期限前だけです。Threads を再接続してください)',
            'cred' => $cred,
            'needs_reconnect' => true,
        ];
    }
    if ($expiresAt - $now <= LETSBLOG_THREADS_REFRESH_WINDOW) {
        if ($now - (int) ($cred['issued_at'] ?? 0) < LETSBLOG_THREADS_MIN_REFRESH_AGE) {
            return [
                'ok' => false,
                'error' => 'Threads のトークンを更新できません(更新できるのは発行から24時間以上たったものだけです)',
                'cred' => $cred,
                'needs_reconnect' => false,
            ];
        }
        $refresh = letsblog_threads_refresh($cred, $now);
        $cred = $refresh['cred'];
        if (!$refresh['ok']) {
            return $refresh;
        }
    }
    $result = letsblog_threads_publish($cred, $text);
    $result['cred'] = $cred;
    return $result;
}

letsblog_sns_register_sender('threads', [
    'secret_fields' => ['access_token'],
    'required' => ['access_token', 'user_id', 'expires_at'],
    'optional' => ['issued_at'],
    'int_fields' => ['expires_at', 'issued_at'],
    'send' => 'letsblog_threads_send',
]);

// ---- Facebook ページ(issue #1580。ページのトークン 1 本だけを持つ。個人アカウントには投稿しない) ----

/** API のベース URL。変えられるのは wp-config.php の定数だけ(e2e でスタブへ向けるため)。アプリから送る設定では変えられない。 */
function letsblog_facebook_api_base(): string
{
    return rtrim(defined('LETSBLOG_FACEBOOK_API_BASE_URL') ? (string) LETSBLOG_FACEBOOK_API_BASE_URL : 'https://graph.facebook.com/v21.0', '/');
}

/** Graph API の応答の本文から error.code を取り出す(取れなければ null)。 */
function letsblog_facebook_error_code($response): ?int
{
    $body = is_wp_error($response) ? null : json_decode((string) wp_remote_retrieve_body($response), true);
    return is_array($body) && is_array($body['error'] ?? null) && is_int($body['error']['code'] ?? null) ? $body['error']['code'] : null;
}

/** 失敗の理由として履歴に残す短い説明。秘密は含まない(応答の本文の error.message だけを使う)。 */
function letsblog_facebook_describe_failure(string $what, $response): string
{
    if (is_wp_error($response)) {
        return "Facebook に接続できません({$what})";
    }
    $code = (int) wp_remote_retrieve_response_code($response);
    $body = json_decode((string) wp_remote_retrieve_body($response), true);
    $message = is_array($body) && is_array($body['error'] ?? null) && is_string($body['error']['message'] ?? null)
        ? ': ' . mb_substr($body['error']['message'], 0, 200)
        : '';
    return "Facebook の{$what}に失敗しました(HTTP {$code}{$message})";
}

/**
 * 告知文を Facebook のフィードの message と link に分ける。最後の行が URL なら link に、残りを message にする
 * (URL が無ければ message だけ。URL だけなら link だけ)。
 *
 * @return array{message: ?string, link: ?string}
 */
function letsblog_facebook_split_text(string $text): array
{
    $text = trim($text);
    $break = mb_strrpos($text, "\n");
    $last = $break === false ? $text : trim(mb_substr($text, $break + 1));
    if (preg_match('#^https?://\S+$#', $last) !== 1) {
        return ['message' => $text === '' ? null : $text, 'link' => null];
    }
    $message = $break === false ? '' : trim(mb_substr($text, 0, $break));
    return ['message' => $message === '' ? null : $message, 'link' => $last];
}

/**
 * ページのフィードへ投稿する(POST /{page_id}/feed、ページのトークン)。投稿先は必ず選んだページで、個人のフィードではない。
 * ページが選ばれていなければ送らず、理由を返す(履歴に残る)。トークンの失効(HTTP 401 / error.code 190)は要再接続にする。
 */
function letsblog_facebook_send(array $cred, string $text, int $now): array
{
    $pageId = (string) ($cred['page_id'] ?? '');
    if ($pageId === '') {
        return [
            'ok' => false,
            'error' => 'Facebook の投稿先のページが選ばれていません(Facebook を再接続してページを選んでください)',
            'cred' => $cred,
            'needs_reconnect' => false,
        ];
    }
    $parts = letsblog_facebook_split_text($text);
    $response = wp_remote_request(letsblog_facebook_api_base() . '/' . rawurlencode($pageId) . '/feed', [
        'method' => 'POST',
        'timeout' => 15,
        'headers' => ['Authorization' => 'Bearer ' . $cred['access_token'], 'Content-Type' => 'application/x-www-form-urlencoded'],
        'body' => http_build_query(array_filter($parts, fn($v) => $v !== null)),
    ]);
    $body = is_wp_error($response) ? null : json_decode((string) wp_remote_retrieve_body($response), true);
    if (is_wp_error($response) || (int) wp_remote_retrieve_response_code($response) !== 200
        || !is_array($body) || !is_string($body['id'] ?? null) || $body['id'] === '') {
        $status = is_wp_error($response) ? 0 : (int) wp_remote_retrieve_response_code($response);
        return [
            'ok' => false,
            'error' => letsblog_facebook_describe_failure('投稿', $response),
            'cred' => $cred,
            'needs_reconnect' => $status === 401 || letsblog_facebook_error_code($response) === 190,
        ];
    }
    return ['ok' => true, 'error' => null, 'cred' => $cred, 'needs_reconnect' => false];
}

letsblog_sns_register_sender('facebook', [
    'secret_fields' => ['access_token'],
    'required' => ['access_token', 'page_id'],
    'send' => 'letsblog_facebook_send',
]);

// ---- 公開時の告知(issue #1575)。公開の検知は WordPress 側で行い、Let's Blog が止まっていても告知できる ----

/** CLI(wp-cli)で実行中か。cron のループバックが期待できないので、CLI ではその場で送る。 */
function letsblog_sns_is_cli(): bool
{
    return defined('WP_CLI') && WP_CLI;
}

/**
 * 保存が終わるまで(CLI)・cron を起動するまで(Web)の、実行待ちの状態。
 *
 * @return array{cli: int[], spawn: bool}
 */
function &letsblog_sns_pending(): array
{
    static $pending = ['cli' => [], 'spawn' => false];
    return $pending;
}

/** 告知文の既定: 記事のタイトルとパーマリンク。 */
function letsblog_sns_default_text($post): string
{
    $title = html_entity_decode(letsblog_strip_tags((string) get_the_title($post)), ENT_QUOTES, 'UTF-8');
    return $title . "\n" . (string) get_permalink($post);
}

function letsblog_strip_tags(string $text): string
{
    return function_exists('wp_strip_all_tags') ? wp_strip_all_tags($text) : strip_tags($text);
}

/**
 * transition_post_status。post の「publish 以外 → publish」を告知の対象にする。
 * 予約(future)の作成と future → publish、パスワード付き、告知済みは対象外。SNS の設定がなければ何もしない。
 * 対象になった記事には告知済みの印を先に付ける(再公開で二重に告知しない。失敗しても再送しない)。
 *
 * @param bool|null $cli null なら実行環境から判定する(テスト用の差し替え)
 */
function letsblog_sns_on_transition($new_status, $old_status, $post, ?bool $cli = null): bool
{
    if (!is_object($post) || ($post->post_type ?? '') !== 'post') {
        return false;
    }
    $id = (int) $post->ID;
    if ($old_status === 'publish' && $new_status !== 'publish') {
        // SNS 設定前・機能有効前に公開された記事も、のちの再公開で告知しないよう印を付けておく。
        if (get_post_meta($id, LETSBLOG_META_SNS_ANNOUNCED, true) === '') {
            update_post_meta($id, LETSBLOG_META_SNS_ANNOUNCED, 'published');
        }
        return false;
    }
    if (letsblog_sns_config_all() === []) {
        return false;
    }
    if ($new_status === 'future') {
        // Let's Blog の予約投稿(publish_scheduled_at)など。のちに公開されても告知しない。
        if (get_post_meta($id, LETSBLOG_META_SNS_ANNOUNCED, true) === '') {
            update_post_meta($id, LETSBLOG_META_SNS_ANNOUNCED, 'scheduled');
        }
        return false;
    }
    if ($new_status !== 'publish' || $old_status === 'publish' || $old_status === 'future') {
        return false;
    }
    if (($post->post_password ?? '') !== '' || get_post_meta($id, LETSBLOG_META_SNS_ANNOUNCED, true) !== '') {
        return false;
    }
    update_post_meta($id, LETSBLOG_META_SNS_ANNOUNCED, (string) time());
    $pending = &letsblog_sns_pending();
    if ($cli ?? letsblog_sns_is_cli()) {
        $pending['cli'][$id] = $id;
    } else {
        wp_schedule_single_event(time(), LETSBLOG_CRON_SNS_ANNOUNCE, [$id]);
        $pending['spawn'] = true;
    }
    return true;
}

/** 接続済みの SNS すべてへ送り、結果を告知履歴に残す。何があっても例外を外へ出さない(公開を失敗させない)。 */
function letsblog_sns_announce_post(int $id): void
{
    $post = get_post($id);
    if (!is_object($post) || ($post->post_status ?? '') !== 'publish' || get_post_meta($id, LETSBLOG_META_SNS_SENT, true) !== '') {
        return;
    }
    update_post_meta($id, LETSBLOG_META_SNS_SENT, (string) time());
    foreach (array_keys(letsblog_sns_config_all()) as $sns) {
        try {
            letsblog_sns_announce((string) $sns, 'publish', $id, letsblog_sns_default_text($post), time());
        } catch (Throwable $e) {
            letsblog_sns_log_append([
                'sns' => (string) $sns, 'kind' => 'publish', 'post_id' => $id, 'at' => gmdate('c'),
                'success' => false, 'error' => '告知中に例外が発生しました: ' . $e->getMessage(),
            ]);
        }
    }
}

/** cron のイベント(Web からの公開)。 */
function letsblog_sns_run_scheduled($id): void
{
    letsblog_sns_announce_post((int) $id);
}

/** CLI: 投稿の保存(ターム・メタ)が済んだあとに、その記事を送る。 */
function letsblog_sns_on_after_insert($post_id): void
{
    $pending = &letsblog_sns_pending();
    $id = (int) $post_id;
    if (isset($pending['cli'][$id])) {
        unset($pending['cli'][$id]);
        letsblog_sns_announce_post($id);
    }
}

/** 終了時: CLI で送り残しがあれば送り(wp_after_insert_post を通らない公開経路)、Web で予約したイベントがあれば cron を起動する。 */
function letsblog_sns_on_shutdown(): void
{
    $pending = &letsblog_sns_pending();
    foreach ($pending['cli'] as $id) {
        unset($pending['cli'][$id]);
        letsblog_sns_announce_post((int) $id);
    }
    if ($pending['spawn']) {
        $pending['spawn'] = false;
        if (function_exists('spawn_cron')) {
            spawn_cron();
        }
    }
}

if (function_exists('add_action')) {
    add_action('transition_post_status', 'letsblog_sns_on_transition', 10, 3);
    add_action(LETSBLOG_CRON_SNS_ANNOUNCE, 'letsblog_sns_run_scheduled', 10, 1);
    add_action('wp_after_insert_post', 'letsblog_sns_on_after_insert', 10, 1);
    add_action('shutdown', 'letsblog_sns_on_shutdown');
}

// ---- GA4 の記事別 PV(issue #1576)。プラグインが GA4 Data API へ直接問い合わせる。Let's Blog が止まっていても動く ----

/** OAuth のトークン URL。変えられるのは wp-config.php の定数だけ(e2e でスタブへ向けるため)。アプリから送る設定では変えられない。 */
function letsblog_ga_oauth_token_url(): string
{
    return defined('LETSBLOG_GA_OAUTH_TOKEN_URL') ? (string) LETSBLOG_GA_OAUTH_TOKEN_URL : 'https://oauth2.googleapis.com/token';
}

/** Data API のベース URL。同じく wp-config.php の定数だけで決まる。 */
function letsblog_ga_data_api_base(): string
{
    return rtrim(defined('LETSBLOG_GA_DATA_API_BASE_URL') ? (string) LETSBLOG_GA_DATA_API_BASE_URL : 'https://analyticsdata.googleapis.com', '/');
}

function letsblog_pv_config_entry(): ?array
{
    $entry = get_option(LETSBLOG_OPTION_PV_CONFIG, null);
    return is_array($entry) && is_string($entry['secret'] ?? null) ? $entry : null;
}

/** 復号した認証情報(property_id, client_id, client_secret, refresh_token)。未設定・復号できないときは null。 */
function letsblog_pv_load_credentials(): ?array
{
    $entry = letsblog_pv_config_entry();
    if ($entry === null) {
        return null;
    }
    $secrets = letsblog_sns_decrypt($entry['secret']);
    if ($secrets === null) {
        return null;
    }
    return array_merge(is_array($entry['public'] ?? null) ? $entry['public'] : [], $secrets);
}

/**
 * config set の入力(JSON のオブジェクト)を検証して、保存する項目だけに絞る。未知の項目(向き先の URL など)は捨てる。
 *
 * @return array{0: ?array, 1: ?string} [認証情報, エラー]
 */
function letsblog_pv_normalize_input(array $decoded): array
{
    $propertyId = $decoded['property_id'] ?? null;
    if (is_int($propertyId)) {
        $propertyId = (string) $propertyId;
    }
    if (!is_string($propertyId) || preg_match('/^\d{1,20}$/', $propertyId) !== 1) {
        return [null, 'property_id は数字のプロパティ ID にしてください'];
    }
    $cred = ['property_id' => $propertyId];
    foreach (['client_id', 'client_secret', 'refresh_token'] as $field) {
        $value = $decoded[$field] ?? null;
        if (!is_string($value) || $value === '') {
            return [null, "{$field} が必要です(文字列)"];
        }
        $cred[$field] = $value;
    }
    return [$cred, null];
}

function letsblog_pv_save_credentials(array $cred): void
{
    $previous = letsblog_pv_config_entry();
    $previousId = $previous['public']['property_id'] ?? null;
    update_option(LETSBLOG_OPTION_PV_CONFIG, [
        'public' => ['property_id' => $cred['property_id'], 'client_id' => $cred['client_id']],
        'secret' => letsblog_sns_encrypt(['client_secret' => $cred['client_secret'], 'refresh_token' => $cred['refresh_token']]),
    ], false);
    if ($previousId !== null && $previousId !== $cred['property_id']) {
        // 別のプロパティの値を混ぜない。
        delete_option(LETSBLOG_OPTION_PV_DATA);
        delete_option(LETSBLOG_OPTION_PV_STATUS);
    }
    letsblog_pv_ensure_schedule();
}

function letsblog_pv_clear_credentials(): void
{
    delete_option(LETSBLOG_OPTION_PV_CONFIG);
    if (function_exists('wp_clear_scheduled_hook')) {
        wp_clear_scheduled_hook(LETSBLOG_CRON_PV_FETCH);
    }
}

/** 設定済みなら1時間ごとの取得を予約する(未設定なら予約しない)。 */
function letsblog_pv_ensure_schedule(): void
{
    if (letsblog_pv_config_entry() === null || !function_exists('wp_next_scheduled') || !function_exists('wp_schedule_event')) {
        return;
    }
    if (!wp_next_scheduled(LETSBLOG_CRON_PV_FETCH)) {
        wp_schedule_event(time(), 'hourly', LETSBLOG_CRON_PV_FETCH);
    }
}

function letsblog_pv_status_all(): array
{
    $status = get_option(LETSBLOG_OPTION_PV_STATUS, []);
    return is_array($status) ? $status : [];
}

/** 取得の結果を status に残す。成功すると失敗の理由は消え、失敗しても最後の成功の日時は残る。 */
function letsblog_pv_record_result(bool $ok, ?string $error, int $now): void
{
    $status = letsblog_pv_status_all();
    $status['last_attempt_at'] = gmdate('c', $now);
    if ($ok) {
        $status['last_success_at'] = gmdate('c', $now);
        $status['last_error'] = null;
    } else {
        $status['last_failure_at'] = gmdate('c', $now);
        $status['last_error'] = $error;
    }
    update_option(LETSBLOG_OPTION_PV_STATUS, $status, false);
}

/** 失敗の理由として残す短い説明。秘密の値は取り除く。 */
function letsblog_ga_describe_failure(string $what, $response, array $secrets): string
{
    if (is_wp_error($response)) {
        return "GA4 に接続できません({$what})";
    }
    $code = (int) wp_remote_retrieve_response_code($response);
    $body = json_decode((string) wp_remote_retrieve_body($response), true);
    $parts = [];
    if (is_array($body)) {
        $candidates = [$body['error'] ?? null, $body['error_description'] ?? null];
        if (is_array($body['error'] ?? null)) {
            $candidates = [$body['error']['message'] ?? null];
        }
        foreach ($candidates as $text) {
            if (is_string($text) && $text !== '' && !in_array($text, $parts, true)) {
                $parts[] = $text;
            }
        }
    }
    $detail = $parts === [] ? '' : ': ' . mb_substr(implode(' / ', $parts), 0, 200);
    $message = "GA4 の{$what}に失敗しました(HTTP {$code}{$detail})";
    foreach ($secrets as $secret) {
        if (is_string($secret) && $secret !== '') {
            $message = str_replace($secret, '***', $message);
        }
    }
    return $message;
}

/** 日別 PV を保存したデータ(記事 ID => [YYYYMMDD => PV])と GA プロパティのタイムゾーン。 */
function letsblog_pv_data(): array
{
    $data = get_option(LETSBLOG_OPTION_PV_DATA, []);
    $data = is_array($data) ? $data : [];
    return ['daily' => is_array($data['daily'] ?? null) ? $data['daily'] : [], 'time_zone' => is_string($data['time_zone'] ?? null) ? $data['time_zone'] : 'UTC'];
}

function letsblog_pv_daily_for_post(int $postId): array
{
    return letsblog_pv_data()['daily'][$postId] ?? [];
}

/** 公開日(YYYYMMDD)。公開済みの記事でなければ null。 */
function letsblog_pv_publish_day(int $postId): ?string
{
    $post = get_post($postId);
    if (!is_object($post) || ($post->post_status ?? '') !== 'publish') {
        return null;
    }
    $day = str_replace('-', '', substr((string) ($post->post_date ?? ''), 0, 10));
    return preg_match('/^\d{8}$/', $day) === 1 ? $day : null;
}

/**
 * GA4 から記事ごとの日別 PV を取得して保存する。未設定なら GA には問い合わせない。失敗しても保存済みの値は変えず、理由を status に残す。
 *
 * @return array{ok: bool, skipped?: bool, error: ?string}
 */
function letsblog_pv_fetch(int $now): array
{
    if (letsblog_pv_config_entry() === null) {
        return ['ok' => false, 'skipped' => true, 'error' => null];
    }
    $cred = letsblog_pv_load_credentials();
    if ($cred === null) {
        $error = 'GA4 の認証情報を復号できません(wp-config.php の salt が変わった可能性があります。wp letsblog pv config set で再設定してください)';
        letsblog_pv_record_result(false, $error, $now);
        return ['ok' => false, 'error' => $error];
    }
    $secrets = [$cred['client_secret'], $cred['refresh_token']];
    $token = wp_remote_request(letsblog_ga_oauth_token_url(), [
        'method' => 'POST',
        'timeout' => 15,
        'headers' => ['Content-Type' => 'application/x-www-form-urlencoded'],
        'body' => http_build_query([
            'grant_type' => 'refresh_token',
            'client_id' => $cred['client_id'],
            'client_secret' => $cred['client_secret'],
            'refresh_token' => $cred['refresh_token'],
        ]),
    ]);
    $tokenBody = is_wp_error($token) ? null : json_decode((string) wp_remote_retrieve_body($token), true);
    if (is_wp_error($token) || (int) wp_remote_retrieve_response_code($token) !== 200 || !is_array($tokenBody)
        || !is_string($tokenBody['access_token'] ?? null) || $tokenBody['access_token'] === '') {
        $error = letsblog_ga_describe_failure('トークン更新', $token, $secrets);
        letsblog_pv_record_result(false, $error, $now);
        return ['ok' => false, 'error' => $error];
    }
    $accessToken = $tokenBody['access_token'];
    $secrets[] = $accessToken;

    $homeParts = parse_url(home_url());
    $host = (string) ($homeParts['host'] ?? '');
    $origin = ($homeParts['scheme'] ?? 'https') . '://' . $host . (isset($homeParts['port']) ? ':' . $homeParts['port'] : '');

    $status = letsblog_pv_status_all();
    $lastSuccess = isset($status['last_success_at']) ? strtotime((string) $status['last_success_at']) : false;
    $full = $lastSuccess === false || $now - $lastSuccess > LETSBLOG_PV_GAP_SECONDS;
    $startDate = $full ? LETSBLOG_PV_BACKFILL_START : LETSBLOG_PV_REFETCH_START;

    $fetched = [];
    $timeZone = null;
    $offset = 0;
    for ($page = 0; $page < LETSBLOG_PV_MAX_PAGES; $page++) {
        $response = wp_remote_request(
            letsblog_ga_data_api_base() . '/v1beta/properties/' . rawurlencode($cred['property_id']) . ':runReport',
            [
                'method' => 'POST',
                'timeout' => 30,
                'headers' => ['Authorization' => 'Bearer ' . $accessToken, 'Content-Type' => 'application/json'],
                'body' => json_encode([
                    'dateRanges' => [['startDate' => $startDate, 'endDate' => 'today']],
                    'dimensions' => [['name' => 'date'], ['name' => 'pagePath'], ['name' => 'hostName']],
                    'metrics' => [['name' => 'screenPageViews']],
                    'dimensionFilter' => ['filter' => ['fieldName' => 'hostName', 'stringFilter' => ['matchType' => 'EXACT', 'value' => $host]]],
                    'limit' => LETSBLOG_PV_PAGE_LIMIT,
                    'offset' => $offset,
                ]),
            ]
        );
        $body = is_wp_error($response) ? null : json_decode((string) wp_remote_retrieve_body($response), true);
        if (is_wp_error($response) || (int) wp_remote_retrieve_response_code($response) !== 200 || !is_array($body)) {
            $error = letsblog_ga_describe_failure('レポート取得', $response, $secrets);
            letsblog_pv_record_result(false, $error, $now);
            return ['ok' => false, 'error' => $error];
        }
        if (is_string($body['metadata']['timeZone'] ?? null)) {
            $timeZone = $body['metadata']['timeZone'];
        }
        $rows = is_array($body['rows'] ?? null) ? $body['rows'] : [];
        foreach ($rows as $row) {
            $fetched[] = $row;
        }
        $offset += count($rows);
        if ($rows === [] || $offset >= (int) ($body['rowCount'] ?? 0)) {
            break;
        }
    }

    // 行を記事ごと・日ごとに合算する(クエリ付きのパスは同じ記事に集まる)。このサイトのホスト以外の行と、記事に対応しない行は捨てる。
    $sums = [];
    $postIdByPath = [];
    foreach ($fetched as $row) {
        $dims = $row['dimensionValues'] ?? [];
        $day = (string) ($dims[0]['value'] ?? '');
        $path = (string) ($dims[1]['value'] ?? '');
        if (($dims[2]['value'] ?? '') !== $host || preg_match('/^\d{8}$/', $day) !== 1 || $path === '' || $path[0] !== '/') {
            continue;
        }
        if (!array_key_exists($path, $postIdByPath)) {
            $id = (int) url_to_postid($origin . $path);
            $postIdByPath[$path] = ($id > 0 && letsblog_pv_publish_day($id) !== null) ? $id : 0;
        }
        $id = $postIdByPath[$path];
        if ($id === 0) {
            continue;
        }
        $sums[$id][$day] = ($sums[$id][$day] ?? 0) + (int) ($row['metricValues'][0]['value'] ?? 0);
    }

    $data = letsblog_pv_data();
    foreach ($sums as $id => $days) {
        foreach ($days as $day => $views) {
            $data['daily'][$id][$day] = $views;
        }
        ksort($data['daily'][$id]);
    }
    if ($timeZone !== null) {
        $data['time_zone'] = $timeZone;
    }
    update_option(LETSBLOG_OPTION_PV_DATA, $data, false);
    letsblog_pv_record_result(true, null, $now);
    try {
        // 初回の取得は、それまでの PV が一度に届く。ルールを先に設定していても、過去の達成を告知しない(基準化だけする)。
        letsblog_pv_evaluate($now, $lastSuccess === false);
    } catch (Throwable $e) {
        // 判定の失敗で取得を失敗にしない。
    }
    return ['ok' => true, 'error' => null];
}

/** GA プロパティのタイムゾーンでの、$now の日付(YYYYMMDD)。 */
function letsblog_pv_today(int $now, string $timeZone): string
{
    try {
        $zone = new DateTimeZone($timeZone);
    } catch (Exception $e) {
        $zone = new DateTimeZone('UTC');
    }
    return (new DateTime('@' . $now))->setTimezone($zone)->format('Ymd');
}

/**
 * 記事ごとの当日 PV・累計 PV(公開日以降の日別 PV の合計)・最終取得日時。公開されていない記事は含めない。
 *
 * @return array<int, array{post_id: int, title: string, today_pv: int, total_pv: int, last_fetched_at: ?string}>
 */
function letsblog_pv_list(int $now): array
{
    $data = letsblog_pv_data();
    $today = letsblog_pv_today($now, $data['time_zone']);
    $fetchedAt = letsblog_pv_status_all()['last_success_at'] ?? null;
    $ids = array_map('intval', array_keys($data['daily']));
    sort($ids);
    $list = [];
    foreach ($ids as $id) {
        $publishDay = letsblog_pv_publish_day($id);
        if ($publishDay === null) {
            continue;
        }
        $total = 0;
        foreach ($data['daily'][$id] as $day => $views) {
            if ((string) $day >= $publishDay) {
                $total += (int) $views;
            }
        }
        $list[] = [
            'post_id' => $id,
            'title' => get_the_title($id),
            'today_pv' => ($today >= $publishDay) ? (int) ($data['daily'][$id][$today] ?? 0) : 0,
            'total_pv' => $total,
            'last_fetched_at' => $fetchedAt,
        ];
    }
    return $list;
}

// ---- PV 達成の告知(issue #1577)。ルールは wp letsblog pv rules set で丸ごと置き換える ----

/** 保存済みのルール。各要素は id・period(daily|total)・threshold・added_at(追加した時刻)。 */
function letsblog_pv_rules(): array
{
    $rules = get_option(LETSBLOG_OPTION_PV_RULES, []);
    return is_array($rules) ? array_values($rules) : [];
}

/** 告知済み(基準化を含む)の記録。キーは「記事ID:ルールID」。 */
function letsblog_pv_announced(): array
{
    $announced = get_option(LETSBLOG_OPTION_PV_ANNOUNCED, []);
    return is_array($announced) ? $announced : [];
}

/**
 * 標準入力の JSON(ルールの配列、または {"rules": [...]})を検証する。
 *
 * @return array{0: ?array, 1: ?string} [ルール(id・period・threshold だけ), エラー]
 */
function letsblog_pv_rules_normalize($decoded): array
{
    if (is_array($decoded) && !array_is_list($decoded) && array_key_exists('rules', $decoded)) {
        $decoded = $decoded['rules'];
    }
    if (!is_array($decoded) || !array_is_list($decoded)) {
        return [null, 'ルールの配列(JSON)を渡してください'];
    }
    $rules = [];
    $seen = [];
    foreach ($decoded as $i => $item) {
        $n = $i + 1;
        if (!is_array($item) || array_is_list($item)) {
            return [null, "{$n} 件目: ルールはオブジェクトにしてください"];
        }
        $id = $item['id'] ?? null;
        if (!is_string($id) || preg_match('/^[A-Za-z0-9_-]{1,64}$/', $id) !== 1) {
            return [null, "{$n} 件目: id は英数字・ハイフン・アンダースコアの1〜64文字にしてください"];
        }
        if (isset($seen[$id])) {
            return [null, "{$n} 件目: id が重複しています: {$id}"];
        }
        $seen[$id] = true;
        $period = $item['period'] ?? null;
        if ($period !== 'daily' && $period !== 'total') {
            return [null, "{$n} 件目: period は daily(1日)か total(累計)にしてください"];
        }
        $threshold = $item['threshold'] ?? null;
        if (!is_int($threshold) || $threshold < 1) {
            return [null, "{$n} 件目: threshold は正の整数にしてください"];
        }
        $rules[] = ['id' => $id, 'period' => $period, 'threshold' => $threshold];
    }
    return [$rules, null];
}

function letsblog_pv_rule_same(array $a, array $b): bool
{
    return ($a['id'] ?? null) === ($b['id'] ?? null) && ($a['period'] ?? null) === ($b['period'] ?? null)
        && ($a['threshold'] ?? null) === ($b['threshold'] ?? null);
}

/** 記事がルールを達成しているか。累計は公開日以降の合計、1日は(公開日と追加した日の遅いほう)以降の日別 PV で判定する。 */
function letsblog_pv_rule_achieved(array $rule, array $daily, string $publishDay, string $addedDay): bool
{
    if ($rule['period'] === 'total') {
        $total = 0;
        foreach ($daily as $day => $views) {
            if ((string) $day >= $publishDay) {
                $total += (int) $views;
            }
        }
        return $total >= $rule['threshold'];
    }
    $from = max($publishDay, $addedDay);
    foreach ($daily as $day => $views) {
        if ((string) $day >= $from && (int) $views >= $rule['threshold']) {
            return true;
        }
    }
    return false;
}

/** 「1日で100PV」「累計5000PV」のような、達成した内容。 */
function letsblog_pv_rule_label(array $rule): string
{
    return ($rule['period'] === 'daily' ? '1日で' : '累計') . $rule['threshold'] . 'PV';
}

/**
 * ルールを丸ごと置き換える。内容が変わらない既存ルールは追加時刻と告知済みの記録を引き継ぎ、消えたルールの記録は捨てる。
 * 新しいルール(内容が変わったものを含む)は、取得済みの PV ですでに達成している記事を告知済みとして記録する。
 */
function letsblog_pv_rules_set(array $rules, int $now): void
{
    $old = [];
    foreach (letsblog_pv_rules() as $rule) {
        $old[(string) ($rule['id'] ?? '')] = $rule;
    }
    $data = letsblog_pv_data();
    $announced = letsblog_pv_announced();
    $kept = [];
    foreach ($announced as $key => $at) {
        $ruleId = substr((string) $key, (int) strpos((string) $key, ':') + 1);
        if (isset($old[$ruleId]) && letsblog_pv_rules_has($rules, $old[$ruleId])) {
            $kept[$key] = $at;
        }
    }
    $stored = [];
    foreach ($rules as $rule) {
        $existing = $old[$rule['id']] ?? null;
        if ($existing !== null && letsblog_pv_rule_same($existing, $rule)) {
            $rule['added_at'] = (int) ($existing['added_at'] ?? $now);
            $stored[] = $rule;
            continue;
        }
        $rule['added_at'] = $now;
        $stored[] = $rule;
        $addedDay = letsblog_pv_today($now, $data['time_zone']);
        foreach ($data['daily'] as $postId => $daily) {
            $publishDay = letsblog_pv_publish_day((int) $postId);
            if ($publishDay !== null && letsblog_pv_rule_achieved($rule, $daily, $publishDay, $addedDay)) {
                $kept[$postId . ':' . $rule['id']] = gmdate('c', $now);
            }
        }
    }
    update_option(LETSBLOG_OPTION_PV_RULES, $stored, false);
    update_option(LETSBLOG_OPTION_PV_ANNOUNCED, $kept, false);
}

function letsblog_pv_rules_has(array $rules, array $rule): bool
{
    foreach ($rules as $candidate) {
        if (letsblog_pv_rule_same($candidate, $rule)) {
            return true;
        }
    }
    return false;
}

/**
 * 保存済みの PV でルールを判定し、新しく達成した記事を接続済みの全 SNS へ告知する。
 * 告知済みの印は送る前に付ける(失敗しても再送しない)。接続済みの SNS が無ければ送らず、印も付けない。
 * $baselineOnly が true なら、達成している組を告知せずに記録だけする。
 */
function letsblog_pv_evaluate(int $now, bool $baselineOnly = false): void
{
    $rules = letsblog_pv_rules();
    if ($rules === []) {
        return;
    }
    $snsList = array_keys(letsblog_sns_config_all());
    if (!$baselineOnly && $snsList === []) {
        return;
    }
    $data = letsblog_pv_data();
    $ids = array_map('intval', array_keys($data['daily']));
    sort($ids);
    foreach ($rules as $rule) {
        $addedDay = letsblog_pv_today((int) ($rule['added_at'] ?? $now), $data['time_zone']);
        foreach ($ids as $id) {
            $key = $id . ':' . $rule['id'];
            $publishDay = letsblog_pv_publish_day($id);
            if ($publishDay === null || isset(letsblog_pv_announced()[$key])
                || !letsblog_pv_rule_achieved($rule, $data['daily'][$id], $publishDay, $addedDay)) {
                continue;
            }
            $announced = letsblog_pv_announced();
            $announced[$key] = gmdate('c', $now);
            update_option(LETSBLOG_OPTION_PV_ANNOUNCED, $announced, false);
            if ($baselineOnly) {
                continue;
            }
            $text = letsblog_sns_default_text(get_post($id)) . "\n" . letsblog_pv_rule_label($rule) . ' を達成しました';
            foreach ($snsList as $sns) {
                try {
                    letsblog_sns_announce((string) $sns, LETSBLOG_SNS_KIND_PV, $id, $text, $now);
                } catch (Throwable $e) {
                    letsblog_sns_log_append([
                        'sns' => (string) $sns, 'kind' => LETSBLOG_SNS_KIND_PV, 'post_id' => $id, 'at' => gmdate('c', $now),
                        'success' => false, 'error' => '告知中に例外が発生しました: ' . $e->getMessage(),
                    ]);
                }
            }
        }
    }
}

function letsblog_pv_run_scheduled(): void
{
    letsblog_pv_fetch(time());
}

if (function_exists('add_action')) {
    add_action(LETSBLOG_CRON_PV_FETCH, 'letsblog_pv_run_scheduled');
    add_action('init', 'letsblog_pv_ensure_schedule');
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

        /**
         * GA4 の記事別 PV の設定・状態・一覧。秘密情報は引数では受け取らず、標準入力の JSON で渡す。
         * 取得は WP-Cron で1時間ごとに行う(設定すると予約される)。
         *
         * ## OPTIONS
         *
         * [<args>...]
         * : `config set`(標準入力に JSON: property_id, client_id, client_secret, refresh_token)、
         *   `config clear`、`status`、`list --format=json`、
         *   `rules set`(標準入力に JSON: [{id, period: daily|total, threshold}]。受け取った内容でルールを丸ごと置き換える)。
         *
         * [--format=<format>]
         * : `list` の出力形式。json のみ。
         *
         * ## EXAMPLES
         *
         *     wp letsblog pv config set < ga4.json
         *     wp letsblog pv status
         *     wp letsblog pv list --format=json
         *     wp letsblog pv rules set < rules.json
         *     wp letsblog pv config clear
         */
        public function pv($args, $assoc_args)
        {
            $sub = $args[0] ?? '';
            if ($sub === 'config') {
                $this->pv_config($args[1] ?? '', $assoc_args);
            } elseif ($sub === 'status') {
                $entry = letsblog_pv_config_entry();
                $status = letsblog_pv_status_all();
                WP_CLI::line(json_encode([
                    'configured' => $entry !== null,
                    'property_id' => $entry['public']['property_id'] ?? null,
                    'state' => $entry === null ? LETSBLOG_SNS_STATUS_UNSET
                        : (letsblog_pv_load_credentials() === null ? '要再設定' : LETSBLOG_SNS_STATUS_CONNECTED),
                    'last_attempt_at' => $status['last_attempt_at'] ?? null,
                    'last_success_at' => $status['last_success_at'] ?? null,
                    'last_failure_at' => $status['last_failure_at'] ?? null,
                    'last_error' => $status['last_error'] ?? null,
                ], JSON_UNESCAPED_UNICODE));
            } elseif ($sub === 'list') {
                if (($assoc_args['format'] ?? 'json') !== 'json') {
                    WP_CLI::error('--format は json だけです');
                    return;
                }
                WP_CLI::line(json_encode(letsblog_pv_list($this->now()), JSON_UNESCAPED_UNICODE));
            } elseif ($sub === 'rules') {
                $this->pv_rules($args[1] ?? '');
            } else {
                WP_CLI::error('サブコマンドは config set / config clear / status / list / rules set のどれかです');
            }
        }

        private function pv_rules(string $action): void
        {
            if ($action !== 'set') {
                WP_CLI::error('rules のサブコマンドは set です');
                return;
            }
            [$rules, $error] = letsblog_pv_rules_normalize(json_decode($this->read_stdin(), true));
            if ($rules === null) {
                WP_CLI::error((string) $error);
                return;
            }
            letsblog_pv_rules_set($rules, $this->now());
            WP_CLI::line(json_encode(['rules' => count($rules)]));
        }

        private function pv_config(string $action, array $assoc_args): void
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
                [$cred, $error] = letsblog_pv_normalize_input($decoded);
                if ($cred === null) {
                    WP_CLI::error((string) $error);
                    return;
                }
                letsblog_pv_save_credentials($cred);
                WP_CLI::line(json_encode(['property_id' => $cred['property_id'], 'status' => LETSBLOG_SNS_STATUS_CONNECTED], JSON_UNESCAPED_UNICODE));
            } elseif ($action === 'clear') {
                letsblog_pv_clear_credentials();
                WP_CLI::line(json_encode(['cleared' => true]));
            } else {
                WP_CLI::error('config のサブコマンドは set か clear です');
            }
        }

        protected function now(): int
        {
            return time();
        }

        protected function read_stdin(): string
        {
            $raw = file_get_contents('php://stdin');
            return is_string($raw) ? $raw : '';
        }
    }

    WP_CLI::add_command('letsblog', 'Letsblog_CLI_Command');
}
