/**
 * @jest-environment node
 *
 * issue #1381: `page.goto` / `page.reload` 直後にサーバ描画済みのボタンをクリックすると、
 * Reactのハイドレーションが完了し`onClick`が結びつく前ではPlaywrightのactionability
 * チェック(visible / stable / enabled / receives events)を全て通過しても`click()`
 * 自体は何も起こらない。標識を待つのではなく「期待した結果が出るまでクリックし直す」
 * 共有ヘルパー(`./support/retryClick`)の単体テスト。
 *
 * このファイルを`e2e/support/`ではなく`e2e/`直下(helpers.test.ts / account-lock.test.ts
 * と同じ場所)に置くのは、`playwright.config.ts`の`steps: ['e2e/steps/**\/*.ts',
 * 'e2e/support/**\/*.ts']`が`e2e/support/**`配下の全`.ts`を無条件にステップ定義として
 * `require`するため。`e2e/support/`に置くと`bddgen`が本ファイルをロードしようとして
 * `ReferenceError: describe is not defined`で落ちる(jestのグローバルはbddgenの実行時に
 * 存在しない)。実装対象の`retryClick.ts`自体は`e2e/support/`に置いて構わない
 * (他のsupportモジュールと同様、副作用なくrequireできるプレーンなモジュールのため)。
 *
 * `apps/web/e2e/**` は通常jestの対象外(jest.config.tsのtestMatch。#994)なので、
 * helpers.test.ts / account-lock.test.ts と同様にCLI引数で明示的に上書きして実行する
 * (実装報告に実行結果を記録):
 *
 *   npx jest --config jest.config.ts \
 *     --testPathIgnore(略・対象除外オプション名)='/node_modules/|/\.next/' \
 *     --testMatch='**\/e2e/retryClick.test.ts' \
 *     e2e/retryClick.test.ts
 *
 * `retryClick.ts` 自体は `apps/web/e2e/**` にあるため、CLAUDE.md → Test-First
 * Implementation のコミット分類上は「テストコード」であり(.claude/hooks/paths.py の
 * TEST_PATTERNS)、C1/C2 カバレッジの数値目標(90%)の対象外(90%目標はプロダクション
 * コードにのみ課される)。実装報告にその旨を明記する。
 *
 * 実ブラウザ・実Playwrightページを使わずに検証するため、`click()` / `waitFor()` だけを
 * 実装した`Locator`風のフェイクオブジェクトを渡す(`as unknown as Locator`でキャスト)。
 * `clickUntilVisible`はこの2メソッドしか呼ばないため、フェイクで挙動を十分に再現できる。
 */

import type { Locator } from '@playwright/test';
import { clickUntilVisible, retryUntilPass } from './support/retryClick';

/** 呼ばれた回数を記録しつつ、指定回数だけ拒否してからresolveする関数を作る。 */
function rejectNTimesThenResolve(times: number): jest.Mock<Promise<void>, []> {
  let calls = 0;
  return jest.fn(async () => {
    calls += 1;
    if (calls <= times) {
      throw new Error(`まだ効いていない(${calls}回目)`);
    }
  });
}

/** 常に拒否し続ける関数を作る(制限時間で失敗することを確認するため)。 */
function alwaysReject(): jest.Mock<Promise<void>, []> {
  return jest.fn(async () => {
    throw new Error('ずっと効かない');
  });
}

describe('retryUntilPass(issue #1381: 再試行ループの本体)', () => {
  test('1回目が失敗しても、再試行して成功すれば例外を投げない', async () => {
    const action = rejectNTimesThenResolve(1);

    await retryUntilPass(action);

    expect(action).toHaveBeenCalledTimes(2);
  });

  test('成功する場合は無駄な再試行をしない(1回だけ呼ばれる)', async () => {
    const action = rejectNTimesThenResolve(0);

    await retryUntilPass(action);

    expect(action).toHaveBeenCalledTimes(1);
  });

  test('最後まで成功しなければ、指定した制限時間内に失敗する(無限に待たない)', async () => {
    const action = alwaysReject();
    const startedAt = Date.now();

    await expect(retryUntilPass(action, { timeoutMs: 300 })).rejects.toThrow();

    const elapsedMs = Date.now() - startedAt;
    // 無限に待っていないことの確認。ポーリング間隔(既定[100,250,500,1000]ms)の
    // 都合で300ちょうどより多少超えることはあるが、次の間隔(500ms超)まで
    // 待ち続けていないことを確認できれば十分。
    expect(elapsedMs).toBeLessThan(2_000);
    expect(action.mock.calls.length).toBeGreaterThan(1);
  }, 10_000);
});

describe('clickUntilVisible(issue #1381: goto/reload直後のクリック空振り対策)', () => {
  function fakeLocator(overrides: Partial<Locator>): Locator {
    return overrides as unknown as Locator;
  }

  test('1回目のクリックが空振りしても、再試行して期待した要素が出れば成功する', async () => {
    const click = jest.fn(async () => {});
    const waitFor = rejectNTimesThenResolve(1);
    const trigger = fakeLocator({ click });
    const expected = fakeLocator({ waitFor: waitFor as unknown as Locator['waitFor'] });

    await clickUntilVisible(trigger, expected);

    expect(click).toHaveBeenCalledTimes(2);
    expect(waitFor).toHaveBeenCalledTimes(2);
  });

  test('成功する場合に無駄なクリックを繰り返さない', async () => {
    const click = jest.fn(async () => {});
    const waitFor = jest.fn(async () => {});
    const trigger = fakeLocator({ click });
    const expected = fakeLocator({ waitFor });

    await clickUntilVisible(trigger, expected);

    expect(click).toHaveBeenCalledTimes(1);
  });

  test('期待した要素が最後まで出なければ、制限時間で失敗する(無限に待たない)', async () => {
    const click = jest.fn(async () => {});
    const waitFor = alwaysReject();
    const trigger = fakeLocator({ click });
    const expected = fakeLocator({ waitFor: waitFor as unknown as Locator['waitFor'] });
    const startedAt = Date.now();

    await expect(clickUntilVisible(trigger, expected, { timeoutMs: 300 })).rejects.toThrow();

    const elapsedMs = Date.now() - startedAt;
    expect(elapsedMs).toBeLessThan(2_000);
    expect(click.mock.calls.length).toBeGreaterThan(1);
  }, 10_000);
});
