<?php
/**
 * process-runner.php のプロセス実行ロジックに対するCLIテスト(issue #1122)。
 * PHPUnit/composerが未導入のため、assert()の代わりに手動if判定でPASS/FAILを出力し、
 * 失敗があれば非0で終了する簡易ハーネス。
 *
 * デッドロック回帰時にこのテスト自身が無限に固まらないよう、必ずシェルのtimeoutで
 * 包んで実行すること(例: `timeout 30 php __tests__/test-process-runner.php`)。
 * PHP CLI・ps・/usr/local/bin/wpが揃った環境(例: lets_blog_server-wordpress イメージ)で実行する:
 *   docker run --rm --entrypoint php -v $(pwd):/tmp/pa:ro lets_blog_server-wordpress:latest \
 *       /tmp/pa/__tests__/test-process-runner.php
 */

declare(strict_types=1);

require __DIR__ . '/../process-runner.php';

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

$oneMebibyte = 1024 * 1024;

// --- AC1: 子プロセスがstderrへ1MB書き込んでもデッドロックせず、全量取得できる ---
[$code, $out, $err] = runCommand(['php', '-r', 'fwrite(STDERR, str_repeat("e", ' . $oneMebibyte . '));'], 30);
check('AC1: 1MBのstderr出力でも正常終了する(exit=0)', $code === 0, "code=$code");
check('AC1: 1MBのstderrが全量取得できる', strlen($err) === $oneMebibyte, 'got=' . strlen($err));

// --- AC2: 子プロセスがstdoutへ1MB書き込んでもデッドロックせず、全量取得できる ---
[$code, $out, $err] = runCommand(['php', '-r', 'fwrite(STDOUT, str_repeat("o", ' . $oneMebibyte . '));'], 30);
check('AC2: 1MBのstdout出力でも正常終了する(exit=0)', $code === 0, "code=$code");
check('AC2: 1MBのstdoutが全量取得できる', strlen($out) === $oneMebibyte, 'got=' . strlen($out));

// stdout/stderr両方に大量出力があっても、互いのブロックで壊れないことを確認する
[$code, $out, $err] = runCommand([
    'php', '-r',
    'fwrite(STDOUT, str_repeat("o", ' . $oneMebibyte . ')); fwrite(STDERR, str_repeat("e", ' . $oneMebibyte . '));',
], 30);
check('AC1+2: stdout/stderr同時大量出力でもstdoutが全量取得できる', strlen($out) === $oneMebibyte, 'got=' . strlen($out));
check('AC1+2: stdout/stderr同時大量出力でもstderrが全量取得できる', strlen($err) === $oneMebibyte, 'got=' . strlen($err));

// --- runWpWithStdin: 大きめのSTDIN書き込みでもデッドロックしない(パイプ埋まり対策) ---
// wp-cli自体を使わず、STDIN書き込みの多重化を検証するため共通の下位関数を直接使う。
$largeStdin = str_repeat('A', 3 * 1024 * 1024);
$slowReaderCmd = implode(' ', array_map('escapeshellarg', [
    'php', '-r', 'usleep(300000); echo strlen(stream_get_contents(STDIN));',
]));
[$code, $out, $err] = runProcessWithIO($slowReaderCmd, $largeStdin, 30);
check('STDIN: 子がすぐ読み始めなくても3MBのSTDIN書き込みでデッドロックしない', $code === 0, "code=$code, err=$err");
check('STDIN: 子が3MB全量を受け取れる', trim($out) === (string) strlen($largeStdin), 'got=' . $out);

// runWpWithStdin自体の配線確認(wp-cliが動作すること自体の確認。WordPressインストール不要な`cli info`を使う)
[$code, $out, $err] = runWpWithStdin(['cli', 'info', '--allow-root'], '');
check('runWpWithStdin: wp-cliが起動しコマンドを実行できる', $code === 0, "code=$code, err=$err");

// --- AC3: 実行時間制限を超えたコマンドはkillされ、子プロセスが残らない ---
$start = microtime(true);
[$code, $out, $err, $timedOut] = runCommand(['sleep', '5'], 1);
$elapsed = microtime(true) - $start;
check('AC3: 制限(1秒)超過時、5秒待たずに戻る', $elapsed < 3.0, "elapsed={$elapsed}s");
check('AC3: タイムアウトを示すフラグが返る', $timedOut === true, 'timedOut=' . var_export($timedOut, true));
usleep(300000); // OSがプロセステーブルを片付ける猶予
$psOutput = shell_exec('ps -eo args 2>/dev/null') ?: '';
$leftoverFound = (bool) preg_match('/(^|\s)sleep\s+5(\s|$)/m', $psOutput);
check('AC3: killされた子(sleep 5)がps上に残っていない', !$leftoverFound, $leftoverFound ? "ps出力に残存:\n$psOutput" : '');

// --- AC3(孫プロセス): `sh -c '... > file'` のように孫プロセスをforkするコマンドの場合、
// proc_terminate()が直接の子(sh自身)にしか届かず、実際に重い処理をする孫プロセス
// (この例ではsleep 20)がタイムアウト後も生き残ってしまう回帰を検出する(issue #1122レビュー指摘)。
function findRunningProcessArgs(string $pattern): string
{
    $psOutput = shell_exec('ps -eo state,args 2>/dev/null') ?: '';
    foreach (explode("\n", $psOutput) as $line) {
        $line = trim($line);
        if ($line === '') {
            continue;
        }
        // 先頭1文字がプロセス状態(STAT)。'Z'(ゾンビ)は既に終了済みなので除外し、
        // 実際に走り続けている(=リソースを消費し続けている)プロセスのみを対象にする。
        $state = $line[0];
        $args = trim(substr($line, 1));
        if ($state === 'Z') {
            continue;
        }
        if (preg_match($pattern, $args)) {
            return $args;
        }
    }
    return '';
}

[$grandchildCode1, $grandchildOut1, $grandchildErr1, $grandchildTimedOut1] =
    runCommand(['sh', '-c', 'sleep 20 > /dev/null'], 1);
check('AC3(孫プロセス/リダイレクト): タイムアウトを示すフラグが返る', $grandchildTimedOut1 === true, 'timedOut=' . var_export($grandchildTimedOut1, true));
usleep(500000); // OSがプロセステーブルを片付ける猶予
$leftover = findRunningProcessArgs('/(^|\s)sleep\s+20(\s|$)/');
check(
    'AC3(孫プロセス/リダイレクト): sh -c経由の孫プロセス(sleep 20)が生存し続けていない',
    $leftover === '',
    $leftover !== '' ? "生存中のプロセスを検出:\n$leftover" : ''
);

[$grandchildCode2, $grandchildOut2, $grandchildErr2, $grandchildTimedOut2] =
    runCommand(['sh', '-c', 'sleep 20 | cat'], 1);
check('AC3(孫プロセス/パイプ): タイムアウトを示すフラグが返る', $grandchildTimedOut2 === true, 'timedOut=' . var_export($grandchildTimedOut2, true));
usleep(500000);
$leftoverSleep = findRunningProcessArgs('/(^|\s)sleep\s+20(\s|$)/');
$leftoverCat = findRunningProcessArgs('/(^|\s)cat(\s|$)/');
check(
    'AC3(孫プロセス/パイプ): sh -c経由の孫プロセス(sleep 20)が生存し続けていない',
    $leftoverSleep === '',
    $leftoverSleep !== '' ? "生存中のプロセスを検出:\n$leftoverSleep" : ''
);
check(
    'AC3(孫プロセス/パイプ): sh -c経由の孫プロセス(cat)が生存し続けていない',
    $leftoverCat === '',
    $leftoverCat !== '' ? "生存中のプロセスを検出:\n$leftoverCat" : ''
);

// --- 存在しないバイナリの実行時のエラーハンドリングを検証する ---
// 注: proc_open()自体は(execを前置したsh -cの起動という)fork自体には成功するため、
// ここでは`runProcessWithIO`内の`!is_resource($process)`分岐そのものには到達しない
// (proc_open()はディスクリプタ指定の異常等、極めて限定的な状況でしか失敗せず、
// このテストハーネスから決定的に再現する実用的な方法がない)。代わりに、
// 「execの対象コマンドが存在しない」という、実運用で発生しうる隣接ケース
// (exec失敗によりshがexit code 127で終了する経路)を検証する。
[$missingBinCode, $missingBinOut, $missingBinErr, $missingBinTimedOut] =
    runCommand(['/no/such/binary-1122-does-not-exist', 'arg'], 5);
check(
    '存在しないコマンド: 異常終了として扱われる(code!==0)',
    $missingBinCode !== 0,
    "code=$missingBinCode, err=$missingBinErr"
);
check(
    '存在しないコマンド: タイムアウトとして扱われない',
    $missingBinTimedOut === false,
    'timedOut=' . var_export($missingBinTimedOut, true)
);

// --- AC5: タイムアウトによる失敗は他の失敗と区別できる ---
check('AC5: タイムアウト時のエラーに識別可能なマーカー(TIMEOUT)が含まれる', strpos($err, 'TIMEOUT') !== false, "err=$err");
[$normalFailCode, , $normalFailErr] = runCommand(['php', '-r', 'fwrite(STDERR, "boom"); exit(1);'], 30);
check('AC5: 通常の失敗(exit=1)にはTIMEOUTマーカーが含まれない', $normalFailCode === 1 && strpos($normalFailErr, 'TIMEOUT') === false, "err=$normalFailErr");

// --- AC4: php -S の単一同時リクエスト制約(PHP_CLI_SERVER_WORKERSで緩和されるべき)の設定確認 ---
// index.php自体を経由した実接続テストは、認証トークンやDB接続がなくても動く「重い」エンドポイントが
// 存在しないため実施しない(既存のsync/provision系エンドポイントはDB・ファイルシステムに依存し、
// このテストハーネスだけで安全に模擬できない)。代わりに、
// (a) start.shが同時実行を許可する設定になっていること、
// (b) その設定(PHP_CLI_SERVER_WORKERS)がphpのビルトインサーバの並行性を実際に変えること
// の2点で検証する。
$startShPath = __DIR__ . '/../../start.sh';
$startShContents = file_exists($startShPath) ? file_get_contents($startShPath) : '';
$workersConfigured = (bool) preg_match('/PHP_CLI_SERVER_WORKERS\s*=\s*([2-9]|[1-9][0-9]+)/', $startShContents, $m);
check('AC4: start.shがPHP_CLI_SERVER_WORKERSを2以上に設定している', $workersConfigured, "start.sh=$startShContents");

// (b) は一般的なPHPビルトインサーバの挙動確認であり、start.shの内容に関わらず常に実施できる。
// 一時ドキュメントルートにslow.php(2秒待つ)とhealth.php(即時応答)を置き、
// slow.phpへのリクエスト中にhealth.phpが応答できるかを、
// PHP_CLI_SERVER_WORKERS未設定(旧来のstart.sh相当)/設定あり(修正後相当)の両方で比較する。
function measureHealthResponseDuringSlowRequest(bool $withWorkers): float
{
    $docroot = sys_get_temp_dir() . '/provision-agent-concurrency-test-' . bin2hex(random_bytes(4));
    mkdir($docroot);
    file_put_contents("$docroot/slow.php", '<?php sleep(2); echo "slow-done";');
    file_put_contents("$docroot/health.php", '<?php echo "ok";');

    $port = 20000 + random_int(0, 9000);
    $descriptors = [0 => ['pipe', 'r'], 1 => ['pipe', 'w'], 2 => ['pipe', 'w']];
    $env = null;
    if ($withWorkers) {
        $env = array_merge(getenv() ?: [], ['PHP_CLI_SERVER_WORKERS' => '4']);
    }
    $serverCmd = implode(' ', array_map('escapeshellarg', [
        PHP_BINARY, '-S', "127.0.0.1:$port", '-t', $docroot,
    ]));
    $server = proc_open($serverCmd, $descriptors, $pipes, null, $env);
    if (!is_resource($server)) {
        throw new RuntimeException('テスト用サーバの起動に失敗しました');
    }
    foreach ($pipes as $pipe) {
        stream_set_blocking($pipe, false);
    }
    usleep(300000); // サーバの起動待ち

    // slow.phpへ非同期に投げておく(このリクエスト自体の応答は待たない)
    $slowClient = proc_open(
        implode(' ', array_map('escapeshellarg', ['curl', '-s', '-o', '/dev/null', "http://127.0.0.1:$port/slow.php"])),
        $descriptors,
        $slowPipes
    );
    usleep(200000); // slow.phpがサーバに受理される猶予

    $healthStart = microtime(true);
    shell_exec('curl -s -o /dev/null --max-time 5 ' . escapeshellarg("http://127.0.0.1:$port/health.php"));
    $healthElapsed = microtime(true) - $healthStart;

    if (is_resource($slowClient)) {
        proc_close($slowClient);
    }
    proc_terminate($server, 9);
    proc_close($server);
    foreach ([$docroot . '/slow.php', $docroot . '/health.php'] as $f) {
        @unlink($f);
    }
    @rmdir($docroot);

    return $healthElapsed;
}

$elapsedWithoutWorkers = measureHealthResponseDuringSlowRequest(false);
$elapsedWithWorkers = measureHealthResponseDuringSlowRequest(true);
check(
    'AC4: PHP_CLI_SERVER_WORKERS未設定では低優先度リクエストがslowリクエストの完了(約2秒)まで足止めされる',
    $elapsedWithoutWorkers > 1.5,
    "elapsed={$elapsedWithoutWorkers}s"
);
check(
    'AC4: PHP_CLI_SERVER_WORKERS>=2では並行リクエストがブロックされず即時応答する',
    $elapsedWithWorkers < 1.0,
    "elapsed={$elapsedWithWorkers}s"
);

echo "\n";
/*
 * issue #1417: `wp post get` の失敗が「対象が無い」と断定できるかを判定する。
 *
 * #1070 / #1326 で入れた削除前の存在確認は、終了コードが0以外なら**理由を問わず404**を
 * 返していた。`wp post get` が非0で終わる理由は「投稿が無い」だけではなく、wp-cli自体の
 * 異常、DB接続の一時的な失敗、PHPのメモリ不足、パーミッション異常などでも非0になる。
 * それらが全て「対象なし(404)」に化けると、呼び出し側は**確定的な答え**として受け取り、
 * 実際にはまだ存在する記事を「もう消えている」ものとして扱ってしまう。
 *
 * #529 が `postExists` について既に否定した形であり、SSH側は #1411 で
 * 「断定できない失敗は502のまま + warn」に揃えた。agent側もここで揃える。
 *
 * 判定文言は Java 側の `WordPressSshOperations.POST_NOT_FOUND_PATTERN` と合わせる。
 */
check(
    'wpPostNotFound: wp-cliの「見つからない」メッセージを対象なしと判定する',
    wpPostNotFound('', 'Error: Could not find the post with ID 99.')
);
check(
    'wpPostNotFound: Invalid post IDも対象なしと判定する',
    wpPostNotFound('', 'Error: Invalid post ID.')
);
check(
    'wpPostNotFound: 日本語ロケールの文言も対象なしと判定する',
    wpPostNotFound('', 'エラー: 無効な投稿 ID です。')
);
check(
    'wpPostNotFound: stdout側に出ていても拾う',
    wpPostNotFound('Could not find the post with ID 7.', '')
);
check(
    'wpPostNotFound: 大文字小文字を区別しない',
    wpPostNotFound('', 'error: could not find the post with id 7.')
);
check(
    'wpPostNotFound: DB接続失敗は対象なしと断定しない',
    !wpPostNotFound('', "Error: Error establishing a database connection.")
);
check(
    'wpPostNotFound: メモリ不足は対象なしと断定しない',
    !wpPostNotFound('', 'PHP Fatal error: Allowed memory size of 134217728 bytes exhausted')
);
check(
    'wpPostNotFound: パーミッション異常は対象なしと断定しない',
    !wpPostNotFound('', "sh: 1: /usr/local/bin/wp: Permission denied")
);
check(
    'wpPostNotFound: 空の出力は対象なしと断定しない',
    !wpPostNotFound('', '')
);
/*
 * issue #1417 のレビュー指摘: Java側と**literally 等価**であること。
 *
 * 当初のPHP側は `無効な投稿\s*ID` までで、Java側の
 * `POST_NOT_FOUND_PATTERN`(`…|無効な投稿\s*ID\s*です`)が要求する `です` を
 * 要求していなかった。同じ文字列に対して agent が404、SSHが502という食い違いが
 * 起こりうる状態で、「文言を揃えてある」という私の記述は literally 誤りだった。
 *
 * レビュアーは wp-cli 2.12.0 の phar を展開し、`wp post get` が失敗時に出す文言は
 * ハードコードの `Could not find the post with ID %d.` ただ1つで、`無効な投稿` は
 * phar 内に存在しないことまで確認した。つまりこの分岐は現時点では発火しない。
 * それでも揃えるのは、#487 が `wp post update` 経由(WordPressコアの翻訳文字列)で
 * この日本語文言を実際に観測しているためで、将来 `wp post get` の実装が
 * コア側の文言を通すようになったときに両者が食い違わないようにする。
 */
check(
    'wpPostNotFound: Java側と揃えて「です」を要求する(単独の「無効な投稿ID」は断定しない)',
    !wpPostNotFound('', 'エラー: 無効な投稿IDのため処理を中断しました')
);
check(
    'wpPostNotFound: 「無効な投稿IDです」は対象なしと判定する',
    wpPostNotFound('', 'エラー: 無効な投稿IDです。')
);
check(
    'wpPostNotFound: 「無効な投稿 ID です」(空白あり)も判定する',
    wpPostNotFound('', 'エラー: 無効な投稿 ID です。')
);

if ($failures) {
    echo count($failures) . ' 件失敗:' . "\n";
    foreach ($failures as $f) {
        echo "  - $f\n";
    }
    exit(1);
}
echo 'すべて成功' . "\n";
exit(0);
