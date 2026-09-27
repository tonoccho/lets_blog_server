<?php
/**
 * WordPressコアのダウンロード(issue #1419)。
 *
 * wp-cliの`core download`はキャッシュ済みファイルがあると md5 検証をせずにそれを使う
 * (「Using cached file ...」)。壊れたキャッシュを掴んで展開に失敗すると構築が詰まるため、
 * 失敗したらコアのキャッシュと途中まで展開されたファイルを捨てて1回だけ再試行する。
 * 再試行しても失敗した場合は握り潰さず、両方の出力を残して失敗を返す。
 */

const WP_CORE_CACHE_DIR = '/root/.wp-cli/cache/core';

/**
 * $dir配下のファイルを(隠しファイル含め)すべて削除する。$dir自体は残す。存在しなければ何もしない。
 */
function emptyDirectory(string $dir): void
{
    if (!is_dir($dir)) {
        return;
    }
    foreach (array_diff(scandir($dir) ?: [], ['.', '..']) as $name) {
        $entry = "$dir/$name";
        if (is_dir($entry) && !is_link($entry)) {
            emptyDirectory($entry);
            @rmdir($entry);
        } else {
            @unlink($entry);
        }
    }
}

/**
 * @param callable(array):array $run wp-cliの引数を受け取り[終了コード, stdout, stderr]を返す(既定はrunWp)
 * @return array{0:int,1:string,2:string}
 */
function downloadWordPressCore(string $sitePath, ?callable $run = null, string $cacheDir = WP_CORE_CACHE_DIR): array
{
    $run ??= 'runWp';
    $args = ['core', 'download', "--path=$sitePath", '--allow-root'];

    [$code, $out, $err] = $run($args);
    if ($code === 0) {
        return [$code, $out, $err];
    }

    emptyDirectory($cacheDir);
    emptyDirectory($sitePath);

    [$retryCode, $retryOut, $retryErr] = $run($args);
    if ($retryCode === 0) {
        return [$retryCode, $retryOut, $retryErr];
    }

    $first = trim($out . ($err !== '' ? "\n$err" : ''));
    return [$retryCode, "[1回目の失敗]\n$first\n[再試行の失敗]\n$retryOut", $retryErr];
}
