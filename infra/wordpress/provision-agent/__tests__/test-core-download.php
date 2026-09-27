<?php
/**
 * core-download.php のキャッシュ回復ロジックに対するCLIテスト(issue #1419)。
 * wp-cliが「Using cached file」と言った直後に失敗した場合に、コアのキャッシュと
 * 途中まで展開されたファイルを捨てて1回だけ再試行することを検証する。
 * 実行: timeout 30 php __tests__/test-core-download.php
 */

declare(strict_types=1);

require __DIR__ . '/../core-download.php';

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
    $dir = sys_get_temp_dir() . '/core-download-test-' . bin2hex(random_bytes(4));
    mkdir($dir, 0777, true);
    return $dir;
}

/** 呼び出しを記録し、$results を順に返す偽のwp実行関数を作る。 */
function fakeRunner(array $results, array &$calls): callable
{
    return function (array $args) use (&$results, &$calls): array {
        $calls[] = $args;
        $next = array_shift($results);
        if ($next instanceof Closure) {
            return $next();
        }
        return $next ?? [1, '', 'no more results'];
    };
}

// --- AC2: 1回目が失敗しても、キャッシュと中途半端な展開物を捨てて再試行し、回復する ---
$root = makeTmpDir();
$cache = "$root/cache";
$site = "$root/site";
mkdir($cache);
mkdir($site);
file_put_contents("$cache/wordpress-7.1.2-en_US.tar.gz", 'broken');
file_put_contents("$site/index.php", 'partial');
mkdir("$site/wp-admin");
file_put_contents("$site/.htaccess", 'hidden');
$calls = [];
$state = [];
$run = fakeRunner([
    [1, "Using cached file '$cache/x.tar.gz'...", 'Error: extract failed'],
    function () use ($cache, $site, &$state) {
        $state['cacheGone'] = glob("$cache/*") === [];
        $state['siteEmpty'] = array_diff(scandir($site), ['.', '..']) === [];
        return [0, 'Success: WordPress downloaded.', ''];
    },
], $calls);
[$code, $out, $err] = downloadWordPressCore($site, $run, $cache);
check('AC2: 再試行で成功すれば終了コード0を返す', $code === 0, "code=$code");
check('AC2: core downloadを2回呼ぶ', count($calls) === 2, 'calls=' . count($calls));
check('AC2: 再試行の前にコアのキャッシュが捨てられている', ($state['cacheGone'] ?? false) === true);
check('AC2: 再試行の前に途中まで展開されたファイルが捨てられている', ($state['siteEmpty'] ?? false) === true);
check('AC2: サイトディレクトリ自体は残る', is_dir($site));
check('AC2: キャッシュディレクトリ自体は残る', is_dir($cache));
check('AC2: 再試行のコマンドにも--pathが渡る', in_array("--path=$site", $calls[1] ?? [], true));

// --- AC1: 1回目が成功すれば再試行もキャッシュ削除もしない ---
$root = makeTmpDir();
mkdir("$root/cache");
file_put_contents("$root/cache/keep.tar.gz", 'good');
mkdir("$root/site");
$calls = [];
[$code] = downloadWordPressCore("$root/site", fakeRunner([[0, 'ok', '']], $calls), "$root/cache");
check('AC1: 初回成功なら1回しか呼ばない', $code === 0 && count($calls) === 1);
check('AC1: 初回成功ならキャッシュを触らない', file_exists("$root/cache/keep.tar.gz"));

// --- AC3: 再試行しても失敗したら明確に失敗させ、両方の出力を残す ---
$root = makeTmpDir();
mkdir("$root/cache");
mkdir("$root/site");
$calls = [];
[$code, $out, $err] = downloadWordPressCore("$root/site", fakeRunner([
    [1, '', 'first: network down'],
    [1, '', 'second: network down'],
], $calls), "$root/cache");
check('AC3: 再試行も失敗したら非0を返す', $code !== 0, "code=$code");
check('AC3: 再試行は1回だけ(無限に繰り返さない)', count($calls) === 2, 'calls=' . count($calls));
check('AC3: 1回目の失敗内容が出力に残る', str_contains($out . $err, 'first: network down'));
check('AC3: 2回目の失敗内容が出力に残る', str_contains($out . $err, 'second: network down'));

// --- キャッシュディレクトリが存在しない場合(初回構築)でも落ちない ---
$root = makeTmpDir();
mkdir("$root/site");
$calls = [];
[$code] = downloadWordPressCore("$root/site", fakeRunner([[1, '', 'e'], [0, 'ok', '']], $calls), "$root/no-such-cache");
check('キャッシュディレクトリが無くても再試行して回復できる', $code === 0 && count($calls) === 2);

// --- サイトディレクトリが消えていても落ちない ---
$root = makeTmpDir();
mkdir("$root/cache");
$calls = [];
[$code] = downloadWordPressCore("$root/gone", fakeRunner([[1, '', 'e'], [0, 'ok', '']], $calls), "$root/cache");
check('サイトディレクトリが無くても再試行して回復できる', $code === 0 && count($calls) === 2);

if ($failures) {
    echo count($failures) . ' 件失敗:' . "\n";
    foreach ($failures as $f) {
        echo "  - $f\n";
    }
    exit(1);
}
echo 'すべて成功' . "\n";
exit(0);
