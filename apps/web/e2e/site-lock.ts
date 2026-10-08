import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

/**
 * issue #1694: 固定サイト(at7cmpmaster / at7cmptarget)を共有するシナリオの直列化に使う、
 * 名前付きのクロスプロセスロック。
 *
 * `account-lock.ts` の `withAccountLock` は関数1本を包む形だが、こちらは「準備(Given)から
 * 後片付け(After)まで」という複数ステップにまたがってロックを保持する必要があるため、
 * 獲得と解放を分けている。手法は同じ(`flock(1)` を Node 側が保持した fd 越しに呼ぶ。
 * fd を close すれば解放されるので、ワーカーが落ちてもロックは残らない)。
 */
const DEFAULT_TIMEOUT_MS = 900_000;
const POLL_MS = 50;

function lockDir(): string {
  return process.env.E2E_TOKEN_CACHE_DIR || process.env.XDG_RUNTIME_DIR || os.tmpdir();
}

function tryLock(fd: number): boolean {
  try {
    execFileSync('flock', ['-n', '3'], { stdio: ['ignore', 'ignore', 'ignore', fd] });
    return true;
  } catch (error) {
    if ((error as NodeJS.ErrnoException)?.code === 'ENOENT') {
      throw new Error('flock コマンドが見つかりません。固定サイトのロックには util-linux 等の flock が必要です。');
    }
    return false;
  }
}

/** ロックを獲得し、解放関数を返す。解放関数は何度呼んでもよい。 */
export async function acquireSiteLock(name: string, options?: { timeoutMs?: number }): Promise<() => void> {
  const timeoutMs = options?.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const fd = fs.openSync(path.join(lockDir(), `lets-blog-server-e2e-site-${name}.lock`), 'a+');
  const started = Date.now();
  while (!tryLock(fd)) {
    if (Date.now() - started > timeoutMs) {
      fs.closeSync(fd);
      throw new Error(`サイトロック(${name})を${timeoutMs}ms待っても獲得できませんでした`);
    }
    await new Promise((resolve) => setTimeout(resolve, POLL_MS));
  }
  let released = false;
  return () => {
    if (!released) {
      released = true;
      fs.closeSync(fd);
    }
  };
}
