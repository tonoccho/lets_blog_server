import type { Page, Request } from '@playwright/test';

/**
 * issue #1476: 応答時間(3秒予算)の共通計測ヘルパー。
 *
 * 元は `steps/customTag.steps.ts` のカスタムタグ専用の計測(#938 / AT-12)で、他の画面から
 * 使えなかった。画面に依存しない形へ移し、`responseBudget.steps.ts`(共通ステップ)と
 * 各画面のステップ定義の両方から使う。
 *
 * このモジュールはステップを登録しない(jest の単体テスト `e2e/responseBudget.test.ts` が
 * 副作用なしに import できるように、プレーンなモジュールにしてある)。
 *
 * ## 計測点は2種類
 *
 * 1. **画面の初回表示**({@link measureFirstDisplay}): 1回目の遷移は Next.js(devモード)の
 *    ルートコンパイルを含むので捨て、2回目の `page.goto` だけを測る。受け入れテスト環境が
 *    `npm run dev` で動くこと(playwright.config.ts)がこのウォームアップの前提。
 * 2. **Server Action の往復**({@link measureServerActionRoundTrip}): `retryClick` 系の
 *    ヘルパー(`clickUntilVisible` 等)を通る操作は、ハイドレーション前の空振りを撃ち直すため、
 *    クリックから期待結果までの経過時間に「再試行回数 x 可視待ちタイムアウト」が混入し、
 *    応答時間の指標にならない。そこで**サーバへ実際に送られた Server Action の POST の往復**
 *    (リクエスト送出から本文受信完了まで)を測る。空振りしたクリックは何も送らないので、
 *    再試行分は構造的に含まれない。複数回送られた場合は最も遅い1往復を返す(予算は1操作の
 *    最悪の応答を縛るため)。
 *    `page.on('request')` を選んだのは、「最後のクリックから期待結果まで」だと、可視待ちの
 *    Reactの再描画時間まで含めて測ってしまい、かつ最後のクリックがどれかを知る手段が無いため。
 */

/** Next.js が Server Action の POST に付けるヘッダ。 */
export const SERVER_ACTION_HEADER = 'next-action';

export type Clock = () => number;

export function formatBudgetMessage(operation: string, elapsedMs: number, limitMs: number): string {
  return `${operation}が ${limitMs}ms を超えました(実測 ${elapsedMs}ms)`;
}

/** 実測が上限未満であることを確かめる。上限ちょうどは超過扱い(従来の `toBeLessThan` と同じ)。 */
export function assertWithinBudget(elapsedMs: number, limitMs: number, operation: string): void {
  if (!(elapsedMs < limitMs)) {
    throw new Error(formatBudgetMessage(operation, elapsedMs, limitMs));
  }
}

/** 画面の初回表示の所要時間(ms)。1回目の遷移はウォームアップとして計測しない。 */
export async function measureFirstDisplay(page: Page, url: string, now: Clock = Date.now): Promise<number> {
  await page.goto(url);
  const startedAt = now();
  await page.goto(url);
  return now() - startedAt;
}

export interface ServerActionTiming {
  /** 最も遅かった Server Action の往復(ms)。 */
  roundTripMs: number;
  /** 完了した Server Action の数。 */
  requestCount: number;
}

export interface ServerActionMeasureOptions {
  now?: Clock;
  /** trigger の後、Server Action の完了を待つ上限(ms)。 */
  timeoutMs?: number;
  pollMs?: number;
  sleep?: (ms: number) => Promise<void>;
}

function isServerAction(request: Request): boolean {
  return request.method() === 'POST' && SERVER_ACTION_HEADER in request.headers();
}

/**
 * `trigger` を実行し、その間に送られた Server Action の往復時間を測る。
 * `trigger` の所要時間(`retryClick` の再試行を含む)は結果に含まれない。
 */
export async function measureServerActionRoundTrip(
  page: Page,
  trigger: () => Promise<void>,
  options: ServerActionMeasureOptions = {}
): Promise<ServerActionTiming> {
  const now = options.now ?? Date.now;
  const timeoutMs = options.timeoutMs ?? 30_000;
  const pollMs = options.pollMs ?? 50;
  const sleep = options.sleep ?? ((ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms)));

  const inFlight = new Map<Request, number>();
  const roundTrips: number[] = [];

  const onRequest = (request: Request): void => {
    if (isServerAction(request)) inFlight.set(request, now());
  };
  const onFinished = (request: Request): void => {
    const startedAt = inFlight.get(request);
    if (startedAt === undefined) return;
    inFlight.delete(request);
    roundTrips.push(now() - startedAt);
  };
  const onFailed = (request: Request): void => {
    inFlight.delete(request);
  };

  page.on('request', onRequest);
  page.on('requestfinished', onFinished);
  page.on('requestfailed', onFailed);
  try {
    await trigger();
    const waitStartedAt = now();
    while ((roundTrips.length === 0 || inFlight.size > 0) && now() - waitStartedAt < timeoutMs) {
      await sleep(pollMs);
    }
  } finally {
    page.off('request', onRequest);
    page.off('requestfinished', onFinished);
    page.off('requestfailed', onFailed);
  }

  if (roundTrips.length === 0) {
    throw new Error('Server Action の往復を計測できませんでした(操作が Server Action を送っていません)');
  }
  return { roundTripMs: Math.max(...roundTrips), requestCount: roundTrips.length };
}

export const RESPONSE_ELAPSED_KEY = 'responseBudgetElapsedMs';
export const RESPONSE_OPERATION_KEY = 'responseBudgetOperation';

/**
 * 計測した所要時間(ms)をシナリオの入れ物(`ctx`)の**共通のキー**へ記録する。
 * 各画面のステップ定義は自分の操作を測ったあとこれを呼び、`ならば` 側は共通ステップ
 * (`responseBudget.steps.ts`)が読む。画面固有のキーを作らないための入口。
 */
export function recordResponseTime(ctx: Record<string, unknown>, elapsedMs: number, operation: string): void {
  ctx[RESPONSE_ELAPSED_KEY] = elapsedMs;
  ctx[RESPONSE_OPERATION_KEY] = operation;
}
