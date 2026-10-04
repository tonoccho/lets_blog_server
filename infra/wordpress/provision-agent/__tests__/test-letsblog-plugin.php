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

if ($failures) {
    echo count($failures) . ' 件失敗:' . "\n";
    foreach ($failures as $f) {
        echo "  - $f\n";
    }
    exit(1);
}
echo 'すべて成功' . "\n";
exit(0);
