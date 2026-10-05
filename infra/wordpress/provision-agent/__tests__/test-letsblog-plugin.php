<?php
/**
 * letsblog プラグイン本体と、そのprovision-agentへの導入処理のCLIテスト(issue #1556)。
 * 実行: timeout 60 php __tests__/test-letsblog-plugin.php
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

function makeTmpDir(): string
{
    $dir = sys_get_temp_dir() . '/letsblog-plugin-test-' . bin2hex(random_bytes(4));
    mkdir($dir, 0777, true);
    return $dir;
}

$pluginDir = __DIR__ . '/../../letsblog-plugin';
$pluginFile = "$pluginDir/letsblog.php";
$agentDir = __DIR__ . '/..';

// --- AC4: wp letsblog status がプラグインとプロトコルのバージョンを返す ---
check('プラグイン本体が存在する', is_file($pluginFile));
$source = is_file($pluginFile) ? (string) file_get_contents($pluginFile) : '';

// WP_CLI の最小スタブ。add_command の登録先と line() の出力を記録する。
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

// WordPress の options API の最小スタブ(プラグインは受け取った内容をDBのoptionに保存する。issue #1558)。
$GLOBALS['letsblog_test_options'] = [];
function get_option(string $name, $default = false)
{
    return array_key_exists($name, $GLOBALS['letsblog_test_options']) ? $GLOBALS['letsblog_test_options'][$name] : $default;
}
function update_option(string $name, $value, $autoload = null): bool
{
    $GLOBALS['letsblog_test_options'][$name] = $value;
    return true;
}

// issue #1561: 署名付きプレビューが使う WordPress API の最小スタブ(transient・フック・URL・署名鍵)。
$GLOBALS['letsblog_test_transients'] = [];
$GLOBALS['letsblog_test_hooks'] = [];
$GLOBALS['letsblog_test_inserted_posts'] = 0;
function set_transient(string $name, $value, int $expiration = 0): bool
{
    $GLOBALS['letsblog_test_transients'][$name] = [$value, $expiration];
    return true;
}
function get_transient(string $name)
{
    return $GLOBALS['letsblog_test_transients'][$name][0] ?? false;
}
function delete_transient(string $name): bool
{
    unset($GLOBALS['letsblog_test_transients'][$name]);
    return true;
}
function delete_option(string $name): bool
{
    unset($GLOBALS['letsblog_test_options'][$name]);
    return true;
}
function home_url(string $path = ''): string
{
    return 'https://example.test' . $path;
}
function add_query_arg(string $key, string $value, string $url): string
{
    return $url . (str_contains($url, '?') ? '&' : '?') . rawurlencode($key) . '=' . rawurlencode($value);
}
function wp_salt(string $scheme = 'auth'): string
{
    return 'test-salt-' . $scheme;
}
function add_action(string $tag, $callback, int $priority = 10, int $args = 1): bool
{
    $GLOBALS['letsblog_test_hooks'][] = ['action', $tag];
    return true;
}
function add_filter(string $tag, $callback, int $priority = 10, int $args = 1): bool
{
    $GLOBALS['letsblog_test_hooks'][] = ['filter', $tag];
    $GLOBALS['letsblog_test_hook_prio'][] = [$tag, $callback, $priority];
    return true;
}
// 投稿を作る API が呼ばれたら数える(プレビューは wp_posts に行を作ってはならない)。
function wp_insert_post($postarr = [], $wp_error = false)
{
    $GLOBALS['letsblog_test_inserted_posts']++;
    return 1;
}
function wp_update_post($postarr = [], $wp_error = false)
{
    $GLOBALS['letsblog_test_inserted_posts']++;
    return 1;
}
define('WP_CLI', true);
define('LETSBLOG_PLUGIN_TESTING', true);

if ($source !== '') {
    require $pluginFile;
}

check('wp letsblog コマンドが登録される', isset(WP_CLI::$commands['letsblog']));
check('プラグインのバージョンが定義されている', defined('LETSBLOG_PLUGIN_VERSION') && LETSBLOG_PLUGIN_VERSION !== '');
check('プロトコルのバージョンが整数で定義されている', defined('LETSBLOG_PROTOCOL_VERSION') && is_int(LETSBLOG_PROTOCOL_VERSION));

if (isset(WP_CLI::$commands['letsblog'])) {
    $command = is_string(WP_CLI::$commands['letsblog']) ? new (WP_CLI::$commands['letsblog'])() : WP_CLI::$commands['letsblog'];
    $command->status([], []);
    $decoded = json_decode(WP_CLI::$lines[0] ?? '', true);
    check('status はJSONを1行出力する', is_array($decoded), WP_CLI::$lines[0] ?? '(出力なし)');
    check('status がプラグインのバージョンを返す',
        is_array($decoded) && ($decoded['plugin_version'] ?? null) === LETSBLOG_PLUGIN_VERSION);
    check('status がプロトコルのバージョンを返す',
        is_array($decoded) && ($decoded['protocol_version'] ?? null) === LETSBLOG_PROTOCOL_VERSION);
}

// --- issue #1558: wp letsblog sync(wp-cli だけで受け取り、DB に保存し、status でハッシュを返す) ---
if (isset(WP_CLI::$commands['letsblog'])) {
    $cmd = is_string(WP_CLI::$commands['letsblog']) ? new (WP_CLI::$commands['letsblog'])() : WP_CLI::$commands['letsblog'];
    $statusSync = function () use ($cmd): array {
        WP_CLI::$lines = [];
        $cmd->status([], []);
        return json_decode(WP_CLI::$lines[0] ?? '', true) ?: [];
    };
    $runSync = function (array $assoc) use ($cmd): ?string {
        WP_CLI::$lines = [];
        try {
            $cmd->sync([], $assoc);
        } catch (RuntimeException $e) {
            return $e->getMessage();
        }
        return null;
    };

    check('sync: 同期前の status の sync_hash は null', array_key_exists('sync_hash', $statusSync()) && $statusSync()['sync_hash'] === null);

    $payload = json_encode(['cssSelectorPrefix' => 'demo', 'cssBundle' => '.demo .a{color:red}', 'customTags' => []], JSON_UNESCAPED_UNICODE);
    $payloadFile = sys_get_temp_dir() . '/letsblog-sync-' . bin2hex(random_bytes(4)) . '.json';
    file_put_contents($payloadFile, $payload);
    $expected = hash('sha256', $payload);

    $err = $runSync(['file' => $payloadFile, 'hash' => $expected]);
    check('sync: 正しいハッシュ付きで成功する', $err === null, (string) $err);
    $out = json_decode(WP_CLI::$lines[0] ?? '', true);
    check('sync: 保存したハッシュを JSON で返す', is_array($out) && ($out['sync_hash'] ?? null) === $expected, WP_CLI::$lines[0] ?? '');
    check('sync: 受け取った内容を DB の option に保存する', get_option('letsblog_sync_payload') === $payload);
    check('sync: status が保存した内容のハッシュを返す', ($statusSync()['sync_hash'] ?? null) === $expected);
    check('sync: ハッシュは保存済み内容の sha256 と一致する', hash('sha256', (string) get_option('letsblog_sync_payload')) === ($statusSync()['sync_hash'] ?? ''));

    $err = $runSync(['file' => $payloadFile, 'hash' => str_repeat('0', 64)]);
    check('sync: 期待ハッシュと内容が食い違えば失敗する', $err !== null);
    check('sync: ハッシュ不一致のとき保存済みの内容を変えない', get_option('letsblog_sync_payload') === $payload && ($statusSync()['sync_hash'] ?? null) === $expected);

    $badFile = sys_get_temp_dir() . '/letsblog-sync-bad-' . bin2hex(random_bytes(4)) . '.json';
    file_put_contents($badFile, 'not json');
    check('sync: JSON でない内容は失敗する', $runSync(['file' => $badFile]) !== null);
    file_put_contents($badFile, '[1,2,3]');
    check('sync: JSON オブジェクトでない内容は失敗する', $runSync(['file' => $badFile]) !== null);
    check('sync: 不正な内容のとき保存済みの内容を変えない', get_option('letsblog_sync_payload') === $payload);
    check('sync: --file が無ければ失敗する', $runSync([]) !== null);
    check('sync: ファイルが読めなければ失敗する', $runSync(['file' => '/nonexistent/letsblog-sync.json']) !== null);

    $payload2 = json_encode(['cssSelectorPrefix' => 'demo', 'cssBundle' => '.demo .b{color:blue}', 'customTags' => [['tagName' => 'x']]], JSON_UNESCAPED_UNICODE);
    file_put_contents($payloadFile, $payload2);
    $err = $runSync(['file' => $payloadFile]);
    check('sync: --hash 省略でも成功し、内容を更新する', $err === null && get_option('letsblog_sync_payload') === $payload2);
    check('sync: 再同期で status のハッシュが新しい内容のものになる', ($statusSync()['sync_hash'] ?? null) === hash('sha256', $payload2));
    @unlink($payloadFile);
    @unlink($badFile);
}


// --- issue #1561: wp letsblog preview(投稿を作らずに実テーマで表示する署名付きプレビュー URL) ---
$GLOBALS['letsblog_test_now'] = 1_800_000_000;
check('プレビュー: 発行関数が定義されている', function_exists('letsblog_preview_issue'));
check('プレビュー: 検証関数が定義されている', function_exists('letsblog_preview_resolve'));
check('プレビュー: 期限切れの削除関数が定義されている', function_exists('letsblog_preview_purge'));
if (function_exists('letsblog_preview_issue') && function_exists('letsblog_preview_resolve') && function_exists('letsblog_preview_purge')) {
    $now = $GLOBALS['letsblog_test_now'];
    $data = ['title' => 'プレビュー題', 'content' => '<p>本文</p>', 'categories' => ['news'], 'tags' => ['a', 'b'], 'featured_image' => 'data:image/png;base64,AAAA'];
    $issued = letsblog_preview_issue($data, 600, $now);
    check('プレビュー: 発行するとトークン付きの URL を返す',
        is_string($issued['url'] ?? null) && str_contains($issued['url'], 'letsblog_preview=') && str_starts_with($issued['url'], 'https://example.test/'), json_encode($issued));
    check('プレビュー: 期限(epoch秒)を返す', ($issued['expires_at'] ?? null) === $now + 600);
    parse_str((string) parse_url($issued['url'], PHP_URL_QUERY), $query);
    $token = (string) ($query['letsblog_preview'] ?? '');
    check('プレビュー: トークンは推測できない長さ(id 32桁 + 期限 + 署名64桁)',
        preg_match('/^[0-9a-f]{32}\.\d+\.[0-9a-f]{64}$/', $token) === 1, $token);
    check('プレビュー: 内容を transient として保存する', count($GLOBALS['letsblog_test_transients']) === 1);
    check('プレビュー: transient の期限は ttl', array_values($GLOBALS['letsblog_test_transients'])[0][1] === 600);

    $ok = letsblog_preview_resolve($token, $now + 10);
    check('プレビュー: 有効なトークンは 200 とタイトル・本文を返す',
        ($ok['status'] ?? null) === 200 && ($ok['data']['title'] ?? null) === 'プレビュー題' && ($ok['data']['content'] ?? null) === '<p>本文</p>');
    check('プレビュー: カテゴリ・タグ・アイキャッチも返す',
        ($ok['data']['categories'] ?? null) === ['news'] && ($ok['data']['tags'] ?? null) === ['a', 'b'] && ($ok['data']['featured_image'] ?? null) === 'data:image/png;base64,AAAA');

    [$id, $expires, $sig] = explode('.', $token);
    $badSig = $id . '.' . $expires . '.' . str_repeat($sig[0] === '0' ? '1' : '0', 64);
    $tampered = letsblog_preview_resolve($badSig, $now + 10);
    check('プレビュー: 署名を改ざんしたトークンは 403 で本文を返さない', ($tampered['status'] ?? null) === 403 && ($tampered['data'] ?? null) === null);
    $extended = letsblog_preview_resolve($id . '.' . ($now + 999999) . '.' . $sig, $now + 10);
    check('プレビュー: 期限を延ばし書き換えたトークンは 403', ($extended['status'] ?? null) === 403 && ($extended['data'] ?? null) === null);
    foreach (['', 'garbage', 'a.b.c', $id . '.' . $expires] as $junk) {
        $r = letsblog_preview_resolve($junk, $now + 10);
        check("プレビュー: 形式の不正なトークン「{$junk}」は 403", ($r['status'] ?? null) === 403 && ($r['data'] ?? null) === null);
    }
    $unknownId = bin2hex(random_bytes(16));
    $unknownSig = hash_hmac('sha256', $unknownId . '.' . ($now + 600), wp_salt('auth'));
    $unknown = letsblog_preview_resolve($unknownId . '.' . ($now + 600) . '.' . $unknownSig, $now + 10);
    check('プレビュー: 署名は正しいが保存されていないトークンは 404', ($unknown['status'] ?? null) === 404 && ($unknown['data'] ?? null) === null);

    $expired = letsblog_preview_resolve($token, $now + 601);
    check('プレビュー: 期限切れのトークンは 404 で本文を返さない', ($expired['status'] ?? null) === 404 && ($expired['data'] ?? null) === null);
    check('プレビュー: 期限切れを検証すると保存した一時データが消える', $GLOBALS['letsblog_test_transients'] === []);

    // 期限切れの一時データは、誰も開かなくても次の発行で消える
    $first = letsblog_preview_issue($data, 60, $now);
    $second = letsblog_preview_issue($data, 600, $now + 120);
    check('プレビュー: 次の発行時に期限切れの一時データを消す', count($GLOBALS['letsblog_test_transients']) === 1);
    letsblog_preview_purge($now + 100000);
    check('プレビュー: purge は期限切れの一時データをすべて消す', $GLOBALS['letsblog_test_transients'] === []);
    $idx = get_option('letsblog_preview_index', []);
    check('プレビュー: purge は索引も空にする', $idx === [] || $idx === false);

    check('プレビュー: 投稿を作る API(wp_insert_post / wp_update_post)を呼ばない', $GLOBALS['letsblog_test_inserted_posts'] === 0);
    check('プレビュー: 本体のコードも投稿を作る API を使わない', !str_contains($source, 'wp_insert_post') && !str_contains($source, 'wp_update_post'));
}
check('preview コマンドが定義されている', isset(WP_CLI::$commands['letsblog']) && method_exists(WP_CLI::$commands['letsblog'], 'preview'));
if (isset(WP_CLI::$commands['letsblog']) && method_exists(WP_CLI::$commands['letsblog'], 'preview')) {
    $cmdP = is_string(WP_CLI::$commands['letsblog']) ? new (WP_CLI::$commands['letsblog'])() : WP_CLI::$commands['letsblog'];
    $runPreview = function (array $assoc) use ($cmdP): ?string {
        WP_CLI::$lines = [];
        try {
            $cmdP->preview([], $assoc);
        } catch (RuntimeException $e) {
            return $e->getMessage();
        }
        return null;
    };
    $pf = sys_get_temp_dir() . '/letsblog-preview-' . bin2hex(random_bytes(4)) . '.json';
    file_put_contents($pf, json_encode(['title' => 'T', 'content' => '<p>x</p>'], JSON_UNESCAPED_UNICODE));
    $err = $runPreview(['file' => $pf, 'ttl' => '120']);
    $out = json_decode(WP_CLI::$lines[0] ?? '', true);
    check('preview コマンド: 成功すると url と expires_at を JSON で出力する',
        $err === null && is_array($out) && is_string($out['url'] ?? null) && is_int($out['expires_at'] ?? null), (string) $err . ' ' . (WP_CLI::$lines[0] ?? ''));
    check('preview コマンド: --ttl を指定しなければ規定値の期限になる', $runPreview(['file' => $pf]) === null
        && is_int((json_decode(WP_CLI::$lines[0] ?? '', true) ?: [])['expires_at'] ?? null));
    check('preview コマンド: --file が無ければ失敗する', $runPreview([]) !== null);
    check('preview コマンド: ファイルが読めなければ失敗する', $runPreview(['file' => '/nonexistent/preview.json']) !== null);
    check('preview コマンド: ttl が範囲外なら失敗する', $runPreview(['file' => $pf, 'ttl' => '0']) !== null && $runPreview(['file' => $pf, 'ttl' => '999999']) !== null && $runPreview(['file' => $pf, 'ttl' => 'abc']) !== null);
    file_put_contents($pf, 'not json');
    check('preview コマンド: JSON でない内容は失敗する', $runPreview(['file' => $pf]) !== null);
    file_put_contents($pf, json_encode(['content' => '<p>x</p>']));
    check('preview コマンド: タイトルが無ければ失敗する', $runPreview(['file' => $pf]) !== null);
    file_put_contents($pf, json_encode(['title' => 'T']));
    check('preview コマンド: 本文が無ければ失敗する', $runPreview(['file' => $pf]) !== null);
    file_put_contents($pf, json_encode(['title' => 'T', 'content' => '<p>x</p>', 'categories' => 'news']));
    check('preview コマンド: categories が配列でなければ失敗する', $runPreview(['file' => $pf]) !== null);
    foreach (['javascript:alert(1)', 'data:text/html;base64,AAAA', 'https://x/a.png" onerror="x'] as $badImage) {
        file_put_contents($pf, json_encode(['title' => 'T', 'content' => '<p>x</p>', 'featured_image' => $badImage]));
        check("preview コマンド: 安全でないアイキャッチ「{$badImage}」は失敗する", $runPreview(['file' => $pf]) !== null);
    }
    foreach (['data:image/png;base64,AAAA', 'https://example.com/a.png'] as $goodImage) {
        file_put_contents($pf, json_encode(['title' => 'T', 'content' => '<p>x</p>', 'featured_image' => $goodImage]));
        check("preview コマンド: アイキャッチ「{$goodImage}」は受け付ける", $runPreview(['file' => $pf]) === null);
    }
    @unlink($pf);
}
$hookTags = array_map(fn($h) => $h[1], $GLOBALS['letsblog_test_hooks']);
check('プレビュー: template_include で単一記事テンプレートを使う', in_array('template_include', $hookTags, true));
check('プレビュー: メインクエリを差し替えて投稿を DB から読まない(posts_pre_query)', in_array('posts_pre_query', $hookTags, true));
check('プレビュー: 検索エンジンに載せない(wp_robots)', in_array('wp_robots', $hookTags, true));

// --- AC4: REST API のルートを登録しない(利用者の決定。wp-cli だけで通信する) ---
check('REST ルートを登録しない', $source !== '' && !str_contains($source, 'register_rest_route') && !str_contains($source, 'rest_api_init'));

// --- 要件5: GPL互換のライセンス表記 ---
check('GPL互換のライセンスを宣言している', $source !== '' && preg_match('/^\s*\*\s*License:\s*GPL/mi', $source) === 1);
check('WordPressのプラグインヘッダを持つ', $source !== '' && preg_match('/^\s*\*\s*Plugin Name:\s*\S+/mi', $source) === 1);

// --- AC1/AC2: provision-agent への導入 ---
$installer = "$agentDir/letsblog-plugin-installer.php";
check('導入処理のファイルが存在する', is_file($installer));
if (is_file($installer)) {
    require $installer;
}

if (function_exists('ensureLetsblogPlugin')) {
    $root = makeTmpDir();
    $site = "$root/site";
    mkdir("$site/wp-content/plugins", 0777, true);
    $dest = "$site/wp-content/plugins/letsblog/letsblog.php";

    $calls = [];
    $run = function (array $args) use (&$calls): array {
        $calls[] = $args;
        return [0, 'Success', ''];
    };

    [$code] = ensureLetsblogPlugin($site, $run, $pluginDir);
    check('AC1: 新規サイトに導入すると終了コード0', $code === 0, "code=$code");
    check('AC1: プラグインがwp-content/plugins/letsblogに配置される', is_file($dest));
    check('AC1: 配置された内容がソースと一致する', is_file($dest) && file_get_contents($dest) === $source);
    check('AC1: wp plugin activate letsblog が実行される',
        count($calls) === 1 && array_slice($calls[0], 0, 3) === ['plugin', 'activate', 'letsblog'] && in_array("--path=$site", $calls[0], true),
        json_encode($calls));

    // AC2: 2回目は何もしない(重複導入しない)
    $calls = [];
    [$code] = ensureLetsblogPlugin($site, $run, $pluginDir);
    check('AC2: 2回目も終了コード0', $code === 0);
    check('AC2: 2回目はwp-cliを呼ばない', $calls === [], json_encode($calls));
    check('AC2: 2回目もプラグインは1つだけ', glob("$site/wp-content/plugins/letsblog*") === ["$site/wp-content/plugins/letsblog"]);

    // 内容が古ければ更新して有効化し直す
    file_put_contents($dest, '<?php // old');
    $calls = [];
    ensureLetsblogPlugin($site, $run, $pluginDir);
    check('AC2: 内容が異なれば上書きする', file_get_contents($dest) === $source);

    // 有効化に失敗したら配置を取り消し、次回再試行できる
    $root2 = makeTmpDir();
    $site2 = "$root2/site";
    mkdir("$site2/wp-content/plugins", 0777, true);
    $failingRun = fn(array $args): array => [1, '', 'Error: activate failed'];
    [$code, , $err] = ensureLetsblogPlugin($site2, $failingRun, $pluginDir);
    check('有効化に失敗したら非0を返す', $code !== 0);
    check('有効化に失敗したらエラー出力を保つ', str_contains($err, 'activate failed'), $err);
    check('有効化に失敗したら配置を取り消す(次回再試行できる)', !is_file("$site2/wp-content/plugins/letsblog/letsblog.php"));

    // 導入済みのプラグインの更新で有効化に失敗したら、消さずに元の内容へ戻す
    $root3 = makeTmpDir();
    $site3 = "$root3/site";
    mkdir("$site3/wp-content/plugins/letsblog", 0777, true);
    $dest3 = "$site3/wp-content/plugins/letsblog/letsblog.php";
    file_put_contents($dest3, '<?php // previous');
    [$code] = ensureLetsblogPlugin($site3, $failingRun, $pluginDir);
    check('更新の有効化に失敗したら非0を返す', $code !== 0);
    check('更新の有効化に失敗したら導入済みの内容へ戻す', is_file($dest3) && file_get_contents($dest3) === '<?php // previous');

    // ソースが無ければ失敗する
    [$code] = ensureLetsblogPlugin($site2, $run, "$root2/no-such-source");
    check('ソースが無ければ非0を返す', $code !== 0);
}

// --- AC1/AC2: index.php・Dockerfile への結線 ---
$index = (string) file_get_contents("$agentDir/index.php");
check('index.php が導入処理を読み込む', str_contains($index, "/letsblog-plugin-installer.php"));
$provisionPos = strpos($index, "\$path === '/provision'");
$adoptPos = strpos($index, "\$path === '/adopt'");
$provisionBlock = ($provisionPos !== false && $adoptPos !== false) ? substr($index, $provisionPos, $adoptPos - $provisionPos) : '';
check('AC1: /provision が新規サイトにプラグインを導入する', str_contains($provisionBlock, 'ensureLetsblogPlugin('));
$resolvePos = strpos($index, 'function resolveExistingSitePath');
$resolveBlock = $resolvePos !== false ? substr($index, $resolvePos, 600) : '';
check('AC2: 既存サイトも wp-cli 系の初回アクセスで導入される', str_contains($resolveBlock, 'ensureLetsblogPlugin('));
$dockerfile = (string) file_get_contents("$agentDir/../Dockerfile");
check('Dockerfile がプラグインをイメージへ入れる', str_contains($dockerfile, 'COPY letsblog-plugin /var/www/letsblog-plugin'));

// --- issue #1557: 導入状態の判定と再導入 ---
if (function_exists('ensureLetsblogPlugin')) {
    // 配置済みで内容が同じでも、再導入(force)では有効化し直す(プラグインを停止したサイトを戻すため)
    $root4 = makeTmpDir();
    $site4 = "$root4/site";
    mkdir("$site4/wp-content/plugins", 0777, true);
    $run4Calls = [];
    $run4 = function (array $args) use (&$run4Calls): array {
        $run4Calls[] = $args;
        return [0, 'Success', ''];
    };
    ensureLetsblogPlugin($site4, $run4, $pluginDir);
    $run4Calls = [];
    [$code] = ensureLetsblogPlugin($site4, $run4, $pluginDir, true);
    check('再導入: 配置済みで内容が同じでも終了コード0', $code === 0, "code=$code");
    check('再導入: 配置済みで内容が同じでも wp plugin activate letsblog が実行される',
        count($run4Calls) === 1 && array_slice($run4Calls[0], 0, 3) === ['plugin', 'activate', 'letsblog'],
        json_encode($run4Calls));
    check('再導入: 配置済みの内容は変わらない', file_get_contents("$site4/wp-content/plugins/letsblog/letsblog.php") === $source);

    // 再導入で有効化に失敗しても、導入済みの配置は消さない
    $failingRun4 = fn(array $args): array => [1, '', 'Error: activate failed'];
    [$code] = ensureLetsblogPlugin($site4, $failingRun4, $pluginDir, true);
    check('再導入: 有効化に失敗したら非0を返す', $code !== 0);
    check('再導入: 有効化に失敗しても導入済みの配置は残す', is_file("$site4/wp-content/plugins/letsblog/letsblog.php"));
}

check('index.php が /wp-cli/letsblog-status を持つ', str_contains($index, "'/wp-cli/letsblog-status'"));
check('index.php が /wp-cli/letsblog-install を持つ', str_contains($index, "'/wp-cli/letsblog-install'"));
$statusPos = strpos($index, "\$path === '/wp-cli/letsblog-status'");
$installPos = strpos($index, "\$path === '/wp-cli/letsblog-install'");
$statusBlock = ($statusPos !== false && $installPos !== false && $installPos > $statusPos) ? substr($index, $statusPos, $installPos - $statusPos) : '';
check('状態の判定は wp letsblog status を wp-cli で実行する', str_contains($statusBlock, "'letsblog', 'status'"));
check('状態の判定は stderr も返す(未登録エラーとその他の失敗をアプリ側で区別するため)', str_contains($statusBlock, "'stderr'"));
check('状態の判定は導入処理を走らせない(停止したサイトを未導入のまま返すため)',
    $statusBlock !== '' && !str_contains($statusBlock, 'ensureLetsblogPlugin(') && !str_contains($statusBlock, 'resolveExistingSitePath('));
$installBlock = $installPos !== false ? substr($index, $installPos, 1500) : '';
check('再導入は force 付きで導入処理を実行する', str_contains($installBlock, 'ensureLetsblogPlugin($sitePath, null, LETSBLOG_PLUGIN_SOURCE_DIR, true)'));

// --- issue #1558: /wp-cli/letsblog-sync(payload を一時ファイルへ書き、wp letsblog sync --file で渡す) ---
check('index.php が /wp-cli/letsblog-sync を持つ', str_contains($index, "'/wp-cli/letsblog-sync'"));
$syncPos = strpos($index, "\$path === '/wp-cli/letsblog-sync'");
// ブロックの終わり(次の `if ($path === ...` の直前)まで。固定長で切ると次のブロックの内容を拾ってしまう。
$syncEnd = $syncPos !== false ? strpos($index, "\nif (\$path === ", $syncPos + 1) : false;
$syncBlock = $syncPos !== false ? substr($index, $syncPos, $syncEnd !== false ? $syncEnd - $syncPos : 2200) : '';
check('同期は wp letsblog sync を wp-cli で実行する', str_contains($syncBlock, "'letsblog', 'sync'"));
check('同期は内容を --file で渡す', str_contains($syncBlock, '--file='));
check('同期は期待ハッシュを --hash で渡す', str_contains($syncBlock, '--hash='));
check('同期は一時ファイルを必ず削除する', str_contains($syncBlock, 'unlink('));
check('同期は導入処理を走らせない(未導入のサイトへは送らない)',
    $syncBlock !== '' && !str_contains($syncBlock, 'ensureLetsblogPlugin(') && !str_contains($syncBlock, 'resolveExistingSitePath('));
check('同期は終了コードと標準出力・標準エラーを返す', str_contains($syncBlock, "'exitCode'") && str_contains($syncBlock, "'stdout'") && str_contains($syncBlock, "'stderr'"));


// --- issue #1561: /wp-cli/letsblog-preview(payload を一時ファイルへ書き、wp letsblog preview --file で渡す) ---
check('index.php が /wp-cli/letsblog-preview を持つ', str_contains($index, "'/wp-cli/letsblog-preview'"));
$prevPos = strpos($index, "\$path === '/wp-cli/letsblog-preview'");
$prevEnd = $prevPos !== false ? strpos($index, "\nif (\$path === ", $prevPos + 1) : false;
$prevBlock = $prevPos !== false ? substr($index, $prevPos, $prevEnd !== false ? $prevEnd - $prevPos : 2200) : '';
check('プレビュー発行は wp letsblog preview を wp-cli で実行する', str_contains($prevBlock, "'letsblog', 'preview'"));
check('プレビュー発行は内容を --file で渡す', str_contains($prevBlock, '--file='));
check('プレビュー発行は有効期限を --ttl で渡せる', str_contains($prevBlock, '--ttl='));
check('プレビュー発行は一時ファイルを必ず削除する', str_contains($prevBlock, 'unlink('));
check('プレビュー発行は導入処理を走らせない', $prevBlock !== '' && !str_contains($prevBlock, 'ensureLetsblogPlugin(') && !str_contains($prevBlock, 'resolveExistingSitePath('));
check('プレビュー発行は終了コードと標準出力・標準エラーを返す', str_contains($prevBlock, "'exitCode'") && str_contains($prevBlock, "'stdout'") && str_contains($prevBlock, "'stderr'"));

// --- issue #1574: /wp-cli/letsblog-sns(SNS 告知の接続・状態・テスト投稿・履歴。秘密は標準入力だけで渡す) ---
check('index.php が /wp-cli/letsblog-sns を持つ', str_contains($index, "'/wp-cli/letsblog-sns'"));
$snsPos = strpos($index, "\$path === '/wp-cli/letsblog-sns'");
$snsEnd = $snsPos !== false ? strpos($index, "\nif (\$path === ", $snsPos + 1) : false;
$snsBlock = $snsPos !== false ? substr($index, $snsPos, $snsEnd !== false ? $snsEnd - $snsPos : 3200) : '';
check('SNS は wp letsblog sns を wp-cli で実行する', str_contains($snsBlock, "'letsblog', 'sns'"));
check('SNS は config set を標準入力つきで実行する(引数に秘密を載せない)',
    str_contains($snsBlock, 'runWpWithStdin(') && str_contains($snsBlock, "'config', 'set'"));
check('SNS は config clear / status / test / log --format=json を呼び分ける',
    str_contains($snsBlock, "'config', 'clear'") && str_contains($snsBlock, "'status'")
    && str_contains($snsBlock, "'test'") && str_contains($snsBlock, "'log', '--format=json'"));
check('SNS はコマンド名を許可リストで検証する', str_contains($snsBlock, 'config-set') && str_contains($snsBlock, 'config-clear') && str_contains($snsBlock, '400'));
check('SNS は SNS 名の形式を検証する', str_contains($snsBlock, 'preg_match('));
check('SNS は標準入力を一時ファイルへ書かず、ログにも出さない',
    $snsBlock !== '' && !str_contains($snsBlock, 'file_put_contents(') && !str_contains($snsBlock, 'error_log('));
check('SNS は導入処理を走らせない', $snsBlock !== '' && !str_contains($snsBlock, 'ensureLetsblogPlugin(') && !str_contains($snsBlock, 'resolveExistingSitePath('));
check('SNS は終了コードと標準出力・標準エラーを返す', str_contains($snsBlock, "'exitCode'") && str_contains($snsBlock, "'stdout'") && str_contains($snsBlock, "'stderr'"));

// --- issue #1559: 同期済み CSS を表側で読み込み、本文の囲みのプレフィックスクラスを表示時に付け直す ---
$GLOBALS['letsblog_test_styles'] = ['registered' => [], 'enqueued' => [], 'inline' => []];
function wp_register_style(string $handle, $src, array $deps = [], $ver = false): bool
{
    $GLOBALS['letsblog_test_styles']['registered'][$handle] = [$src, $ver];
    return true;
}
function wp_enqueue_style(string $handle, $src = '', array $deps = [], $ver = false): void
{
    $GLOBALS['letsblog_test_styles']['enqueued'][] = $handle;
}
function wp_add_inline_style(string $handle, string $data): bool
{
    $GLOBALS['letsblog_test_styles']['inline'][$handle][] = $data;
    return true;
}
$resetStyles = function (): void {
    $GLOBALS['letsblog_test_styles'] = ['registered' => [], 'enqueued' => [], 'inline' => []];
};
$setSync = function (array $data): void {
    $GLOBALS['letsblog_test_options']['letsblog_sync_payload'] = json_encode($data);
};
$inlineCss = fn() => implode("\n", array_merge(...array_values($GLOBALS['letsblog_test_styles']['inline'] ?: [[]])));

check('css: wp_enqueue_scripts に CSS 読み込みを登録する',
    in_array(['action', 'wp_enqueue_scripts'], $GLOBALS['letsblog_test_hooks'], true));
check('css: the_content に囲みクラスの付け直しを登録する',
    in_array(['filter', 'the_content'], $GLOBALS['letsblog_test_hooks'], true));
check('css: 読み込み関数が定義されている', function_exists('letsblog_enqueue_synced_css'));
check('css: 付け直し関数が定義されている', function_exists('letsblog_rewrite_wrapper_class'));

if (function_exists('letsblog_enqueue_synced_css') && function_exists('letsblog_rewrite_wrapper_class')) {
    // 同期前: 何も読み込まない
    unset($GLOBALS['letsblog_test_options']['letsblog_sync_payload']);
    $resetStyles();
    letsblog_enqueue_synced_css();
    check('css: 同期前は何も読み込まない', $GLOBALS['letsblog_test_styles']['enqueued'] === []);

    // 壊れた内容・CSS が空・CSS が文字列でない: 読み込まない
    $GLOBALS['letsblog_test_options']['letsblog_sync_payload'] = 'not json';
    $resetStyles();
    letsblog_enqueue_synced_css();
    check('css: 壊れた保存内容では何も読み込まない', $GLOBALS['letsblog_test_styles']['enqueued'] === []);
    $setSync(['cssSelectorPrefix' => 'p', 'cssBundle' => '']);
    $resetStyles();
    letsblog_enqueue_synced_css();
    check('css: cssBundle が空なら何も読み込まない', $GLOBALS['letsblog_test_styles']['enqueued'] === []);
    $setSync(['cssSelectorPrefix' => 'p', 'cssBundle' => ['x']]);
    $resetStyles();
    letsblog_enqueue_synced_css();
    check('css: cssBundle が文字列でなければ何も読み込まない', $GLOBALS['letsblog_test_styles']['enqueued'] === []);
    $GLOBALS['letsblog_test_options']['letsblog_sync_payload'] = '[1,2]';
    $resetStyles();
    letsblog_enqueue_synced_css();
    check('css: JSON の配列では何も読み込まない', $GLOBALS['letsblog_test_styles']['enqueued'] === []);

    // AC1/AC3: 同期済み CSS(組み込みタグのデザイン CSS を含む統合 CSS)を読み込む
    $bundle = ".pfx .toc{color:red}\n.pfx .blog-card{border:1px solid #000}\n.pfx .amazon{color:#f90}";
    $setSync(['cssSelectorPrefix' => 'pfx', 'cssBundle' => $bundle]);
    $resetStyles();
    letsblog_enqueue_synced_css();
    check('css: 統合 CSS を wp_enqueue_style で読み込む', count($GLOBALS['letsblog_test_styles']['enqueued']) === 1);
    check('css: 統合 CSS をインラインで付ける(組み込みタグのデザイン CSS を含む)',
        str_contains($inlineCss(), '.pfx .toc{color:red}') && str_contains($inlineCss(), '.pfx .blog-card') && str_contains($inlineCss(), '.pfx .amazon'));
    $handle = $GLOBALS['letsblog_test_styles']['enqueued'][0] ?? '';
    check('css: 登録したハンドルにインライン CSS を付ける', isset($GLOBALS['letsblog_test_styles']['inline'][$handle]));

    // AC1: 再同期で CSS が変われば、再投稿なしで次の表示から新しい CSS になる
    $setSync(['cssSelectorPrefix' => 'pfx', 'cssBundle' => '.pfx .toc{color:blue}']);
    $resetStyles();
    letsblog_enqueue_synced_css();
    check('css: 再同期後は新しい CSS を読み込む', str_contains($inlineCss(), 'color:blue') && !str_contains($inlineCss(), 'color:red'));

    // </style> でインライン CSS から抜け出せない
    $setSync(['cssSelectorPrefix' => 'pfx', 'cssBundle' => ".a{}</style><script>alert(1)</script>"]);
    $resetStyles();
    letsblog_enqueue_synced_css();
    check('css: </style> でスタイル要素を閉じさせない', !str_contains(strtolower($inlineCss()), '</style'));

    // AC2: 囲みのプレフィックスクラスを現在のプレフィックスへ付け直す
    $setSync(['cssSelectorPrefix' => 'newpfx', 'cssBundle' => '.newpfx a{}']);
    $old = '<div class="lets-blog-rendered oldpfx"><p>本文</p></div>';
    check('wrapper: 古いプレフィックスを現在のものに付け直す',
        letsblog_rewrite_wrapper_class($old) === '<div class="lets-blog-rendered newpfx"><p>本文</p></div>', letsblog_rewrite_wrapper_class($old));
    check('wrapper: すでに現在のプレフィックスならそのまま',
        letsblog_rewrite_wrapper_class('<div class="lets-blog-rendered newpfx">x</div>') === '<div class="lets-blog-rendered newpfx">x</div>');
    check('wrapper: プレフィックスが無かった囲みにも付ける',
        letsblog_rewrite_wrapper_class('<div class="lets-blog-rendered">x</div>') === '<div class="lets-blog-rendered newpfx">x</div>');
    check('wrapper: 囲みのない本文は変えない', letsblog_rewrite_wrapper_class('<p>手書き</p>') === '<p>手書き</p>');
    check('wrapper: 他のクラスの div は変えない',
        letsblog_rewrite_wrapper_class('<div class="other oldpfx">x</div>') === '<div class="other oldpfx">x</div>');
    check('wrapper: 本文中の囲みの後ろの内容は保つ',
        str_contains(letsblog_rewrite_wrapper_class('<div class="lets-blog-rendered oldpfx"><div class="inner">a</div></div>'), '<div class="inner">a</div></div>'));

    // プレフィックスが空・同期前・不正な値のとき、囲みは変えない/クラスだけ
    $setSync(['cssSelectorPrefix' => '', 'cssBundle' => '.a{}']);
    check('wrapper: プレフィックスが空なら固定クラスだけにする',
        letsblog_rewrite_wrapper_class('<div class="lets-blog-rendered oldpfx">x</div>') === '<div class="lets-blog-rendered">x</div>');
    $setSync(['cssSelectorPrefix' => 'a"><script>', 'cssBundle' => '.a{}']);
    check('wrapper: 不正なプレフィックスでは本文を変えない(注入しない)',
        letsblog_rewrite_wrapper_class('<div class="lets-blog-rendered oldpfx">x</div>') === '<div class="lets-blog-rendered oldpfx">x</div>');
    $setSync(['cssBundle' => '.a{}']);
    check('wrapper: プレフィックスのキーが無ければ本文を変えない',
        letsblog_rewrite_wrapper_class('<div class="lets-blog-rendered oldpfx">x</div>') === '<div class="lets-blog-rendered oldpfx">x</div>');
    unset($GLOBALS['letsblog_test_options']['letsblog_sync_payload']);
    check('wrapper: 同期前は本文を変えない',
        letsblog_rewrite_wrapper_class('<div class="lets-blog-rendered oldpfx">x</div>') === '<div class="lets-blog-rendered oldpfx">x</div>');
    check('wrapper: 本文が文字列でなければそのまま返す', letsblog_rewrite_wrapper_class(null) === null);
}

// --- issue #1560: 目印付きのカスタムタグを、保存済みのテンプレートで表示時に展開し直す ---
function letsblog_test_marker(string $name, array $attrs, string $content, string $embedded): string
{
    $json = json_encode(['name' => $name, 'attrs' => (object) $attrs, 'content' => $content], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    $json = strtr($json, ['-' => '\u002d', '<' => '\u003c', '>' => '\u003e', '[' => '\u005b', ']' => '\u005d']);
    return '<!-- lbs:tag ' . $json . ' -->' . $embedded . '<!-- /lbs:tag -->';
}
$ctPrio = null;
foreach ($GLOBALS['letsblog_test_hook_prio'] ?? [] as [$t, $cb, $pr]) {
    if ($t === 'the_content' && $cb === 'letsblog_expand_custom_tags') {
        $ctPrio = $pr;
    }
}
check('tags: the_content に展開を登録する', function_exists('letsblog_expand_custom_tags') && $ctPrio !== null);
check('tags: wpautop(優先度 10)より前に処理する', $ctPrio !== null && $ctPrio < 10);

if (function_exists('letsblog_expand_custom_tags')) {
    $setSync([
        'cssSelectorPrefix' => 'p',
        'customTags' => [
            ['scope' => 'GLOBAL', 'tagName' => 'box', 'tagFormat' => 'BLOCK', 'htmlTemplate' => '<div class="box v2" data-lv="{{attr:level}}">{{content}}</div>', 'cssContent' => ''],
            ['scope' => 'GLOBAL', 'tagName' => 'my-badge', 'tagFormat' => 'INLINE', 'htmlTemplate' => '<span class="b2">{{content}}</span>', 'cssContent' => ''],
        ],
    ]);

    // AC1/AC2: テンプレートが変わると、再投稿なしで新しいテンプレートになる。属性・変換済み本文は保たれる
    $old = letsblog_test_marker('box', ['level' => 'warn'], '<p>本文<strong>太字</strong></p>', '<div class="box v1" data-lv="warn"><p>本文<strong>太字</strong></p></div>');
    $out = letsblog_expand_custom_tags('前置き' . "\n" . $old . "\n後書き");
    check('tags: 同期済みの新しいテンプレートで展開し直す', str_contains($out, '<div class="box v2" data-lv="warn">') && !str_contains($out, 'box v1'));
    check('tags: 変換済みの {{content}} の HTML が保たれる', str_contains($out, '<p>本文<strong>太字</strong></p></div>'));
    check('tags: 目印の外の本文は変えない', str_starts_with($out, '前置き' . "\n") && str_ends_with($out, "\n後書き"));
    check('tags: 展開結果に目印コメントが残らない', !str_contains($out, 'lbs:tag'));

    // ハイフンを含む名前・複数箇所
    $badge = letsblog_test_marker('my-badge', [], 'NEW', '<span class="b1">NEW</span>');
    $out = letsblog_expand_custom_tags("a{$badge}b{$badge}c");
    check('tags: ハイフンを含む名前を展開し、複数箇所を処理する', $out === 'a<span class="b2">NEW</span>b<span class="b2">NEW</span>c');

    // XSS: 属性値はエスケープする。本文の中の {{attr:..}} は差し込まない
    $xss = letsblog_test_marker('box', ['level' => '"><script>alert(1)</script>'], 'x', 'old');
    $out = letsblog_expand_custom_tags($xss);
    check('tags: 属性値の < > " をエスケープする', !str_contains($out, '<script>') && str_contains($out, '&quot;&gt;&lt;script&gt;'));
    $inj = letsblog_test_marker('box', ['level' => 'L'], '{{attr:level}}', 'old');
    check('tags: 本文の中のプレースホルダーを属性で置換しない', str_contains(letsblog_expand_custom_tags($inj), '>{{attr:level}}</div>'));
    $missing = letsblog_test_marker('box', [], 'x', 'old');
    check('tags: 未指定の属性は空文字', str_contains(letsblog_expand_custom_tags($missing), 'data-lv=""'));
    $nonStr = '<!-- lbs:tag ' . strtr(json_encode(['name' => 'box', 'attrs' => ['level' => ['a'], 'n' => 5], 'content' => 'x']), ['-' => '\u002d']) . ' -->old<!-- /lbs:tag -->';
    $out = letsblog_expand_custom_tags($nonStr);
    check('tags: 文字列でない属性値は空文字として扱う', str_contains($out, 'data-lv=""') && !str_contains($out, 'Array'));

    // AC4: 同期済みの定義にないタグは投稿時点の HTML のまま
    $gone = letsblog_test_marker('removed', ['a' => 'b'], 'x', '<div class="old">投稿時点</div>');
    $out = letsblog_expand_custom_tags('前' . $gone . '後');
    check('tags: 定義にないタグは投稿時点の HTML のまま表示される', str_contains($out, '<div class="old">投稿時点</div>') && str_starts_with($out, '前'));
    // 壊れた目印も投稿時点の HTML
    $broken = '<!-- lbs:tag {not json} --><i>keep</i><!-- /lbs:tag -->';
    check('tags: 壊れた目印は投稿時点の HTML のまま', str_contains(letsblog_expand_custom_tags($broken), '<i>keep</i>'));
    $badName = '<!-- lbs:tag {"name":5,"attrs":{},"content":"x"} --><i>keep2</i><!-- /lbs:tag -->';
    check('tags: 名前が文字列でない目印は投稿時点の HTML のまま', str_contains(letsblog_expand_custom_tags($badName), '<i>keep2</i>'));
    $noContent = '<!-- lbs:tag {"name":"box","attrs":[]} --><i>keep3</i><!-- /lbs:tag -->';
    check('tags: 本文のない目印は投稿時点の HTML のまま', str_contains(letsblog_expand_custom_tags($noContent), '<i>keep3</i>'));
    $badAttrs = '<!-- lbs:tag {"name":"box","attrs":"x","content":"c"} --><i>keep4</i><!-- /lbs:tag -->';
    check('tags: attrs が配列でない目印は投稿時点の HTML のまま', str_contains(letsblog_expand_custom_tags($badAttrs), '<i>keep4</i>'));
    $unclosed = '<!-- lbs:tag {"name":"box","attrs":{},"content":"c"} --><i>no close</i>';
    check('tags: 閉じのない目印は触らない', letsblog_expand_custom_tags($unclosed) === $unclosed);
    $stray = 'a<!-- /lbs:tag -->b';
    check('tags: 対応のない閉じは触らない', letsblog_expand_custom_tags($stray) === $stray);

    // 入れ子: 外が既知なら外を展開し直す。外が未知なら、中の既知のタグを展開し直す
    $inner = letsblog_test_marker('my-badge', [], 'IN', '<span class="b1">IN</span>');
    $outer = letsblog_test_marker('box', ['level' => 'o'], 'c', '<div class="box v1">' . $inner . '</div>');
    $out = letsblog_expand_custom_tags($outer);
    check('tags: 外が既知なら外のテンプレートで展開し直す', str_contains($out, 'box v2') && !str_contains($out, 'b1'));
    $outer2 = letsblog_test_marker('removed', [], 'c', '<div class="old">' . $inner . '</div>');
    $out = letsblog_expand_custom_tags($outer2);
    check('tags: 外が未知でも中の既知のタグは展開し直す', str_contains($out, '<div class="old"><span class="b2">IN</span></div>'));

    // 入れ子: 外側の目印の content に内側の目印が入っていても、展開し直した後に目印も生のタグも残らない(レビュー指摘)
    $innerInContent = letsblog_test_marker('my-badge', [], 'IN', '<span class="b1">IN</span>');
    $outerNested = letsblog_test_marker('box', ['level' => 'o'], '本文' . $innerInContent . 'です', '<div class="box v1">本文' . $innerInContent . 'です</div>');
    $out = letsblog_expand_custom_tags($outerNested);
    check('tags: 外側の content の中の目印も新しいテンプレートで展開し直す',
        $out === '<div class="box v2" data-lv="o">本文<span class="b2">IN</span>です</div>');
    $outerRawInner = letsblog_test_marker('box', [], '本文[my-badge]IN[/my-badge]', 'old');
    check('tags: 展開されなかった生のタグは触らない(Java 側が展開済みで渡す前提)', str_contains(letsblog_expand_custom_tags($outerRawInner), '[my-badge]IN[/my-badge]'));

    // AC5: 目印のない既存記事は変わらない
    $plain = "<p>既存の記事</p>\n<!-- wp:paragraph --><div class=\"box v1\">x</div>";
    check('tags: 目印のない本文は一切変えない', letsblog_expand_custom_tags($plain) === $plain);
    check('tags: 本文が文字列でなければそのまま返す', letsblog_expand_custom_tags(null) === null);

    // 同期前・壊れた同期内容・customTags が配列でない: 投稿時点の HTML のまま
    $m = letsblog_test_marker('box', [], 'x', '<div class="box v1">old</div>');
    unset($GLOBALS['letsblog_test_options']['letsblog_sync_payload']);
    check('tags: 同期前は投稿時点の HTML のまま', str_contains(letsblog_expand_custom_tags($m), 'box v1'));
    $setSync(['cssSelectorPrefix' => 'p', 'customTags' => 'x']);
    check('tags: customTags が配列でなければ投稿時点の HTML のまま', str_contains(letsblog_expand_custom_tags($m), 'box v1'));
    $setSync(['customTags' => ['x', ['tagName' => 5, 'htmlTemplate' => 't'], ['tagName' => 'box', 'htmlTemplate' => 7]]]);
    check('tags: 形式の壊れた定義は無視して投稿時点の HTML のまま', str_contains(letsblog_expand_custom_tags($m), 'box v1'));

    // 同名はプロジェクト固有を優先する
    $setSync(['customTags' => [
        ['scope' => 'GLOBAL', 'tagName' => 'box', 'tagFormat' => 'BLOCK', 'htmlTemplate' => '<g>{{content}}</g>'],
        ['scope' => 'PROJECT', 'tagName' => 'box', 'tagFormat' => 'BLOCK', 'htmlTemplate' => '<p>{{content}}</p>'],
    ]]);
    check('tags: 同名ならプロジェクト固有を優先する', letsblog_expand_custom_tags($m) === '<p>x</p>');
    $setSync(['customTags' => [
        ['scope' => 'PROJECT', 'tagName' => 'box', 'tagFormat' => 'BLOCK', 'htmlTemplate' => '<p>{{content}}</p>'],
        ['scope' => 'GLOBAL', 'tagName' => 'box', 'tagFormat' => 'BLOCK', 'htmlTemplate' => '<g>{{content}}</g>'],
    ]]);
    check('tags: 順序が逆でもプロジェクト固有を優先する', letsblog_expand_custom_tags($m) === '<p>x</p>');
}

// --- issue #1563: 組み込みタグ(ブログカード・Amazon・目次)の目印を、保存済みのデザインで表示時に展開し直す ---
function letsblog_test_embed(string $type, array $data, string $embedded): string
{
    $json = json_encode(['type' => $type, 'data' => $data], JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    // 文字列の中だけをエスケープする(配列の [ ] は JSON の構造なのでそのまま)。アプリ(EmbedMarker)と同じ。
    $json = preg_replace_callback('/"(?:[^"\\\\]|\\\\.)*"/s', function (array $m): string {
        return strtr($m[0], ['-' => '\\u002d', '<' => '\\u003c', '>' => '\\u003e', '[' => '\\u005b', ']' => '\\u005d']);
    }, $json);
    return '<!-- lbs:embed ' . $json . ' -->' . $embedded . '<!-- /lbs:embed -->';
}
function letsblog_test_strip(string $html): string
{
    return preg_replace('/<!-- \\/?lbs:(?:tag|embed)[^>]*? -->/', '', $html);
}
$design = function (string $type, ?string $template): array {
    return ['tagType' => $type, 'backgroundColor' => '#fff', 'textColor' => '#000', 'accentColor' => '#f00', 'customCss' => null, 'htmlTemplate' => $template];
};
$card = ['title' => 'タイトル', 'description' => '説明', 'siteName' => 'サイト', 'url' => 'https://example.com/a?x=1&y=2', 'imageUrl' => 'https://example.com/og.png'];

if (function_exists('letsblog_expand_custom_tags')) {
    // AC1: ブログカードのデザインテンプレートが変わると、再投稿なしで新しいテンプレートで表示される
    $setSync(['tagDesigns' => [$design('BLOGCARD', '<section class="bc2"><h3>{{title}}</h3><p>{{description}}</p><i>{{siteName}}</i><a href="{{url}}"><img src="{{imageUrl}}"></a></section>')]]);
    $old = letsblog_test_embed('BLOGCARD', $card, '<a class="lb-blogcard" href="https://example.com/a">OLD</a>');
    $out = letsblog_expand_custom_tags('前' . "\n\n" . $old . "\n\n" . '後');
    check('embed: ブログカードを同期済みのテンプレートで展開し直す', str_contains($out, '<section class="bc2"><h3>タイトル</h3><p>説明</p><i>サイト</i>') && !str_contains($out, 'OLD'));
    check('embed: URL の & は HTML エスケープして差し込む', str_contains($out, 'href="https://example.com/a?x=1&amp;y=2"') && str_contains($out, 'src="https://example.com/og.png"'));
    check('embed: 展開結果に目印コメントが残らず、目印の外の本文は変わらない', !str_contains($out, 'lbs:embed') && str_starts_with($out, "前\n\n") && str_ends_with($out, "\n\n後"));

    // XSS: 取得したデータの < > " ' はエスケープする。本文の中のプレースホルダーは再置換しない
    $xss = letsblog_test_embed('BLOGCARD', ['title' => '"><script>alert(1)</script>', 'description' => '{{title}}', 'siteName' => "'", 'url' => 'https://e.com/', 'imageUrl' => ''], 'OLD');
    $out = letsblog_expand_custom_tags($xss);
    check('embed: 取得したデータの < > " をエスケープする', !str_contains($out, '<script>') && str_contains($out, '&quot;&gt;&lt;script&gt;'));
    check('embed: データの中のプレースホルダーを再置換しない', str_contains($out, '<p>{{title}}</p>'));
    $jsUrl = letsblog_test_embed('BLOGCARD', ['title' => 't', 'description' => '', 'siteName' => '', 'url' => 'javascript:alert(1)', 'imageUrl' => ''], '<b class="posted">投稿時点</b>');
    check('embed: http(s) でない URL のデータは展開せず投稿時点の HTML のまま', letsblog_test_strip(letsblog_expand_custom_tags($jsUrl)) === '<b class="posted">投稿時点</b>');
    $jsImg = letsblog_test_embed('BLOGCARD', ['title' => 't', 'description' => '', 'siteName' => '', 'url' => 'https://e.com/', 'imageUrl' => 'javascript:alert(2)'], 'OLD');
    check('embed: http(s) でない画像 URL は空にして展開する', !str_contains(letsblog_expand_custom_tags($jsImg), 'javascript:'));

    // テンプレートなし(既定の見た目): 既定のブログカードを組み立てる。画像なしなら thumb を出さない
    $setSync(['tagDesigns' => [$design('BLOGCARD', null)]]);
    $out = letsblog_expand_custom_tags($old);
    check('embed: テンプレートが外れたら既定のブログカードで展開し直す',
        $out === '<a class="lb-blogcard" href="https://example.com/a?x=1&amp;y=2" target="_blank" rel="noopener noreferrer"><div class="lb-blogcard-thumb" style="background-image:url(\'https://example.com/og.png\')"></div><div class="lb-blogcard-body"><div class="lb-blogcard-title">タイトル</div><div class="lb-blogcard-description">説明</div><div class="lb-blogcard-site">サイト</div></div></a>');
    $noImg = letsblog_test_embed('BLOGCARD', ['title' => 't', 'description' => 'd', 'siteName' => 's', 'url' => 'https://e.com/', 'imageUrl' => ''], 'OLD');
    check('embed: 画像がなければ既定のカードに thumb を出さない', !str_contains(letsblog_expand_custom_tags($noImg), 'lb-blogcard-thumb'));

    // AC2: Amazon
    $amz = ['productName' => '商品 & 名', 'price' => '￥1,000', 'summary' => '概要', 'productUrl' => 'https://amazon.co.jp/dp/x', 'imageUrl' => 'https://m.media-amazon.com/i.jpg', 'priceTimestamp' => '2026/09/08 20:03時点の価格です'];
    $setSync(['tagDesigns' => [$design('AMAZON', '<div class="az2">{{productName}}|{{price}}|{{summary}}|{{priceTimestamp}}|<a href="{{productUrl}}"></a><img src="{{imageUrl}}"></div>')]]);
    $oldAmz = letsblog_test_embed('AMAZON', $amz, '<a class="lb-amazon-card">OLD</a>');
    $out = letsblog_expand_custom_tags($oldAmz);
    check('embed: Amazon を同期済みのテンプレートで展開し直す',
        str_contains($out, '<div class="az2">商品 &amp; 名|￥1,000|概要|2026/09/08 20:03時点の価格です|<a href="https://amazon.co.jp/dp/x"></a><img src="https://m.media-amazon.com/i.jpg"></div>') && !str_contains($out, 'OLD'));
    $nonProd = $amz;
    $nonProd['productUrl'] = '';
    check('embed: 非本番サイトで投稿した Amazon は商品 URL を空のまま展開する', str_contains(letsblog_expand_custom_tags(letsblog_test_embed('AMAZON', $nonProd, 'OLD')), '<a href=""></a>'));
    $setSync(['tagDesigns' => [$design('AMAZON', null)]]);
    $out = letsblog_expand_custom_tags($oldAmz);
    check('embed: Amazon の既定の見た目(本番サイト)', $out === '<a class="lb-amazon-card" href="https://amazon.co.jp/dp/x" target="_blank" rel="noopener noreferrer nofollow sponsored"><div class="lb-amazon-card-thumb" style="background-image:url(\'https://m.media-amazon.com/i.jpg\')"></div><div class="lb-amazon-card-body"><div class="lb-amazon-card-name">商品 &amp; 名</div><div class="lb-amazon-card-summary">概要</div><div class="lb-amazon-card-price">￥1,000</div><div class="lb-amazon-card-timestamp">2026/09/08 20:03時点の価格です</div><div class="lb-amazon-card-cta">Amazonで見る</div></div></a>');
    $bare = letsblog_test_embed('AMAZON', ['productName' => 'N', 'price' => '', 'summary' => '', 'productUrl' => '', 'imageUrl' => '', 'priceTimestamp' => ''], 'OLD');
    check('embed: Amazon の既定の見た目(非本番・項目なし)は div で、空の項目を出さない',
        letsblog_expand_custom_tags($bare) === '<div class="lb-amazon-card"><div class="lb-amazon-card-body"><div class="lb-amazon-card-name">N</div><div class="lb-amazon-card-cta">Amazonで見る</div></div></div>');
    $badAmzUrl = $amz;
    $badAmzUrl['productUrl'] = 'javascript:x';
    check('embed: Amazon の http(s) でない商品 URL は展開せず投稿時点の HTML のまま', letsblog_test_strip(letsblog_expand_custom_tags(letsblog_test_embed('AMAZON', $badAmzUrl, 'KEEP'))) === 'KEEP');

    // AC3: 目次
    $items = [['text' => '第一章 <1>', 'href' => '#a', 'children' => [['text' => '節', 'href' => '#b', 'children' => []]]], ['text' => '第二章', 'href' => '#c', 'children' => []]];
    $oldToc = letsblog_test_embed('TOC', ['items' => $items], '<ul class="lb-toc-list"><li>OLD</li></ul>');
    $setSync(['tagDesigns' => [$design('TOC', '<nav class="toc2">{{toc}}</nav>')]]);
    $out = letsblog_expand_custom_tags($oldToc);
    check('embed: 目次を同期済みのテンプレートで展開し直す',
        $out === '<nav class="toc2"><ul class="lb-toc-list"><li><a href="#a">第一章 &lt;1&gt;</a><ul><li><a href="#b">節</a></li></ul></li><li><a href="#c">第二章</a></li></ul></nav>');
    $setSync(['tagDesigns' => [$design('TOC', null)]]);
    check('embed: 目次のテンプレートが外れたら既定の目次で展開し直す',
        letsblog_expand_custom_tags($oldToc) === '<ul class="lb-toc-list"><li><a href="#a">第一章 &lt;1&gt;</a><ul><li><a href="#b">節</a></li></ul></li><li><a href="#c">第二章</a></li></ul>');
    $deep = function (int $n) use (&$deep): array {
        return [['text' => (string) $n, 'href' => '#' . $n, 'children' => $n > 1 ? $deep($n - 1) : []]];
    };
    $badToc = [
        'items 欠落' => ['x' => 1],
        '空の items' => ['items' => []],
        '項目が配列でない' => ['items' => ['x']],
        'text が文字列でない' => ['items' => [['text' => 5, 'href' => '#a', 'children' => []]]],
        'href が # で始まらない' => ['items' => [['text' => 't', 'href' => 'javascript:x', 'children' => []]]],
        'children が配列でない' => ['items' => [['text' => 't', 'href' => '#a', 'children' => 'x']]],
        '深すぎる入れ子' => ['items' => $deep(7)],
    ];
    foreach ($badToc as $label => $data) {
        check("embed: 壊れた目次データ($label)は投稿時点の HTML のまま", letsblog_test_strip(letsblog_expand_custom_tags(letsblog_test_embed('TOC', $data, 'KEEP'))) === 'KEEP');
    }

    // AC5/互換: 同期されていない種別、壊れた目印、閉じのない目印は投稿時点の HTML のまま
    $setSync(['tagDesigns' => [$design('TOC', 'x{{toc}}')]]);
    check('embed: 同期済みのデザインにない種別は投稿時点の HTML のまま', letsblog_test_strip(letsblog_expand_custom_tags(letsblog_test_embed('BLOGCARD', $card, 'KEEP'))) === 'KEEP');
    check('embed: 知らない種別は投稿時点の HTML のまま', letsblog_test_strip(letsblog_expand_custom_tags(letsblog_test_embed('UNKNOWN', [], 'KEEP'))) === 'KEEP');
    check('embed: 壊れた目印は投稿時点の HTML のまま', letsblog_expand_custom_tags('<!-- lbs:embed {not json} -->KEEP<!-- /lbs:embed -->') === '<!-- lbs:embed {not json} -->KEEP<!-- /lbs:embed -->');
    check('embed: 種別が文字列でない目印は投稿時点の HTML のまま', str_contains(letsblog_expand_custom_tags('<!-- lbs:embed {"type":5,"data":{}} -->KEEP<!-- /lbs:embed -->'), 'KEEP'));
    check('embed: data が配列でない目印は投稿時点の HTML のまま', str_contains(letsblog_expand_custom_tags('<!-- lbs:embed {"type":"TOC","data":"x"} -->KEEP<!-- /lbs:embed -->'), 'KEEP'));
    $unclosed = letsblog_test_embed('TOC', ['items' => $items], 'KEEP');
    $unclosed = str_replace('<!-- /lbs:embed -->', '', $unclosed);
    check('embed: 閉じのない目印は触らない', letsblog_expand_custom_tags($unclosed) === $unclosed);
    unset($GLOBALS['letsblog_test_options']['letsblog_sync_payload']);
    check('embed: 同期前は投稿時点の HTML のまま', letsblog_expand_custom_tags($oldToc) === $oldToc);
    $setSync(['tagDesigns' => 'x']);
    check('embed: tagDesigns が配列でなければ投稿時点の HTML のまま', letsblog_expand_custom_tags($oldToc) === $oldToc);
    $setSync(['tagDesigns' => ['x', ['tagType' => 5], ['tagType' => 'TOC', 'htmlTemplate' => 7]]]);
    check('embed: 形式の壊れたデザインは無視する(テンプレートが文字列でなければ既定の見た目)', str_contains(letsblog_expand_custom_tags($oldToc), '<ul class="lb-toc-list"><li><a href="#a">'));

    // 目印のない既存記事は変わらない
    $plain = '<p>既存</p><!-- lbs:embedX --><a class="lb-blogcard">x</a>';
    $setSync(['tagDesigns' => [$design('BLOGCARD', '<i>{{title}}</i>')]]);
    check('embed: 目印のない本文は一切変えない', letsblog_expand_custom_tags($plain) === $plain);

    // 入れ子: 未知のカスタムタグの投稿時点の HTML の中の組み込みタグも展開し直す。
    // 既知のカスタムタグの content に組み込みタグの生の記法が残っていれば、生のまま画面に出さず投稿時点の HTML を使う(#1560 のレビュー指摘)。
    $setSync([
        'customTags' => [['scope' => 'GLOBAL', 'tagName' => 'box', 'tagFormat' => 'BLOCK', 'htmlTemplate' => '<div class="box v2">{{content}}</div>']],
        'tagDesigns' => [$design('BLOGCARD', '<section class="bc2">{{title}}</section>')],
    ]);
    $inEmbed = letsblog_test_embed('BLOGCARD', $card, 'OLDCARD');
    $unknownOuter = letsblog_test_marker('removed', [], 'c', '<div class="old">' . $inEmbed . '</div>');
    check('embed: 未知のカスタムタグの中の組み込みタグも展開し直す', letsblog_test_strip(letsblog_expand_custom_tags($unknownOuter)) === '<div class="old"><section class="bc2">タイトル</section></div>');
    $outerRaw = letsblog_test_marker('box', [], '前[blogcard https://example.com/a]後', '<div class="box v1">前' . $inEmbed . '後</div>');
    $out = letsblog_expand_custom_tags($outerRaw);
    check('embed: content に組み込みタグの生の記法が残るカスタムタグは投稿時点の HTML を使い、生の記法を出さない',
        letsblog_test_strip($out) === '<div class="box v1">前<section class="bc2">タイトル</section>後</div>' && !str_contains($out, '[blogcard'));
    $outerOk = letsblog_test_marker('box', [], '本文', '<div class="box v1">本文</div>');
    check('embed: 生の記法がなければ従来どおりカスタムタグを展開し直す', letsblog_expand_custom_tags($outerOk) === '<div class="box v2">本文</div>');
    $outerToc = letsblog_test_marker('box', [], 'a [TOC] b', 'KEEP');
    check('embed: [toc] の生の記法も同様に扱う', letsblog_test_strip(letsblog_expand_custom_tags($outerToc)) === 'KEEP');
}
check('embed: 目印は HTML コメントだけで、プラグインがなくても画面に出ない',
    function_exists('letsblog_test_embed') && preg_match('/^<!-- lbs:embed [^>]*? -->.*<!-- \/lbs:embed -->$/s', letsblog_test_embed('TOC', ['k' => '-->x'], '<ul>[x]</ul>')) === 1);

// AC3: プラグインを停止すると、目印はコメントなので画面に出ず、投稿時点の HTML が表示される(フックごと消える)。
check('tags: 目印は HTML コメントだけで、プラグインがなくても画面に出ない',
    function_exists('letsblog_test_marker') && preg_match('/^<!-- lbs:tag [^>]*? -->.*<!-- \/lbs:tag -->$/s', letsblog_test_marker('a', ['k' => '-->x'], '<p>[x]</p>', 'E')) === 1);

// AC4: 停止すると CSS は読み込まれない。読み込みは全てこのプラグインが登録するフック経由(上の hooks 検査)で、
// 停止すればフックごと消える。プラグイン外に CSS ファイルを置かない。

if ($failures) {
    echo count($failures) . ' 件失敗:' . "\n";
    foreach ($failures as $f) {
        echo "  - $f\n";
    }
    exit(1);
}
echo 'すべて成功' . "\n";
exit(0);
