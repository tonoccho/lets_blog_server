<?php
/**
 * letsblog プラグインの PV 達成告知(issue #1577)のCLIテスト。
 * wp letsblog pv rules set(標準入力の JSON、丸ごと置き換え)、PV 取得のたびの判定、接続済み SNS への告知、
 * 記事×ルールの告知済み記録、ルール追加時の基準化(すでに達成していれば告知しない)、告知履歴の「PV 達成」。
 * GA4 と X は WordPress の HTTP API(wp_remote_request)の差し替えで再現する(test-letsblog-pv.php / test-letsblog-sns.php と同じ方式)。
 * 受け入れシナリオ(Gherkin)にしないのは、WordPress プラグインの wp-cli 契約で、Web UI からは到達できないため(設定画面は #1578)。
 * 実行: timeout 60 php __tests__/test-letsblog-pv-announce.php
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
function add_action(string $hook, $callable, int $priority = 10, int $args = 1): bool
{
    return true;
}
function add_filter(string $hook, $callable, int $priority = 10, int $args = 1): bool
{
    return true;
}
function wp_next_scheduled(string $hook, array $args = [])
{
    return false;
}
function wp_schedule_event(int $timestamp, string $recurrence, string $hook, array $args = []): bool
{
    return true;
}
function wp_clear_scheduled_hook(string $hook, array $args = []): int
{
    return 0;
}

$GLOBALS['t_posts'] = [];
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
function home_url(string $path = ''): string
{
    return 'https://blog.example.test' . $path;
}
function url_to_postid(string $url): int
{
    $parts = parse_url($url);
    return ['/first-post/' => 11, '/second-post/' => 12][$parts['path'] ?? ''] ?? 0;
}
function mk_post(int $id, string $title, string $status, string $date): void
{
    $GLOBALS['t_posts'][$id] = (object) ['ID' => $id, 'post_title' => $title, 'post_status' => $status, 'post_type' => 'post', 'post_date' => $date];
}
mk_post(11, '最初の記事', 'publish', '2026-08-26 10:00:00');
mk_post(12, '二つ目の記事', 'publish', '2026-08-30 09:00:00');

// ---- GA4 と X の差し替え ----
const GA_OAUTH = 'https://oauth-stub.test/token';
const GA_DATA = 'https://data-stub.test';
const X_BASE = 'https://x-stub.test';
define('LETSBLOG_GA_OAUTH_TOKEN_URL', GA_OAUTH);
define('LETSBLOG_GA_DATA_API_BASE_URL', GA_DATA);
define('LETSBLOG_X_API_BASE_URL', X_BASE);
$GLOBALS['ga'] = ['rows' => [], 'force_error' => false];
$GLOBALS['x'] = ['tweets' => [], 'force' => null];
function set_rows(array $rows): void
{
    $out = [];
    foreach ($rows as [$date, $path, $views]) {
        $out[] = [$date, $path, 'blog.example.test', (string) $views];
    }
    $GLOBALS['ga']['rows'] = $out;
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
    $reply = fn(int $code, array $body) => ['response' => ['code' => $code], 'body' => json_encode($body)];
    if ($GLOBALS['ga']['force_error'] && !str_starts_with($url, X_BASE)) {
        return new WP_Error();
    }
    if ($url === GA_OAUTH) {
        return $reply(200, ['access_token' => 'ga-access-1', 'expires_in' => 3599]);
    }
    if (str_starts_with($url, GA_DATA . '/v1beta/properties/')) {
        $rows = [];
        foreach ($GLOBALS['ga']['rows'] as [$date, $path, $host, $views]) {
            $rows[] = ['dimensionValues' => [['value' => $date], ['value' => $path], ['value' => $host]], 'metricValues' => [['value' => $views]]];
        }
        return $reply(200, ['rows' => $rows, 'rowCount' => count($rows), 'metadata' => ['timeZone' => 'Asia/Tokyo']]);
    }
    if ($url === X_BASE . '/2/tweets') {
        $GLOBALS['x']['tweets'][] = json_decode((string) $args['body'], true)['text'] ?? '';
        if ($GLOBALS['x']['force'] !== null) {
            return $reply($GLOBALS['x']['force'], ['title' => 'Too Many Requests', 'status' => $GLOBALS['x']['force']]);
        }
        return $reply(201, ['data' => ['id' => '1001']]);
    }
    return $reply(404, []);
}

define('WP_CLI', true);
define('LETSBLOG_PLUGIN_TESTING', true);
require __DIR__ . '/../../letsblog-plugin/letsblog.php';

class TestRulesCommand extends Letsblog_CLI_Command
{
    public string $stdin = '';
    public ?int $clock = null;
    protected function read_stdin(): string
    {
        return $this->stdin;
    }
    protected function now(): int
    {
        return $this->clock ?? time();
    }
}

$cmd = new TestRulesCommand();
function run_cmd(string $method, array $args, array $assoc = [], ?string $stdin = null): array
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
function set_rules(array $rules, int $clock, bool $wrapped = false): ?string
{
    global $cmd;
    $cmd->clock = $clock;
    [$err] = run_cmd('pv', ['rules', 'set'], [], json_encode($wrapped ? ['rules' => $rules] : $rules));
    return $err;
}
function rule(string $id, string $period, $threshold): array
{
    return ['id' => $id, 'period' => $period, 'threshold' => $threshold];
}
function log_entries(): array
{
    [, $lines] = run_cmd('sns', ['log'], ['format' => 'json']);
    $decoded = json_decode($lines[0] ?? '', true);
    return is_array($decoded) ? $decoded : [];
}
function pv_entries(): array
{
    return array_values(array_filter(log_entries(), fn($e) => ($e['kind'] ?? '') === 'PV 達成'));
}
function tweets(): array
{
    return $GLOBALS['x']['tweets'];
}
function tweets_for(string $titlePart): array
{
    return array_values(array_filter(tweets(), fn($t) => str_contains($t, $titlePart)));
}
function fetch_at(int $now): array
{
    return letsblog_pv_fetch($now);
}

$T0 = strtotime('2026-08-31 03:00:00 UTC');   // 東京では 08-31 12:00
$T1 = strtotime('2026-08-31 16:00:00 UTC');   // 東京では 09-01 01:00
$cmd->clock = $T0;
$P1 = '/first-post/';
$P2 = '/second-post/';

[$err] = run_cmd('pv', ['config', 'set'], [], json_encode(['property_id' => '987654321', 'client_id' => 'cid', 'client_secret' => 'csecret', 'refresh_token' => 'refresh-1']));
check('前提: GA4 を設定できる', $err === null, (string) $err);
[$err] = run_cmd('sns', ['config', 'set'], [], json_encode(['sns' => 'x', 'client_id' => 'cid', 'client_secret' => 'csecret', 'access_token' => 'access-1', 'refresh_token' => 'refresh-1', 'expires_at' => time() + 3600, 'account_name' => 'Official']));
check('前提: X を接続できる', $err === null, (string) $err);

// --- rules set の入力検証。失敗しても保存済みのルールは壊れない ---
check('rules set: 正しい配列を受け取る', set_rules([rule('t30', 'total', 30), rule('t50', 'total', 50), rule('d15', 'daily', 15)], $T0) === null);
$bad = [
    '配列でない' => '{"id":"a"}',
    'JSON でない' => '{not json',
    'id が無い' => json_encode([['period' => 'total', 'threshold' => 5]]),
    'id が不正な文字' => json_encode([rule('a b/..', 'total', 5)]),
    'id が重複' => json_encode([rule('a', 'total', 5), rule('a', 'daily', 5)]),
    '期間が不正' => json_encode([rule('a', 'weekly', 5)]),
    '閾値が0' => json_encode([rule('a', 'total', 0)]),
    '閾値が負' => json_encode([rule('a', 'total', -3)]),
    '閾値が文字列' => json_encode([rule('a', 'total', '5')]),
    '閾値が小数' => json_encode([rule('a', 'total', 5.5)]),
    '要素がオブジェクトでない' => json_encode(['x']),
];
foreach ($bad as $label => $json) {
    [$e] = run_cmd('pv', ['rules', 'set'], [], $json);
    check("rules set: {$label}は失敗する", $e !== null);
}
[$e] = run_cmd('pv', ['rules', 'set'], [], json_encode(['rules' => 'x']));
check('rules set: rules が配列でなければ失敗する', $e !== null);
[$e] = run_cmd('pv', ['rules', 'nope'], [], '[]');
check('rules: 未知のサブコマンドは失敗する', $e !== null);

// --- 初回の取得: すでに達成している記事は告知しない(基準化)。ルールはまだ PV データが無い時点で設定済み ---
set_rows([['20260826', $P1, 10], ['20260827', $P1, 20], ['20260831', $P1, 7], ['20260830', $P2, 3], ['20260831', $P2, 4]]);
$res = fetch_at($T0);
check('初回の取得が成功する', ($res['ok'] ?? null) === true);
check('初回: すでに累計30を超えている記事は告知されない', tweets() === [] && pv_entries() === [], json_encode(tweets(), JSON_UNESCAPED_UNICODE));

// --- AC1: 累計ルールの閾値を超えた記事が X へ達成告知として投稿され、履歴に残る ---
set_rows([['20260826', $P1, 10], ['20260827', $P1, 20], ['20260831', $P1, 20], ['20260830', $P2, 3], ['20260831', $P2, 4]]);
fetch_at($T0 + 3600);
$t50 = array_values(array_filter(tweets(), fn($t) => str_contains($t, '累計50PV')));
check('累計: 閾値(50)に達した記事が投稿される', count($t50) === 1, json_encode(tweets(), JSON_UNESCAPED_UNICODE));
check('累計: 告知文にタイトルが入る', str_contains($t50[0] ?? '', '最初の記事'));
check('累計: 告知文に URL が入る', str_contains($t50[0] ?? '', 'https://blog.example.test/?p=11'));
check('累計: 告知文に達成した内容が入る', str_contains($t50[0] ?? '', '累計50PV'));
$d15 = array_values(array_filter(tweets(), fn($t) => str_contains($t, '1日で15PV')));
check('1日: 追加した日のうちに閾値(15)以上になった記事が投稿される', count($d15) === 1 && str_contains($d15[0], '最初の記事'), json_encode(tweets(), JSON_UNESCAPED_UNICODE));
check('累計: 達していない記事は告知されない', tweets_for('二つ目の記事') === []);
check('累計: 告知はこの2件だけ', count(tweets()) === 2);
$entries = pv_entries();
check('履歴: 種類「PV 達成」で2件', count($entries) === 2, json_encode($entries, JSON_UNESCAPED_UNICODE));
check('履歴: SNS・記事・成功が入る', ($entries[0]['sns'] ?? null) === 'x' && ($entries[0]['post_id'] ?? null) === 11 && ($entries[0]['success'] ?? null) === true);

// --- AC3: 同じ記事×ルールの組では2回目以降は告知されない ---
fetch_at($T0 + 7200);
set_rows([['20260826', $P1, 10], ['20260827', $P1, 20], ['20260831', $P1, 30], ['20260830', $P2, 3], ['20260831', $P2, 4]]);
fetch_at($T0 + 10800);
check('2回目以降は告知されない', count(tweets()) === 2 && count(pv_entries()) === 2);

// --- AC4: ルールを追加した時点ですでに達成していた記事は告知されない。残したルールの記録は保たれる ---
check('ルール追加(既存ルールも同じ内容で渡す)', set_rules([rule('t30', 'total', 30), rule('t50', 'total', 50), rule('d15', 'daily', 15), rule('t40', 'total', 40)], $T0 + 10900, true) === null);
fetch_at($T0 + 11000);
check('追加時にすでに達成: 告知されない', tweets_for('累計40PV') === [] && count(tweets()) === 2);
set_rows([['20260826', $P1, 10], ['20260827', $P1, 20], ['20260831', $P1, 30], ['20260830', $P2, 3], ['20260831', $P2, 36]]);
fetch_at($T0 + 14400);
check('追加後に達した別の記事は告知される(累計30)', count(tweets_for('累計30PV')) === 1 && str_contains(tweets_for('累計30PV')[0], '二つ目の記事'), json_encode(tweets(), JSON_UNESCAPED_UNICODE));
check('追加後に達した別の記事は告知される(1日15)', count(tweets_for('1日で15PV')) === 2);
check('達していないルール(累計40)は告知されない', tweets_for('累計40PV') === []);
check('基準化された記事×ルールは、その後も告知されない', count(tweets_for('最初の記事')) === 2);

// --- AC2: 1日ルールは GA プロパティのタイムゾーンの日付で、追加した日以降の日だけを見る ---
check('1日ルールだけに置き換える(UTC 16:00 = 東京は翌日)', set_rules([rule('d20', 'daily', 20)], $T1) === null);
$before = count(tweets());
set_rows([['20260826', $P1, 10], ['20260827', $P1, 20], ['20260831', $P1, 30], ['20260830', $P2, 3], ['20260831', $P2, 36], ['20260901', $P1, 19]]);
fetch_at($T1);
check('追加した日より前の日(東京の 08-31)が閾値以上でも告知されない', count(tweets()) === $before, json_encode(array_slice(tweets(), $before), JSON_UNESCAPED_UNICODE));
set_rows([['20260826', $P1, 10], ['20260827', $P1, 20], ['20260831', $P1, 30], ['20260830', $P2, 3], ['20260831', $P2, 36], ['20260901', $P1, 20]]);
fetch_at($T1 + 600);
$new = array_slice(tweets(), $before);
check('追加した日(東京の 09-01)が閾値以上になれば告知される', count($new) === 1 && str_contains($new[0], '最初の記事') && str_contains($new[0], '1日で20PV'), json_encode($new, JSON_UNESCAPED_UNICODE));
fetch_at($T1 + 1200);
check('1日ルールも同じ記事では2回目は告知されない', count(tweets()) === $before + 1);

// --- 同じ id でも内容(閾値)が変われば新しいルールとして基準化する ---
set_rows([['20260831', $P2, 36], ['20260901', $P1, 20], ['20260901', $P2, 500]]);
fetch_at($T1 + 1800);
$before = count(tweets());
check('同じ id で閾値を変える', set_rules([rule('d20', 'daily', 600)], $T1 + 1900) === null);
set_rows([['20260831', $P2, 36], ['20260901', $P1, 20], ['20260901', $P2, 650]]);
fetch_at($T1 + 2000);
$new = array_slice(tweets(), $before);
check('閾値を変えたルールは、変更後の閾値に達したときに告知される', count($new) === 1 && str_contains($new[0], '二つ目の記事') && str_contains($new[0], '1日で600PV'), json_encode($new, JSON_UNESCAPED_UNICODE));

// --- AC5: ルールを削除すると、その後は判定されない。再び追加すれば基準化される ---
$before = count(tweets());
check('ルールを空にする(すべて削除)', set_rules([], $T1 + 2100) === null);
set_rows([['20260831', $P2, 36], ['20260901', $P1, 5000], ['20260901', $P2, 5000]]);
fetch_at($T1 + 2200);
check('削除後は判定されない', count(tweets()) === $before);
check('ルールを再び追加する', set_rules([rule('d20', 'daily', 20), rule('t100', 'total', 100)], $T1 + 2300) === null);
fetch_at($T1 + 2400);
check('削除してから再追加したルールは、すでに達成していれば告知されない', count(tweets()) === $before);

// --- 接続済みの SNS が無いときは判定しても送らず、記録もしない(接続後に告知される) ---
run_cmd('sns', ['config', 'clear']);
check('総計ルールを追加する', set_rules([rule('t100', 'total', 100), rule('t9000', 'total', 9000)], $T1 + 2500) === null);
set_rows([['20260831', $P2, 36], ['20260901', $P1, 5000], ['20260901', $P2, 9000]]);
$before = count(tweets());
$logBefore = count(log_entries());
fetch_at($T1 + 2600);
check('SNS が未接続: 送らず、履歴にも残さない', count(tweets()) === $before && count(log_entries()) === $logBefore);
run_cmd('sns', ['config', 'set'], [], json_encode(['sns' => 'x', 'client_id' => 'cid', 'client_secret' => 'csecret', 'access_token' => 'access-1', 'refresh_token' => 'refresh-1', 'expires_at' => time() + 3600]));
fetch_at($T1 + 2700);
check('SNS を接続すると、達成していた記事が告知される', count(tweets_for('累計9000PV')) >= 1);
$count = count(tweets());
fetch_at($T1 + 2800);
check('接続後の告知も1回だけ', count(tweets()) === $count);

// --- 失敗した告知は履歴に残り、再送しない ---
$GLOBALS['x']['force'] = 429;
check('別の累計ルールを追加する', set_rules([rule('t9000', 'total', 9000), rule('t20000', 'total', 20000)], $T1 + 2900) === null);
set_rows([['20260831', $P2, 36], ['20260901', $P1, 5000], ['20260901', $P2, 25000]]);
$before = count(tweets());
fetch_at($T1 + 3000);
$last = pv_entries();
$last = end($last);
check('X が失敗: 履歴に失敗として残る', ($last['success'] ?? null) === false && str_contains((string) ($last['error'] ?? ''), '429'), json_encode($last, JSON_UNESCAPED_UNICODE));
check('X が失敗: 1回だけ試す', count(tweets()) === $before + 1);
fetch_at($T1 + 3100);
check('失敗した告知は再送しない', count(tweets()) === $before + 1);
$GLOBALS['x']['force'] = null;

// --- 送信側が例外を投げても、取得は成功し、履歴に残り、他の SNS へは送る ---
letsblog_sns_register_sender('boom', ['required' => ['token'], 'secret_fields' => ['token'], 'send' => function () {
    throw new RuntimeException('送信側の障害');
}]);
letsblog_sns_save_credentials('boom', ['token' => 't']);
check('例外のテスト用ルールを追加する', set_rules([rule('t20000', 'total', 20000), rule('t30000', 'total', 30000)], $T1 + 3200) === null);
set_rows([['20260831', $P2, 36], ['20260901', $P1, 5000], ['20260901', $P2, 35000]]);
$before = count(tweets());
$res = fetch_at($T1 + 3300);
$boom = array_values(array_filter(pv_entries(), fn($e) => ($e['sns'] ?? '') === 'boom'));
check('送信側が例外: 取得は成功する', ($res['ok'] ?? null) === true);
check('送信側が例外: 履歴に失敗として残る', count($boom) === 1 && ($boom[0]['success'] ?? null) === false && str_contains((string) ($boom[0]['error'] ?? ''), '送信側の障害'), json_encode($boom, JSON_UNESCAPED_UNICODE));
check('送信側が例外: 他の SNS(X)へは送られる', count(tweets()) === $before + 1);

// --- GA 取得に失敗したときは判定しない。公開されていない記事は対象外 ---
check('取得失敗のテスト用ルール', set_rules([rule('t30000', 'total', 30000), rule('t50000', 'total', 50000)], $T1 + 3400) === null);
$GLOBALS['ga']['force_error'] = true;
set_rows([['20260831', $P2, 36], ['20260901', $P1, 5000], ['20260901', $P2, 60000]]);
$before = count(tweets());
$res = fetch_at($T1 + 3500);
check('GA 取得に失敗: 判定も告知もしない', ($res['ok'] ?? null) === false && count(tweets()) === $before);
$GLOBALS['ga']['force_error'] = false;
$GLOBALS['t_posts'][12]->post_status = 'draft';
fetch_at($T1 + 3600);
check('公開されていない記事は告知しない', count(tweets()) === $before);
$GLOBALS['t_posts'][12]->post_status = 'publish';
fetch_at($T1 + 3700);
check('公開に戻れば判定される', count(tweets()) === $before + 1);

// ============ 告知文テンプレート(issue #1583): PV 達成時の {period}・{threshold} の置き換え ============
[$err] = run_cmd('sns', ['templates', 'set'], [], json_encode(['publish' => '【新着】{title}', 'pv' => '{period}で{threshold}PV達成! {title} {url}'], JSON_UNESCAPED_UNICODE));
check('テンプレート: templates set で PV 達成時のテンプレートを渡せる', $err === null, (string) $err);
check('テンプレート: ルールを入れ替える(1日62000・累計70000)', set_rules([rule('tpl-d', 'daily', 62000), rule('tpl-t', 'total', 70000)], $T1 + 4000) === null);
set_rows([['20260831', $P2, 36], ['20260901', $P1, 5000], ['20260901', $P2, 35000]]);
fetch_at($T1 + 4050);
$before = count(tweets());
set_rows([['20260831', $P2, 36], ['20260901', $P1, 5000], ['20260901', $P2, 63000]]);
fetch_at($T1 + 4100);
$new = array_slice(tweets(), $before);
check('テンプレート: 1日ルールの達成で {period}={1日}・{threshold} が値に置き換わる', $new === ['1日で62000PV達成! 二つ目の記事 https://blog.example.test/?p=12'], json_encode($new, JSON_UNESCAPED_UNICODE));
$before = count(tweets());
set_rows([['20260831', $P2, 36], ['20260901', $P1, 5000], ['20260901', $P2, 80000]]);
fetch_at($T1 + 4200);
$new = array_slice(tweets(), $before);
check('テンプレート: 累計ルールの達成で {period}={累計}・{threshold} が値に置き換わる', $new === ['累計で70000PV達成! 二つ目の記事 https://blog.example.test/?p=12'], json_encode($new, JSON_UNESCAPED_UNICODE));
check('テンプレート: 公開時のテンプレートは PV 達成の告知に使われない', tweets_for('【新着】') === []);

run_cmd('sns', ['templates', 'set'], [], json_encode(['publish' => '【新着】{title}', 'pv' => ''], JSON_UNESCAPED_UNICODE));
check('テンプレート: PV のテンプレートが空なら、既定の告知文で告知する(ルールを追加)', set_rules([rule('tpl-t2', 'total', 90000)], $T1 + 4300) === null);
set_rows([['20260831', $P2, 36], ['20260901', $P1, 5000], ['20260901', $P2, 80000]]);
fetch_at($T1 + 4350);
$before = count(tweets());
set_rows([['20260831', $P2, 36], ['20260901', $P1, 5000], ['20260901', $P2, 95000]]);
fetch_at($T1 + 4400);
$new = array_slice(tweets(), $before);
check('テンプレート: 空なら既定(タイトル・URL・達成した内容)', $new === ["二つ目の記事\nhttps://blog.example.test/?p=12\n累計90000PV を達成しました"], json_encode($new, JSON_UNESCAPED_UNICODE));

if ($failures !== []) {
    echo count($failures) . " 件以上失敗\n";
    exit(1);
}
echo 'すべて成功' . "\n";
exit(0);
