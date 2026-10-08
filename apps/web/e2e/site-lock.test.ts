/**
 * @jest-environment node
 *
 * issue #1694: resource-comparison のプラグイン/テーマ比較シナリオを `--repeat-each` や
 * 既定の並列度で回すと、固定サイト(at7cmpmaster / at7cmptarget)の `active_plugins` を
 * 複数ワーカーが同時に読み書きして互いの有効化を失い、プラグインが INACTIVE のままになる。
 * シナリオ全体(準備から後片付けまで)を固定サイト単位のクロスプロセスロックで直列化する。
 *
 * `apps/web/e2e/**` は通常jestの対象外なので、account-lock.test.ts と同様にCLI引数で上書きして実行する:
 *
 *   npx jest --config jest.config.ts --testMatch='**\/e2e/site-lock.test.ts' e2e/site-lock.test.ts
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { acquireSiteLock } from './site-lock';

describe('acquireSiteLock(issue #1694: 固定サイトを共有するシナリオの直列化)', () => {
  let lockDir: string;

  beforeEach(() => {
    lockDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lbs-e2e-site-lock-test-'));
    process.env.E2E_TOKEN_CACHE_DIR = lockDir;
  });

  afterEach(() => {
    delete process.env.E2E_TOKEN_CACHE_DIR;
    fs.rmSync(lockDir, { recursive: true, force: true });
  });

  test('同じ名前のロックは、解放されるまで次の獲得を待たせる', async () => {
    let inFlight = 0;
    let maxConcurrent = 0;
    async function scenario(): Promise<void> {
      const release = await acquireSiteLock('at7cmp');
      inFlight += 1;
      maxConcurrent = Math.max(maxConcurrent, inFlight);
      await new Promise((resolve) => setTimeout(resolve, 30));
      inFlight -= 1;
      release();
    }
    await Promise.all([scenario(), scenario(), scenario()]);
    expect(maxConcurrent).toBe(1);
  });

  test('異なる名前のロックは互いにブロックしない', async () => {
    const a = await acquireSiteLock('a');
    const b = await acquireSiteLock('b', { timeoutMs: 200 });
    a();
    b();
  });

  test('解放を2回呼んでも例外にならず、解放後は再獲得できる', async () => {
    const release = await acquireSiteLock('twice');
    release();
    release();
    const again = await acquireSiteLock('twice', { timeoutMs: 200 });
    again();
  });

  test('timeoutMs 内に獲得できなければ、待った旨のエラーで失敗する', async () => {
    const held = await acquireSiteLock('busy');
    await expect(acquireSiteLock('busy', { timeoutMs: 150 })).rejects.toThrow(/busy.*150ms/);
    held();
  });
});
