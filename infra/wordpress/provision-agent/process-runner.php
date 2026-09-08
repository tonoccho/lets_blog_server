<?php
/**
 * proc_open経由の外部コマンド実行を担うヘルパー群(issue #1122)。
 * runCommand/runWp/runWpWithStdinはindex.phpから利用される(互換シグネチャを維持)。
 *
 * stdout/stderrを順番に(例: stdout→stderrの順で)先読みすると、子プロセスが一方の
 * パイプ(OSのパイプバッファ、Linuxでは既定64KB)を溢れさせた場合に、親がまだ読んでいない
 * 側への書き込みで子がブロックし、親はまだ読み終わっていない側の読み取りでブロックしたまま
 * 双方が進めなくなる(proc_openデッドロック。issue #1122で本番発生、12時間agentが無応答に
 * なった)。stream_selectで3本のパイプ(stdin書き込み・stdout/stderr読み取り)をノンブロッキング
 * に多重化することで回避する。あわせて実行時間の上限を設け、超過時はproc_terminate()で
 * 子プロセスを終了させる(publishing-serviceが誤ってハングしたコマンドを永遠に待たないため)。
 */

// タイムアウトによる強制終了であることを示す識別可能な文字列。detail/errorメッセージに
// 含めることで、呼び出し元(publishing-service)が通常のコマンド失敗と区別できるようにする。
const PROVISION_AGENT_TIMEOUT_MARKER = '[TIMEOUT]';

// 1コマンドあたりの実行時間上限(秒)。PROVISION_AGENT_COMMAND_TIMEOUT_SECONDS環境変数で
// 上書きできる(未設定・不正値の場合は既定値を使う)。
function defaultCommandTimeoutSeconds(): int
{
    $configured = getenv('PROVISION_AGENT_COMMAND_TIMEOUT_SECONDS');
    if ($configured !== false && ctype_digit((string) $configured) && (int) $configured > 0) {
        return (int) $configured;
    }
    return 300;
}

/**
 * $pidを起点に、/proc/<pid>/task/<pid>/children を再帰的に辿って子孫プロセス全体の
 * PIDを収集する(pcntl拡張が未導入の環境のため、PHPからは自プロセスの直接の子しか
 * proc_close()等でwaitできない。孫プロセス以降を捕捉するにはこの方法によるしかない)。
 * 戻り値は$pid自身を含み、深い子孫が先、$pidが最後になる順序(親を先に辿れなくなる
 * 前に子を発見しておくため)。
 * @return int[]
 */
function collectProcessTree(int $pid): array
{
    $pids = [];
    $childrenFile = "/proc/{$pid}/task/{$pid}/children";
    if (is_readable($childrenFile)) {
        $contents = trim((string) @file_get_contents($childrenFile));
        if ($contents !== '') {
            foreach (preg_split('/\s+/', $contents) as $childPid) {
                $pids = array_merge($pids, collectProcessTree((int) $childPid));
            }
        }
    }
    $pids[] = $pid;
    return $pids;
}

/**
 * $pidの子孫プロセス全体(自身を含む)へシグナルを送る。
 * `sh -c 'mysqldump ... > file'` のようにproc_openの直接の子がさらに内部で
 * リダイレクト/パイプ処理のため子プロセスをforkするケースでは、proc_terminate()が
 * 直接の子(sh自身)にしか届かず、実際に重い処理をしている孫プロセス(mysqldump等)が
 * 終了後も生き残ってしまう(issue #1122 レビュー指摘)。posix_kill未対応環境では
 * 何もしない(直接の子に対するproc_terminate()側のフォールバックに任せる)。
 */
function killProcessTree(int $pid, int $signal): void
{
    if (!function_exists('posix_kill')) {
        return;
    }
    foreach (collectProcessTree($pid) as $descendantPid) {
        @posix_kill($descendantPid, $signal);
    }
}

/**
 * proc_openで起動した子プロセスのstdin/stdout/stderrをstream_selectでノンブロッキングに
 * 多重化しながらやり取りする下位関数。runCommand/runWpWithStdinはこれの薄いラッパー。
 *
 * @return array{0:int,1:string,2:string,3:bool} [終了コード, stdout, stderr, タイムアウトしたか]
 *   タイムアウト時の終了コードは124(timeout(1)コマンドの慣例に合わせる)。
 */
function runProcessWithIO(string $command, ?string $stdin, ?int $timeoutSeconds = null): array
{
    $timeoutSeconds = $timeoutSeconds ?? defaultCommandTimeoutSeconds();

    $descriptors = [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['pipe', 'w']];
    // execを前置し、proc_openが内部で生成する/bin/sh -cのプロセスを対象コマンドへ確実に
    // 置き換える(execシステムコールでpidを引き継ぐ)。単純なコマンド(パイプ・リダイレクト
    // なし)ではシェル側の最適化により既にこうなっていることが多いが、明示することで
    // タイムアウト時のproc_terminate()が対象プロセス自身に届くことを保証する。
    $process = proc_open('exec ' . $command, $descriptors, $pipes);
    if (!is_resource($process)) {
        return [1, '', 'proc_openに失敗しました', false];
    }

    foreach ($pipes as $pipe) {
        stream_set_blocking($pipe, false);
    }

    $stdout = '';
    $stderr = '';
    $stdinRemaining = $stdin ?? '';
    $stdinOpen = $stdinRemaining !== '';
    if (!$stdinOpen) {
        fclose($pipes[0]);
    }

    $deadline = microtime(true) + $timeoutSeconds;
    $timedOut = false;

    while (true) {
        $stdoutOpen = is_resource($pipes[1]);
        $stderrOpen = is_resource($pipes[2]);
        if (!$stdinOpen && !$stdoutOpen && !$stderrOpen) {
            break;
        }

        $remaining = $deadline - microtime(true);
        if ($remaining <= 0) {
            $timedOut = true;
            break;
        }

        $read = [];
        if ($stdoutOpen) {
            $read[] = $pipes[1];
        }
        if ($stderrOpen) {
            $read[] = $pipes[2];
        }
        $write = $stdinOpen ? [$pipes[0]] : [];
        $except = null;

        // 残り時間全てを一度のselectで待つと、タイムアウト検知が最大でその分遅れるため、
        // 応答性を保つよう最大0.2秒刻みでポーリングする。
        $waitSeconds = (int) min($remaining, 0.2);
        $waitMicroseconds = (int) (min($remaining, 0.2) * 1_000_000) % 1_000_000;

        $changed = @stream_select($read, $write, $except, $waitSeconds, $waitMicroseconds);
        if ($changed === false) {
            break;
        }

        if ($stdinOpen && in_array($pipes[0], $write, true)) {
            $chunk = substr($stdinRemaining, 0, 65536);
            $written = @fwrite($pipes[0], $chunk);
            if ($written === false || $written === 0) {
                // これ以上書き込めない(子がSTDINを読まずに終了した等) → 諦めて閉じる
                fclose($pipes[0]);
                $stdinOpen = false;
            } else {
                $stdinRemaining = substr($stdinRemaining, $written);
                if ($stdinRemaining === '') {
                    fclose($pipes[0]);
                    $stdinOpen = false;
                }
            }
        }

        if ($stdoutOpen && in_array($pipes[1], $read, true)) {
            $chunk = fread($pipes[1], 65536);
            if ($chunk === false || $chunk === '') {
                if (feof($pipes[1])) {
                    fclose($pipes[1]);
                }
            } else {
                $stdout .= $chunk;
            }
        }

        if ($stderrOpen && in_array($pipes[2], $read, true)) {
            $chunk = fread($pipes[2], 65536);
            if ($chunk === false || $chunk === '') {
                if (feof($pipes[2])) {
                    fclose($pipes[2]);
                }
            } else {
                $stderr .= $chunk;
            }
        }
    }

    if ($timedOut) {
        $directChildStatus = @proc_get_status($process);
        $directChildPid = (is_array($directChildStatus) && isset($directChildStatus['pid']))
            ? (int) $directChildStatus['pid']
            : null;

        // `sh -c '... > file'` のようにproc_openの直接の子がさらに孫プロセスをforkする
        // 場合、proc_terminate()は直接の子にしか届かない。孫プロセスも含めた全体へ
        // SIGTERMを送ってから、直接の子にはproc_terminate()(SIGKILL)を重ねて送る。
        if ($directChildPid !== null) {
            killProcessTree($directChildPid, 15);
        }
        proc_terminate($process, 9);
        // 子の終了をproc_close前に少し待つ(SIGKILL配送の反映猶予。ゾンビ化防止)。
        for ($i = 0; $i < 30; $i++) {
            $status = proc_get_status($process);
            if (!$status['running']) {
                break;
            }
            if ($i === 5 && $directChildPid !== null) {
                // SIGTERMで終了しなかった孫プロセスにSIGKILLを重ねて送る。
                killProcessTree($directChildPid, 9);
            }
            usleep(100000);
        }
        foreach ($pipes as $pipe) {
            if (is_resource($pipe)) {
                fclose($pipe);
            }
        }
        proc_close($process);
        $stderr = trim($stderr . "\n" . PROVISION_AGENT_TIMEOUT_MARKER
            . " 実行時間が上限({$timeoutSeconds}秒)を超過したため強制終了しました");
        return [124, trim($stdout), $stderr, true];
    }

    foreach ($pipes as $pipe) {
        if (is_resource($pipe)) {
            fclose($pipe);
        }
    }
    $exitCode = proc_close($process);
    return [$exitCode, trim($stdout), trim($stderr), false];
}

/**
 * escapeshellargで各トークンを個別にエスケープしてからシェルへ渡す(コマンドインジェクション対策)。
 * stdout/stderrは分離して返す。porcelain出力(投稿ID等)はstdoutのみから取り出すこと
 * (stderrにPHP Warning等が出た場合に、stdoutの値と混ざって壊れるのを防ぐため)。
 * @param string[] $args
 * @return array{0:int,1:string,2:string,3:bool}
 */
function runCommand(array $args, ?int $timeoutSeconds = null): array
{
    $command = implode(' ', array_map('escapeshellarg', $args));
    return runProcessWithIO($command, null, $timeoutSeconds);
}

/**
 * wp-cli(phar)実行時、PHP CLIのデフォルトmemory_limitではWordPressコア展開時に
 * メモリ不足になることがあるため、明示的に緩和した上で実行する。
 * @param string[] $args wp-cliへのサブコマンド以降の引数
 * @return array{0:int,1:string,2:string,3:bool}
 */
function runWp(array $args, ?int $timeoutSeconds = null): array
{
    return runCommand(array_merge(['php', '-d', 'memory_limit=512M', '/usr/local/bin/wp'], $args), $timeoutSeconds);
}

/**
 * 投稿本文の送信等、STDIN経由の入力が必要なwp-cli呼び出し用。
 * runWp/runCommandと同様に各トークンをescapeshellargで個別にエスケープしたコマンド文字列を
 * proc_openでSTDINパイプ付き実行する(exec()はSTDINを渡せないため)。
 * stdout/stderrは分離して返す(runCommandと同じ理由)。大きなSTDIN書き込みも
 * runProcessWithIOのノンブロッキングループで処理するため、子がすぐに読み始めない場合の
 * パイプ詰まりでもデッドロックしない。
 * @param string[] $args
 * @return array{0:int,1:string,2:string,3:bool}
 */
function runWpWithStdin(array $args, string $stdin, ?int $timeoutSeconds = null): array
{
    $command = implode(' ', array_map('escapeshellarg',
        array_merge(['php', '-d', 'memory_limit=512M', '/usr/local/bin/wp'], $args)));
    return runProcessWithIO($command, $stdin, $timeoutSeconds);
}
