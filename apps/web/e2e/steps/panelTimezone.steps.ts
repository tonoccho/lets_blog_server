import type { APIRequestContext, APIResponse, BrowserContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  expect,
  fetchAccessToken,
  loginViaKeycloak,
} from '../support';

/**
 * issue #1362(親issue #1261 分割A)のステップ定義。
 *
 * ダッシュボード(`ConnectedServiceStatusPanel`の管理者向け詳細診断)と
 * SSH鍵管理ページ(`SshKeyPairsPanel`)の日時表示が、個人設定TZ・ブラウザTZのいずれにも
 * 正しく従い、ハイドレーション不一致も出ないことを固定する。
 *
 * 「個人設定のタイムゾーンを「X」に変更する」「コンソールにハイドレーションエラーが
 * 記録されない」は media.steps.ts(issue #1236)が既に持つ汎用ステップをそのまま使う。
 * playwright-bdd のステップは全ステップ定義ファイル共通の単一レジストリで解決されるため、
 * 同名ステップを重複定義しない限り、別ファイルの feature から使ってよい
 * (fixtures.ts のコメント参照)。「コンソールにハイドレーションエラーが記録されない」は
 * `ctx.mediaTzConsoleErrors` を読むので、このファイルのブラウザTZ指定ステップも
 * 同じフィールド名へコンソールエラーを積む。
 */

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await adminToken(request)}` };
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

/**
 * `response.ok()`(2xx)だけでは本文がJSONである保証にならない。ステータスが2xxでも、
 * 未認証時のリダイレクト先(`/login`)がステータス200のHTMLを返すような経路(#1362の
 * 実装調査で`/api/dashboard/service-status/detail`が該当すると誤認して踏んだ)では
 * `response.json()`が`SyntaxError: Unexpected token '<'`という読み解きにくい失敗になる。
 * Content-Typeを先に見て、JSONで無ければ本文の先頭を添えた分かりやすいエラーにする。
 */
async function parseJsonOrThrow<T>(response: APIResponse, context: string): Promise<T> {
  const contentType = response.headers()['content-type'] ?? '';
  const text = await response.text();
  if (!contentType.includes('application/json')) {
    throw new Error(
      `${context}: JSON以外の応答が返りました (status=${response.status()}, `
        + `content-type=${contentType || '(無し)'}, url=${response.url()}): ${text.slice(0, 300)}`
    );
  }
  try {
    return JSON.parse(text) as T;
  } catch (err) {
    throw new Error(
      `${context}: 応答のJSON解析に失敗しました (status=${response.status()}, url=${response.url()}): `
        + `${text.slice(0, 300)} (${err instanceof Error ? err.message : String(err)})`
    );
  }
}

// ------------------------------------------------------- 個人設定TZの状態づくり

Given('個人設定のタイムゾーンを未設定にする', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const me = await request.get('/api/identity/me', { headers });
  expect(me.ok(), `自ユーザー情報の取得に失敗しました (status=${me.status()})`).toBe(true);
  const profile = await parseJsonOrThrow<{ locale: string | null; timezone: string | null }>(
    me,
    '自ユーザー情報の取得'
  );
  ctx.panelTzOriginalTimezone = profile.timezone;
  ctx.panelTzOriginalLocale = profile.locale;
  const response = await request.patch('/api/identity/me/preferences', {
    headers: { ...headers, 'Content-Type': 'application/json' },
    data: { locale: profile.locale ?? 'ja', timezone: null },
  });
  expect(
    response.ok(),
    `タイムゾーンの未設定化に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

// ------------------------------------------------------- ブラウザTZを指定してページを開く

const TARGET_PAGES: Record<string, { path: string; heading: string }> = {
  ダッシュボード: { path: '/', heading: 'ダッシュボード' },
  SSH鍵管理ページ: { path: '/admin/ssh-keys', heading: 'SSH鍵管理' },
};

/**
 * ブラウザTZは Playwright の `newContext({ timezoneId })` でしか指定できない(既存の`page`は
 * 生成済みのコンテキストに属し、後から変更できない)ため、media.steps.ts の #1236 と同じく
 * 専用の `BrowserContext` / `Page` を作り、その中でログインする。
 */
When(
  /^ブラウザのタイムゾーンを「([^」]+)」にして管理者としてログインし、(ダッシュボード|SSH鍵管理ページ)を開く$/,
  async ({ ctx, page }, timezoneId: string, targetName: string) => {
    const target = TARGET_PAGES[targetName];
    const browser = page.context().browser();
    if (!browser) {
      throw new Error('ブラウザインスタンスを取得できない(TZ指定のコンテキストを作成できない)');
    }
    const tzContext = await browser.newContext({ ignoreHTTPSErrors: true, timezoneId });
    const tzPage = await tzContext.newPage();
    const consoleErrors: string[] = [];
    tzPage.on('console', (msg) => {
      if (msg.type() === 'error') {
        consoleErrors.push(msg.text());
      }
    });
    tzPage.on('pageerror', (err) => {
      consoleErrors.push(err.message);
    });
    ctx.panelTzContext = tzContext;
    // media.steps.ts の既存Thenステップ「コンソールにハイドレーションエラーが記録されない」が
    // 読むフィールド名に合わせる(上のファイルコメント参照)。
    ctx.mediaTzConsoleErrors = consoleErrors;
    ctx.panelTzPage = tzPage;

    await loginViaKeycloak(tzPage, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
    await tzPage.goto(target.path, { waitUntil: 'networkidle' });
    await expect(tzPage.getByRole('heading', { name: target.heading })).toBeVisible({ timeout: 30_000 });
    // ハイドレーション後の再描画にも猶予を見る(media.steps.ts #1236 と同じ理由)。
    await tzPage.waitForTimeout(1000);
  }
);

// ------------------------------------------------------- AC1/AC2: 接続サービス詳細のチェック時刻

/**
 * `GET /api/dashboard/service-status/detail` はブラウザ/APIRequestContextから公開到達
 * できない(#1362の実装調査で判明)。`apiClient.ts`の`getConnectedServiceStatusDetail()`は
 * `server-only`で、Next.jsのサーバーコンポーネント(`page.tsx`)がSSR中に内部ゲートウェイURL
 * (`gatewayUrl()`)へ直接叩くための関数であり、ブラウザ向けの中継ルート
 * (`src/app/api/dashboard/service-status/route.ts`のような`route.ts`)がこのパスには存在
 * しない。素朴に`request.get('/api/dashboard/service-status/detail', ...)`を叩くと、
 * Next.jsの認証ミドルウェアが「一致するAPIルートが無い保護対象パス」として`/login`へ
 * 307リダイレクトし、`APIRequestContext`は既定でリダイレクトを追うため`response.ok()`は
 * true(ログイン画面のHTML、200)のまま返り、続く`.json()`が
 * `SyntaxError: Unexpected token '<'`という読み解きにくい失敗になる(実測で確認済み)。
 *
 * そのため、独立した基準値をAPIから取得する方式は採らず、このステップを実行している
 * 「今」を基準にした許容範囲(下記offsets)で換算値を照合する。`checkedAt`は
 * ページのSSR描画時に記録されたヘルスチェック実行時刻で、このThenステップの実行時点
 * より必ず過去(ログイン・画面遷移・クリック分の遅延がある)なので、範囲は過去方向にだけ
 * 広く取る。
 *
 * **この許容範囲がTZの取り違えを見逃さない理由**: このシナリオ群が使う3つのTZ
 * (Asia/Tokyo・Pacific/Auckland・コンテナの既定であるUTC)は、どの2つを取っても
 * オフセット差が3時間(180分)以上ある。許容範囲は「今から過去120秒」(1秒刻みで120個の候補)
 * に過ぎず、実際の壁時計表示で3時間以上のずれを生む取り違えを「今から120秒以内」の表示と
 * 誤って一致させることはできない(現実のIANAタイムゾーンのオフセットは15分刻みが最小で、
 * 120秒の範囲に別のゾーンの表示が入り込むことはない)。
 *
 * 候補は表示の秒の桁まで(`toLocaleString`の既定形式)含めて比較するため、候補の間隔は
 * 表示の最小単位である1秒でなければならない。5秒刻みにしていた版では、実際の値が
 * 候補の間(例: 候補が...48/43で実際は46)に落ちて誤って不一致(false RED)になったことを
 * 実測で確認したため、1秒刻みに修正した。
 */
const CHECKED_AT_TOLERANCE_WINDOW_SECONDS = 120;

Then(
  /^接続サービス詳細のチェック時刻が「([^」]+)」への換算値と一致する$/,
  async ({ ctx }, timeZone: string) => {
    const tzPage = ctx.panelTzPage as Page;
    await tzPage.getByText('管理者向け詳細診断').click();
    const cell = tzPage.locator('table tbody tr').first().locator('td').last();
    await expect(cell).toBeVisible({ timeout: 10_000 });
    const displayed = (await cell.textContent())?.trim() ?? '';

    const now = Date.now();
    const candidates = Array.from({ length: CHECKED_AT_TOLERANCE_WINDOW_SECONDS }, (_, i) =>
      new Date(now - i * 1_000).toLocaleString('ja-JP', { timeZone })
    );
    expect(
      candidates,
      `表示された「${displayed}」が${timeZone}換算の直近${CHECKED_AT_TOLERANCE_WINDOW_SECONDS}秒以内の`
        + `いずれの候補とも一致しません: ${candidates.join(' / ')}`
    ).toContain(displayed);
  }
);

// ------------------------------------------------------- AC1/AC2: SSH鍵ペアの作成日時

interface TzKeyPairFixture {
  id: number;
  name: string;
  createdAt: string;
}

/**
 * 生成レスポンス(POST)がエコーする`createdAt`は、実際に永続化された値と1秒程度ずれ得る
 * (実測で確認済み: `POST /api/ssh-key-pairs`が返す値と、直後の`GET /api/ssh-key-pairs`が
 * 返す同じ行の値が1秒差になるケースがあった。エンティティの生成時刻がDB書き込み確定時に
 * 別途確定する構成と推測される)。UIが実際に描画する値は`SshKeyPairsPanel`の`keyPairs`
 * prop、すなわち`listSshKeyPairs()`(`GET /api/ssh-key-pairs`)が返す値なので、期待値は
 * 必ずこちらから取り直す。POSTのレスポンスはid特定にのみ使う。
 */
Given('TZ検証用のSSH鍵ペアが1件登録されている', async ({ request, ctx }) => {
  const headers = await adminHeaders(request);
  const name = `e2e-1362-tz-${uniqueSuffix()}`;
  const createResponse = await request.post('/api/ssh-key-pairs', {
    headers,
    data: { name },
  });
  expect(
    createResponse.ok(),
    `TZ検証用のSSH鍵ペア生成に失敗しました (status=${createResponse.status()}): ${await createResponse.text()}`
  ).toBe(true);
  const created = await parseJsonOrThrow<{ id: number; name: string }>(
    createResponse,
    'TZ検証用のSSH鍵ペア生成'
  );

  const listResponse = await request.get('/api/ssh-key-pairs', { headers });
  expect(
    listResponse.ok(),
    `SSH鍵ペア一覧の取得に失敗しました (status=${listResponse.status()}): ${await listResponse.text()}`
  ).toBe(true);
  const items = await parseJsonOrThrow<{ id: number; name: string; createdAt: string }[]>(
    listResponse,
    'SSH鍵ペア一覧の取得'
  );
  const persisted = items.find((item) => item.id === created.id);
  expect(persisted, `生成したSSH鍵ペア(id=${created.id})が一覧に見つかりません`).toBeTruthy();

  const fixture: TzKeyPairFixture = {
    id: created.id,
    name: created.name,
    createdAt: (persisted as { createdAt: string }).createdAt,
  };
  ctx.panelTzKeyPair = fixture;
});

/** バックエンドの`createdAt`はオフセット無し(`LocalDateTime`)。#1236と同じくUTCとして扱う。 */
function withUtcOffsetIfMissing(iso: string): string {
  const timePart = iso.includes('T') ? iso.slice(iso.indexOf('T') + 1) : iso;
  const hasOffset = /[Zz]$/.test(timePart) || /[+-]\d{2}:?\d{2}$/.test(timePart);
  return hasOffset ? iso : `${iso}Z`;
}

Then(
  /^そのSSH鍵ペアの作成日時が「([^」]+)」への換算値と一致する$/,
  async ({ ctx }, timeZone: string) => {
    const tzPage = ctx.panelTzPage as Page;
    const fixture = ctx.panelTzKeyPair as TzKeyPairFixture;
    const row = tzPage.locator(`tr:has-text("${fixture.name}")`);
    await expect(row).toBeVisible({ timeout: 10_000 });
    const displayed = (await row.locator('td').nth(2).textContent())?.trim() ?? '';

    const expected = new Date(withUtcOffsetIfMissing(fixture.createdAt)).toLocaleString('ja-JP', {
      timeZone,
    });
    expect(displayed).toBe(expected);
  }
);

// ------------------------------------------------------- 後片付け

After({ tags: '@panel-timezone' }, async ({ ctx, request }) => {
  const tzContext = ctx.panelTzContext as BrowserContext | undefined;
  if (tzContext) {
    await tzContext.close();
  }

  const headers = await adminHeaders(request);
  const fixture = ctx.panelTzKeyPair as TzKeyPairFixture | undefined;
  if (fixture) {
    await request.delete(`/api/ssh-key-pairs/${fixture.id}`, { headers });
  }

  // 「個人設定のタイムゾーンを「X」に変更する」(media.steps.ts)、または本ファイルの
  // 「個人設定のタイムゾーンを未設定にする」のどちらで変更していても、元へ戻す。
  if (ctx.mediaOriginalTimezone !== undefined) {
    await request.patch('/api/identity/me/preferences', {
      headers: { ...headers, 'Content-Type': 'application/json' },
      data: {
        locale: (ctx.mediaOriginalLocale as string | null) ?? 'ja',
        timezone: (ctx.mediaOriginalTimezone as string | null) ?? 'Asia/Tokyo',
      },
    });
  } else if (ctx.panelTzOriginalTimezone !== undefined) {
    await request.patch('/api/identity/me/preferences', {
      headers: { ...headers, 'Content-Type': 'application/json' },
      data: {
        locale: (ctx.panelTzOriginalLocale as string | null) ?? 'ja',
        timezone: (ctx.panelTzOriginalTimezone as string | null) ?? 'Asia/Tokyo',
      },
    });
  }
});
