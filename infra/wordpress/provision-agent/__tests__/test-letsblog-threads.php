<?php
/**
 * letsblog プラグインの Threads 送信処理(issue #1579。Epic #1572。基盤は #1573)のCLIテスト。
 * wp letsblog sns config set / status / test / log、秘密の暗号化保存、長期トークンの更新(発行から24時間以上・期限前だけ)、
 * 更新できないときは投稿せず理由を告知履歴に残すこと、投稿が「作成」→「公開」の2段階であること。
 * Threads API は WordPress の HTTP API(wp_remote_request)の差し替えで再現する。実スタブ(infra/e2e-stubs/threads)との
 * 突き合わせは infra/e2e-stubs/threads/server.test.js が受け持つ。
 * 実行: timeout 60 php __tests__/test-letsblog-threads.php
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

// ---- Threads API の差し替え(wp_remote_request) ----
const TH_BASE = 'https://threads-stub.test';
define('LETSBLOG_THREADS_API_BASE_URL', TH_BASE);
const TH_USER = '17841400000000001';
$GLOBALS['th'] = [];
function th_reset(): void
{
    $GLOBALS['th'] = [
        'valid' => ['tok-1'], 'refreshed' => 'tok-2', 'requests' => [], 'published' => [], 'containers' => [],
        'fail' => [], 'network_down' => false, 'n' => 0,
    ];
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
    $th = &$GLOBALS['th'];
    $th['requests'][] = ['url' => $url, 'args' => $args];
    if ($th['network_down']) {
        return new WP_Error();
    }
    $reply = fn(int $code, array $body) => ['response' => ['code' => $code], 'body' => json_encode($body)];
    if (!str_starts_with($url, TH_BASE)) {
        return new WP_Error();
    }
    $parts = parse_url(substr($url, strlen(TH_BASE)));
    $path = $parts['path'];
    parse_str($parts['query'] ?? '', $query);
    parse_str((string) ($args['body'] ?? ''), $form);
    $auth = $args['headers']['Authorization'] ?? '';
    $oauthError = fn(string $message) => $reply(400, ['error' => ['message' => $message, 'type' => 'OAuthException', 'code' => 190]]);
    foreach ($th['fail'] as $step => $code) {
        if (str_ends_with($path, $step)) {
            return $reply($code, ['error' => ['message' => 'forced failure', 'type' => 'ApiException', 'code' => 2]]);
        }
    }
    if ($path === '/refresh_access_token') {
        if (($query['grant_type'] ?? '') !== 'th_refresh_token' || !in_array($query['access_token'] ?? '', $th['valid'], true)) {
            return $oauthError('Invalid OAuth access token.');
        }
        $th['valid'][] = $th['refreshed'];
        return $reply(200, ['access_token' => $th['refreshed'], 'token_type' => 'bearer', 'expires_in' => 5184000]);
    }
    $valid = fn() => in_array(substr($auth, 7), $th['valid'], true) && str_starts_with($auth, 'Bearer ');
    if ($path === '/v1.0/' . TH_USER . '/threads') {
        if (!$valid()) {
            return $reply(401, ['error' => ['message' => 'Invalid OAuth access token.', 'type' => 'OAuthException', 'code' => 190]]);
        }
        $th['n']++;
        $th['containers']['c' . $th['n']] = $form['text'] ?? '';
        return $reply(200, ['id' => 'c' . $th['n']]);
    }
    if ($path === '/v1.0/' . TH_USER . '/threads_publish') {
        if (!$valid()) {
            return $reply(401, ['error' => ['message' => 'Invalid OAuth access token.', 'type' => 'OAuthException', 'code' => 190]]);
        }
        $id = $form['creation_id'] ?? '';
        if (!isset($th['containers'][$id])) {
            return $reply(400, ['error' => ['message' => 'unknown container', 'type' => 'ApiException', 'code' => 24]]);
        }
        $th['published'][] = $th['containers'][$id];
        return $reply(200, ['id' => 'p' . count($th['published'])]);
    }
    return $reply(404, []);
}
function th_published(): array
{
    return $GLOBALS['th']['published'];
}
function th_requests_to(string $suffix): array
{
    return array_values(array_filter($GLOBALS['th']['requests'], fn($r) => str_contains(parse_url($r['url'], PHP_URL_PATH), $suffix)));
}
th_reset();

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
function th_config(array $override = []): string
{
    return json_encode(array_merge([
        'sns' => 'threads', 'access_token' => 'tok-1', 'user_id' => TH_USER,
        'issued_at' => time() - 86400 * 10, 'expires_at' => time() + 86400 * 50, 'account_name' => 'lets_blog_e2e',
    ], $override));
}
function th_log(): array
{
    [, $lines] = run_sns(['log'], ['format' => 'json']);
    $decoded = json_decode($lines[0] ?? '', true);
    return is_array($decoded) ? $decoded : [];
}
function th_reset_all(): void
{
    run_sns(['config', 'clear']);
    $GLOBALS['t_options'][LETSBLOG_OPTION_SNS_LOG] = [];
    th_reset();
}
function last_log(): array
{
    $log = th_log();
    return $log === [] ? [] : $log[count($log) - 1];
}

// --- 登録 ---
check('Threads の送信処理が登録されている', isset(letsblog_sns_senders()['threads']));
check('status は未設定の Threads を「未設定」と返す', (status_of('threads')['status'] ?? null) === '未設定');

// --- AC1/AC5: 接続と秘密の暗号化 ---
[$err, $lines] = run_sns(['config', 'set'], [], th_config(['api_base_url' => 'https://evil.example']));
check('config set: 成功する', $err === null, (string) $err);
$st = status_of('threads');
check('status: 「接続済み」', ($st['status'] ?? null) === '接続済み');
check('status: アカウント名を返す', ($st['account_name'] ?? null) === 'lets_blog_e2e');
$dump = json_encode($GLOBALS['t_options']);
$printed = implode("\n", WP_CLI::$lines) . implode("\n", $lines);
check('DB にアクセストークンが平文で残らない', !str_contains($dump, 'tok-1'));
check('出力にアクセストークンが出ない', !str_contains($printed, 'tok-1'));
check('未知の項目(api_base_url)は保存しない', !str_contains($dump, 'evil.example'));
check('user_id は公開項目として DB に持つ', str_contains($dump, TH_USER));
[$err] = run_sns(['config', 'set'], [], th_config(['access_token' => '']));
check('config set: access_token が空なら拒否する', $err !== null);
[$err] = run_sns(['config', 'set'], [], json_encode(['sns' => 'threads', 'access_token' => 'x', 'expires_at' => time() + 100]));
check('config set: user_id が無ければ拒否する', $err !== null);
[$err] = run_sns(['config', 'set'], [], th_config(['expires_at' => 'soon']));
check('config set: expires_at が整数でなければ拒否する', $err !== null);
run_sns(['config', 'set'], [], th_config());

// --- AC2: テスト投稿(作成 → 公開の2段階) ---
th_reset();
[$err, $lines] = run_sns(['test', 'threads']);
check('test: 成功する', $err === null, (string) $err);
check('test: 投稿が公開された', count(th_published()) === 1 && str_contains(th_published()[0], '接続テスト'));
check('test: コンテナの作成のあとに公開している', count(th_requests_to('/threads')) === 2 && count(th_requests_to('/threads_publish')) === 1);
$create = th_requests_to('/threads')[0];
check('test: 認証は Bearer ヘッダ(本文・URL に秘密を載せない)',
    ($create['args']['headers']['Authorization'] ?? '') === 'Bearer tok-1'
    && !str_contains($create['url'], 'tok-1') && !str_contains((string) ($create['args']['body'] ?? ''), 'tok-1'));
check('test: 更新は呼ばない(期限まで十分ある)', th_requests_to('/refresh_access_token') === []);
$entry = last_log();
check('test: 告知履歴に成功として記録される', ($entry['sns'] ?? '') === 'threads' && ($entry['kind'] ?? '') === 'test' && ($entry['success'] ?? false) === true && array_key_exists('error', $entry) && $entry['error'] === null);

// --- AC4: 更新が必要で、制約を満たすなら更新して投稿 ---
th_reset_all();
run_sns(['config', 'set'], [], th_config(['issued_at' => time() - 86400 * 55, 'expires_at' => time() + 86400 * 5]));
[$err] = run_sns(['test', 'threads']);
check('期限7日前以内・発行24時間以上: 更新してから投稿する', $err === null && count(th_requests_to('/refresh_access_token')) === 1 && count(th_published()) === 1, (string) $err);
$refreshReq = th_requests_to('/refresh_access_token')[0];
check('更新: th_refresh_token で GET する', ($refreshReq['args']['method'] ?? '') === 'GET' && str_contains($refreshReq['url'], 'grant_type=th_refresh_token'));
$create = th_requests_to('/threads')[0];
check('更新後の新しいトークンで投稿する', ($create['args']['headers']['Authorization'] ?? '') === 'Bearer tok-2');
$dump = json_encode($GLOBALS['t_options']);
check('更新後のトークンも平文で DB に残らない', !str_contains($dump, 'tok-2') && !str_contains($dump, 'tok-1'));
th_reset();
$GLOBALS['th']['valid'] = ['tok-2'];
$GLOBALS['th']['refreshed'] = 'tok-3';
[$err] = run_sns(['test', 'threads']);
check('更新後の期限は新しい値(約60日)になり、続けて更新しない', $err === null && th_requests_to('/refresh_access_token') === [] && count(th_published()) === 1, (string) $err);

// --- AC4: 発行から24時間未満で更新が必要 → 投稿せず理由を残す ---
th_reset_all();
run_sns(['config', 'set'], [], th_config(['issued_at' => time() - 3600, 'expires_at' => time() + 86400 * 3]));
th_reset();
[$err] = run_sns(['test', 'threads']);
$entry = last_log();
check('発行24時間未満: 投稿しない', th_published() === [] && th_requests_to('/threads') === [] && th_requests_to('/refresh_access_token') === []);
check('発行24時間未満: 失敗の理由が告知履歴に残る', ($entry['success'] ?? true) === false && str_contains((string) ($entry['error'] ?? ''), '24時間'), json_encode($entry, JSON_UNESCAPED_UNICODE));
check('発行24時間未満: テストコマンドはエラーを返す', $err !== null);
check('発行24時間未満: 接続状態は要再接続にならない(再接続不要)', (status_of('threads')['status'] ?? null) === '接続済み');

// --- AC4: 期限切れ → 更新できない(期限前だけ)ので投稿せず理由と要再接続 ---
th_reset_all();
run_sns(['config', 'set'], [], th_config(['issued_at' => time() - 86400 * 70, 'expires_at' => time() - 10]));
th_reset();
run_sns(['test', 'threads']);
$entry = last_log();
check('期限切れ: 更新も投稿もしない', th_published() === [] && th_requests_to('/refresh_access_token') === [] && th_requests_to('/threads') === []);
check('期限切れ: 理由が告知履歴に残る', ($entry['success'] ?? true) === false && str_contains((string) ($entry['error'] ?? ''), '期限が切れ'), json_encode($entry, JSON_UNESCAPED_UNICODE));
check('期限切れ: 要再接続になる', (status_of('threads')['status'] ?? null) === '要再接続');

// --- 更新の失敗 ---
th_reset_all();
run_sns(['config', 'set'], [], th_config(['issued_at' => time() - 86400 * 55, 'expires_at' => time() + 86400 * 2]));
th_reset();
$GLOBALS['th']['valid'] = ['other'];
run_sns(['test', 'threads']);
$entry = last_log();
check('更新が拒否されたら投稿しない', th_published() === [] && th_requests_to('/threads') === []);
check('更新の失敗: 理由(トークン更新・HTTP 400)が履歴に残り、秘密は含まれない',
    str_contains((string) ($entry['error'] ?? ''), 'トークン更新') && str_contains((string) ($entry['error'] ?? ''), '400') && !str_contains((string) ($entry['error'] ?? ''), 'tok-1'),
    json_encode($entry, JSON_UNESCAPED_UNICODE));
check('更新が 400 なら要再接続', (status_of('threads')['status'] ?? null) === '要再接続');

th_reset_all();
run_sns(['config', 'set'], [], th_config(['issued_at' => time() - 86400 * 55, 'expires_at' => time() + 86400 * 2]));
th_reset();
$GLOBALS['th']['fail'] = ['/refresh_access_token' => 500];
run_sns(['test', 'threads']);
check('更新が 500 なら再接続は求めない', (status_of('threads')['status'] ?? null) === '接続済み' && th_published() === []);

th_reset_all();
run_sns(['config', 'set'], [], th_config(['issued_at' => time() - 86400 * 55, 'expires_at' => time() + 86400 * 2]));
th_reset();
$GLOBALS['th']['network_down'] = true;
run_sns(['test', 'threads']);
check('更新で接続できないときも理由を残す', str_contains((string) (last_log()['error'] ?? ''), '接続できません'));

// 更新の応答に access_token が無い
th_reset_all();
run_sns(['config', 'set'], [], th_config(['issued_at' => time() - 86400 * 55, 'expires_at' => time() + 86400 * 2]));
th_reset();
$GLOBALS['th']['refreshed'] = '';
run_sns(['test', 'threads']);
check('更新の応答にトークンが無ければ投稿しない', th_published() === [] && (last_log()['success'] ?? true) === false);

// issued_at が無い(古い設定)ときは制約を満たすものとして更新する
th_reset_all();
run_sns(['config', 'set'], [], json_encode(['sns' => 'threads', 'access_token' => 'tok-1', 'user_id' => TH_USER, 'expires_at' => time() + 86400]));
th_reset();
run_sns(['test', 'threads']);
check('issued_at が無いときは更新を試みる', count(th_requests_to('/refresh_access_token')) === 1 && count(th_published()) === 1);

// --- 投稿の失敗 ---
th_reset_all();
run_sns(['config', 'set'], [], th_config());
th_reset();
$GLOBALS['th']['valid'] = ['nope'];
run_sns(['test', 'threads']);
check('投稿が 401: 失敗として記録し、要再接続になる', (last_log()['success'] ?? true) === false && (status_of('threads')['status'] ?? null) === '要再接続');

th_reset_all();
run_sns(['config', 'set'], [], th_config());
th_reset();
$GLOBALS['th']['fail'] = ['/threads' => 429];
run_sns(['test', 'threads']);
check('作成が 429: 理由に HTTP 429 が残り、公開は呼ばない', str_contains((string) (last_log()['error'] ?? ''), '429') && th_requests_to('/threads_publish') === [] && (status_of('threads')['status'] ?? null) === '接続済み');

th_reset_all();
run_sns(['config', 'set'], [], th_config());
th_reset();
$GLOBALS['th']['fail'] = ['/threads_publish' => 500];
run_sns(['test', 'threads']);
check('公開が 500: 失敗として理由が残る', str_contains((string) (last_log()['error'] ?? ''), '500') && th_published() === []);

th_reset_all();
run_sns(['config', 'set'], [], th_config());
th_reset();
$GLOBALS['th']['network_down'] = true;
run_sns(['test', 'threads']);
check('投稿で接続できないとき理由を残す', str_contains((string) (last_log()['error'] ?? ''), '接続できません'));

th_reset_all();
run_sns(['config', 'set'], [], th_config());
th_reset();
$GLOBALS['th']['fail'] = ['/threads' => 400];
run_sns(['test', 'threads']);
check('応答の error.message が理由に含まれる', str_contains((string) (last_log()['error'] ?? ''), 'forced failure'));

// --- 本文の長さ(Threads は 500 文字まで) ---
th_reset_all();
run_sns(['config', 'set'], [], th_config());
th_reset();
$long = str_repeat('あ', 600) . "\nhttps://blog.example.test/?p=1";
$r = letsblog_sns_announce('threads', 'publish', 1, $long, time());
$posted = th_published()[0] ?? '';
check('500 文字を超える告知文は、末尾の URL を残して先頭を切り詰める', $r['ok'] && mb_strlen($posted) <= 500 && str_ends_with($posted, "\nhttps://blog.example.test/?p=1") && str_contains($posted, '…'), (string) mb_strlen($posted));
th_reset();
$short = "短いタイトル\nhttps://blog.example.test/?p=2";
letsblog_sns_announce('threads', 'publish', 2, $short, time());
check('500 文字以内の告知文はそのまま送る', (th_published()[0] ?? '') === $short);
th_reset();
$noBreak = str_repeat('x', 700);
letsblog_sns_announce('threads', 'publish', 3, $noBreak, time());
check('改行が無い長文は先頭 500 文字に切り詰める', mb_strlen(th_published()[0] ?? '') === 500);
th_reset();
$longUrl = "t\n" . 'https://blog.example.test/' . str_repeat('u', 600);
letsblog_sns_announce('threads', 'publish', 4, $longUrl, time());
check('URL だけで 500 文字を超えるときも 500 文字に収める', mb_strlen(th_published()[0] ?? '') === 500);

// --- AC3: 公開時の告知は1回だけ(#1575 の基盤に Threads が載る) ---
th_reset_all();
run_sns(['config', 'set'], [], th_config());
th_reset();
$GLOBALS['t_posts'][50] = (object) ['ID' => 50, 'post_type' => 'post', 'post_password' => '', 'post_title' => '新しい記事', 'post_status' => 'publish'];
letsblog_sns_announce_post(50);
letsblog_sns_announce_post(50);
check('公開した記事は Threads へ1回だけ、タイトルとパーマリンクで投稿される',
    th_published() === ["新しい記事\nhttps://blog.example.test/?p=50"]);
$entry = last_log();
check('公開の告知が履歴に残る', ($entry['kind'] ?? '') === 'publish' && ($entry['post_id'] ?? null) === 50 && ($entry['success'] ?? false) === true);

// --- 切断 ---
run_sns(['config', 'clear', 'threads']);
check('config clear threads: 未設定に戻る', (status_of('threads')['status'] ?? null) === '未設定');
th_reset();
[$err] = run_sns(['test', 'threads']);
check('切断後は送らない', $err !== null && th_requests_to('/threads') === []);

// --- 例外のとき(トークンが壊れて復号できない) ---
run_sns(['config', 'set'], [], th_config());
$GLOBALS['t_options'][LETSBLOG_OPTION_SNS_CONFIG]['threads']['secret'] = 'v1:broken';
check('復号できなければ要再接続', (status_of('threads')['status'] ?? null) === '要再接続');

if ($failures) {
    echo count($failures) . ' 件失敗:' . "\n";
    foreach ($failures as $f) {
        echo "  - $f\n";
    }
    exit(1);
}
echo 'すべて成功' . "\n";
exit(0);
