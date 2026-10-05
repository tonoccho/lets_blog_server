<?php
/**
 * letsblog プラグインの Facebook ページ送信処理(issue #1580。Epic #1572。基盤は #1573)のCLIテスト。
 * wp letsblog sns config set / status / test / log、ページのトークンの暗号化保存、`/{page_id}/feed` へリンク付きで投稿すること
 * (個人アカウント(/me)には投稿しない)、投稿先のページが無い・トークンが失効している・権限が無いときは投稿せず
 * 理由を告知履歴に残すこと。Facebook(Graph API)は WordPress の HTTP API(wp_remote_request)の差し替えで再現する。
 * 実スタブ(infra/e2e-stubs/facebook)との突き合わせは infra/e2e-stubs/facebook/server.test.js が受け持つ。
 * 実行: timeout 60 php __tests__/test-letsblog-facebook.php
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

// ---- Facebook(Graph API)の差し替え(wp_remote_request) ----
const FB_BASE = 'https://facebook-stub.test';
define('LETSBLOG_FACEBOOK_API_BASE_URL', FB_BASE);
const FB_PAGE = '100000000000001';
$GLOBALS['fb'] = [];
function fb_reset(): void
{
    $GLOBALS['fb'] = ['valid' => ['page-tok'], 'requests' => [], 'published' => [], 'fail' => [], 'network_down' => false, 'error_code' => null, 'no_id' => false];
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
function wp_remote_request(string $url, array $args = [])
{
    $fb = &$GLOBALS['fb'];
    $fb['requests'][] = ['url' => $url, 'args' => $args];
    if ($fb['network_down']) {
        return new WP_Error();
    }
    $reply = fn(int $code, array $body) => ['response' => ['code' => $code], 'body' => json_encode($body)];
    if (!str_starts_with($url, FB_BASE)) {
        return new WP_Error();
    }
    $parts = parse_url(substr($url, strlen(FB_BASE)));
    $path = $parts['path'];
    parse_str((string) ($args['body'] ?? ''), $form);
    $auth = $args['headers']['Authorization'] ?? '';
    foreach ($fb['fail'] as $step => $code) {
        if (str_ends_with($path, $step)) {
            $err = ['message' => 'forced failure', 'type' => 'ApiException', 'code' => $fb['error_code'] ?? 2];
            return $reply($code, ['error' => $err]);
        }
    }
    if ($path === '/' . FB_PAGE . '/feed') {
        if (!str_starts_with($auth, 'Bearer ') || !in_array(substr($auth, 7), $fb['valid'], true)) {
            return $reply(400, ['error' => ['message' => 'Error validating access token: Session has expired', 'type' => 'OAuthException', 'code' => 190]]);
        }
        if ($fb['no_id']) {
            return $reply(200, []);
        }
        $fb['published'][] = ['message' => $form['message'] ?? null, 'link' => $form['link'] ?? null];
        return $reply(200, ['id' => FB_PAGE . '_' . count($fb['published'])]);
    }
    return $reply(404, []);
}
function fb_published(): array
{
    return $GLOBALS['fb']['published'];
}
function fb_requests_to(string $suffix): array
{
    return array_values(array_filter($GLOBALS['fb']['requests'], fn($r) => str_ends_with(parse_url($r['url'], PHP_URL_PATH), $suffix)));
}
fb_reset();

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
function fb_config(array $override = []): string
{
    return json_encode(array_merge(['sns' => 'facebook', 'access_token' => 'page-tok', 'page_id' => FB_PAGE, 'account_name' => '公式ページ'], $override));
}
function fb_log(): array
{
    [, $lines] = run_sns(['log'], ['format' => 'json']);
    $decoded = json_decode($lines[0] ?? '', true);
    return is_array($decoded) ? $decoded : [];
}
function fb_reset_all(): void
{
    run_sns(['config', 'clear']);
    $GLOBALS['t_options'][LETSBLOG_OPTION_SNS_LOG] = [];
    fb_reset();
}
function last_log(): array
{
    $log = fb_log();
    return $log === [] ? [] : $log[count($log) - 1];
}

// --- 登録 ---
check('Facebook ページの送信処理が登録されている', isset(letsblog_sns_senders()['facebook']));
check('status は未設定の Facebook を「未設定」と返す', (status_of('facebook')['status'] ?? null) === '未設定');

// --- AC1/AC5: 接続と秘密の暗号化 ---
[$err, $lines] = run_sns(['config', 'set'], [], fb_config(['api_base_url' => 'https://evil.example']));
check('config set: 成功する', $err === null, (string) $err);
$st = status_of('facebook');
check('status: 「接続済み」', ($st['status'] ?? null) === '接続済み');
check('status: ページ名をアカウント名として返す', ($st['account_name'] ?? null) === '公式ページ');
$dump = json_encode($GLOBALS['t_options']);
$printed = implode("\n", WP_CLI::$lines) . implode("\n", $lines);
check('DB にページのトークンが平文で残らない', !str_contains($dump, 'page-tok'));
check('出力にページのトークンが出ない', !str_contains($printed, 'page-tok'));
check('未知の項目(api_base_url)は保存しない', !str_contains($dump, 'evil.example'));
check('page_id は公開項目として DB に持つ', str_contains($dump, FB_PAGE));
[$err] = run_sns(['config', 'set'], [], fb_config(['access_token' => '']));
check('config set: access_token が空なら拒否する', $err !== null);
[$err] = run_sns(['config', 'set'], [], json_encode(['sns' => 'facebook', 'access_token' => 'x']));
check('config set: 投稿先のページ(page_id)が無ければ拒否する', $err !== null && str_contains($err, 'page_id'), (string) $err);
[$err] = run_sns(['config', 'set'], [], fb_config(['page_id' => 123]));
check('config set: page_id が文字列でなければ拒否する', $err !== null);
run_sns(['config', 'set'], [], fb_config());

// --- AC2: テスト投稿(ページのフィードへ。個人アカウントではない) ---
fb_reset();
[$err] = run_sns(['test', 'facebook']);
check('test: 成功する', $err === null, (string) $err);
check('test: ページのフィードに1回投稿された', count(fb_published()) === 1 && str_contains((string) fb_published()[0]['message'], '接続テスト'));
$post = fb_requests_to('/feed')[0];
check('test: 投稿先は /{page_id}/feed(個人の /me ではない)', str_ends_with(parse_url($post['url'], PHP_URL_PATH), '/' . FB_PAGE . '/feed') && count(fb_requests_to('/me/feed')) === 0);
check('test: 認証は Bearer ヘッダ(本文・URL に秘密を載せない)',
    ($post['args']['headers']['Authorization'] ?? '') === 'Bearer page-tok'
    && !str_contains($post['url'], 'page-tok') && !str_contains((string) ($post['args']['body'] ?? ''), 'page-tok'));
$entry = last_log();
check('test: 告知履歴に成功として記録される', ($entry['sns'] ?? '') === 'facebook' && ($entry['kind'] ?? '') === 'test' && ($entry['success'] ?? false) === true && array_key_exists('error', $entry) && $entry['error'] === null);

// --- リンクの投稿: 最後の行の URL を link に、残りを message にする ---
fb_reset_all();
run_sns(['config', 'set'], [], fb_config());
fb_reset();
$r = letsblog_sns_announce('facebook', 'publish', 1, "新しい記事\nhttps://blog.example.test/?p=1", time());
check('URL 付きの告知文: タイトルは message、URL は link として送る', $r['ok'] && fb_published() === [['message' => '新しい記事', 'link' => 'https://blog.example.test/?p=1']], json_encode(fb_published(), JSON_UNESCAPED_UNICODE));
fb_reset();
letsblog_sns_announce('facebook', 'test', null, 'URL の無い文', time());
check('URL が無い告知文は message だけ送る', fb_published() === [['message' => 'URL の無い文', 'link' => null]]);
fb_reset();
letsblog_sns_announce('facebook', 'test', null, "https://blog.example.test/?p=2", time());
check('URL だけの告知文は link だけ送る', fb_published() === [['message' => null, 'link' => 'https://blog.example.test/?p=2']]);

// --- AC4: 投稿先のページが無い → 投稿せず理由を残す ---
fb_reset_all();
letsblog_sns_save_credentials('facebook', ['access_token' => 'page-tok', 'account_name' => 'x']);
fb_reset();
[$err] = run_sns(['test', 'facebook']);
$entry = last_log();
check('ページ未選択: 投稿しない', fb_published() === [] && $GLOBALS['fb']['requests'] === []);
check('ページ未選択: 理由が告知履歴に残る', ($entry['success'] ?? true) === false && str_contains((string) ($entry['error'] ?? ''), 'ページが選ばれていません'), json_encode($entry, JSON_UNESCAPED_UNICODE));
check('ページ未選択: テストコマンドはエラーを返す', $err !== null);

// --- AC4: トークンの失効(code 190) → 投稿せず理由と要再接続 ---
fb_reset_all();
run_sns(['config', 'set'], [], fb_config());
fb_reset();
$GLOBALS['fb']['valid'] = ['other'];
run_sns(['test', 'facebook']);
$entry = last_log();
check('トークン失効: 投稿されない', fb_published() === []);
check('トークン失効: 理由(HTTP 400 と Facebook のメッセージ)が残り、秘密は含まれない',
    str_contains((string) ($entry['error'] ?? ''), '400') && str_contains((string) ($entry['error'] ?? ''), 'Session has expired') && !str_contains((string) ($entry['error'] ?? ''), 'page-tok'),
    json_encode($entry, JSON_UNESCAPED_UNICODE));
check('トークン失効: 要再接続になる', (status_of('facebook')['status'] ?? null) === '要再接続');

fb_reset_all();
run_sns(['config', 'set'], [], fb_config());
fb_reset();
$GLOBALS['fb']['fail'] = ['/feed' => 401];
run_sns(['test', 'facebook']);
check('HTTP 401 でも要再接続になる', (status_of('facebook')['status'] ?? null) === '要再接続');

// --- AC4: 権限なし(code 200 / HTTP 403) → 理由が残り、再接続は求めない ---
fb_reset_all();
run_sns(['config', 'set'], [], fb_config());
fb_reset();
$GLOBALS['fb']['fail'] = ['/feed' => 403];
$GLOBALS['fb']['error_code'] = 200;
run_sns(['test', 'facebook']);
$entry = last_log();
check('権限なし: 理由に HTTP 403 と Facebook のメッセージが残る', ($entry['success'] ?? true) === false && str_contains((string) ($entry['error'] ?? ''), '403') && str_contains((string) ($entry['error'] ?? ''), 'forced failure'), json_encode($entry, JSON_UNESCAPED_UNICODE));
check('権限なし: 再接続は求めない', (status_of('facebook')['status'] ?? null) === '接続済み');

fb_reset_all();
run_sns(['config', 'set'], [], fb_config());
fb_reset();
$GLOBALS['fb']['fail'] = ['/feed' => 500];
run_sns(['test', 'facebook']);
check('HTTP 500: 失敗として理由が残り、再接続は求めない', str_contains((string) (last_log()['error'] ?? ''), '500') && (status_of('facebook')['status'] ?? null) === '接続済み' && fb_published() === []);

fb_reset_all();
run_sns(['config', 'set'], [], fb_config());
fb_reset();
$GLOBALS['fb']['network_down'] = true;
run_sns(['test', 'facebook']);
check('接続できないとき理由を残す', str_contains((string) (last_log()['error'] ?? ''), '接続できません'));

fb_reset_all();
run_sns(['config', 'set'], [], fb_config());
fb_reset();
$GLOBALS['fb']['fail'] = ['/feed' => 400];
$GLOBALS['fb']['error_code'] = null;
run_sns(['test', 'facebook']);
check('応答の error.message が理由に含まれる', str_contains((string) (last_log()['error'] ?? ''), 'forced failure'));

// 応答が 200 でも id が無ければ失敗
fb_reset_all();
run_sns(['config', 'set'], [], fb_config());
fb_reset();
$GLOBALS['fb']['no_id'] = true;
run_sns(['test', 'facebook']);
check('応答に投稿の id が無ければ失敗として記録する', (last_log()['success'] ?? true) === false && str_contains((string) (last_log()['error'] ?? ''), '200'));

// 送信結果の形
fb_reset();
$r = letsblog_facebook_send(letsblog_sns_load_credentials('facebook'), 'x', time());
check('送信結果は ok と cred を返す', $r['ok'] === true && ($r['cred']['page_id'] ?? '') === FB_PAGE && $r['needs_reconnect'] === false);

// --- AC3: 公開時の告知は1回だけ(#1575 の基盤に Facebook が載る) ---
fb_reset_all();
run_sns(['config', 'set'], [], fb_config());
fb_reset();
$GLOBALS['t_posts'][50] = (object) ['ID' => 50, 'post_type' => 'post', 'post_password' => '', 'post_title' => '新しい記事', 'post_status' => 'publish'];
letsblog_sns_announce_post(50);
letsblog_sns_announce_post(50);
check('公開した記事は Facebook ページへ1回だけ、タイトルとリンクで投稿される',
    fb_published() === [['message' => '新しい記事', 'link' => 'https://blog.example.test/?p=50']]);
$entry = last_log();
check('公開の告知が履歴に残る', ($entry['kind'] ?? '') === 'publish' && ($entry['post_id'] ?? null) === 50 && ($entry['success'] ?? false) === true);

// --- 切断 ---
run_sns(['config', 'clear', 'facebook']);
check('config clear facebook: 未設定に戻る', (status_of('facebook')['status'] ?? null) === '未設定');
fb_reset();
[$err] = run_sns(['test', 'facebook']);
check('切断後は送らない', $err !== null && fb_requests_to('/feed') === []);

// --- 復号できない ---
run_sns(['config', 'set'], [], fb_config());
$GLOBALS['t_options'][LETSBLOG_OPTION_SNS_CONFIG]['facebook']['secret'] = 'v1:broken';
check('復号できなければ要再接続', (status_of('facebook')['status'] ?? null) === '要再接続');

if ($failures) {
    echo count($failures) . ' 件失敗:' . "\n";
    foreach ($failures as $f) {
        echo "  - $f\n";
    }
    exit(1);
}
echo 'すべて成功' . "\n";
exit(0);
