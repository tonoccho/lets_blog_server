import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

/**
 * 受け入れテストの実行区間を、起動経路によらず排他する(issue #1187)。
 *
 * `npm run test:at` 系のnpm scriptを`flock`で包む案は、`npx playwright test` の
 * 直接起動を取りこぼす(エージェントは実際によく直に叩く)。そこでPlaywrightの
 * `globalSetup` / `globalTeardown`(`playwright.config.ts` のconfigレベルの設定であり、
 * どの起動経路でも必ず呼ばれる)でロックの獲得・解放を行う。
 *
 * ## OSが自動解放する仕組み
 *
 * 1. Node自身が `fs.openSync` でロックファイルを開き、そのfdをこのプロセスが
 *    生きている間ずっと保持する。
 * 2. `flock` コマンドへそのfdを子プロセスの`stdio`経由で**継承**させ、子プロセス内で
 *    `flock(2)` を呼ばせる(`flock -n 3`)。`flock(2)` のロックはfd番号ではなく
 *    「オープンファイル記述(open file description)」に対して張られるため、
 *    子プロセスが`flock`を呼んだ直後に終了しても、同じオープンファイル記述を
 *    参照し続けるNode側のfdがある限りロックは保持され続ける
 *    (`man flock` の「fdを直接渡す形式はサブシェルをforkせずに済む」という説明の通り)。
 * 3. 保持中のプロセスが異常終了(SIGKILL含む)しても、カーネルは終了時に
 *    そのプロセスの全fdを強制的に閉じるため、ロックは即座に解放される。
 *    明示的な後始末コードを一切必要としない。
 *
 * 実機検証(#1187): 2つのnodeプロセスでこの手順を踏み、ロックを保持している側を
 * SIGKILLしたところ、待っていた側は即座に獲得できた(ポーリング間隔分すら待たなかった)。
 *
 * ## 待機の可視化と上限
 *
 * `flock -n`(非ブロッキング)で試行 → 失敗したら保持者情報(PID・開始時刻)を
 * ロックファイルの内容から読み、経過時間とともに標準出力へ出す → 一定間隔で
 * 再試行、を上限に達するまで繰り返す。ロックファイルの内容は、保持者が獲得直後に
 * 自分のPIDと開始時刻を書き込むことで維持する(読み取りはロック不要)。
 *
 * 環境変数:
 *   AT_LOCK_FILE            : ロックファイルの場所を上書きする(既定は
 *                             `${XDG_RUNTIME_DIR:-os.tmpdir()}/lets-blog-server-acceptance-test.lock`。
 *                             リポジトリ外に置くのは、read-onlyステージのガードと衝突させず、
 *                             worktree間で共有するため)
 *   AT_LOCK_TIMEOUT_SECONDS : 待機上限(秒)。既定 7200(2時間、ACCEPTANCE_RESET のゼロ構築
 *                             最大90分 + テスト実行時間を見込む)
 *   AT_LOCK_POLL_SECONDS    : 待機中の再試行間隔(秒)。既定 30
 */

const DEFAULT_TIMEOUT_SECONDS = 7200;
const DEFAULT_POLL_SECONDS = 30;

function lockFilePath(): string {
  if (process.env.AT_LOCK_FILE) return process.env.AT_LOCK_FILE;
  const dir = process.env.XDG_RUNTIME_DIR || os.tmpdir();
  return path.join(dir, 'lets-blog-server-acceptance-test.lock');
}

function positiveNumberEnv(name: string, fallback: number): number {
  const raw = process.env[name];
  if (!raw) return fallback;
  const n = Number(raw);
  return Number.isFinite(n) && n > 0 ? n : fallback;
}

// 自プロセスが既に保持しているロックのfd。二重取得(冪等)と解放に使う。
let heldFd: number | undefined;

function readHolderInfo(lockPath: string): string {
  try {
    const text = fs.readFileSync(lockPath, 'utf8').trim();
    return text || '(情報なし)';
  } catch {
    return '(情報なし)';
  }
}

/**
 * ロックファイルのfdへ、非ブロッキングで`flock(2)`を試みる。獲得できればtrue。
 *
 * `flock -n` の非0終了(誰かがロックを保持中)と、`flock` コマンド自体を spawn できない
 * こと(ENOENT等、ホストに `flock` が無い)は区別する。前者は「まだ獲得できていない」
 * としてリトライ対象だが、後者をそのまま `false` にすると
 * `AT_LOCK_TIMEOUT_SECONDS`(既定7200秒)いっぱいまで無言でリトライし続け、原因が
 * 「競合」ではなく「コマンドが無い」ことに気づけない(レビュー指摘 #1187)。
 * spawn自体の失敗は即座に例外を投げて区別する。
 */
function tryAcquireNonBlocking(fd: number): boolean {
  try {
    // インデックス3(stdio配列の4番目)が子プロセス内でのfd番号になる。
    execFileSync('flock', ['-n', '3'], { stdio: ['ignore', 'ignore', 'ignore', fd] });
    return true;
  } catch (error) {
    if (error && typeof error === 'object' && (error as NodeJS.ErrnoException).code === 'ENOENT') {
      throw new Error(
        'flock コマンドが見つかりません。' +
          'このホストには受け入れテストの排他ロックに必要な `flock` ユーティリティが' +
          '導入されていません(util-linux 等のパッケージを導入してください)。'
      );
    }
    // 上記以外(典型的には `flock -n` の非0終了、つまりロック競合)は、まだ獲得できて
    // いないだけなのでリトライ対象として扱う。
    return false;
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * 受け入れテストの排他ロックを獲得する。自プロセスが既に保持していれば何もしない(冪等)。
 *
 * 待機中は保持者(PID・開始時刻)と経過時間を周期的に標準出力へ出す(無言で待たない)。
 * `AT_LOCK_TIMEOUT_SECONDS` 秒を超えて獲得できなければ例外を投げる。
 */
export async function acquireAcceptanceTestLock(
  log: (message: string) => void = console.log
): Promise<void> {
  if (heldFd !== undefined) return;

  const lockPath = lockFilePath();
  const timeoutSeconds = positiveNumberEnv('AT_LOCK_TIMEOUT_SECONDS', DEFAULT_TIMEOUT_SECONDS);
  const pollSeconds = positiveNumberEnv('AT_LOCK_POLL_SECONDS', DEFAULT_POLL_SECONDS);
  const fd = fs.openSync(lockPath, 'a+');
  const waitStarted = Date.now();
  let hasWaited = false;

  for (;;) {
    if (tryAcquireNonBlocking(fd)) {
      fs.ftruncateSync(fd, 0);
      fs.writeSync(fd, `${process.pid} ${new Date().toISOString()}\n`, 0);
      heldFd = fd;
      if (hasWaited) {
        log(`[e2e] 受け入れテストの排他ロック(${lockPath})を獲得しました`);
      }
      return;
    }

    const elapsedSeconds = (Date.now() - waitStarted) / 1000;
    if (elapsedSeconds >= timeoutSeconds) {
      fs.closeSync(fd);
      throw new Error(
        `受け入れテストの排他ロック(${lockPath})を ${timeoutSeconds}秒 待っても獲得できませんでした。` +
          `保持者: ${readHolderInfo(lockPath)}。` +
          'AT_LOCK_TIMEOUT_SECONDS 環境変数で待機上限を調整できます。'
      );
    }

    log(
      `[e2e] 受け入れテストの排他ロック(${lockPath})を待機中... ` +
        `保持者: ${readHolderInfo(lockPath)} / 経過 ${Math.floor(elapsedSeconds)}秒 / 上限 ${timeoutSeconds}秒`
    );
    hasWaited = true;
    const remainingSeconds = timeoutSeconds - elapsedSeconds;
    await sleep(Math.min(pollSeconds, remainingSeconds) * 1000);
  }
}

/** 受け入れテストの排他ロックを解放する。自プロセスが保持していなければ何もしない(冪等)。 */
export function releaseAcceptanceTestLock(): void {
  if (heldFd === undefined) return;
  try {
    fs.ftruncateSync(heldFd, 0);
  } catch {
    // ベストエフォート。解放そのもの(fdのclose、すなわちflockの解除)は必ず行う。
  }
  fs.closeSync(heldFd);
  heldFd = undefined;
}
