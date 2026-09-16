/**
 * @jest-environment node
 *
 * issue #1295フォローアップ(QAのFAIL、note 7391): 実際の受け入れテスト実行(既定の並列度)で、
 * `fetchAccessToken`のパスワードグラント経路とは全く別のコード経路である
 * ブラウザ対話ログイン(`apps/web/e2e/helpers.ts`の`loginViaKeycloak`、
 * clientId="letsblog-web"、auth_type="code")でも`user_temporarily_disabled`が
 * 2件再現した。`token-cache.ts`のアカウント単位クロスプロセスロック(`withAccountLock`)は
 * `fetchAccessToken`専用に実装されており、ブラウザ経由のログインには一切効いていなかった。
 *
 * このロックの本体を`./account-lock`へ切り出し、`fetchAccessToken`と`loginViaKeycloak`の
 * 両方が同じアカウント単位ロックファイルを使うようにする。これにより「同一アカウントに対する
 * 実Keycloak認証リクエストは、パスワードグラントか対話ログインかを問わず同時に1本まで」に揃い、
 * quickLoginCheckMilliSeconds(1秒)以内に同一アカウントへの認証試行が重なる状況そのものが
 * 経路によらず起きなくなる。
 *
 * `apps/web/e2e/**` は通常jestの対象外(jest.config.tsのtestMatch)なので、helpers.test.tsと
 * 同様にCLI引数で明示的に上書きして実行する(実装報告に実行結果を記録):
 *
 *   npx jest --config jest.config.ts \
 *     --testPathIgnore(略・対象除外オプション名)='/node_modules/|/\.next/' \
 *     --testMatch='**\/e2e/account-lock.test.ts' \
 *     e2e/account-lock.test.ts
 *
 * 単一プロセス内でも、`fs.openSync`を呼ぶたびに独立したオープンファイル記述(OFD)が
 * 作られ、`flock(2)`のロックはOFD単位であって呼び出し元プロセスの同一性は問わないため、
 * ここでの検証は実際の`flock`コマンドを使った本物のOSレベル排他を確認している
 * (プロセスをまたいだ検証はtoken-cross-process.test.tsで別途行っている)。
 */

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { withAccountLock } from './account-lock';

describe('withAccountLock(issue #1295フォローアップ: 経路によらないアカウント単位のロック)', () => {
  let lockDir: string;

  beforeEach(() => {
    lockDir = fs.mkdtempSync(path.join(os.tmpdir(), 'lbs-e2e-account-lock-test-'));
    process.env.E2E_TOKEN_CACHE_DIR = lockDir;
  });

  afterEach(() => {
    delete process.env.E2E_TOKEN_CACHE_DIR;
    fs.rmSync(lockDir, { recursive: true, force: true });
  });

  test('同一アカウントに対する呼び出しは重複実行されず、常に直列化される', async () => {
    const email = 'e2e-admin@letsblog.local';
    let inFlight = 0;
    let maxConcurrent = 0;
    const order: number[] = [];

    async function task(id: number): Promise<void> {
      await withAccountLock(email, async () => {
        inFlight += 1;
        maxConcurrent = Math.max(maxConcurrent, inFlight);
        await new Promise((resolve) => setTimeout(resolve, 30));
        order.push(id);
        inFlight -= 1;
      });
    }

    await Promise.all([task(1), task(2), task(3)]);

    expect(maxConcurrent).toBe(1);
    expect(order.sort((a, b) => a - b)).toEqual([1, 2, 3]);
  });

  test('異なるアカウントは互いにブロックしない(同時に実行できる)', async () => {
    let concurrentAcrossAccounts = 0;
    let observedBothAtOnce = false;

    async function task(email: string): Promise<void> {
      await withAccountLock(email, async () => {
        concurrentAcrossAccounts += 1;
        if (concurrentAcrossAccounts === 2) {
          observedBothAtOnce = true;
        }
        await new Promise((resolve) => setTimeout(resolve, 50));
        concurrentAcrossAccounts -= 1;
      });
    }

    await Promise.all([task('e2e-test@letsblog.local'), task('e2e-admin@letsblog.local')]);

    expect(observedBothAtOnce).toBe(true);
  });

  test('fnの戻り値をそのまま返す', async () => {
    const result = await withAccountLock('e2e-test@letsblog.local', async () => 'ok');
    expect(result).toBe('ok');
  });
});
