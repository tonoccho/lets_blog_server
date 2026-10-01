/**
 * @jest-environment node
 *
 * issue #1476: 応答時間(3秒予算)の共通計測ヘルパー `./support/responseBudget` の単体テスト。
 *
 * このファイルを `e2e/support/` ではなく `e2e/` 直下に置くのは、`retryClick.test.ts` の冒頭に
 * ある理由と同じ(`playwright.config.ts` の `steps` が `e2e/support/**` 配下の全 `.ts` を
 * ステップ定義として require し、jest のグローバルが無い bddgen で落ちるため)。
 *
 * `apps/web/e2e/**` は通常 jest の対象外(jest.config.ts の testMatch)なので、
 * `retryClick.test.ts` と同様にCLI引数で明示的に上書きして実行する:
 *
 *   npx jest --config jest.config.ts --testMatch='**\/e2e/responseBudget.test.ts' e2e/responseBudget.test.ts
 *
 * 対象の `responseBudget.ts` は `apps/web/e2e/**` にあり、.claude/hooks/paths.py 上は
 * テストコード扱い。C1/C2 の数値目標の対象(プロダクションコード)ではないが、分岐は
 * すべて本ファイルで踏む。
 */

import { EventEmitter } from 'node:events';
import type { Page, Request } from '@playwright/test';
import {
  assertWithinBudget,
  measureFirstDisplay,
  measureServerActionRoundTrip,
  formatBudgetMessage,
  recordResponseTime,
  RESPONSE_ELAPSED_KEY,
  RESPONSE_OPERATION_KEY,
  SERVER_ACTION_HEADER,
} from './support/responseBudget';

/** 時刻を進められる偽の時計。 */
function fakeClock(): { now: () => number; advance: (ms: number) => void } {
  let t = 1_000;
  return { now: () => t, advance: (ms) => void (t += ms) };
}

function fakeRequest(method: string, headers: Record<string, string>): Request {
  return { method: () => method, headers: () => headers } as unknown as Request;
}

describe('assertWithinBudget', () => {
  it('上限未満なら通る', () => {
    expect(() => assertWithinBudget(2999, 3000, 'x')).not.toThrow();
  });
  it('上限ちょうどは超過として落ちる(従来の toBeLessThan と同じ)', () => {
    expect(() => assertWithinBudget(3000, 3000, 'x')).toThrow(/3000ms/);
  });
  it('上限を超えると、操作名と実測値を含む文面で落ちる', () => {
    expect(() => assertWithinBudget(5000, 3000, 'ページロード')).toThrow(/ページロード[\s\S]*3000ms[\s\S]*5000ms/);
  });
  it('メッセージ整形は操作名・実測・上限を含む', () => {
    expect(formatBudgetMessage('op', 10, 5)).toContain('op');
  });
});

describe('measureFirstDisplay(ウォームアップ除外)', () => {
  it('goto を2回行い、2回目だけを計測する', async () => {
    const clock = fakeClock();
    const durations = [9_000, 250];
    const goto = jest.fn(async () => {
      clock.advance(durations.shift() as number);
    });
    const elapsed = await measureFirstDisplay({ goto } as unknown as Page, '/projects', clock.now);
    expect(goto).toHaveBeenCalledTimes(2);
    expect(goto).toHaveBeenNthCalledWith(1, '/projects');
    expect(goto).toHaveBeenNthCalledWith(2, '/projects');
    expect(elapsed).toBe(250);
  });
});

describe('measureServerActionRoundTrip(retryClick の再試行時間を含めない)', () => {
  function setup() {
    const page = new EventEmitter();
    const clock = fakeClock();
    return { page, clock, asPage: page as unknown as Page };
  }

  it('Server Action の POST の往復だけを測り、trigger の所要時間(再試行)は含めない', async () => {
    const { page, clock, asPage } = setup();
    const action = fakeRequest('POST', { [SERVER_ACTION_HEADER]: 'abc' });
    const result = await measureServerActionRoundTrip(
      asPage,
      async () => {
        clock.advance(6_000); // 空振りのクリックと再試行(サーバへ届かない)
        page.emit('request', action);
        clock.advance(400); // 実際のサーバ往復
        page.emit('requestfinished', action);
        clock.advance(500); // 可視待ち
      },
      { now: clock.now, timeoutMs: 1_000 }
    );
    expect(result.roundTripMs).toBe(400);
    expect(result.requestCount).toBe(1);
  });

  it('複数回送られたときは最も遅い1往復を返す', async () => {
    const { page, clock, asPage } = setup();
    const a = fakeRequest('POST', { [SERVER_ACTION_HEADER]: '1' });
    const b = fakeRequest('POST', { [SERVER_ACTION_HEADER]: '2' });
    const result = await measureServerActionRoundTrip(
      asPage,
      async () => {
        page.emit('request', a);
        clock.advance(100);
        page.emit('requestfinished', a);
        page.emit('request', b);
        clock.advance(700);
        page.emit('requestfinished', b);
      },
      { now: clock.now, timeoutMs: 1_000 }
    );
    expect(result.roundTripMs).toBe(700);
    expect(result.requestCount).toBe(2);
  });

  it('完了した往復を、完了した順にすべて返す(連続する Server Action の後ろの1つを選べるように)', async () => {
    const { page, clock, asPage } = setup();
    const first = fakeRequest('POST', { [SERVER_ACTION_HEADER]: '1' });
    const second = fakeRequest('POST', { [SERVER_ACTION_HEADER]: '2' });
    const result = await measureServerActionRoundTrip(
      asPage,
      async () => {
        page.emit('request', first);
        clock.advance(900);
        page.emit('requestfinished', first);
        page.emit('request', second);
        clock.advance(120);
        page.emit('requestfinished', second);
      },
      { now: clock.now, timeoutMs: 1_000 }
    );
    expect(result.roundTripsMs).toEqual([900, 120]);
    expect(result.roundTripMs).toBe(900);
  });

  it('Server Action でないリクエスト(GET・ヘッダ無しのPOST)は数えない', async () => {
    const { page, clock, asPage } = setup();
    const get = fakeRequest('GET', { [SERVER_ACTION_HEADER]: 'x' });
    const plainPost = fakeRequest('POST', {});
    const action = fakeRequest('POST', { [SERVER_ACTION_HEADER]: 'y' });
    const result = await measureServerActionRoundTrip(
      asPage,
      async () => {
        for (const r of [get, plainPost]) {
          page.emit('request', r);
          clock.advance(9_000);
          page.emit('requestfinished', r);
        }
        page.emit('request', action);
        clock.advance(50);
        page.emit('requestfinished', action);
      },
      { now: clock.now, timeoutMs: 1_000 }
    );
    expect(result.roundTripMs).toBe(50);
    expect(result.requestCount).toBe(1);
  });

  it('完了しなかった(失敗した)リクエストは往復として数えない', async () => {
    const { page, clock, asPage } = setup();
    const failed = fakeRequest('POST', { [SERVER_ACTION_HEADER]: 'f' });
    const ok = fakeRequest('POST', { [SERVER_ACTION_HEADER]: 'o' });
    const result = await measureServerActionRoundTrip(
      asPage,
      async () => {
        page.emit('request', failed);
        page.emit('requestfailed', failed);
        page.emit('request', ok);
        clock.advance(30);
        page.emit('requestfinished', ok);
      },
      { now: clock.now, timeoutMs: 1_000 }
    );
    expect(result.requestCount).toBe(1);
    expect(result.roundTripMs).toBe(30);
  });

  it('未知のリクエストの完了イベントは無視する', async () => {
    const { page, clock, asPage } = setup();
    const stranger = fakeRequest('POST', { [SERVER_ACTION_HEADER]: 's' });
    const ok = fakeRequest('POST', { [SERVER_ACTION_HEADER]: 'o' });
    const result = await measureServerActionRoundTrip(
      asPage,
      async () => {
        page.emit('requestfinished', stranger);
        page.emit('request', ok);
        clock.advance(20);
        page.emit('requestfinished', ok);
      },
      { now: clock.now, timeoutMs: 1_000 }
    );
    expect(result.roundTripMs).toBe(20);
  });

  it('Server Action が一度も完了しなければ、計測できなかったと失敗する', async () => {
    const { clock, asPage } = setup();
    await expect(
      measureServerActionRoundTrip(asPage, async () => undefined, { now: clock.now, timeoutMs: 50, pollMs: 5, sleep: async () => clock.advance(20) })
    ).rejects.toThrow(/Server Action/);
  });

  it('trigger が失敗しても、登録したリスナーを外す', async () => {
    const { page, clock, asPage } = setup();
    await expect(
      measureServerActionRoundTrip(
        asPage,
        async () => {
          throw new Error('boom');
        },
        { now: clock.now, timeoutMs: 10 }
      )
    ).rejects.toThrow('boom');
    expect(page.listenerCount('request')).toBe(0);
    expect(page.listenerCount('requestfinished')).toBe(0);
    expect(page.listenerCount('requestfailed')).toBe(0);
  });

  it('trigger 後に遅れて完了する往復を待つ', async () => {
    const { page, clock, asPage } = setup();
    const action = fakeRequest('POST', { [SERVER_ACTION_HEADER]: 'late' });
    const result = await measureServerActionRoundTrip(
      asPage,
      async () => {
        page.emit('request', action);
        setTimeout(() => {
          clock.advance(120);
          page.emit('requestfinished', action);
        }, 10);
      },
      { now: clock.now, timeoutMs: 1_000, pollMs: 5 }
    );
    expect(result.roundTripMs).toBe(120);
  });
});

describe('recordResponseTime', () => {
  it('共通のキーへ所要時間と操作名を入れる', () => {
    const ctx: Record<string, unknown> = {};
    recordResponseTime(ctx, 123, '操作');
    expect(ctx[RESPONSE_ELAPSED_KEY]).toBe(123);
    expect(ctx[RESPONSE_OPERATION_KEY]).toBe('操作');
  });
});
