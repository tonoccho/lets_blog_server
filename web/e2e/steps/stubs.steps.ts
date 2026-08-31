import { Before, Given, Then, When } from './fixtures';
import { expect } from '../support';
import {
  ALL_STUBS,
  STUB_URLS,
  type StubName,
  forceStatusHeader,
  requireStubs,
} from '../support/stubs';

/**
 * 外部依存スタブ自体の受け入れテスト(issue #928 / AT-2)。
 *
 * スタブは「受け入れテストの土台」であって製品機能ではない。だが土台が壊れると、
 * それに乗る AT-8 / AT-9 / AT-10 / AT-13 のシナリオが**理由の分からない形で**落ちる。
 * 決定性とエラー注入という2つの約束をここで守らせる。
 */

/**
 * `@stub` が付いたシナリオは、スタブが起動していなければスキップではなく失敗させる
 * (#843 の「恒常スキップで未検証」の再発防止)。全 `@stub` シナリオに効く。
 */
Before({ tags: '@stub' }, async () => {
  await requireStubs();
});

/** 直前の応答本文。決定性の比較に使う。 */
const responses = new Map<string, string[]>();

/** 各スタブの「読み取り専用で決定的な」代表リクエスト。 */
const PROBES: Record<StubName, { path: string; method: string; body?: unknown; headers?: Record<string, string> }> = {
  llm: {
    path: '/chat/completions',
    method: 'POST',
    body: { messages: [{ role: 'user', content: 'カスタムタグを作ってください' }] },
  },
  'google-analytics': {
    path: '/v1beta/properties/123456789:runReport',
    method: 'POST',
    body: { metrics: [{ name: 'activeUsers' }, { name: 'screenPageViews' }], dimensions: [{ name: 'date' }] },
  },
  adsense: {
    path: '/v2/accounts/pub-e2e/reports:generate?dateRange=LAST_7_DAYS&dimensions=DATE',
    method: 'GET',
  },
  'brave-search': {
    path: '/res/v1/web/search?q=let%27s%20blog&count=3',
    method: 'GET',
    headers: { 'X-Subscription-Token': 'e2e-stub-key' },
  },
  'openai-image': {
    path: '/images/generations',
    method: 'POST',
    body: { model: 'gpt-image-1', prompt: 'a blue button', n: 2, size: '1024x1024' },
  },
  github: {
    path: '/repos/e2e-stub/acceptance/issues?state=open&per_page=100',
    method: 'GET',
    headers: { Authorization: 'Bearer e2e-stub-token' },
  },
};

async function callProbe(
  name: StubName,
  extraHeaders: Record<string, string> = {}
): Promise<{ status: number; body: string; headers: Headers }> {
  const probe = PROBES[name];
  const res = await fetch(`${STUB_URLS[name]}${probe.path}`, {
    method: probe.method,
    headers: {
      ...(probe.body ? { 'Content-Type': 'application/json' } : {}),
      ...(probe.headers ?? {}),
      ...extraHeaders,
    },
    body: probe.body ? JSON.stringify(probe.body) : undefined,
  });
  return { status: res.status, body: await res.text(), headers: res.headers };
}

Given('全ての外部依存スタブが起動している', async () => {
  await requireStubs();
  responses.clear();
});

When('各スタブへ同じリクエストを3回送る', async () => {
  for (const name of ALL_STUBS) {
    const bodies: string[] = [];
    for (let i = 0; i < 3; i += 1) {
      const { status, body } = await callProbe(name);
      expect(status, `${name} スタブが 200 を返さなかった`).toBe(200);
      bodies.push(body);
    }
    responses.set(name, bodies);
  }
});

Then('全てのスタブが3回とも同一の応答を返す', async () => {
  for (const name of ALL_STUBS) {
    const bodies = responses.get(name);
    expect(bodies, `${name} の応答が記録されていない`).toHaveLength(3);
    expect(bodies![1], `${name} スタブの応答が1回目と2回目で異なる`).toBe(bodies![0]);
    expect(bodies![2], `${name} スタブの応答が1回目と3回目で異なる`).toBe(bodies![0]);
  }
});

/** 注入した応答。ヘッダ経由なのでスタブの状態は変わらず、並列実行に干渉しない。 */
const injected = new Map<StubName, { status: number; headers: Headers }>();

When('各スタブに {int} を注入する', async ({}, status: number) => {
  injected.clear();
  for (const name of ALL_STUBS) {
    const res = await callProbe(name, forceStatusHeader(status));
    injected.set(name, { status: res.status, headers: res.headers });
  }
});

Then('各スタブが注入した {int} を返す', async ({}, status: number) => {
  for (const name of ALL_STUBS) {
    const res = injected.get(name);
    expect(res, `${name} スタブの応答が記録されていない`).toBeDefined();
    expect(res!.status, `${name} スタブが注入した ${status} を返さなかった`).toBe(status);
  }
});

Then('注入していないリクエストは正常に戻る', async () => {
  for (const name of ALL_STUBS) {
    const res = await callProbe(name);
    expect(res.status, `${name} スタブが注入なしのリクエストで 200 を返さなかった`).toBe(200);
  }
});

Then('レート制限のエラーには Retry-After が付く', async () => {
  for (const name of ALL_STUBS) {
    const res = injected.get(name);
    expect(res!.headers.get('retry-after'), `${name} スタブに Retry-After が無い`).toBe('1');
  }
});

Then('タイムアウトを注入すると呼び出しは応答を得られない', async () => {
  // 遅延中に接続を切る。呼び元(サービス)からはリードタイムアウトとして観測される。
  await expect(
    callProbe('llm', { 'X-E2E-Stub-Force-Delay': '300' })
  ).rejects.toThrow();
});
