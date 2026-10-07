<?php
/**
 * letsblog プラグインのはてなブックマーク送信処理(issue #1582。Epic #1572。基盤は #1573)のCLIテスト。
 * wp letsblog sns config set / status / test / log、consumer secret・アクセストークン・アクセストークンの秘密の暗号化保存、
 * OAuth 1.0a(HMAC-SHA1)で署名して POST /rest/1/my/bookmark へ url と comment(100文字に切り詰め)を送ること、
 * 要件を満たさない(有効な http(s) の URL が無い・認証情報が空・API がエラー)ときは投稿せず(または失敗として)
 * 理由を告知履歴に残すこと(HTTP 401 は要再接続)。はてな API は WordPress の HTTP API(wp_remote_request)の差し替えで再現し、
 * 署名もその場で検証する。実スタブ(infra/e2e-stubs/hatena)との突き合わせは infra/e2e-stubs/hatena/server.test.js が受け持つ。
 * 実行: timeout 60 php __tests__/test-letsblog-hatena.php
 */

declare(strict_types=1);

$failures = [];

function check(string $name, bool $condition, string $detail = ''): void
{
    global $failures;
    if ($condition) {
        echo "PASS: $name\n";
    } else {
        echo "FAIL: $name" . ($detail !== '' ? " ($detail)" : '') . "\n";
        $failures[] = $name;
    }
}

class WP_CLI
{
    public static array $commands = [];
    public static array $lines = [];

    public static function add_command(string $name, $callable): void
    {
        self::$commands[$name] = $callable;
    }

    public static function line(string $text): void
    {
        self::$lines[] = $text;
    }

    public static function error(string $message): void
    {
        throw new RuntimeException($message);
    }
}

class WP_Error
{
}

$GLOBALS['t_options'] = [];
function get_option(string $name, $default = false)
{
    return array_key_exists($name, $GLOBALS['t_options']) ? $GLOBALS['t_options'][$name] : $default;
}
function update_option(string $name, $value, $autoload = null): bool
{
    $GLOBALS['t_options'][$name] = $value;
    return true;
}
function delete_option(string $name): bool
{
    unset($GLOBALS['t_options'][$name]);
    return true;
}
function wp_salt(string $scheme = 'auth'): string
{
    return 'salt-A-' . $scheme;
}

$GLOBALS['t_actions'] = [];
$GLOBALS['t_meta'] = [];
$GLOBALS['t_posts'] = [];
function add_action(string $hook, $callable, int $priority = 10, int $args = 1): bool
{
    $GLOBALS['t_actions'][$hook][] = ['fn' => $callable, 'priority' => $priority, 'args' => $args];
    return true;
}
function add_filter(string $hook, $callable, int $priority = 10, int $args = 1): bool
{
    return add_action($hook, $callable, $priority, $args);
}
function get_post_meta(int $id, string $key, bool $single = false)
{
    return $GLOBALS['t_meta'][$id][$key] ?? '';
}
function update_post_meta(int $id, string $key, $value): bool
{
    $GLOBALS['t_meta'][$id][$key] = $value;
    return true;
}
function get_post($id)
{
    return $GLOBALS['t_posts'][$id] ?? null;
}
function get_the_title($post = 0): string
{
    return (string) (is_object($post) ? $post->post_title : ($GLOBALS['t_posts'][$post]->post_title ?? ''));
}
function get_permalink($post = 0)
{
    $id = is_object($post) ? $post->ID : $post;
    return 'https://blog.example.test/?p=' . $id;
}

// ---- はてなブックマーク API の差し替え(wp_remote_request。OAuth 1.0a の署名もここで検証する) ----
const HB_BASE = 'https://hatena-stub.test';
define('LETSBLOG_HATENA_API_BASE_URL', HB_BASE);
const HB_CONSUMER_KEY = 'ck-key';
const HB_CONSUMER_SECRET = 'ck-secret-value';
const HB_TOKEN = 'at-token-value';
const HB_TOKEN_SECRET = 'at-secret-value';
$GLOBALS['hb'] = [];
function hb_reset(): void
{
    $GLOBALS['hb'] = ['requests' => [], 'bookmarks' => [], 'fail' => null, 'network_down' => false, 'bad_signature' => 0, 'revoked' => false];
}
function is_wp_error($thing): bool
{
    return $thing instanceof WP_Error;
}
function wp_remote_retrieve_response_code($response)
{
    return $response['response']['code'] ?? 0;
}
function wp_remote_retrieve_body($response)
{
    return $response['body'] ?? '';
}
function home_url(string $path = ''): string
{
    return 'https://blog.example.test' . $path;
}
function wp_remote_request(string $url, array $args = [])
{
    $hb = &$GLOBALS['hb'];
    $hb['requests'][] = ['url' => $url, 'args' => $args];
    if ($hb['network_down']) {
        return new WP_Error();
    }
    $reply = fn(int $code, array $body) => ['response' => ['code' => $code], 'body' => json_encode($body)];
    if (!str_starts_with($url, HB_BASE)) {
        return new WP_Error();
    }
    if ($hb['fail'] !== null) {
        return $reply($hb['fail'], ['message' => 'forced failure']);
    }
    $path = parse_url(substr($url, strlen(HB_BASE)), PHP_URL_PATH);
    if ($path !== '/rest/1/my/bookmark' || ($args['method'] ?? '') !== 'POST') {
        return $reply(404, []);
    }
    // Authorization: OAuth k="v", ... を取り出し、署名を独立に計算して突き合わせる。
    $header = (string) ($args['headers']['Authorization'] ?? '');
    $oauth = [];
    if (str_starts_with($header, 'OAuth ') && preg_match_all('/(oauth_[a-z_]+)="([^"]*)"/', $header, $m, PREG_SET_ORDER)) {
        foreach ($m as $pair) {
            $oauth[$pair[1]] = rawurldecode($pair[2]);
        }
    }
    parse_str((string) ($args['body'] ?? ''), $form);
    $signed = array_merge($form, array_diff_key($oauth, ['oauth_signature' => 1]));
    ksort($signed);
    $pairs = [];
    foreach ($signed as $k => $v) {
        $pairs[] = rawurlencode((string) $k) . '=' . rawurlencode((string) $v);
    }
    $base = 'POST&' . rawurlencode($url) . '&' . rawurlencode(implode('&', $pairs));
    $key = rawurlencode(HB_CONSUMER_SECRET) . '&' . rawurlencode(HB_TOKEN_SECRET);
    $expected = base64_encode(hash_hmac('sha1', $base, $key, true));
    $valid = ($oauth['oauth_consumer_key'] ?? '') === HB_CONSUMER_KEY
        && ($oauth['oauth_token'] ?? '') === HB_TOKEN
        && ($oauth['oauth_signature_method'] ?? '') === 'HMAC-SHA1'
        && ($oauth['oauth_version'] ?? '') === '1.0'
        && ($oauth['oauth_nonce'] ?? '') !== '' && ctype_digit($oauth['oauth_timestamp'] ?? '')
        && hash_equals($expected, $oauth['oauth_signature'] ?? '');
    if (!$valid || $hb['revoked']) {
        $hb['bad_signature']++;
        return ['response' => ['code' => 401], 'body' => 'oauth_problem=signature_invalid'];
    }
    if (!isset($form['url']) || $form['url'] === '') {
        return $reply(400, ['message' => 'url is required']);
    }
    $hb['bookmarks'][] = ['url' => $form['url'], 'comment' => $form['comment'] ?? '', 'headers' => $args['headers']];
    return $reply(200, ['url' => $form['url'], 'comment' => $form['comment'] ?? '']);
}
function hb_bookmarks(): array
{
    return $GLOBALS['hb']['bookmarks'];
}
hb_reset();

define('WP_CLI', true);
define('LETSBLOG_PLUGIN_TESTING', true);
require __DIR__ . '/../../letsblog-plugin/letsblog.php';

class TestSnsCommand extends Letsblog_CLI_Command
{
    public string $stdin = '';
    protected function read_stdin(): string
    {
        return $this->stdin;
    }
}

$cmd = new TestSnsCommand();
function run_sns(array $args, array $assoc = [], ?string $stdin = null): array
{
    global $cmd;
    WP_CLI::$lines = [];
    $cmd->stdin = $stdin ?? '';
    $err = null;
    try {
        $cmd->sns($args, $assoc);
    } catch (RuntimeException $e) {
        $err = $e->getMessage();
    }
    return [$err, WP_CLI::$lines];
}
function status_of(string $sns): ?array
{
    [, $lines] = run_sns(['status']);
    $out = json_decode($lines[0] ?? '', true);
    return is_array($out) ? ($out[$sns] ?? null) : null;
}
function hb_config(array $override = []): string
{
    return json_encode(array_merge(['sns' => 'hatena', 'consumer_key' => HB_CONSUMER_KEY, 'consumer_secret' => HB_CONSUMER_SECRET, 'access_token' => HB_TOKEN, 'access_token_secret' => HB_TOKEN_SECRET, 'account_name' => '公式アカウント'], $override));
}
function hb_log(): array
{
    [, $lines] = run_sns(['log'], ['format' => 'json']);
    $decoded = json_decode($lines[0] ?? '', true);
    return is_array($decoded) ? $decoded : [];
}
function hb_reset_all(): void
{
    run_sns(['config', 'clear']);
    $GLOBALS['t_options'][LETSBLOG_OPTION_SNS_LOG] = [];
    hb_reset();
}
function last_log(): array
{
    $log = hb_log();
    return $log === [] ? [] : $log[count($log) - 1];
}

// --- 登録 ---
check('はてなブックマークの送信処理が登録されている', isset(letsblog_sns_senders()['hatena']));
check('status は未設定のはてなブックマークを「未設定」と返す', (status_of('hatena')['status'] ?? null) === '未設定');

// --- OAuth 1.0a の署名(公開されているテストベクタ: Twitter の「Creating a signature」の例) ---
$sig = letsblog_hatena_oauth_signature('POST', 'https://api.twitter.com/1.1/statuses/update.json', [
    'include_entities' => 'true',
    'oauth_consumer_key' => 'xvz1evFS4wEEPTGEFPHBog',
    'oauth_nonce' => 'kYjzVBB8Y0ZFabxSWbWovY3uYSQ2pTgmZeNu2VS4cg',
    'oauth_signature_method' => 'HMAC-SHA1',
    'oauth_timestamp' => '1318622958',
    'oauth_token' => '370773112-GmHxMAgYyLbNEtIKZeRNFsMKPR9EyMZeS9weJAEb',
    'oauth_version' => '1.0',
    'status' => 'Hello Ladies + Gentlemen, a signed OAuth request!',
], 'kAcSOqF21Fu85e7zjz7ZN2U4ZRhfV3WpwPAoE3Z7kBw', 'LswwdoUaIvS8ltyTt5jkRh4J50vUPVVHtR2YPi5kE');
check('署名: 既知のテストベクタと一致する', $sig === 'hCtSmYh+iHYCEqBWrE7C7hYmtUk=', $sig);

// --- 接続と秘密の暗号化 ---
[$err, $lines] = run_sns(['config', 'set'], [], hb_config(['api_base_url' => 'https://evil.example']));
check('config set: 成功する', $err === null, (string) $err);
$st = status_of('hatena');
check('status: 「接続済み」', ($st['status'] ?? null) === '接続済み');
check('status: アカウント名を返す', ($st['account_name'] ?? null) === '公式アカウント');
$dump = json_encode($GLOBALS['t_options']);
$printed = implode("\n", WP_CLI::$lines) . implode("\n", $lines) . json_encode($st);
foreach ([HB_CONSUMER_SECRET, HB_TOKEN, HB_TOKEN_SECRET] as $secret) {
    check("DB に {$secret} が平文で残らない", !str_contains($dump, $secret));
    check("出力に {$secret} が出ない", !str_contains($printed, $secret));
}
check('未知の項目(api_base_url)は保存しない', !str_contains($dump, 'evil.example'));
check('consumer key は公開項目として DB に持つ', str_contains($dump, HB_CONSUMER_KEY));
foreach (['consumer_key', 'consumer_secret', 'access_token', 'access_token_secret'] as $field) {
    $input = json_decode(hb_config(), true);
    unset($input[$field]);
    [$err] = run_sns(['config', 'set'], [], json_encode($input));
    check("config set: {$field} が無ければ拒否する", $err !== null && str_contains($err, $field), (string) $err);
    $input[$field] = '';
    [$err] = run_sns(['config', 'set'], [], json_encode($input));
    check("config set: {$field} が空なら拒否する", $err !== null);
}
run_sns(['config', 'set'], [], hb_config());

// --- テスト投稿: サイトの URL をブックマークし、署名が正しい ---
hb_reset();
[$err] = run_sns(['test', 'hatena']);
check('test: 成功する', $err === null, (string) $err);
check('test: 1回ブックマークされた', count(hb_bookmarks()) === 1);
$bm = hb_bookmarks()[0] ?? ['url' => null, 'comment' => null, 'headers' => []];
check('test: url はサイトの URL', $bm['url'] === 'https://blog.example.test/', (string) $bm['url']);
check('test: comment に接続テストが含まれ、URL は含めない', str_contains((string) $bm['comment'], '接続テスト') && !str_contains((string) $bm['comment'], 'https://'));
check('test: 署名は検証を通る(不正な署名は 0 回)', $GLOBALS['hb']['bad_signature'] === 0);
$req = $GLOBALS['hb']['requests'][0];
check('test: POST /rest/1/my/bookmark へ送る', $req['url'] === HB_BASE . '/rest/1/my/bookmark' && ($req['args']['method'] ?? '') === 'POST');
check('test: 秘密は URL・本文に載せない', !str_contains($req['url'], HB_TOKEN) && !str_contains((string) $req['args']['body'], HB_TOKEN) && !str_contains((string) $req['args']['body'], HB_CONSUMER_SECRET) && !str_contains(json_encode($req['args']['headers']), HB_CONSUMER_SECRET) && !str_contains(json_encode($req['args']['headers']), HB_TOKEN_SECRET));
$entry = last_log();
check('test: 告知履歴に成功として記録される', ($entry['sns'] ?? '') === 'hatena' && ($entry['kind'] ?? '') === 'test' && ($entry['success'] ?? false) === true && $entry['error'] === null);

// --- 告知文 → url と comment ---
$parts = letsblog_hatena_split_text("新しい記事\nhttps://blog.example.test/?p=1");
check('分割: 最後の URL が url、残りが comment', $parts === ['comment' => '新しい記事', 'url' => 'https://blog.example.test/?p=1'], json_encode($parts, JSON_UNESCAPED_UNICODE));
$parts = letsblog_hatena_split_text('本文 https://blog.example.test/a と続き');
check('分割: 文中の URL も url にし、comment から取り除く', $parts['url'] === 'https://blog.example.test/a' && !str_contains((string) $parts['comment'], 'https://'), json_encode($parts, JSON_UNESCAPED_UNICODE));
check('分割: URL が無ければ url は null', letsblog_hatena_split_text('URL の無い文')['url'] === null);
check('分割: http 以外のスキームは URL とみなさない', letsblog_hatena_split_text('ftp://example.test/a javascript:alert(1)')['url'] === null);
check('分割: 空の告知文', letsblog_hatena_split_text("  \n ") === ['comment' => '', 'url' => null]);
check('分割: URL だけなら comment は空', letsblog_hatena_split_text('https://blog.example.test/x')['comment'] === '');

// --- comment は100文字に切り詰める ---
check('comment: 100文字以内ならそのまま', letsblog_hatena_fit_comment(str_repeat('あ', 100)) === str_repeat('あ', 100));
$cut = letsblog_hatena_fit_comment(str_repeat('あ', 101));
check('comment: 101文字は「…」を付けて100文字に収める', mb_strlen($cut) === 100 && str_ends_with($cut, '…'), (string) mb_strlen($cut));
hb_reset_all();
run_sns(['config', 'set'], [], hb_config());
hb_reset();
$r = letsblog_sns_announce('hatena', 'publish', 1, str_repeat('い', 300) . "\nhttps://blog.example.test/?p=1", time());
$bm = hb_bookmarks()[0] ?? ['url' => null, 'comment' => ''];
check('長い告知文: comment は100文字以内、url はそのまま', $r['ok'] && mb_strlen((string) $bm['comment']) === 100 && $bm['url'] === 'https://blog.example.test/?p=1', (string) mb_strlen((string) $bm['comment']));
hb_reset();
letsblog_sns_announce('hatena', 'publish', 1, "題名 & 記号 = + \"引用\"\nhttps://blog.example.test/?p=1&q=a", time());
$bm = hb_bookmarks()[0] ?? ['url' => null, 'comment' => ''];
check('記号を含む comment も署名が通り、そのまま届く', ($bm['comment'] ?? '') === '題名 & 記号 = + "引用"' && $GLOBALS['hb']['bad_signature'] === 0, json_encode($bm, JSON_UNESCAPED_UNICODE));

// --- 要件を満たさない: 有効な URL が無い → 投稿せず理由 ---
hb_reset();
$r = letsblog_sns_announce('hatena', 'publish', 2, 'URL の無い告知文', time());
$entry = last_log();
check('URL なし: API を呼ばず投稿しない', $GLOBALS['hb']['requests'] === [] && !$r['ok']);
check('URL なし: 理由(URL)が告知履歴に残る', ($entry['success'] ?? true) === false && str_contains((string) ($entry['error'] ?? ''), 'URL'), json_encode($entry, JSON_UNESCAPED_UNICODE));
check('URL なし: 要再接続にはしない', (status_of('hatena')['status'] ?? null) === '接続済み');

// --- 要件を満たさない: 認証情報が空 → 投稿せず理由 ---
foreach (['consumer_secret', 'access_token', 'access_token_secret', 'consumer_key'] as $field) {
    hb_reset();
    $r = letsblog_hatena_send(array_merge(json_decode(hb_config(), true), [$field => '']), "題名\nhttps://blog.example.test/?p=1", time());
    check("{$field} が空: API を呼ばず、理由を返す", $r['ok'] === false && $GLOBALS['hb']['requests'] === [] && str_contains((string) $r['error'], $field), json_encode($r, JSON_UNESCAPED_UNICODE));
}

// --- API の失敗: HTTP 401 → 要再接続 ---
hb_reset_all();
run_sns(['config', 'set'], [], hb_config());
hb_reset();
$GLOBALS['hb']['revoked'] = true;
run_sns(['test', 'hatena']);
$entry = last_log();
check('HTTP 401: ブックマークされない', hb_bookmarks() === []);
check('HTTP 401: 理由(HTTP 401)が残り、秘密は含まれない', str_contains((string) ($entry['error'] ?? ''), '401') && !str_contains((string) ($entry['error'] ?? ''), HB_TOKEN) && !str_contains((string) ($entry['error'] ?? ''), HB_CONSUMER_SECRET), json_encode($entry, JSON_UNESCAPED_UNICODE));
check('HTTP 401: 要再接続になる', (status_of('hatena')['status'] ?? null) === '要再接続');
check('HTTP 401: 再試行しない', count($GLOBALS['hb']['requests']) === 1);

// --- 400 / 429 / 500・通信不能は理由だけ(要再接続にしない) ---
foreach ([400, 429, 500] as $code) {
    hb_reset_all();
    run_sns(['config', 'set'], [], hb_config());
    hb_reset();
    $GLOBALS['hb']['fail'] = $code;
    run_sns(['test', 'hatena']);
    $entry = last_log();
    check("HTTP {$code}: 失敗として HTTP ステータスと理由が残る", ($entry['success'] ?? true) === false && str_contains((string) ($entry['error'] ?? ''), (string) $code) && str_contains((string) ($entry['error'] ?? ''), 'forced failure'), json_encode($entry, JSON_UNESCAPED_UNICODE));
    check("HTTP {$code}: 再接続は求めない", (status_of('hatena')['status'] ?? null) === '接続済み' && hb_bookmarks() === []);
}
hb_reset();
$GLOBALS['hb']['network_down'] = true;
run_sns(['test', 'hatena']);
check('接続できないとき理由を残し、再接続は求めない', str_contains((string) (last_log()['error'] ?? ''), '接続できません') && (status_of('hatena')['status'] ?? null) === '接続済み');
hb_reset();
$GLOBALS['hb']['fail'] = 500;
$GLOBALS['hb']['requests'] = [];
$plain = ['response' => ['code' => 502], 'body' => 'Bad Gateway'];
check('本文が JSON でない失敗でも HTTP ステータスを理由にする', str_contains(letsblog_hatena_describe_failure('投稿', $plain), '502'));

// --- 送信結果の形 ---
hb_reset();
$r = letsblog_hatena_send(json_decode(hb_config(), true), "x\nhttps://blog.example.test/", time());
check('送信結果は ok と cred を返す', $r['ok'] === true && ($r['cred']['consumer_key'] ?? '') === HB_CONSUMER_KEY && $r['needs_reconnect'] === false);

// --- 公開時の告知(#1575) ---
hb_reset_all();
run_sns(['config', 'set'], [], hb_config());
hb_reset();
$GLOBALS['t_posts'][50] = (object) ['ID' => 50, 'post_type' => 'post', 'post_password' => '', 'post_title' => '新しい記事', 'post_status' => 'publish'];
letsblog_sns_announce_post(50);
letsblog_sns_announce_post(50);
check('公開した記事ははてなブックマークへ1回だけ、タイトルを comment・URL を url にして投稿される', count(hb_bookmarks()) === 1
    && hb_bookmarks()[0]['comment'] === '新しい記事' && hb_bookmarks()[0]['url'] === 'https://blog.example.test/?p=50');
$entry = last_log();
check('公開の告知が履歴に残る', ($entry['kind'] ?? '') === 'publish' && ($entry['post_id'] ?? null) === 50 && ($entry['success'] ?? false) === true);

// --- 切断 ---
run_sns(['config', 'clear', 'hatena']);
check('config clear hatena: 未設定に戻る', (status_of('hatena')['status'] ?? null) === '未設定');
hb_reset();
[$err] = run_sns(['test', 'hatena']);
check('切断後は送らない', $err !== null && $GLOBALS['hb']['requests'] === []);

// --- 復号できない ---
run_sns(['config', 'set'], [], hb_config());
$GLOBALS['t_options'][LETSBLOG_OPTION_SNS_CONFIG]['hatena']['secret'] = 'v1:broken';
check('復号できなければ要再接続', (status_of('hatena')['status'] ?? null) === '要再接続');

if ($failures) {
    echo count($failures) . ' 件失敗:' . "\n";
    foreach ($failures as $f) {
        echo "  - $f\n";
    }
    exit(1);
}
echo 'すべて成功' . "\n";
exit(0);
