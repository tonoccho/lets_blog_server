<?php
/**
 * letsblog プラグインの SNS 告知の土台(issue #1573)のCLIテスト。
 * wp letsblog sns config set / status / test / log / config clear、暗号化保存、X のトークン更新、告知履歴の上限。
 * X API は WordPress の HTTP API(wp_remote_request)の差し替えで再現する。実スタブ(infra/e2e-stubs/x)との
 * 突き合わせは infra/e2e-stubs/x/server.test.js が受け持つ。
 * 実行: timeout 60 php __tests__/test-letsblog-sns.php
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
$GLOBALS['t_salt'] = 'salt-A';
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
    return $GLOBALS['t_salt'] . '-' . $scheme;
}


// ---- WordPress のフック・投稿メタ・cron の差し替え(issue #1575) ----
$GLOBALS['t_actions'] = [];
$GLOBALS['t_meta'] = [];
$GLOBALS['t_cron'] = [];
$GLOBALS['t_posts'] = [];
$GLOBALS['t_spawned'] = 0;
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
function wp_schedule_single_event(int $timestamp, string $hook, array $args = []): bool
{
    $GLOBALS['t_cron'][] = ['at' => $timestamp, 'hook' => $hook, 'args' => $args];
    return true;
}
function spawn_cron(): void
{
    $GLOBALS['t_spawned']++;
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

// ---- X API の差し替え(wp_remote_request) ----
const X_BASE = 'https://x-stub.test';
define('LETSBLOG_X_API_BASE_URL', X_BASE);
$GLOBALS['x'] = [];
function x_reset(): void
{
    $GLOBALS['x'] = ['valid_access' => 'access-1', 'valid_refresh' => 'refresh-1', 'requests' => [], 'force' => null, 'n' => 1];
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
    $x = &$GLOBALS['x'];
    $x['requests'][] = ['url' => $url, 'args' => $args];
    $reply = fn(int $code, array $body) => ['response' => ['code' => $code], 'body' => json_encode($body)];
    if (!str_starts_with($url, X_BASE)) {
        return new WP_Error();
    }
    $path = substr($url, strlen(X_BASE));
    $headers = $args['headers'] ?? [];
    if ($path === '/2/oauth2/token') {
        parse_str((string) ($args['body'] ?? ''), $form);
        $basic = 'Basic ' . base64_encode('cid:csecret');
        if (($headers['Authorization'] ?? '') !== $basic) {
            return $reply(401, ['error' => 'unauthorized_client']);
        }
        if (($form['grant_type'] ?? '') !== 'refresh_token' || ($form['refresh_token'] ?? '') !== $x['valid_refresh']) {
            return $reply(400, ['error' => 'invalid_grant', 'error_description' => 'Value passed for the token was invalid.']);
        }
        $x['n']++;
        $x['valid_access'] = 'access-' . $x['n'];
        $x['valid_refresh'] = 'refresh-' . $x['n'];
        return $reply(200, ['token_type' => 'bearer', 'expires_in' => 7200, 'access_token' => $x['valid_access'], 'refresh_token' => $x['valid_refresh']]);
    }
    if ($path === '/2/tweets') {
        if ($x['force'] !== null) {
            return $reply($x['force'], ['title' => 'Too Many Requests', 'detail' => 'Too Many Requests', 'status' => $x['force']]);
        }
        if (($headers['Authorization'] ?? '') !== 'Bearer ' . $x['valid_access']) {
            return $reply(401, ['title' => 'Unauthorized', 'detail' => 'Unauthorized', 'status' => 401]);
        }
        return $reply(201, ['data' => ['id' => '1001', 'text' => json_decode((string) $args['body'], true)['text'] ?? '']]);
    }
    return $reply(404, []);
}
function x_tweet_requests(): array
{
    return array_values(array_filter($GLOBALS['x']['requests'], fn($r) => str_ends_with($r['url'], '/2/tweets')));
}
function x_refresh_requests(): array
{
    return array_values(array_filter($GLOBALS['x']['requests'], fn($r) => str_ends_with($r['url'], '/2/oauth2/token')));
}
x_reset();

define('WP_CLI', true);
define('LETSBLOG_PLUGIN_TESTING', true);
require __DIR__ . '/../../letsblog-plugin/letsblog.php';

check('wp letsblog が登録されている', isset(WP_CLI::$commands['letsblog']));

class TestSnsCommand extends Letsblog_CLI_Command
{
    public string $stdin = '';
    protected function read_stdin(): string
    {
        return $this->stdin;
    }
}

$cmd = new TestSnsCommand();
function run_sns(string $method, array $args, array $assoc = [], ?string $stdin = null): array
{
    global $cmd;
    WP_CLI::$lines = [];
    $cmd->stdin = $stdin ?? '';
    $err = null;
    try {
        $cmd->$method($args, $assoc);
    } catch (RuntimeException $e) {
        $err = $e->getMessage();
    }
    return [$err, WP_CLI::$lines];
}
function status_of(string $sns): ?array
{
    [, $lines] = run_sns('sns', ['status']);
    $out = json_decode($lines[0] ?? '', true);
    return is_array($out) ? ($out[$sns] ?? null) : null;
}
function valid_config(array $override = []): string
{
    return json_encode(array_merge([
        'sns' => 'x', 'client_id' => 'cid', 'client_secret' => 'csecret',
        'access_token' => 'access-1', 'refresh_token' => 'refresh-1',
        'expires_at' => time() + 3600, 'account_name' => 'LetsBlogOfficial',
    ], $override));
}
function log_entries(): array
{
    [, $lines] = run_sns('sns', ['log'], ['format' => 'json']);
    $decoded = json_decode($lines[0] ?? '', true);
    return is_array($decoded) ? $decoded : [];
}

check('sns コマンドが定義されている', method_exists($cmd, 'sns'));
if (!method_exists($cmd, 'sns')) {
    echo "sns コマンドが無いため以降を中止\n";
    echo "1 件以上失敗\n";
    exit(1);
}

// --- 未設定 ---
$st = status_of('x');
check('status: 未設定のとき「未設定」', ($st['status'] ?? null) === '未設定', json_encode($st, JSON_UNESCAPED_UNICODE));
check('log: 履歴が無いとき空の配列', log_entries() === []);
[$err] = run_sns('sns', ['test', 'x']);
check('test: 未設定の SNS へは送らず失敗する', $err !== null && x_tweet_requests() === []);

// --- AC1: config set → status は接続済み+アカウント名、秘密は平文で出ない ---
[$err, $lines] = run_sns('sns', ['config', 'set'], [], valid_config());
check('config set: 成功する', $err === null, (string) $err);
$st = status_of('x');
check('status: 「接続済み」', ($st['status'] ?? null) === '接続済み', json_encode($st, JSON_UNESCAPED_UNICODE));
check('status: アカウントの表示名を返す', ($st['account_name'] ?? null) === 'LetsBlogOfficial');
$dump = json_encode($GLOBALS['t_options']);
$printed = implode("\n", WP_CLI::$lines) . implode("\n", $lines);
foreach (['access-1', 'refresh-1', 'csecret'] as $secret) {
    check("DB に秘密「{$secret}」が平文で残らない", !str_contains($dump, $secret));
    check("出力に秘密「{$secret}」が出ない", !str_contains($printed, $secret));
}
[$err] = run_sns('sns', ['config', 'set'], ['access_token' => 'access-1'], valid_config());
check('config set: 秘密を引数で受け取らない', $err !== null);
[$err] = run_sns('sns', ['config', 'set'], [], '{not json');
check('config set: JSON でなければ失敗', $err !== null);
[$err] = run_sns('sns', ['config', 'set'], [], valid_config(['sns' => 'nope']));
check('config set: 未対応の SNS は失敗', $err !== null);
[$err] = run_sns('sns', ['config', 'set'], [], valid_config(['refresh_token' => '']));
check('config set: 必須項目が欠けていれば失敗', $err !== null);
check('config set: 失敗した入力で保存済みの設定は壊れない', (status_of('x')['status'] ?? null) === '接続済み');

// --- 要再接続: 復号できない ---
$GLOBALS['t_salt'] = 'salt-B';
check('status: 復号できないとき「要再接続」', (status_of('x')['status'] ?? null) === '要再接続');
[$err] = run_sns('sns', ['test', 'x']);
check('test: 復号できないときは送らず失敗する', $err !== null && x_tweet_requests() === []);
$GLOBALS['t_salt'] = 'salt-A';
check('status: 鍵が戻れば「接続済み」に戻る', (status_of('x')['status'] ?? null) === '接続済み');

// --- AC2: test x → スタブに投稿が届き、成功が履歴に ---
[$err, $lines] = run_sns('sns', ['test', 'x']);
check('test x: 成功する', $err === null, (string) $err);
$tweets = x_tweet_requests();
check('test x: X の投稿 API に1件届く', count($tweets) === 1);
check('test x: アクセストークンで認証する', ($tweets[0]['args']['headers']['Authorization'] ?? '') === 'Bearer access-1');
check('test x: 期限内なのでトークン更新はしない', x_refresh_requests() === []);
$log = log_entries();
// 直前の「復号できない」ときの試行も、失敗として1件目に残る(送れなかった告知も履歴で追えるようにする)。
check('log: 復号できず送れなかった試行が失敗として記録される', count($log) === 2 && ($log[0]['success'] ?? null) === false
    && str_contains((string) ($log[0]['error'] ?? ''), '要再接続'), json_encode($log, JSON_UNESCAPED_UNICODE));
check('log: 成功が記録される', ($log[1]['success'] ?? null) === true, json_encode($log));
check('log: SNS・種類・記事・日時を持つ', ($log[1]['sns'] ?? null) === 'x' && ($log[1]['kind'] ?? null) === 'test'
    && array_key_exists('post_id', $log[1]) && is_string($log[1]['at'] ?? null) && strtotime($log[1]['at']) !== false, json_encode($log));

// --- AC3: X がエラー → 失敗と理由が履歴に ---
$GLOBALS['x']['force'] = 429;
[$err] = run_sns('sns', ['test', 'x']);
$GLOBALS['x']['force'] = null;
check('test x: X のエラーは失敗として報告される', $err !== null);
$log = log_entries();
check('log: 失敗と理由が記録される', count($log) === 3 && ($log[2]['success'] ?? null) === false
    && str_contains((string) ($log[2]['error'] ?? ''), '429'), json_encode($log, JSON_UNESCAPED_UNICODE));
check('log: 失敗の理由に秘密が含まれない', !str_contains(json_encode($log), 'access-1') && !str_contains(json_encode($log), 'csecret'));
check('status: 投稿の失敗では「要再接続」にならない', (status_of('x')['status'] ?? null) === '接続済み');

// --- AC4: トークン切れ → 更新してから投稿し、更新後のトークンを保存 ---
run_sns('sns', ['config', 'set'], [], valid_config(['expires_at' => time() - 10]));
x_reset();
[$err] = run_sns('sns', ['test', 'x']);
check('期限切れ: 投稿に成功する', $err === null, (string) $err);
$refresh = x_refresh_requests();
check('期限切れ: 先にトークンを更新する', count($refresh) === 1);
check('期限切れ: クライアント認証(Basic)で更新する', ($refresh[0]['args']['headers']['Authorization'] ?? '') === 'Basic ' . base64_encode('cid:csecret'));
parse_str((string) ($refresh[0]['args']['body'] ?? ''), $form);
check('期限切れ: refresh_token グラントで更新する', ($form['grant_type'] ?? '') === 'refresh_token' && ($form['refresh_token'] ?? '') === 'refresh-1');
$tweets = x_tweet_requests();
check('期限切れ: 更新後のアクセストークンで投稿する', count($tweets) === 1 && ($tweets[0]['args']['headers']['Authorization'] ?? '') === 'Bearer access-2');
$saved = letsblog_sns_load_credentials('x');
check('期限切れ: 更新後のトークンが保存される', ($saved['access_token'] ?? null) === 'access-2' && ($saved['refresh_token'] ?? null) === 'refresh-2'
    && ($saved['expires_at'] ?? 0) > time(), json_encode($saved));
check('期限切れ: 更新後のトークンも平文で DB に残らない', !str_contains(json_encode($GLOBALS['t_options']), 'access-2') && !str_contains(json_encode($GLOBALS['t_options']), 'refresh-2'));
x_reset();
$GLOBALS['x']['valid_access'] = 'access-2';
$GLOBALS['x']['valid_refresh'] = 'refresh-2';
[$err] = run_sns('sns', ['test', 'x']);
check('更新後の保存値で続けて投稿できる(更新なし)', $err === null && x_refresh_requests() === [] && count(x_tweet_requests()) === 1, (string) $err);

// アクセストークンが 401(期限の前に失効)でも、更新して一度だけやり直す
run_sns('sns', ['config', 'set'], [], valid_config(['access_token' => 'stale', 'expires_at' => time() + 3600]));
x_reset();
[$err] = run_sns('sns', ['test', 'x']);
check('401: 更新してやり直し、成功する', $err === null && count(x_refresh_requests()) === 1 && count(x_tweet_requests()) === 2, (string) $err);

// 更新できない(refresh token が失効)→ 失敗を記録し「要再接続」
run_sns('sns', ['config', 'set'], [], valid_config(['refresh_token' => 'revoked', 'expires_at' => time() - 10]));
x_reset();
[$err] = run_sns('sns', ['test', 'x']);
check('更新失敗: 失敗として報告され、投稿は送られない', $err !== null && x_tweet_requests() === []);
$log = log_entries();
$last = end($log);
check('更新失敗: 理由が履歴に残る', ($last['success'] ?? null) === false && str_contains((string) ($last['error'] ?? ''), 'invalid_grant'), json_encode($last, JSON_UNESCAPED_UNICODE));
check('更新失敗: status は「要再接続」', (status_of('x')['status'] ?? null) === '要再接続');

// --- API のベース URL は wp-config の定数だけで決まる ---
run_sns('sns', ['config', 'set'], [], valid_config(['api_base_url' => 'https://evil.test', 'base_url' => 'https://evil.test']));
x_reset();
run_sns('sns', ['test', 'x']);
$allInBase = true;
foreach ($GLOBALS['x']['requests'] as $r) {
    $allInBase = $allInBase && str_starts_with($r['url'], X_BASE . '/');
}
check('アプリから送る設定でベース URL は変えられない', $GLOBALS['x']['requests'] !== [] && $allInBase);
check('ベース URL は定数から読む', str_contains((string) file_get_contents(__DIR__ . '/../../letsblog-plugin/letsblog.php'), 'LETSBLOG_X_API_BASE_URL'));

// --- SNS ごとの処理は後から追加できる ---
letsblog_sns_register_sender('dummy', [
    'secret_fields' => ['token'],
    'required' => ['token'],
    'send' => function (array $cred, string $text, int $now): array {
        return ['ok' => true, 'error' => null, 'cred' => $cred + ['sent' => $text]];
    },
]);
[$err] = run_sns('sns', ['config', 'set'], [], json_encode(['sns' => 'dummy', 'token' => 'tok-dummy', 'account_name' => 'dummy-acc']));
check('追加した SNS に config set できる', $err === null, (string) $err);
check('追加した SNS の status が出る', (status_of('dummy')['status'] ?? null) === '接続済み' && (status_of('dummy')['account_name'] ?? null) === 'dummy-acc');
check('追加した SNS の秘密も暗号化される', !str_contains(json_encode($GLOBALS['t_options']), 'tok-dummy'));
[$err] = run_sns('sns', ['test', 'dummy']);
$log = log_entries();
check('追加した SNS へ test でき、履歴に残る', $err === null && ($log[count($log) - 1]['sns'] ?? null) === 'dummy' && ($log[count($log) - 1]['success'] ?? null) === true);
[$err] = run_sns('sns', ['test', 'unknown']);
check('test: 未対応の SNS は失敗', $err !== null);

// --- 告知履歴は上限を超えたら古いものから消す ---
$GLOBALS['t_options'][LETSBLOG_OPTION_SNS_LOG] = [];
$limit = LETSBLOG_SNS_LOG_LIMIT;
check('履歴の上限が定義されている', is_int($limit) && $limit > 0);
for ($i = 1; $i <= $limit + 5; $i++) {
    letsblog_sns_log_append(['sns' => 'x', 'kind' => 'publish', 'post_id' => $i, 'at' => gmdate('c'), 'success' => true, 'error' => null]);
}
$log = log_entries();
check('履歴は上限件数で止まる', count($log) === $limit, (string) count($log));
check('履歴は古いものから消える', ($log[0]['post_id'] ?? null) === 6 && ($log[$limit - 1]['post_id'] ?? null) === $limit + 5);

// --- AC5: config clear ---
run_sns('sns', ['config', 'set'], [], valid_config());
[$err] = run_sns('sns', ['config', 'clear']);
check('config clear: 成功する', $err === null, (string) $err);
check('config clear: status が「未設定」に戻る', (status_of('x')['status'] ?? null) === '未設定' && (status_of('dummy')['status'] ?? null) === '未設定');
check('config clear: 認証情報が DB から消える', !str_contains(json_encode($GLOBALS['t_options']), 'cid') && letsblog_sns_load_credentials('x') === null);
check('config clear: 告知履歴は残る', count(log_entries()) > 0);

// --- 不正な位置引数 ---
[$err] = run_sns('sns', ['bogus']);
check('不明なサブコマンドは失敗', $err !== null);
[$err] = run_sns('sns', ['config', 'bogus']);
check('config の不明なサブコマンドは失敗', $err !== null);
[$err] = run_sns('sns', ['test']);
check('test: SNS 名が無ければ失敗', $err !== null);
[$err, $lines] = run_sns('sns', ['log'], ['format' => 'table']);
check('log: json 以外の形式は失敗', $err !== null);

// --- 要件6 ---
$source = (string) file_get_contents(__DIR__ . '/../../letsblog-plugin/letsblog.php');
check('REST ルートを増やさない', !str_contains($source, 'register_rest_route') && !str_contains($source, 'rest_api_init'));


// ============ 公開時の告知(issue #1575) ============
run_sns('sns', ['config', 'clear']);
$GLOBALS['t_options'][LETSBLOG_OPTION_SNS_LOG] = [];
x_reset();

function mkpost(int $id, string $type = 'post', string $password = ''): object
{
    $post = (object) ['ID' => $id, 'post_type' => $type, 'post_password' => $password, 'post_title' => '新しい記事', 'post_status' => 'publish'];
    $GLOBALS['t_posts'][$id] = $post;
    return $post;
}
function announce_meta(int $id)
{
    return get_post_meta($id, LETSBLOG_META_SNS_ANNOUNCED, true);
}
function cron_events(): array
{
    return array_values(array_filter($GLOBALS['t_cron'], fn($e) => $e['hook'] === LETSBLOG_CRON_SNS_ANNOUNCE));
}
function tweet_count(): int
{
    return count(x_tweet_requests());
}
function hooked(string $hook, string $fn): bool
{
    foreach ($GLOBALS['t_actions'][$hook] ?? [] as $h) {
        if ($h['fn'] === $fn) {
            return true;
        }
    }
    return false;
}
/** 公開処理の外から見える結果を、例外なしで取り出す。 */
function survives(callable $fn): bool
{
    try {
        $fn();
        return true;
    } catch (Throwable $e) {
        return false;
    }
}

check('transition_post_status にフックされている', hooked('transition_post_status', 'letsblog_sns_on_transition'));
check('cron のイベントにフックされている', hooked(LETSBLOG_CRON_SNS_ANNOUNCE, 'letsblog_sns_run_scheduled'));
check('wp_after_insert_post にフックされている', hooked('wp_after_insert_post', 'letsblog_sns_on_after_insert'));
check('shutdown にフックされている', hooked('shutdown', 'letsblog_sns_on_shutdown'));

// 要件3: SNS 設定がなければ何もしない
$p = mkpost(101);
letsblog_sns_on_transition('publish', 'draft', $p, false);
check('設定なし: cron を予約せず、メタも書かず、送らない', cron_events() === [] && announce_meta(101) === '' && tweet_count() === 0 && log_entries() === []);

run_sns('sns', ['config', 'set'], [], valid_config());

// AC1/要件4: Web からの公開は即時のシングルイベント。公開処理の中では送らない
letsblog_sns_on_transition('publish', 'draft', $p, false);
$events = cron_events();
check('Web: 即時のシングルイベントを1件予約する', count($events) === 1 && $events[0]['args'] === [101] && $events[0]['at'] <= time());
check('Web: 公開処理の中では送らない', tweet_count() === 0);
check('Web: 告知済みメタを立てる', announce_meta(101) !== '');
$GLOBALS['t_spawned'] = 0;
letsblog_sns_on_shutdown();
check('Web: 終了時に cron を起動する', $GLOBALS['t_spawned'] === 1);
letsblog_sns_on_shutdown();
check('Web: 起動は1回だけ', $GLOBALS['t_spawned'] === 1);
letsblog_sns_run_scheduled(101);
$tweets = x_tweet_requests();
check('Web: cron の実行で1回だけ投稿される', count($tweets) === 1);
check('告知文は記事のタイトルとパーマリンク', (json_decode($tweets[0]['args']['body'] ?? '', true)['text'] ?? '') === "新しい記事\nhttps://blog.example.test/?p=101", $tweets[0]['args']['body'] ?? '');
$entries = log_entries();
check('告知履歴に kind=publish・post_id・成功で残る', count($entries) === 1 && $entries[0]['kind'] === 'publish' && $entries[0]['post_id'] === 101 && $entries[0]['success'] === true, json_encode($entries));

// 要件2: 告知済みは再告知しない(非公開から再公開しても)
letsblog_sns_on_transition('publish', 'draft', $p, false);
check('再公開: 再告知しない(予約も増えない)', count(cron_events()) === 1);
letsblog_sns_run_scheduled(101);
check('再公開: cron が二重に呼ばれても二度投稿しない', tweet_count() === 1);

// AC2/要件4: wp-cli(CLI)は、コマンドの終了前にその場で送る
$p = mkpost(102);
letsblog_sns_on_transition('publish', 'draft', $p, true);
check('CLI: cron は予約しない', count(cron_events()) === 1);
check('CLI: 投稿の保存が終わるまでは送らない', tweet_count() === 1);
letsblog_sns_on_after_insert(102);
check('CLI: 保存後に1回だけ送る', tweet_count() === 2);
letsblog_sns_on_after_insert(102);
letsblog_sns_on_shutdown();
check('CLI: 二度目以降は送らない', tweet_count() === 2);
$p = mkpost(103);
letsblog_sns_on_transition('publish', 'draft', $p, true);
letsblog_sns_on_shutdown();
check('CLI: wp_after_insert_post を通らなくても終了時に送る', tweet_count() === 3);

// AC3: 予約公開は告知しない
$p = mkpost(104);
letsblog_sns_on_transition('future', 'new', $p, false);
check('予約投稿の作成(future)では告知しない', count(cron_events()) === 1 && tweet_count() === 3);
check('予約投稿には告知しない印が付く', announce_meta(104) !== '');
letsblog_sns_on_transition('publish', 'future', $p, false);
letsblog_sns_on_transition('publish', 'future', $p, true);
letsblog_sns_on_shutdown();
check('予約時刻の公開(future → publish)では告知しない', count(cron_events()) === 1 && tweet_count() === 3);
$p = mkpost(105);
letsblog_sns_on_transition('publish', 'future', $p, false);
check('印が無くても future → publish は告知しない', count(cron_events()) === 1);
$p = mkpost(106);
letsblog_sns_on_transition('future', 'draft', $p, false);
letsblog_sns_on_transition('publish', 'draft', $p, false);
letsblog_sns_on_transition('publish', 'draft', $p, true);
letsblog_sns_on_shutdown();
check('予約投稿を draft に戻して公開しても告知しない', count(cron_events()) === 1 && tweet_count() === 3);

// 対象外
foreach ([
    'パスワード付き' => [mkpost(110, 'post', 'secret'), 'publish', 'draft'],
    '固定ページ' => [mkpost(111, 'page'), 'publish', 'draft'],
    'publish → publish(更新)' => [mkpost(112), 'publish', 'publish'],
    '公開以外への遷移(draft)' => [mkpost(113), 'draft', 'publish'],
    'private への遷移' => [mkpost(114), 'private', 'draft'],
] as $label => [$post, $new, $old]) {
    letsblog_sns_on_transition($new, $old, $post, false);
    letsblog_sns_on_transition($new, $old, $post, true);
    letsblog_sns_on_shutdown();
    check("対象外: {$label}", count(cron_events()) === 1 && tweet_count() === 3);
}

// AC5: 失敗しても公開は失敗させず、履歴に理由を残す
$p = mkpost(120);
letsblog_sns_on_transition('publish', 'draft', $p, false);
$GLOBALS['x']['force'] = 429;
check('SNS がエラーでも例外にならない', survives(fn() => letsblog_sns_run_scheduled(120)));
$last = log_entries()[count(log_entries()) - 1];
check('失敗と理由が履歴に残る', $last['post_id'] === 120 && $last['success'] === false && str_contains((string) $last['error'], '429'), json_encode($last, JSON_UNESCAPED_UNICODE));
$GLOBALS['x']['force'] = null;
$p = mkpost(121);
letsblog_sns_on_transition('publish', 'draft', $p, true);
$GLOBALS['x']['force'] = 429;
check('CLI でも SNS のエラーでコマンドが失敗しない', survives(fn() => letsblog_sns_on_after_insert(121)));
$last = log_entries()[count(log_entries()) - 1];
check('CLI: 失敗が履歴に残る', $last['post_id'] === 121 && $last['success'] === false);
$GLOBALS['x']['force'] = null;
letsblog_sns_register_sender('boom', ['required' => ['k'], 'send' => function () {
    throw new RuntimeException('送信処理が壊れた');
}]);
$GLOBALS['t_options'][LETSBLOG_OPTION_SNS_CONFIG]['boom'] = ['public' => [], 'secret' => letsblog_sns_encrypt(['k' => 'v']), 'reconnect' => false];
$p = mkpost(122);
letsblog_sns_on_transition('publish', 'draft', $p, false);
check('送信処理が例外を投げても公開は失敗しない', survives(fn() => letsblog_sns_run_scheduled(122)));
$boom = array_values(array_filter(log_entries(), fn($e) => $e['sns'] === 'boom' && $e['post_id'] === 122));
check('送信処理の例外が履歴に残る', count($boom) === 1 && $boom[0]['success'] === false && str_contains((string) $boom[0]['error'], '送信処理が壊れた'), json_encode($boom, JSON_UNESCAPED_UNICODE));
unset($GLOBALS['t_options'][LETSBLOG_OPTION_SNS_CONFIG]['boom']);

// 公開でなくなった記事・消えた記事は、cron 実行時に送らない
$n = tweet_count();
$p = mkpost(130);
letsblog_sns_on_transition('publish', 'draft', $p, false);
$GLOBALS['t_posts'][130]->post_status = 'draft';
letsblog_sns_run_scheduled(130);
letsblog_sns_run_scheduled(99999);
check('cron 実行時に公開でない/存在しない記事へは送らない', tweet_count() === $n);

// AC4/Out of Scope: SNS 設定前に公開された記事は、下書きに戻して再公開しても告知しない
run_sns('sns', ['config', 'clear']);
$p = mkpost(140);
letsblog_sns_on_transition('publish', 'draft', $p, false);
letsblog_sns_on_transition('draft', 'publish', $p, false);
run_sns('sns', ['config', 'set'], [], valid_config());
$n = tweet_count();
$c = count(cron_events());
letsblog_sns_on_transition('publish', 'draft', $p, false);
letsblog_sns_on_transition('publish', 'draft', $p, true);
letsblog_sns_on_after_insert(140);
check('設定前に公開済みの記事は、非公開化→再公開でも告知しない', tweet_count() === $n && count(cron_events()) === $c && announce_meta(140) !== '');

// ============ 告知文テンプレート(issue #1583) ============
run_sns('sns', ['config', 'clear']);
$GLOBALS['t_options'][LETSBLOG_OPTION_SNS_LOG] = [];
x_reset();
$GLOBALS['t_options'] = array_diff_key($GLOBALS['t_options'], ['letsblog_sns_templates' => true]);

function templates_json(array $t): string
{
    return json_encode($t, JSON_UNESCAPED_UNICODE);
}
function last_tweet_text(): string
{
    $reqs = x_tweet_requests();
    $last = end($reqs);
    return (string) (json_decode($last['args']['body'] ?? '', true)['text'] ?? '');
}
/** 検証用の X の重み付き長さ(プラグインの実装とは別に持つ): URL は 23、U+0000-U+10FF・U+2000-U+200D・U+2010-U+201F・U+2032-U+2037 は 1、それ以外は 2。 */
function x_weight(string $text): int
{
    $total = 0;
    $rest = preg_replace_callback('#https?://\S+#u', function () use (&$total) {
        $total += 23;
        return '';
    }, $text);
    foreach (preg_split('//u', (string) $rest, -1, PREG_SPLIT_NO_EMPTY) as $ch) {
        $c = mb_ord($ch);
        $total += ($c <= 0x10FF || ($c >= 0x2000 && $c <= 0x200D) || ($c >= 0x2010 && $c <= 0x201F) || ($c >= 0x2032 && $c <= 0x2037)) ? 1 : 2;
    }
    return $total;
}

// --- templates set: 標準入力の JSON で公開時と PV 達成時を別々に受け取り、丸ごと置き換える ---
check('テンプレート: 何も設定していなければ両方空', letsblog_sns_templates() === ['publish' => '', 'pv' => '']);
[$err, $lines] = run_sns('sns', ['templates', 'set'], [], templates_json(['publish' => '【新着】{title} {url}', 'pv' => '{period}で{threshold}PV {url}']));
check('templates set: 成功する', $err === null, (string) $err);
check('templates set: 保存した内容が別々に読める', letsblog_sns_templates() === ['publish' => '【新着】{title} {url}', 'pv' => '{period}で{threshold}PV {url}'], json_encode(letsblog_sns_templates(), JSON_UNESCAPED_UNICODE));
check('templates set: 結果を JSON で出力する', (json_decode($lines[0] ?? '', true)['templates'] ?? null) === true, implode('|', $lines));
[$err] = run_sns('sns', ['templates', 'set'], [], templates_json(['publish' => 'だけ']));
check('templates set: 渡さなかった側は空になる(丸ごと置き換え)', $err === null && letsblog_sns_templates() === ['publish' => 'だけ', 'pv' => '']);
[$err] = run_sns('sns', ['templates', 'set'], [], templates_json(['publish' => '', 'pv' => '']));
check('templates set: 空を渡すと空に戻る', $err === null && letsblog_sns_templates() === ['publish' => '', 'pv' => '']);
foreach ([
    'JSON でない' => '{not json',
    '配列(リスト)' => '["a"]',
    '公開時が文字列でない' => json_encode(['publish' => 5, 'pv' => '']),
    'PV が文字列でない' => json_encode(['publish' => '', 'pv' => ['x']]),
    '公開時が1000文字を超える' => json_encode(['publish' => str_repeat('あ', 1001), 'pv' => '']),
    'PV が1000文字を超える' => json_encode(['publish' => '', 'pv' => str_repeat('あ', 1001)]),
] as $label => $input) {
    run_sns('sns', ['templates', 'set'], [], templates_json(['publish' => '保存済み', 'pv' => '保存済みPV']));
    [$err] = run_sns('sns', ['templates', 'set'], [], $input);
    check("templates set: {$label}は失敗し、保存済みの内容は壊れない", $err !== null && letsblog_sns_templates() === ['publish' => '保存済み', 'pv' => '保存済みPV']);
}
[$err] = run_sns('sns', ['templates', 'set'], [], templates_json(['publish' => str_repeat('あ', 1000), 'pv' => str_repeat('い', 1000)]));
check('templates set: ちょうど1000文字は受け取る', $err === null);
[$err] = run_sns('sns', ['templates', 'set'], ['publish' => 'x'], templates_json(['publish' => 'a']));
check('templates set: 引数でテンプレートを受け取らない', $err !== null);
[$err] = run_sns('sns', ['templates', 'nope'], [], '{}');
check('templates: 未知のサブコマンドは失敗する', $err !== null);
$GLOBALS['t_options']['letsblog_sns_templates'] = 'broken';
check('テンプレート: 壊れた保存値は空として扱う', letsblog_sns_templates() === ['publish' => '', 'pv' => '']);
$GLOBALS['t_options']['letsblog_sns_templates'] = ['publish' => 5, 'pv' => null];
check('テンプレート: 文字列でない値は空として扱う', letsblog_sns_templates() === ['publish' => '', 'pv' => '']);
run_sns('sns', ['templates', 'set'], [], templates_json(['publish' => '', 'pv' => '']));

// --- 差し込み項目の置き換え ---
check('差し込み: title と url を置き換える', letsblog_sns_render('【{title}】{url}', ['title' => '記事', 'url' => 'https://a.test/1']) === '【記事】https://a.test/1');
check('差し込み: 同じ項目を何度でも置き換える', letsblog_sns_render('{title}/{title}', ['title' => 'T']) === 'T/T');
check('差し込み: period と threshold を置き換える', letsblog_sns_render('{period}で{threshold}PV', ['period' => '1日', 'threshold' => '100']) === '1日で100PV');
check('差し込み: 値が渡されない項目(公開時の period・threshold)は置き換えない', letsblog_sns_render('{title} {period} {threshold}', ['title' => 'T', 'url' => 'u']) === 'T {period} {threshold}');
check('差し込み: 知らない波括弧はそのまま残す', letsblog_sns_render('{foo} {title}', ['title' => 'T']) === '{foo} T');
check('差し込み: 置き換えた値の中の {url} を、さらに置き換えない', letsblog_sns_render('{title} {url}', ['title' => '{url}', 'url' => 'https://a.test/1']) === '{url} https://a.test/1');
check('差し込み: 項目が無いテンプレートはそのまま', letsblog_sns_render('固定の文面', ['title' => 'T']) === '固定の文面');

// --- 公開時の告知文: テンプレートがあれば使い、空(空白だけを含む)なら既定の告知文 ---
$post = mkpost(301);
check('公開時の告知文: テンプレートが無ければ既定(タイトルと URL)', letsblog_sns_publish_text($post) === "新しい記事\nhttps://blog.example.test/?p=301");
run_sns('sns', ['templates', 'set'], [], templates_json(['publish' => '【新着】{title} {url}', 'pv' => '']));
check('公開時の告知文: テンプレートの差し込み項目が値に置き換わる', letsblog_sns_publish_text($post) === '【新着】新しい記事 https://blog.example.test/?p=301');
$GLOBALS['t_posts'][302] = (object) ['ID' => 302, 'post_type' => 'post', 'post_password' => '', 'post_title' => 'A &amp; B <b>強調</b>', 'post_status' => 'publish'];
check('公開時の告知文: タイトルは既定と同じく、タグを除きエンティティを戻した値で差し込む', letsblog_sns_publish_text($GLOBALS['t_posts'][302]) === '【新着】A & B 強調 https://blog.example.test/?p=302');
run_sns('sns', ['templates', 'set'], [], templates_json(['publish' => " \n\t ", 'pv' => '']));
check('公開時の告知文: 空白だけのテンプレートは空と同じで既定の告知文', letsblog_sns_publish_text($post) === "新しい記事\nhttps://blog.example.test/?p=301");

// --- 公開時の告知を実際に X へ投稿する(AC2/AC3): 置き換えた本文が届く。空なら既定の告知文 ---
run_sns('sns', ['config', 'set'], [], valid_config());
run_sns('sns', ['templates', 'set'], [], templates_json(['publish' => '【新着】{title} {url}', 'pv' => '']));
mkpost(310);
letsblog_sns_announce_post(310);
check('告知: テンプレートの差し込み項目が値に置き換わって X へ投稿される', last_tweet_text() === '【新着】新しい記事 https://blog.example.test/?p=310', last_tweet_text());
run_sns('sns', ['templates', 'set'], [], templates_json(['publish' => '', 'pv' => '']));
mkpost(311);
letsblog_sns_announce_post(311);
check('告知: テンプレートが空なら、既定の告知文(タイトルと URL)で投稿される', last_tweet_text() === "新しい記事\nhttps://blog.example.test/?p=311", last_tweet_text());

// --- 文字数の上限(AC4): 超えるときは URL を残して切り詰める ---
$url = 'https://blog.example.test/?p=320';
check('切り詰め: 上限以内ならそのまま', letsblog_sns_fit_text("短い\n{$url}", 500, 'mb_strlen') === "短い\n{$url}");
check('切り詰め: 上限ちょうどならそのまま', letsblog_sns_fit_text(str_repeat('a', 10), 10, 'mb_strlen') === str_repeat('a', 10));
$fit = letsblog_sns_fit_text(str_repeat('あ', 100) . "\n{$url}", 60, 'mb_strlen');
check('切り詰め: 末尾の URL を残して先頭を「…」で切り詰める', mb_strlen($fit) <= 60 && str_ends_with($fit, "…\n{$url}"), $fit);
check('切り詰め: 上限に収まる中で、先頭をできるだけ残す', mb_strlen($fit) === 60, (string) mb_strlen($fit));
$fit = letsblog_sns_fit_text("見出し {$url} " . str_repeat('い', 100), 60, 'mb_strlen');
check('切り詰め: URL が途中にあるときは、URL の後ろから先に削る(URL と先頭は残る)', mb_strlen($fit) <= 60 && str_starts_with($fit, "見出し {$url} ") && str_ends_with($fit, '…'), $fit);
$fit = letsblog_sns_fit_text(str_repeat('う', 100) . " {$url} " . str_repeat('え', 100), 40, 'mb_strlen');
check('切り詰め: 後ろを削りきっても足りなければ、先頭を削る(URL は残る)', mb_strlen($fit) <= 40 && str_contains($fit, $url) && str_contains($fit, '…'), $fit);
$fit = letsblog_sns_fit_text(str_repeat('お', 100), 30, 'mb_strlen');
check('切り詰め: URL が無ければ、上限まで「…」つきで切る', mb_strlen($fit) === 30 && str_ends_with($fit, '…'), $fit);
$longUrl = 'https://blog.example.test/' . str_repeat('x', 100);
$fit = letsblog_sns_fit_text("タイトル\n{$longUrl}", 50, 'mb_strlen');
check('切り詰め: URL だけで上限を超えるときは、上限で切る', mb_strlen($fit) === 50, (string) mb_strlen($fit));
$fit = letsblog_sns_fit_text("{$url}", 10, 'mb_strlen');
check('切り詰め: URL しか無く上限を超えるときも、上限で切る', mb_strlen($fit) === 10, $fit);
$fit = letsblog_sns_fit_text("{$url}\n" . str_repeat('か', 100), mb_strlen($url) + 1, 'mb_strlen');
check('切り詰め: 先頭が URL なら、後ろをすべて削って URL だけを残す', $fit === $url || $fit === "{$url}…", $fit);

// X: 重み付き 280(全角は 2、URL は 23)
check('X: 全角140文字ちょうど(重み 280)は切り詰めない', letsblog_x_fit_text(str_repeat('あ', 140)) === str_repeat('あ', 140));
check('X: 全角141文字(重み 282)は切り詰める', x_weight(letsblog_x_fit_text(str_repeat('あ', 141))) <= 280 && letsblog_x_fit_text(str_repeat('あ', 141)) !== str_repeat('あ', 141));
check('X: 半角280文字は切り詰めない', letsblog_x_fit_text(str_repeat('a', 280)) === str_repeat('a', 280));
check('X: URL は実際の長さによらず 23 で数える', letsblog_x_weighted_length("\n" . 'https://blog.example.test/' . str_repeat('x', 200)) === 24);
$text = str_repeat('a', 257) . "\n" . 'https://blog.example.test/' . str_repeat('y', 80);
$fit = letsblog_x_fit_text($text);
check('X: 半角257文字+改行+URL(重み 281)は切り詰め、URL は残す', x_weight($fit) <= 280 && str_ends_with($fit, 'https://blog.example.test/' . str_repeat('y', 80)) && str_contains($fit, '…'), $fit);
$text = str_repeat('a', 256) . "\n" . 'https://blog.example.test/z';
check('X: 半角256文字+改行+URL(重み 280)は切り詰めない', letsblog_x_fit_text($text) === $text);
check('X: 判定は全角記号・半角カナの区別を含め、検証側の数え方と一致する', letsblog_x_weighted_length('ｱ→—…あ') === x_weight('ｱ→—…あ'), (string) letsblog_x_weighted_length('ｱ→—…あ'));

run_sns('sns', ['templates', 'set'], [], templates_json(['publish' => "{title}\n" . str_repeat('あ', 300) . "\n{url}", 'pv' => '']));
mkpost(330);
letsblog_sns_announce_post(330);
$posted = last_tweet_text();
check('告知: X の上限を超えるテンプレートは、URL を残して切り詰めて投稿される', x_weight($posted) <= 280 && str_ends_with($posted, "\nhttps://blog.example.test/?p=330") && str_contains($posted, '…') && str_starts_with($posted, '新しい記事'), $posted);

// Threads: 500 文字。既定の告知文でも、PV 達成の告知のように末尾が URL でなくても URL を残す
check('Threads の上限は500文字', LETSBLOG_THREADS_TEXT_LIMIT === 500);
$fit = letsblog_threads_fit_text(str_repeat('か', 600) . "\n{$url}");
check('Threads: 500文字を超える告知文は URL を残して切り詰める', mb_strlen($fit) <= 500 && str_ends_with($fit, "…\n{$url}"), (string) mb_strlen($fit));
$fit = letsblog_threads_fit_text("タイトル\n{$url}\n累計5000PV を達成しました" . str_repeat('き', 600));
check('Threads: URL が末尾でなくても URL を残す', mb_strlen($fit) <= 500 && str_contains($fit, $url), (string) mb_strlen($fit));
check('Threads: 500文字以内ならそのまま', letsblog_threads_fit_text("短い\n{$url}") === "短い\n{$url}");
check('各 SNS の送信処理に文字数の上限への調整が登録されている(X・Threads)', isset(letsblog_sns_senders()['x']['fit'], letsblog_sns_senders()['threads']['fit']) && !isset(letsblog_sns_senders()['facebook']['fit']));
$GLOBALS['t_options'][LETSBLOG_OPTION_SNS_LOG] = [];

if ($failures) {
    echo count($failures) . ' 件失敗:' . "\n";
    foreach ($failures as $f) {
        echo "  - $f\n";
    }
    exit(1);
}
echo 'すべて成功' . "\n";
exit(0);
