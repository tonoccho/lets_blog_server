<?php
/**
 * letsblog プラグインのサイトへの導入(issue #1556)。
 *
 * イメージに焼き込んだプラグイン(`/var/www/letsblog-plugin`)をサイトの wp-content/plugins へ
 * 配置し、`wp plugin activate letsblog` で有効化する。配置済みで内容が同じなら何もしない(冪等)。
 * 有効化に失敗したら配置を取り消す(導入済みの更新なら元の内容へ戻す)。配置だけが残ると、次回以降「導入済み」と見なされて
 * 有効化されないままになるため。
 */

const LETSBLOG_PLUGIN_SOURCE_DIR = '/var/www/letsblog-plugin';

/**
 * @param callable(array):array|null $run wp-cliの引数を受け取り[終了コード, stdout, stderr]を返す(既定はrunWp)
 * @param bool $force trueなら配置済みで内容が同じでも有効化し直す(サイト画面からの再導入。プラグインを停止した
 *                    サイトを戻すため。issue #1557)。有効化に失敗しても導入済みの配置は消さない
 * @return array{0:int,1:string,2:string}
 */
function ensureLetsblogPlugin(string $sitePath, ?callable $run = null, string $sourceDir = LETSBLOG_PLUGIN_SOURCE_DIR, bool $force = false): array
{
    $run ??= 'runWp';
    $source = "$sourceDir/letsblog.php";
    if (!is_file($source)) {
        return [1, '', "プラグインのソースが見つかりません: $source"];
    }
    $contents = (string) file_get_contents($source);

    $pluginDir = "$sitePath/wp-content/plugins/letsblog";
    $dest = "$pluginDir/letsblog.php";
    $previous = is_file($dest) ? file_get_contents($dest) : null;
    if ($previous === $contents && !$force) {
        return [0, '', ''];
    }

    if (!is_dir($pluginDir) && !@mkdir($pluginDir, 0755, true) && !is_dir($pluginDir)) {
        return [1, '', "プラグインのディレクトリを作成できません: $pluginDir"];
    }
    if (file_put_contents($dest, $contents) === false) {
        return [1, '', "プラグインを配置できません: $dest"];
    }

    [$code, $out, $err] = $run(['plugin', 'activate', 'letsblog', "--path=$sitePath", '--allow-root']);
    if ($code !== 0) {
        if ($previous === null) {
            @unlink($dest);
        } else {
            file_put_contents($dest, $previous);
        }
        return [$code, $out, $err];
    }
    return [0, $out, $err];
}
