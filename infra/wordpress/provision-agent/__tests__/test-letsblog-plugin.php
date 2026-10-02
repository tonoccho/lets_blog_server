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

if ($failures) {
    echo count($failures) . ' 件失敗:' . "\n";
    foreach ($failures as $f) {
        echo "  - $f\n";
    }
    exit(1);
}
echo 'すべて成功' . "\n";
exit(0);
