<?php
/**
 * letsblog プラグインの LinkedIn 送信処理(issue #1581。Epic #1572。基盤は #1573)のCLIテスト。
 * wp letsblog sns config set / status / test / log、アクセストークンの暗号化保存、`/v2/ugcPosts` へ
 * 接続したメンバー(urn:li:person:<sub>)を投稿者として記事のリンク付きで投稿すること、
 * 期限切れのトークン・HTTP 401 では投稿せず要再接続にして理由を告知履歴に残すこと(429 などは理由だけ)、
 * トークンを更新しないこと。LinkedIn API は WordPress の HTTP API(wp_remote_request)の差し替えで再現する。
 * 実スタブ(infra/e2e-stubs/linkedin)との突き合わせは infra/e2e-stubs/linkedin/server.test.js が受け持つ。
 * 実行: timeout 60 php __tests__/test-letsblog-linkedin.php
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

// ---- LinkedIn API の差し替え(wp_remote_request) ----
const LI_BASE = 'https://linkedin-stub.test';
define('LETSBLOG_LINKEDIN_API_BASE_URL', LI_BASE);
const LI_SUB = 'abc123SUB';
$GLOBALS['li'] = [];
function li_reset(): void
{
    $GLOBALS['li'] = ['valid' => ['li-tok'], 'requests' => [], 'published' => [], 'fail' => null, 'network_down' => false, 'no_id' => false];
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
    $li = &$GLOBALS['li'];
    $li['requests'][] = ['url' => $url, 'args' => $args];
    if ($li['network_down']) {
        return new WP_Error();
    }
    $reply = fn(int $code, array $body) => ['response' => ['code' => $code], 'body' => json_encode($body)];
    if (!str_starts_with($url, LI_BASE)) {
        return new WP_Error();
    }
    $path = parse_url(substr($url, strlen(LI_BASE)), PHP_URL_PATH);
    $auth = $args['headers']['Authorization'] ?? '';
    if ($li['fail'] !== null) {
        return $reply($li['fail'], ['status' => $li['fail'], 'message' => 'forced failure', 'serviceErrorCode' => 65600]);
    }
    if ($path === '/v2/ugcPosts') {
        if (!str_starts_with($auth, 'Bearer ') || !in_array(substr($auth, 7), $li['valid'], true)) {
            return $reply(401, ['status' => 401, 'message' => 'Invalid access token', 'serviceErrorCode' => 65601]);
        }
        if ($li['no_id']) {
            return $reply(200, []);
        }
        $li['published'][] = ['body' => json_decode((string) ($args['body'] ?? ''), true), 'headers' => $args['headers']];
        return $reply(201, ['id' => 'urn:li:share:' . count($li['published'])]);
    }
    return $reply(404, []);
}
function li_published(): array
{
    return $GLOBALS['li']['published'];
}
function li_requests_to(string $suffix): array
{
    return array_values(array_filter($GLOBALS['li']['requests'], fn($r) => str_ends_with(parse_url($r['url'], PHP_URL_PATH), $suffix)));
}
/** 公開された投稿から、本文・記事のリンク・投稿者・公開範囲を取り出す。 */
function li_summary(array $p): array
{
    $content = $p['body']['specificContent']['com.linkedin.ugc.ShareContent'] ?? [];
    return [
        'author' => $p['body']['author'] ?? null,
        'text' => $content['shareCommentary']['text'] ?? null,
        'category' => $content['shareMediaCategory'] ?? null,
        'link' => $content['media'][0]['originalUrl'] ?? null,
        'visibility' => $p['body']['visibility']['com.linkedin.ugc.MemberNetworkVisibility'] ?? null,
        'state' => $p['body']['lifecycleState'] ?? null,
    ];
}
li_reset();

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
function li_config(array $override = []): string
{
    return json_encode(array_merge(['sns' => 'linkedin', 'access_token' => 'li-tok', 'member_id' => LI_SUB, 'expires_at' => time() + 5184000, 'account_name' => '公式メンバー'], $override));
}
function li_log(): array
{
    [, $lines] = run_sns(['log'], ['format' => 'json']);
    $decoded = json_decode($lines[0] ?? '', true);
    return is_array($decoded) ? $decoded : [];
}
function li_reset_all(): void
{
    run_sns(['config', 'clear']);
    $GLOBALS['t_options'][LETSBLOG_OPTION_SNS_LOG] = [];
    li_reset();
}
function last_log(): array
{
    $log = li_log();
    return $log === [] ? [] : $log[count($log) - 1];
}

// --- 登録 ---
check('LinkedIn の送信処理が登録されている', isset(letsblog_sns_senders()['linkedin']));
check('status は未設定の LinkedIn を「未設定」と返す', (status_of('linkedin')['status'] ?? null) === '未設定');

// --- AC1/AC5: 接続と秘密の暗号化 ---
[$err, $lines] = run_sns(['config', 'set'], [], li_config(['api_base_url' => 'https://evil.example', 'client_secret' => 'sekret-cs']));
check('config set: 成功する', $err === null, (string) $err);
$st = status_of('linkedin');
check('status: 「接続済み」', ($st['status'] ?? null) === '接続済み');
check('status: アカウント名を返す', ($st['account_name'] ?? null) === '公式メンバー');
$dump = json_encode($GLOBALS['t_options']);
$printed = implode("\n", WP_CLI::$lines) . implode("\n", $lines);
check('DB にアクセストークンが平文で残らない', !str_contains($dump, 'li-tok'));
check('出力にアクセストークンが出ない', !str_contains($printed, 'li-tok') && !str_contains(json_encode($st), 'li-tok'));
check('未知の項目(api_base_url)は保存しない', !str_contains($dump, 'evil.example'));
check('Client Secret は受け取っても保存しない', !str_contains($dump, 'sekret-cs'));
check('member_id は公開項目として DB に持つ', str_contains($dump, LI_SUB));
[$err] = run_sns(['config', 'set'], [], li_config(['access_token' => '']));
check('config set: access_token が空なら拒否する', $err !== null);
[$err] = run_sns(['config', 'set'], [], json_encode(['sns' => 'linkedin', 'access_token' => 'x', 'expires_at' => 1]));
check('config set: member_id が無ければ拒否する', $err !== null && str_contains($err, 'member_id'), (string) $err);
[$err] = run_sns(['config', 'set'], [], json_encode(['sns' => 'linkedin', 'access_token' => 'x', 'member_id' => LI_SUB]));
check('config set: expires_at が無ければ拒否する', $err !== null && str_contains($err, 'expires_at'), (string) $err);
[$err] = run_sns(['config', 'set'], [], li_config(['member_id' => 123]));
check('config set: member_id が文字列でなければ拒否する', $err !== null);
run_sns(['config', 'set'], [], li_config());

// --- AC2: テスト投稿(接続したメンバーが投稿者) ---
li_reset();
[$err] = run_sns(['test', 'linkedin']);
check('test: 成功する', $err === null, (string) $err);
check('test: 1回投稿された', count(li_published()) === 1);
$sum = li_summary(li_published()[0] ?? ['body' => []]);
check('test: 投稿者は urn:li:person:<sub>', $sum['author'] === 'urn:li:person:' . LI_SUB, json_encode($sum));
check('test: 本文に接続テストが含まれる', str_contains((string) $sum['text'], '接続テスト'));
check('test: 公開範囲は PUBLIC、状態は PUBLISHED', $sum['visibility'] === 'PUBLIC' && $sum['state'] === 'PUBLISHED');
$post = li_requests_to('/v2/ugcPosts')[0];
check('test: X-Restli-Protocol-Version 2.0.0 を付ける', ($post['args']['headers']['X-Restli-Protocol-Version'] ?? '') === '2.0.0');
check('test: 認証は Bearer ヘッダ(本文・URL に秘密を載せない)',
    ($post['args']['headers']['Authorization'] ?? '') === 'Bearer li-tok'
    && !str_contains($post['url'], 'li-tok') && !str_contains((string) ($post['args']['body'] ?? ''), 'li-tok'));
check('test: トークンを更新しようとしない(/v2/ugcPosts 以外は叩かない)', count($GLOBALS['li']['requests']) === 1);
$entry = last_log();
check('test: 告知履歴に成功として記録される', ($entry['sns'] ?? '') === 'linkedin' && ($entry['kind'] ?? '') === 'test' && ($entry['success'] ?? false) === true && array_key_exists('error', $entry) && $entry['error'] === null);

// --- リンクの投稿: 最後の行の URL を記事リンクに、残りを本文にする ---
li_reset_all();
run_sns(['config', 'set'], [], li_config());
li_reset();
$r = letsblog_sns_announce('linkedin', 'publish', 1, "新しい記事\nhttps://blog.example.test/?p=1", time());
$sum = li_summary(li_published()[0] ?? ['body' => []]);
check('URL 付きの告知文: 本文はタイトル、URL は ARTICLE の originalUrl', $r['ok'] && $sum['text'] === '新しい記事' && $sum['link'] === 'https://blog.example.test/?p=1' && $sum['category'] === 'ARTICLE', json_encode($sum, JSON_UNESCAPED_UNICODE));
li_reset();
letsblog_sns_announce('linkedin', 'test', null, 'URL の無い文', time());
$sum = li_summary(li_published()[0] ?? ['body' => []]);
check('URL が無い告知文は本文だけ送る(リンクなし)', $sum['text'] === 'URL の無い文' && $sum['link'] === null && $sum['category'] === 'NONE', json_encode($sum, JSON_UNESCAPED_UNICODE));
li_reset();
letsblog_sns_announce('linkedin', 'test', null, "https://blog.example.test/?p=2", time());
$sum = li_summary(li_published()[0] ?? ['body' => []]);
check('URL だけの告知文は、URL をリンクにし本文にも入れる', $sum['link'] === 'https://blog.example.test/?p=2' && $sum['text'] === 'https://blog.example.test/?p=2' && $sum['category'] === 'ARTICLE', json_encode($sum, JSON_UNESCAPED_UNICODE));
li_reset();
$long = str_repeat('あ', 4000) . "\nhttps://blog.example.test/?p=3";
letsblog_sns_announce('linkedin', 'publish', 3, $long, time());
$sum = li_summary(li_published()[0] ?? ['body' => []]);
check('長い告知文は上限(3000文字)に収め、URL は残す', $sum['link'] === 'https://blog.example.test/?p=3' && mb_strlen((string) $sum['text']) <= 3000 + 40 && str_ends_with((string) $sum['text'], '…'), (string) mb_strlen((string) $sum['text']));

// --- AC4: 期限切れ → 投稿せず理由と要再接続 ---
li_reset_all();
run_sns(['config', 'set'], [], li_config(['expires_at' => time() - 10]));
li_reset();
[$err] = run_sns(['test', 'linkedin']);
$entry = last_log();
check('期限切れ: 投稿せず API も叩かない', li_published() === [] && $GLOBALS['li']['requests'] === []);
check('期限切れ: 理由(期限切れのため再接続が必要)が履歴に残る', ($entry['success'] ?? true) === false && str_contains((string) ($entry['error'] ?? ''), '期限切れ') && str_contains((string) ($entry['error'] ?? ''), '再接続'), json_encode($entry, JSON_UNESCAPED_UNICODE));
check('期限切れ: 要再接続になる', (status_of('linkedin')['status'] ?? null) === '要再接続');
check('期限切れ: テストコマンドはエラーを返す', $err !== null);
check('期限切れ: 秘密は履歴に出ない', !str_contains(json_encode($entry), 'li-tok'));

// --- AC4: HTTP 401 → 理由と要再接続 ---
li_reset_all();
run_sns(['config', 'set'], [], li_config());
li_reset();
$GLOBALS['li']['valid'] = ['other'];
run_sns(['test', 'linkedin']);
$entry = last_log();
check('HTTP 401: 投稿されない', li_published() === []);
check('HTTP 401: 理由(HTTP 401 と LinkedIn のメッセージ)が残り、秘密は含まれない',
    str_contains((string) ($entry['error'] ?? ''), '401') && str_contains((string) ($entry['error'] ?? ''), 'Invalid access token') && !str_contains((string) ($entry['error'] ?? ''), 'li-tok'),
    json_encode($entry, JSON_UNESCAPED_UNICODE));
check('HTTP 401: 要再接続になる', (status_of('linkedin')['status'] ?? null) === '要再接続');
check('HTTP 401: 再試行・更新はしない', count($GLOBALS['li']['requests']) === 1);

// --- 429 などは理由だけ(要再接続にしない) ---
li_reset_all();
run_sns(['config', 'set'], [], li_config());
li_reset();
$GLOBALS['li']['fail'] = 429;
run_sns(['test', 'linkedin']);
$entry = last_log();
check('HTTP 429: 理由に HTTP 429 と LinkedIn のメッセージが残る', ($entry['success'] ?? true) === false && str_contains((string) ($entry['error'] ?? ''), '429') && str_contains((string) ($entry['error'] ?? ''), 'forced failure'), json_encode($entry, JSON_UNESCAPED_UNICODE));
check('HTTP 429: 再接続は求めない', (status_of('linkedin')['status'] ?? null) === '接続済み');
li_reset();
$GLOBALS['li']['fail'] = 500;
run_sns(['test', 'linkedin']);
check('HTTP 500: 失敗として理由が残り、再接続は求めない', str_contains((string) (last_log()['error'] ?? ''), '500') && (status_of('linkedin')['status'] ?? null) === '接続済み' && li_published() === []);

li_reset();
$GLOBALS['li']['network_down'] = true;
run_sns(['test', 'linkedin']);
check('接続できないとき理由を残し、再接続は求めない', str_contains((string) (last_log()['error'] ?? ''), '接続できません') && (status_of('linkedin')['status'] ?? null) === '接続済み');

li_reset();
$GLOBALS['li']['no_id'] = true;
run_sns(['test', 'linkedin']);
check('201 以外(id なしの 200)は失敗として記録する', (last_log()['success'] ?? true) === false && str_contains((string) (last_log()['error'] ?? ''), '200'));

li_reset();
$GLOBALS['li']['fail'] = 400;
$GLOBALS['li']['valid'] = ['li-tok'];
run_sns(['test', 'linkedin']);
check('HTTP 400 でも HTTP ステータスが理由に残る', str_contains((string) (last_log()['error'] ?? ''), '400'));

// --- 送信結果の形 ---
li_reset();
$r = letsblog_linkedin_send(['access_token' => 'li-tok', 'member_id' => LI_SUB, 'expires_at' => time() + 1000], 'x', time());
check('送信結果は ok と cred を返す', $r['ok'] === true && ($r['cred']['member_id'] ?? '') === LI_SUB && $r['needs_reconnect'] === false);
$r = letsblog_linkedin_split_text("  \n ");
check('空の告知文は本文もリンクも無い', $r === ['text' => '', 'link' => null]);

// --- 公開時の告知(#1575) ---
li_reset_all();
run_sns(['config', 'set'], [], li_config());
li_reset();
$GLOBALS['t_posts'][50] = (object) ['ID' => 50, 'post_type' => 'post', 'post_password' => '', 'post_title' => '新しい記事', 'post_status' => 'publish'];
letsblog_sns_announce_post(50);
letsblog_sns_announce_post(50);
check('公開した記事は LinkedIn へ1回だけ、タイトルとリンクで投稿される', count(li_published()) === 1
    && li_summary(li_published()[0])['text'] === '新しい記事' && li_summary(li_published()[0])['link'] === 'https://blog.example.test/?p=50');
$entry = last_log();
check('公開の告知が履歴に残る', ($entry['kind'] ?? '') === 'publish' && ($entry['post_id'] ?? null) === 50 && ($entry['success'] ?? false) === true);

// --- 切断 ---
run_sns(['config', 'clear', 'linkedin']);
check('config clear linkedin: 未設定に戻る', (status_of('linkedin')['status'] ?? null) === '未設定');
li_reset();
[$err] = run_sns(['test', 'linkedin']);
check('切断後は送らない', $err !== null && li_requests_to('/v2/ugcPosts') === []);

// --- 復号できない ---
run_sns(['config', 'set'], [], li_config());
$GLOBALS['t_options'][LETSBLOG_OPTION_SNS_CONFIG]['linkedin']['secret'] = 'v1:broken';
check('復号できなければ要再接続', (status_of('linkedin')['status'] ?? null) === '要再接続');

if ($failures) {
    echo count($failures) . ' 件失敗:' . "\n";
    foreach ($failures as $f) {
        echo "  - $f\n";
    }
    exit(1);
}
echo 'すべて成功' . "\n";
exit(0);
