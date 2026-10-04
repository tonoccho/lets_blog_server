<?php
/**
 * letsblog プラグインの GA4 記事別 PV 取得(issue #1576)のCLIテスト。
 * wp letsblog pv config set / config clear / status / list、WP-Cron による定期取得、暗号化保存、取得失敗の記録。
 * GA4(OAuth と Data API)は WordPress の HTTP API(wp_remote_request)の差し替えで再現する。実スタブ
 * (infra/e2e-stubs/google-analytics)との突き合わせは infra/e2e-stubs/google-analytics/server.test.js が受け持つ。
 * 実行: timeout 60 php __tests__/test-letsblog-pv.php
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

// ---- WordPress のフック・cron・投稿の差し替え ----
$GLOBALS['t_actions'] = [];
$GLOBALS['t_scheduled'] = [];
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
function do_registered_action(string $hook, array $args = []): void
{
    foreach ($GLOBALS['t_actions'][$hook] ?? [] as $a) {
        ($a['fn'])(...$args);
    }
}
function wp_next_scheduled(string $hook, array $args = [])
{
    return $GLOBALS['t_scheduled'][$hook] ?? false;
}
function wp_schedule_event(int $timestamp, string $recurrence, string $hook, array $args = []): bool
{
    $GLOBALS['t_scheduled'][$hook] = ['at' => $timestamp, 'recurrence' => $recurrence];
    return true;
}
function wp_clear_scheduled_hook(string $hook, array $args = []): int
{
    $had = isset($GLOBALS['t_scheduled'][$hook]);
    unset($GLOBALS['t_scheduled'][$hook]);
    return $had ? 1 : 0;
}
function get_post($id)
{
    return $GLOBALS['t_posts'][$id] ?? null;
}
function get_the_title($post = 0): string
{
    return (string) (is_object($post) ? $post->post_title : ($GLOBALS['t_posts'][$post]->post_title ?? ''));
}
function home_url(string $path = ''): string
{
    return 'https://blog.example.test' . $path;
}
function url_to_postid(string $url): int
{
    $parts = parse_url($url);
    if (($parts['host'] ?? '') !== 'blog.example.test') {
        return 0;
    }
    return ['/first-post/' => 11, '/second-post/' => 12, '/draft-post/' => 13][$parts['path'] ?? ''] ?? 0;
}
function mk_post(int $id, string $title, string $status, string $date): void
{
    $GLOBALS['t_posts'][$id] = (object) ['ID' => $id, 'post_title' => $title, 'post_status' => $status, 'post_type' => 'post', 'post_date' => $date];
}
mk_post(11, '最初の記事', 'publish', '2026-08-26 10:00:00');
mk_post(12, '二つ目の記事', 'publish', '2026-08-30 09:00:00');
mk_post(13, '下書きの記事', 'draft', '2026-08-20 09:00:00');

// ---- GA4 の差し替え(wp_remote_request) ----
const GA_OAUTH = 'https://oauth-stub.test/token';
const GA_DATA = 'https://data-stub.test';
define('LETSBLOG_GA_OAUTH_TOKEN_URL', GA_OAUTH);
define('LETSBLOG_GA_DATA_API_BASE_URL', GA_DATA);
$GLOBALS['ga'] = [];
function ga_reset(): void
{
    $host = 'blog.example.test';
    $GLOBALS['ga'] = [
        'valid_refresh' => 'refresh-1', 'requests' => [], 'force_report' => null, 'force_error' => false, 'apply_filter' => true,
        'rows' => [
            ['20260825', '/first-post/', $host, '100'],
            ['20260826', '/first-post/', $host, '10'],
            ['20260827', '/first-post/', $host, '20'],
            ['20260831', '/first-post/', $host, '5'],
            ['20260831', '/first-post/?utm_source=x', $host, '2'],
            ['20260830', '/second-post/', $host, '3'],
            ['20260831', '/second-post/', $host, '4'],
            ['20260831', '/', $host, '50'],
            ['20260831', '/unknown/', $host, '9'],
            ['20260831', '/draft-post/', $host, '8'],
            ['20260831', '/first-post/', 'other.example.test', '1000'],
        ],
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
    $ga = &$GLOBALS['ga'];
    $ga['requests'][] = ['url' => $url, 'args' => $args];
    $reply = fn(int $code, array $body) => ['response' => ['code' => $code], 'body' => json_encode($body)];
    if ($ga['force_error']) {
        return new WP_Error();
    }
    if ($url === GA_OAUTH) {
        parse_str((string) ($args['body'] ?? ''), $form);
        if (($form['grant_type'] ?? '') !== 'refresh_token' || ($form['client_id'] ?? '') !== 'cid'
            || ($form['client_secret'] ?? '') !== 'csecret' || ($form['refresh_token'] ?? '') !== $ga['valid_refresh']) {
            return $reply(400, ['error' => 'invalid_grant', 'error_description' => 'Bad Request']);
        }
        return $reply(200, ['access_token' => 'ga-access-1', 'expires_in' => 3599, 'token_type' => 'Bearer']);
    }
    if (str_starts_with($url, GA_DATA . '/v1beta/properties/') && str_ends_with($url, ':runReport')) {
        if (($args['headers']['Authorization'] ?? '') !== 'Bearer ga-access-1') {
            return $reply(401, ['error' => ['code' => 401, 'message' => 'invalid access token']]);
        }
        if ($ga['force_report'] !== null) {
            return $reply($ga['force_report'], ['error' => ['code' => $ga['force_report'], 'message' => 'The caller does not have permission']]);
        }
        $payload = json_decode((string) $args['body'], true);
        $filterHost = $payload['dimensionFilter']['filter']['stringFilter']['value'] ?? null;
        $rows = [];
        foreach ($ga['rows'] as [$date, $path, $host, $views]) {
            if ($ga['apply_filter'] && $filterHost !== null && $host !== $filterHost) {
                continue;
            }
            $rows[] = ['dimensionValues' => [['value' => $date], ['value' => $path], ['value' => $host]], 'metricValues' => [['value' => $views]]];
        }
        return $reply(200, ['rows' => $rows, 'rowCount' => count($rows), 'metadata' => ['timeZone' => 'Asia/Tokyo']]);
    }
    return $reply(404, []);
}
function ga_report_requests(): array
{
    return array_values(array_filter($GLOBALS['ga']['requests'], fn($r) => str_ends_with($r['url'], ':runReport')));
}
ga_reset();

define('WP_CLI', true);
define('LETSBLOG_PLUGIN_TESTING', true);
require __DIR__ . '/../../letsblog-plugin/letsblog.php';

check('wp letsblog が登録されている', isset(WP_CLI::$commands['letsblog']));

class TestPvCommand extends Letsblog_CLI_Command
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

$cmd = new TestPvCommand();
function run_pv(string $method, array $args, array $assoc = [], ?string $stdin = null): array
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
function pv_status(): ?array
{
    [, $lines] = run_pv('pv', ['status']);
    $out = json_decode($lines[0] ?? '', true);
    return is_array($out) ? $out : null;
}
function pv_list(): array
{
    [, $lines] = run_pv('pv', ['list'], ['format' => 'json']);
    $out = json_decode($lines[0] ?? '', true);
    $byId = [];
    foreach (is_array($out) ? $out : [] as $row) {
        $byId[$row['post_id']] = $row;
    }
    return $byId;
}
function valid_pv_config(array $override = []): string
{
    return json_encode(array_merge([
        'property_id' => '987654321', 'client_id' => 'cid', 'client_secret' => 'csecret', 'refresh_token' => 'refresh-1',
    ], $override));
}
$NOW = strtotime('2026-08-31 03:00:00 UTC');
$cmd->clock = $NOW;

check('pv コマンドが定義されている', method_exists($cmd, 'pv'));
if (!method_exists($cmd, 'pv')) {
    echo "pv コマンドが無いため以降を中止\n";
    echo "1 件以上失敗\n";
    exit(1);
}

// --- AC4: 未設定なら GA に問い合わせない ---
$st = pv_status();
check('status: 未設定のとき configured が false', ($st['configured'] ?? null) === false, json_encode($st));
$res = letsblog_pv_fetch($NOW);
check('未設定: 取得しない(GA へ1件も問い合わせない)', $GLOBALS['ga']['requests'] === [] && ($res['ok'] ?? null) === false);
check('未設定: 失敗として記録しない', (pv_status()['last_error'] ?? null) === null);
check('list: 未設定のとき空の配列', pv_list() === []);
check('cron: 未設定のときは予約しない', !isset($GLOBALS['t_scheduled']['letsblog_pv_fetch']));
do_registered_action('init');
check('cron: init でも未設定なら予約しない', !isset($GLOBALS['t_scheduled']['letsblog_pv_fetch']));

// --- AC5: 秘密は平文で DB にも出力にも出ない ---
[$err, $lines] = run_pv('pv', ['config', 'set'], [], valid_pv_config());
check('config set: 成功する', $err === null, (string) $err);
$dump = json_encode($GLOBALS['t_options']);
$printed = implode("\n", $lines) . json_encode(pv_status()) . json_encode(pv_list());
foreach (['csecret', 'refresh-1'] as $secret) {
    check("DB に秘密「{$secret}」が平文で残らない", !str_contains($dump, $secret));
    check("出力に秘密「{$secret}」が出ない", !str_contains($printed, $secret));
}
check('status: 設定後は configured が true でプロパティ ID を返す', (pv_status()['configured'] ?? null) === true && (pv_status()['property_id'] ?? null) === '987654321');
[$err] = run_pv('pv', ['config', 'set'], ['refresh_token' => 'refresh-1'], valid_pv_config());
check('config set: 秘密を引数で受け取らない', $err !== null);
[$err] = run_pv('pv', ['config', 'set'], [], '{not json');
check('config set: JSON でなければ失敗', $err !== null);
[$err] = run_pv('pv', ['config', 'set'], [], valid_pv_config(['refresh_token' => '']));
check('config set: 必須項目が欠けていれば失敗', $err !== null);
[$err] = run_pv('pv', ['config', 'set'], [], valid_pv_config(['property_id' => 'abc/../x']));
check('config set: プロパティ ID が数字でなければ失敗', $err !== null);
check('config set: 失敗した入力で保存済みの設定は壊れない', (pv_status()['configured'] ?? null) === true && letsblog_sns_decrypt($GLOBALS['t_options']['letsblog_pv_config']['secret'])['refresh_token'] === 'refresh-1');
[$err, $lines] = run_pv('pv', ['config', 'set'], [], valid_pv_config(['property_id' => 987654321]));
check('config set: 整数のプロパティ ID も受け取る', $err === null && (pv_status()['property_id'] ?? null) === '987654321', (string) $err);

// --- cron: 1時間ごと ---
$sched = $GLOBALS['t_scheduled']['letsblog_pv_fetch'] ?? null;
check('cron: config set で1時間ごとの取得が予約される', ($sched['recurrence'] ?? null) === 'hourly', json_encode($sched));
check('cron: 取得処理がフックに登録されている', isset($GLOBALS['t_actions']['letsblog_pv_fetch']));
unset($GLOBALS['t_scheduled']['letsblog_pv_fetch']);
do_registered_action('init');
check('cron: init で予約が欠けていれば張り直す', isset($GLOBALS['t_scheduled']['letsblog_pv_fetch']));

// --- AC1/AC3: 定期取得で記事ごとの日別 PV が保存される ---
$GLOBALS['ga']['requests'] = [];
$res = letsblog_pv_fetch($NOW);
check('fetch: 成功する', ($res['ok'] ?? null) === true, json_encode($res, JSON_UNESCAPED_UNICODE));
$reqs = ga_report_requests();
check('fetch: レポートを1回問い合わせる', count($reqs) === 1);
$body = json_decode((string) ($reqs[0]['args']['body'] ?? ''), true) ?: [];
$dims = array_map(fn($d) => $d['name'], $body['dimensions'] ?? []);
check('fetch: date と pagePath のディメンションを取る', in_array('date', $dims, true) && in_array('pagePath', $dims, true), json_encode($dims));
check('fetch: screenPageViews を取る', ($body['metrics'][0]['name'] ?? null) === 'screenPageViews');
check('fetch: このサイトのホスト名に絞る', ($body['dimensionFilter']['filter']['stringFilter']['value'] ?? null) === 'blog.example.test', json_encode($body['dimensionFilter'] ?? null));
check('fetch: 初回は固定日付から全期間を取る', preg_match('/^\d{4}-\d{2}-\d{2}$/', (string) ($body['dateRanges'][0]['startDate'] ?? '')) === 1);
check('fetch: Bearer のアクセストークンで問い合わせる', ($reqs[0]['args']['headers']['Authorization'] ?? '') === 'Bearer ga-access-1');
check('fetch: リフレッシュトークンでアクセストークンを得る', count(array_filter($GLOBALS['ga']['requests'], fn($r) => $r['url'] === GA_OAUTH)) === 1);

$list = pv_list();
check('list: 公開済みの記事だけが並ぶ(未公開・対応しないパスは加算されない)', array_keys($list) === [11, 12], json_encode(array_keys($list)));
check('list: 当日 PV(GA プロパティのタイムゾーンの今日)', ($list[11]['today_pv'] ?? null) === 7 && ($list[12]['today_pv'] ?? null) === 4, json_encode($list));
check('list: 累計 PV は公開日以降の日別 PV の合計(公開前の100は含まない)', ($list[11]['total_pv'] ?? null) === 37 && ($list[12]['total_pv'] ?? null) === 7, json_encode($list));
check('list: クエリ付きのパスも同じ記事に合算される', ($list[11]['today_pv'] ?? null) === 7);
check('list: 最終取得日時を返す', is_string($list[11]['last_fetched_at'] ?? null) && strtotime($list[11]['last_fetched_at']) === $NOW);
check('list: 記事の題名を返す', ($list[11]['title'] ?? null) === '最初の記事');
$daily = letsblog_pv_daily_for_post(11);
check('保存: 記事ごとの日別 PV', $daily === ['20260826' => 10, '20260827' => 20, '20260831' => 7] || $daily === ['20260825' => 100, '20260826' => 10, '20260827' => 20, '20260831' => 7], json_encode($daily));
$sumFromDaily = 0;
foreach ($daily as $d => $v) {
    if ($d >= '20260826') {
        $sumFromDaily += $v;
    }
}
check('累計 = 公開日以降の日別 PV の合計', $sumFromDaily === ($list[11]['total_pv'] ?? -1));
$sum12 = 0;
foreach (letsblog_pv_daily_for_post(12) as $d => $v) {
    if ($d >= '20260830') {
        $sum12 += $v;
    }
}
check('累計 = 公開日以降の日別 PV の合計(別の記事)', $sum12 === ($list[12]['total_pv'] ?? -1));
$GLOBALS['ga']['apply_filter'] = false;
letsblog_pv_fetch($NOW + 60);
$GLOBALS['ga']['apply_filter'] = true;
$list = pv_list();
check('他ホストの行: GA が絞り込みを守らなくても加算されない', ($list[11]['today_pv'] ?? null) === 7 && ($list[11]['total_pv'] ?? null) === 37, json_encode($list[11] ?? null));

// --- 再取得は上書き(二重に足さない)。2回目以降は直近だけ取り直す ---
$GLOBALS['ga']['requests'] = [];
$GLOBALS['ga']['rows'] = [
    ['20260831', '/first-post/', 'blog.example.test', '8'],
    ['20260831', '/first-post/?utm_source=x', 'blog.example.test', '2'],
];
letsblog_pv_fetch($NOW + 3600);
$body = json_decode((string) (ga_report_requests()[0]['args']['body'] ?? ''), true) ?: [];
check('再取得: 直近の日数だけを取り直す', ($body['dateRanges'][0]['startDate'] ?? '') === LETSBLOG_PV_REFETCH_START, json_encode($body['dateRanges'] ?? null));
$list = pv_list();
check('再取得: 当日の値は上書きされ、二重に足されない', ($list[11]['today_pv'] ?? null) === 10 && ($list[11]['total_pv'] ?? null) === 40, json_encode($list[11] ?? null));
check('再取得: 取り直さなかった過去の日は残る', (letsblog_pv_daily_for_post(11)['20260827'] ?? null) === 20);
check('再取得: 返らなかった記事の保存値も残る', ($list[12]['total_pv'] ?? null) === 7);
check('status: 成功の日時と、失敗なし', (pv_status()['last_success_at'] ?? null) !== null && (pv_status()['last_error'] ?? null) === null);

// 日付が変われば「当日」も変わる(プロパティのタイムゾーンで判定: UTC 15:30 は東京ではもう翌日)
$crossing = strtotime('2026-08-31 15:30:00 UTC');
$cmd->clock = $crossing;
$list = pv_list();
$cmd->clock = $NOW;
check('当日: GA プロパティのタイムゾーンで日付が変わる', ($list[11]['today_pv'] ?? null) === 0 && ($list[11]['total_pv'] ?? null) === 40, json_encode($list[11] ?? null));

// --- cron のフックから実行できる ---
$GLOBALS['ga']['requests'] = [];
do_registered_action('letsblog_pv_fetch');
check('cron: フックの実行で取得される', count(ga_report_requests()) === 1);

// --- AC4: 取得に失敗したら status に理由が残り、保存済みの値は消えない ---
$before = pv_list();
$GLOBALS['ga']['valid_refresh'] = 'revoked';
$res = letsblog_pv_fetch($NOW + 7200);
$st = pv_status();
check('失敗(トークン更新): 失敗として返る', ($res['ok'] ?? null) === false);
check('失敗(トークン更新): status に理由が出る', str_contains((string) ($st['last_error'] ?? ''), 'invalid_grant'), json_encode($st, JSON_UNESCAPED_UNICODE));
check('失敗(トークン更新): 失敗の日時が出る', is_string($st['last_failure_at'] ?? null));
check('失敗(トークン更新): 理由に秘密が含まれない', !str_contains(json_encode($st), 'csecret') && !str_contains(json_encode($st), 'revoked'));
check('失敗: 保存済みの PV は消えない', pv_list()[11]['total_pv'] === $before[11]['total_pv']);
$GLOBALS['ga']['valid_refresh'] = 'refresh-1';
$GLOBALS['ga']['force_report'] = 403;
letsblog_pv_fetch($NOW + 7300);
check('失敗(レポート 403): status に HTTP ステータスと理由が出る', str_contains((string) (pv_status()['last_error'] ?? ''), '403') && str_contains((string) (pv_status()['last_error'] ?? ''), 'permission'), json_encode(pv_status(), JSON_UNESCAPED_UNICODE));
$GLOBALS['ga']['force_report'] = null;
$GLOBALS['ga']['force_error'] = true;
letsblog_pv_fetch($NOW + 7400);
$GLOBALS['ga']['force_error'] = false;
check('失敗(接続できない): status に理由が出る', (pv_status()['last_error'] ?? '') !== '');
letsblog_pv_fetch($NOW + 7500);
check('成功に戻れば失敗の理由は消える', (pv_status()['last_error'] ?? null) === null);

// --- 復号できない(salt が変わった) ---
$GLOBALS['t_salt'] = 'salt-B';
$GLOBALS['ga']['requests'] = [];
$res = letsblog_pv_fetch($NOW + 7600);
check('復号できない: GA へ問い合わせず失敗する', $GLOBALS['ga']['requests'] === [] && ($res['ok'] ?? null) === false);
check('復号できない: status に理由が出る', str_contains((string) (pv_status()['last_error'] ?? ''), '再設定'), json_encode(pv_status(), JSON_UNESCAPED_UNICODE));
$GLOBALS['t_salt'] = 'salt-A';

// --- API の向き先は wp-config の定数だけで決まる ---
run_pv('pv', ['config', 'set'], [], valid_pv_config(['data_api_base_url' => 'https://evil.test', 'oauth_token_url' => 'https://evil.test/token']));
$GLOBALS['ga']['requests'] = [];
letsblog_pv_fetch($NOW + 7700);
$allInBase = true;
foreach ($GLOBALS['ga']['requests'] as $r) {
    $allInBase = $allInBase && (str_starts_with($r['url'], GA_DATA . '/') || $r['url'] === GA_OAUTH);
}
check('アプリから送る設定で向き先は変えられない', $GLOBALS['ga']['requests'] !== [] && $allInBase);
$source = (string) file_get_contents(__DIR__ . '/../../letsblog-plugin/letsblog.php');
check('向き先は定数から読む', str_contains($source, 'LETSBLOG_GA_DATA_API_BASE_URL') && str_contains($source, 'LETSBLOG_GA_OAUTH_TOKEN_URL'));

// --- config clear: 設定・予約を消す(取得済みの PV は残す) ---
[$err] = run_pv('pv', ['config', 'clear']);
check('config clear: 成功する', $err === null, (string) $err);
check('config clear: 未設定に戻る', (pv_status()['configured'] ?? null) === false);
check('config clear: 予約が消える', !isset($GLOBALS['t_scheduled']['letsblog_pv_fetch']));
check('config clear: 秘密が DB から消える', !isset($GLOBALS['t_options']['letsblog_pv_config']));
$GLOBALS['ga']['requests'] = [];
letsblog_pv_fetch($NOW + 7800);
check('config clear 後: 取得しない', $GLOBALS['ga']['requests'] === []);

// --- 引数の検証 ---
[$err] = run_pv('pv', ['nope']);
check('未知のサブコマンドは失敗', $err !== null);
[$err] = run_pv('pv', ['config', 'nope']);
check('未知の config サブコマンドは失敗', $err !== null);
[$err] = run_pv('pv', ['list'], ['format' => 'table']);
check('list: --format は json だけ', $err !== null);

if ($failures !== []) {
    echo count($failures) . " 件以上失敗\n";
    exit(1);
}
echo 'すべて成功' . "\n";
exit(0);
