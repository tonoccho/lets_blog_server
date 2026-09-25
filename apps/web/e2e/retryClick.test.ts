/**
 * @jest-environment node
 *
 * issue #1381: `page.goto` / `page.reload` 直後にサーバ描画済みのボタンをクリックすると、
 * Reactのハイドレーションが完了し`onClick`が結びつく前ではPlaywrightのactionability
 * チェック(visible / stable / enabled / receives events)を全て通過しても`click()`
 * 自体は何も起こらない。標識を待つのではなく「期待した結果が出るまでクリックし直す」
 * 共有ヘルパー(`./support/retryClick`)の単体テスト。issue #1360で、クリック直前に
 * `expected`が既に見えていればクリックを撃たないstate-aware化のテストを追加した
 * (トグル、たとえばハンバーガーメニューの開閉に対する残存リスクの緩和)。
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

import type { Dialog, Locator, Page } from '@playwright/test';
import { clickUntilVisible, retryUntilPass, withDialogAccepted } from './support/retryClick';

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
    const isVisible = jest.fn(async () => false);
    const trigger = fakeLocator({ click });
    const expected = fakeLocator({
      isVisible: isVisible as unknown as Locator['isVisible'],
      waitFor: waitFor as unknown as Locator['waitFor'],
    });

    await clickUntilVisible(trigger, expected);

    expect(click).toHaveBeenCalledTimes(2);
    expect(waitFor).toHaveBeenCalledTimes(2);
  });

  test('成功する場合に無駄なクリックを繰り返さない', async () => {
    const click = jest.fn(async () => {});
    const waitFor = jest.fn(async () => {});
    const isVisible = jest.fn(async () => false);
    const trigger = fakeLocator({ click });
    const expected = fakeLocator({ isVisible: isVisible as unknown as Locator['isVisible'], waitFor });

    await clickUntilVisible(trigger, expected);

    expect(click).toHaveBeenCalledTimes(1);
  });

  test('期待した要素が最後まで出なければ、制限時間で失敗する(無限に待たない)', async () => {
    const click = jest.fn(async () => {});
    const waitFor = alwaysReject();
    const isVisible = jest.fn(async () => false);
    const trigger = fakeLocator({ click });
    const expected = fakeLocator({
      isVisible: isVisible as unknown as Locator['isVisible'],
      waitFor: waitFor as unknown as Locator['waitFor'],
    });
    const startedAt = Date.now();

    await expect(clickUntilVisible(trigger, expected, { timeoutMs: 300 })).rejects.toThrow();

    const elapsedMs = Date.now() - startedAt;
    expect(elapsedMs).toBeLessThan(2_000);
    expect(click.mock.calls.length).toBeGreaterThan(1);
  }, 10_000);
});

describe('clickUntilVisible(issue #1360: 既に目的の状態ならクリックを撃たないstate-aware化)', () => {
  function fakeLocator(overrides: Partial<Locator>): Locator {
    return overrides as unknown as Locator;
  }

  /**
   * issue #1360: `clickUntilVisible`はトグル(ハンバーガーメニューの開閉など、同じ
   * ボタンへの再クリックが状態を反転させてしまう操作)に対して残存リスクを持つ
   * (`support/retryClick.ts`冒頭のコメント参照)。1回目のクリックが実際には効いて
   * いたのに、Reactの再描画コミットが`visibleTimeoutMs`を超えて遅れると、再試行が
   * 2回目のクリックを撃って既に開いた状態を閉じてしまう。
   *
   * 対策として、**2回目以降の**試行に限り、クリックの直前に`expected`が既に見えて
   * いないかを確認し、見えていればクリックをスキップする(#1381レビューでの提案を
   * #1360のレビュー指摘で絞り込んだもの)。これは「状態は変わったがまだ描画されて
   * いない」極狭の窓までは消せないが、再試行時点で既に描画が追いついている場合の
   * 誤クリックは防げる。
   *
   * 【1回目をスキップしてはいけない理由】この確認を1回目にも効かせると、`expected`が
   * 入室時点で既に見えている場合にクリックが**一度も行われない**まま成功扱いになる。
   * 例えば将来の退行で`HeaderNav`の`isOpen`が初期値`true`になったり、ドロワーが無条件
   * 描画になったりすると、開くボタンが完全に壊れていてもシナリオが通ってしまう。
   * それはこの受け入れテストが検出すべき欠陥そのものなので、1回目は必ずクリックする。
   */

  test('expected が既に見えていても、1回目は必ずクリックする(壊れたトリガーを見逃さない)', async () => {
    const click = jest.fn(async () => {});
    const isVisible = jest.fn(async () => true);
    const waitFor = jest.fn(async () => {});
    const trigger = fakeLocator({ click });
    const expected = fakeLocator({ isVisible: isVisible as unknown as Locator['isVisible'], waitFor });

    await clickUntilVisible(trigger, expected);

    expect(click).toHaveBeenCalledTimes(1);
    // 1回目はisVisibleを見ずに必ずクリックする。
    expect(isVisible).not.toHaveBeenCalled();
  });

  test('expected がまだ見えていない場合は従来どおりクリックしてから出現を待つ', async () => {
    const click = jest.fn(async () => {});
    const isVisible = jest.fn(async () => false);
    const waitFor = jest.fn(async () => {});
    const trigger = fakeLocator({ click });
    const expected = fakeLocator({ isVisible: isVisible as unknown as Locator['isVisible'], waitFor });

    await clickUntilVisible(trigger, expected);

    expect(click).toHaveBeenCalledTimes(1);
    expect(waitFor).toHaveBeenCalledTimes(1);
  });

  test('1回目のクリック後に描画待ちが尽きても、再試行時に既に見えていればクリックし直さずに回復する(トグルを誤って閉じ直さない)', async () => {
    const click = jest.fn(async () => {});
    // 再試行時にはReactの再描画がコミット済みになりtrueを返す想定
    // (1回目のクリックは実際には効いていた)。
    const isVisible = jest.fn(async () => true);
    // waitForは常に失敗させる。もし製品コードが再試行のたびに無条件でクリックし直すなら、
    // 2回目のクリックがトグルを閉じてしまい、click呼び出し回数が2以上になる。
    const waitFor = alwaysReject();
    const trigger = fakeLocator({ click });
    const expected = fakeLocator({
      isVisible: isVisible as unknown as Locator['isVisible'],
      waitFor: waitFor as unknown as Locator['waitFor'],
    });

    await clickUntilVisible(trigger, expected, { timeoutMs: 1_000 });

    // 1回目はisVisibleを見ずにクリックし、2回目以降は見えているのでクリックしない。
    expect(click).toHaveBeenCalledTimes(1);
    expect(isVisible.mock.calls.length).toBeGreaterThan(0);
  });

  test('再試行時でも expected が見えていなければクリックし直す', async () => {
    const click = jest.fn(async () => {});
    const isVisible = jest.fn(async () => false);
    const waitFor = rejectNTimesThenResolve(1);
    const trigger = fakeLocator({ click });
    const expected = fakeLocator({
      isVisible: isVisible as unknown as Locator['isVisible'],
      waitFor: waitFor as unknown as Locator['waitFor'],
    });

    await clickUntilVisible(trigger, expected);

    // 1回目(isVisibleを見ない)+ 2回目(見たがfalse)の計2回。
    expect(click).toHaveBeenCalledTimes(2);
    expect(isVisible).toHaveBeenCalledTimes(1);
  });
});

describe('withDialogAccepted(issue #1385: window.confirm()を伴う操作を再試行できるようにする)', () => {
  /**
   * issue #1385: `page.once('dialog', ...)`は1回受けたら外れる。削除ボタンの
   * クリックのように`window.confirm()`を出す操作を`clickUntilVisible`で再試行すると、
   * 2回目以降に出るダイアログは誰にも受けられずPlaywrightが自動でdismissしてしまい、
   * 再試行のクリックは確認ダイアログを閉じるだけで操作自体は永久に実行されない。
   * `page.on`(`once`ではない)で毎回受け、`action`を終えたら`page.off`で必ず外す
   * (外し忘れると同一シナリオの後続ステップの`page.once('dialog', ...)`より先に
   * 本ヘルパーのハンドラがダイアログを消費してしまう)。
   *
   * 実ブラウザを使わず、`on` / `off`だけを実装したフェイクの`Page`で検証する。
   */
  function fakePage(): { page: Page; handlers: Set<(dialog: Dialog) => void> } {
    const handlers = new Set<(dialog: Dialog) => void>();
    const page = {
      on: jest.fn((event: string, handler: (dialog: Dialog) => void) => {
        if (event === 'dialog') {
          handlers.add(handler);
        }
        return page;
      }),
      off: jest.fn((event: string, handler: (dialog: Dialog) => void) => {
        if (event === 'dialog') {
          handlers.delete(handler);
        }
        return page;
      }),
    } as unknown as Page;
    return { page, handlers };
  }

  test('actionの実行中だけダイアログハンドラを登録し、終了後は必ず外す', async () => {
    const { page, handlers } = fakePage();

    await withDialogAccepted(page, async () => {
      expect(handlers.size).toBe(1);
    });

    expect(handlers.size).toBe(0);
    expect(page.on).toHaveBeenCalledWith('dialog', expect.any(Function));
    expect(page.off).toHaveBeenCalledWith('dialog', expect.any(Function));
  });

  test('actionが例外を投げても、ハンドラは外れる(finally)', async () => {
    const { page, handlers } = fakePage();

    await expect(
      withDialogAccepted(page, async () => {
        throw new Error('action failed');
      })
    ).rejects.toThrow('action failed');

    expect(handlers.size).toBe(0);
  });

  test('登録したハンドラは、actionの中で出たダイアログを毎回acceptする(page.onceの1回きりの制約を持たない)', async () => {
    const { page, handlers } = fakePage();
    const accept1 = jest.fn(async () => {});
    const accept2 = jest.fn(async () => {});

    await withDialogAccepted(page, async () => {
      for (const handler of handlers) {
        handler({ accept: accept1 } as unknown as Dialog);
        handler({ accept: accept2 } as unknown as Dialog);
      }
    });

    expect(accept1).toHaveBeenCalledTimes(1);
    expect(accept2).toHaveBeenCalledTimes(1);
  });

  test('actionの戻り値をそのまま返す', async () => {
    const { page } = fakePage();

    const result = await withDialogAccepted(page, async () => 'ok');

    expect(result).toBe('ok');
  });
});
