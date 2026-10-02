<?php
/**
 * media-hash.php のテスト(issue #1436)。media-upload が _letsblog_sha256 に記録する値は、
 * client送信値ではなくアップロードされたファイルの内容から agent が計算した値でなければならない。
 * PHPUnit未導入のため手動if判定の簡易ハーネス(test-process-runner.php と同形式)。
 *   docker run --rm --entrypoint php -v $(pwd)/infra/wordpress:/tmp/wp:ro lets_blog_server-wordpress:latest \
 *       /tmp/wp/provision-agent/__tests__/test-media-hash.php
 */

declare(strict_types=1);

require __DIR__ . '/../media-hash.php';

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

$file = tempnam(sys_get_temp_dir(), 'media-hash-');
file_put_contents($file, 'abc');
$abc = 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad';

// --- AC1: 内容と異なる(形式は正しい)sha256を送っても、記録されるのはファイル内容から計算した値 ---
$forged = str_repeat('0', 64);
check('AC1: 偽のsha256を送っても内容から計算した値になる', computeMediaSha256($file, $forged) === $abc,
    'got=' . var_export(computeMediaSha256($file, $forged), true));
check('AC1: 大文字や不正形式の送信値でも内容から計算した値になる',
    computeMediaSha256($file, 'NOT-A-HASH') === $abc);
check('AC1: 送信値が内容と一致していても同じ値', computeMediaSha256($file, $abc) === $abc);
check('AC1: 送信値なしでも内容から計算した値になる', computeMediaSha256($file) === $abc);

// --- 読めないファイルは null(記録をスキップする) ---
check('読めないファイルはnullを返す', computeMediaSha256($file . '.missing', $forged) === null);

unlink($file);

if ($failures !== []) {
    echo "\n" . count($failures) . " 件失敗\n";
    exit(1);
}
echo "\nすべて成功\n";
