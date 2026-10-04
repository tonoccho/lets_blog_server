import type { APIRequestContext, APIResponse, BrowserContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import type { ScenarioContext } from './fixtures';
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

/**
 * `path`を関数にしてあるのは、`/projects/${projectId}/posts`のような動的パスを
 * 静的な文字列で持てないため。`pageInventory.ts:63`の`path: (f) => ...`の慣習(issue #944)
 * に合わせ、シナリオ間の値渡し(`ctx`)から必要な値を都度組み立てる
 * (issue #1363、親issue #1261 分割B)。
 */
const TARGET_PAGES: Record<string, { path: (ctx: ScenarioContext) => string; heading: string }> = {
  ダッシュボード: { path: () => '/', heading: 'ダッシュボード' },
  SSH鍵管理ページ: { path: () => '/admin/ssh-keys', heading: 'SSH鍵管理' },
  // media.steps.ts が issue #1236 で既に「...生成画像ギャラリーを開く」という同名の
  // Whenステップを持つため、targetNameは末尾に「画面」を付けて区別する
  // (bddgen: "Multiple definitions matched scenario step" を実測で確認済み)。
  生成画像ギャラリー画面: { path: () => '/image-gallery', heading: '生成画像ギャラリー' },
  サイト一覧: { path: () => '/sites', heading: 'サイト' },
  // `publishLifecycle.steps.ts`の「公開検証用のWordPressサイトがあり、プロジェクトの
  // テスト環境に紐づいている」が設定する`ctx.publishProjectId`を使う。
  投稿履歴: {
    path: (ctx) => `/projects/${ctx.publishProjectId as number}/posts`,
    heading: '投稿履歴',
  },
  // issue #1364(親issue #1261 分割C)。`users/page.tsx:30`・`projects/page.tsx:14`の見出し。
  ユーザー管理: { path: () => '/users', heading: 'ユーザー管理' },
  プロジェクト: { path: () => '/projects', heading: 'プロジェクト' },
  // issue #1260。`operation-logs/page.tsx`の見出し。
  操作ログ: { path: () => '/operation-logs', heading: '操作ログ' },
  // トップレベル`/posts`一覧(`posts/page.tsx:15`の見出しは`投稿履歴`だが、`TARGET_PAGES`の
  // キーが既存の`投稿履歴`(`/projects/{id}/posts`)と重複しないよう区別する)。
  投稿履歴一覧: { path: () => '/posts', heading: '投稿履歴' },
};

/**
 * ブラウザTZは Playwright の `newContext({ timezoneId })` でしか指定できない(既存の`page`は
 * 生成済みのコンテキストに属し、後から変更できない)ため、media.steps.ts の #1236 と同じく
 * 専用の `BrowserContext` / `Page` を作り、その中でログインする。
 */
When(
  /^ブラウザのタイムゾーンを「([^」]+)」にして管理者としてログインし、(ダッシュボード|SSH鍵管理ページ|生成画像ギャラリー画面|サイト一覧|投稿履歴一覧|投稿履歴|ユーザー管理|プロジェクト|操作ログ)を開く$/,
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
    await tzPage.goto(target.path(ctx), { waitUntil: 'networkidle' });
    // `level: 1` で見出しレベルを絞る。`/sites`は「サイト」(h1)と「サイトを登録」(h2、
    // SiteCreationPanel)の両方を`name`の部分一致(既定)が拾ってしまい、
    // `strict mode violation: ... resolved to 2 elements`で落ちることを実測で確認した
    // (issue #1363)。`/projects`も同様に「プロジェクト」(h1)と「プロジェクトを作成」
    // (h2、`ProjectForm.tsx:31`)が部分一致で衝突する(issue #1364)。対象8画面はいずれも
    // ページ本体の見出しがh1であるため(`page.tsx`各ファイルの`<h1>`を確認済み)、
    // 全エントリに一律で付けてよい。
    await expect(tzPage.getByRole('heading', { name: target.heading, level: 1 })).toBeVisible({
      timeout: 30_000,
    });
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

// ------------------------------------------------------- issue #1363: 生成画像ギャラリーの作成日時

/**
 * ギャラリーのフィクスチャ自体は media.steps.ts の既存Given「seedを持たないChatGPT画像が
 * ギャラリーにある」をシナリオ側でそのまま再利用する(`ctx.mediaImageId`が立つ)。
 * ここではその画像の永続化された`createdAt`を取り直し(SSH鍵ペアと同じ理由。POST直後の
 * エコー値は1秒程度ずれ得る)、一覧のキャプションに表示された値と突き合わせる。
 */
Then(
  /^生成画像ギャラリーのその画像の作成日時が「([^」]+)」への換算値と一致する$/,
  async ({ ctx, request }, timeZone: string) => {
    const tzPage = ctx.panelTzPage as Page;
    const imageId = ctx.mediaImageId as number;
    const headers = await adminHeaders(request);

    const listResponse = await request.get('/api/generated-images', { headers });
    expect(
      listResponse.ok(),
      `生成画像一覧の取得に失敗しました (status=${listResponse.status()}): ${await listResponse.text()}`
    ).toBe(true);
    const items = await parseJsonOrThrow<{ id: number; createdAt: string }[]>(
      listResponse,
      '生成画像一覧の取得'
    );
    const persisted = items.find((item) => item.id === imageId);
    expect(persisted, `生成画像(id=${imageId})が一覧に見つかりません`).toBeTruthy();
    const createdAt = (persisted as { createdAt: string }).createdAt;

    const card = tzPage.locator(`button:has(img[src="/image-gallery/${imageId}/file"])`);
    await expect(card).toBeVisible({ timeout: 10_000 });
    const displayed = (await card.locator('p.text-neutral-400').textContent())?.trim() ?? '';

    const expected = new Date(withUtcOffsetIfMissing(createdAt)).toLocaleString('ja-JP', { timeZone });
    expect(displayed).toBe(expected);
  }
);

// ------------------------------------------------------- issue #1363: サイト一覧の登録日

interface TzSiteFixture {
  id: number;
  siteKey: string;
  createdAt: string;
}

/**
 * `cross-cutting.steps.ts`の`registerSiteFixture`(issue #830)と同じ最小限のボディで
 * サイトを登録する。POSTのレスポンスは`createdAt`を返さないため、SSH鍵ペアと同じく
 * `GET /api/sites`で永続化された値を取り直す。
 */
Given('TZ検証用のサイトが1件登録されている', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const siteKey = `e2e-1363-tz-${uniqueSuffix()}`;
  const createResponse = await request.post('/api/sites', {
    headers,
    data: {
      name: `TZ検証用サイト ${siteKey}`,
      siteKey,
      cmsType: 'WORDPRESS',
      credentials: {
        transport: 'AGENT',
        baseUrl: 'http://wordpress',
        username: 'e2e-1363-fixture',
      },
    },
  });
  expect(
    createResponse.ok(),
    `TZ検証用のサイト登録に失敗しました (status=${createResponse.status()}): ${await createResponse.text()}`
  ).toBe(true);
  const created = await parseJsonOrThrow<{ id: number }>(createResponse, 'TZ検証用のサイト登録');

  const listResponse = await request.get('/api/sites', { headers });
  expect(
    listResponse.ok(),
    `サイト一覧の取得に失敗しました (status=${listResponse.status()}): ${await listResponse.text()}`
  ).toBe(true);
  const items = await parseJsonOrThrow<{ id: number; siteKey: string; createdAt: string }[]>(
    listResponse,
    'サイト一覧の取得'
  );
  const persisted = items.find((item) => item.id === created.id);
  expect(persisted, `登録したサイト(id=${created.id})が一覧に見つかりません`).toBeTruthy();

  const fixture: TzSiteFixture = {
    id: created.id,
    siteKey,
    createdAt: (persisted as { createdAt: string }).createdAt,
  };
  ctx.panelTzSite = fixture;
});

Then(
  /^そのサイトの登録日が「([^」]+)」への換算値と一致する$/,
  async ({ ctx }, timeZone: string) => {
    const tzPage = ctx.panelTzPage as Page;
    const fixture = ctx.panelTzSite as TzSiteFixture;
    const row = tzPage.locator(`tr:has-text("${fixture.siteKey}")`);
    await expect(row).toBeVisible({ timeout: 10_000 });
    const displayed = (await row.locator('td').nth(5).textContent())?.trim() ?? '';

    const expected = new Date(withUtcOffsetIfMissing(fixture.createdAt)).toLocaleString('ja-JP', {
      timeZone,
    });
    expect(displayed).toBe(expected);
  }
);

// ------------------------------------------------------- issue #1363: 投稿履歴の最終投稿日時

/**
 * 投稿フィクスチャ自体は publishLifecycle.steps.ts の既存Given/When
 * 「公開検証用のWordPressサイトがあり、プロジェクトのテスト環境に紐づいている」
 * 「記事を新規公開する」をシナリオ側でそのまま再利用する(`ctx.publishProjectId`
 * `ctx.newPostSlug`が立つ)。`lastPublishedAt`は公開レスポンスに含まれないため、
 * `GET /api/posts`から取り直す(SSH鍵ペア・サイトと同じ理由)。
 */
Then(
  /^その投稿の最終投稿日時が「([^」]+)」への換算値と一致する$/,
  async ({ ctx, request }, timeZone: string) => {
    const tzPage = ctx.panelTzPage as Page;
    const slug = ctx.newPostSlug as string;
    const headers = await adminHeaders(request);

    const listResponse = await request.get('/api/posts', { headers });
    expect(
      listResponse.ok(),
      `投稿一覧の取得に失敗しました (status=${listResponse.status()}): ${await listResponse.text()}`
    ).toBe(true);
    const items = await parseJsonOrThrow<{ slug: string | null; lastPublishedAt: string | null }[]>(
      listResponse,
      '投稿一覧の取得'
    );
    const persisted = items.find((item) => item.slug === slug);
    expect(persisted, `投稿(slug=${slug})が一覧に見つかりません`).toBeTruthy();
    const lastPublishedAt = (persisted as { lastPublishedAt: string | null }).lastPublishedAt;
    expect(lastPublishedAt, `投稿(slug=${slug})にlastPublishedAtがありません`).toBeTruthy();

    const row = tzPage.locator(`tr:has-text("${slug}")`);
    await expect(row).toBeVisible({ timeout: 10_000 });
    const displayed = (await row.locator('td').nth(5).textContent())?.trim() ?? '';

    const expected = new Date(withUtcOffsetIfMissing(lastPublishedAt as string)).toLocaleString('ja-JP', {
      timeZone,
    });
    expect(displayed).toBe(expected);
  }
);

// ------------------------------------------------------- issue #1364: ユーザー一覧の登録日

interface TzUserFixture {
  id: number;
  email: string;
  createdAt: string;
}

/**
 * `userManagement.steps.ts`等の`POST /api/users`パターン(issue #1159)を踏襲する。
 * POSTのレスポンスは`createdAt`を返すが、SSH鍵ペア・サイトと同じ理由(1秒程度ずれ得る)で
 * `GET /api/users`から永続化された値を取り直す。
 */
Given('TZ検証用のユーザーが1件登録されている', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const suffix = uniqueSuffix();
  const email = `e2e-1364-tz-${suffix}@example.com`;
  const createResponse = await request.post('/api/users', {
    headers,
    data: { email, password: `E2e1364Tz!${suffix}`, role: 'user' },
  });
  expect(
    createResponse.ok(),
    `TZ検証用のユーザー登録に失敗しました (status=${createResponse.status()}): ${await createResponse.text()}`
  ).toBe(true);
  const created = await parseJsonOrThrow<{ id: number; email: string }>(createResponse, 'TZ検証用のユーザー登録');

  const listResponse = await request.get('/api/users', { headers });
  expect(
    listResponse.ok(),
    `ユーザー一覧の取得に失敗しました (status=${listResponse.status()}): ${await listResponse.text()}`
  ).toBe(true);
  const items = await parseJsonOrThrow<{ id: number; email: string; createdAt: string }[]>(
    listResponse,
    'ユーザー一覧の取得'
  );
  const persisted = items.find((item) => item.id === created.id);
  expect(persisted, `登録したユーザー(id=${created.id})が一覧に見つかりません`).toBeTruthy();

  const fixture: TzUserFixture = {
    id: created.id,
    email,
    createdAt: (persisted as { createdAt: string }).createdAt,
  };
  ctx.panelTzUser = fixture;
});

Then(
  /^そのユーザーの登録日が「([^」]+)」への換算値と一致する$/,
  async ({ ctx }, timeZone: string) => {
    const tzPage = ctx.panelTzPage as Page;
    const fixture = ctx.panelTzUser as TzUserFixture;
    const row = tzPage.locator(`tr:has-text("${fixture.email}")`);
    await expect(row).toBeVisible({ timeout: 10_000 });
    // `users/page.tsx`の列構成: 参加プロジェクト(0)・メールアドレス(1)・権限(2)・登録日(3)。
    const displayed = (await row.locator('td').nth(3).textContent())?.trim() ?? '';

    const expected = new Date(withUtcOffsetIfMissing(fixture.createdAt)).toLocaleString('ja-JP', {
      timeZone,
    });
    expect(displayed).toBe(expected);
  }
);

// ------------------------------------------------------- issue #1364: プロジェクト一覧の作成日

interface TzProjectFixture {
  id: number;
  slug: string;
  createdAt: string;
}

/**
 * `diagram.steps.ts`等の`POST /api/projects`パターン(issue #937)を踏襲する。POSTの
 * レスポンスは`createdAt`を返すが、他のフィクスチャと同じ理由で`GET /api/projects`から
 * 永続化された値を取り直す。
 */
Given('TZ検証用のプロジェクトが1件登録されている', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const suffix = uniqueSuffix();
  const slug = `e2e-1364-tz-${suffix}`;
  const createResponse = await request.post('/api/projects', {
    headers,
    data: { name: `TZ検証用プロジェクト ${suffix}`, slug },
  });
  expect(
    createResponse.ok(),
    `TZ検証用のプロジェクト作成に失敗しました (status=${createResponse.status()}): ${await createResponse.text()}`
  ).toBe(true);
  const created = await parseJsonOrThrow<{ id: number }>(createResponse, 'TZ検証用のプロジェクト作成');

  const listResponse = await request.get('/api/projects', { headers });
  expect(
    listResponse.ok(),
    `プロジェクト一覧の取得に失敗しました (status=${listResponse.status()}): ${await listResponse.text()}`
  ).toBe(true);
  const items = await parseJsonOrThrow<{ id: number; slug: string; createdAt: string }[]>(
    listResponse,
    'プロジェクト一覧の取得'
  );
  const persisted = items.find((item) => item.id === created.id);
  expect(persisted, `作成したプロジェクト(id=${created.id})が一覧に見つかりません`).toBeTruthy();

  const fixture: TzProjectFixture = {
    id: created.id,
    slug,
    createdAt: (persisted as { createdAt: string }).createdAt,
  };
  ctx.panelTzProject = fixture;
});

Then(
  /^そのプロジェクトの作成日が「([^」]+)」への換算値と一致する$/,
  async ({ ctx }, timeZone: string) => {
    const tzPage = ctx.panelTzPage as Page;
    const fixture = ctx.panelTzProject as TzProjectFixture;
    const row = tzPage.locator(`tr:has-text("${fixture.slug}")`);
    await expect(row).toBeVisible({ timeout: 10_000 });
    // `projects/page.tsx`の列構成: 名前(0)・slug(1)・環境(2)・作成日(3)。
    const displayed = (await row.locator('td').nth(3).textContent())?.trim() ?? '';

    const expected = new Date(withUtcOffsetIfMissing(fixture.createdAt)).toLocaleString('ja-JP', {
      timeZone,
    });
    expect(displayed).toBe(expected);
  }
);

// ------------------------------------------------------- issue #1364: 投稿一覧(トップレベル/posts)の最終投稿日時

/**
 * 投稿フィクスチャ自体は分割B(issue #1363)と同じくpublishLifecycle.steps.tsの既存
 * Given/When「公開検証用のWordPressサイトがあり、プロジェクトのテスト環境に紐づいている」
 * 「記事を新規公開する」をシナリオ側でそのまま再利用する(`ctx.publishProjectId`
 * `ctx.newPostSlug`が立つ)。トップレベル`/posts`は`GET /api/posts`(全プロジェクト横断)を
 * 見るため、`/projects/{id}/posts`向けの`その投稿の最終投稿日時が...`と同じ考え方で
 * `lastPublishedAt`を取り直すが、`posts/page.tsx`はカテゴリ列を持たないため列位置が異なる
 * (末尾の列がそのまま最終投稿日時)。列構成の違いを理由に別のThenステップとして用意する。
 */
Then(
  /^投稿一覧のその投稿の最終投稿日時が「([^」]+)」への換算値と一致する$/,
  async ({ ctx, request }, timeZone: string) => {
    const tzPage = ctx.panelTzPage as Page;
    const slug = ctx.newPostSlug as string;
    const headers = await adminHeaders(request);

    const listResponse = await request.get('/api/posts', { headers });
    expect(
      listResponse.ok(),
      `投稿一覧の取得に失敗しました (status=${listResponse.status()}): ${await listResponse.text()}`
    ).toBe(true);
    const items = await parseJsonOrThrow<{ slug: string | null; lastPublishedAt: string | null }[]>(
      listResponse,
      '投稿一覧の取得'
    );
    const persisted = items.find((item) => item.slug === slug);
    expect(persisted, `投稿(slug=${slug})が一覧に見つかりません`).toBeTruthy();
    const lastPublishedAt = (persisted as { lastPublishedAt: string | null }).lastPublishedAt;
    expect(lastPublishedAt, `投稿(slug=${slug})にlastPublishedAtがありません`).toBeTruthy();

    // `posts/page.tsx`の列構成: サイト(0)・WP投稿ID(1)・スラッグ(2)・ステータス(3)・
    // 最終投稿日時(4、カテゴリ列が無いため末尾)。
    const row = tzPage.locator(`tr:has-text("${slug}")`);
    await expect(row).toBeVisible({ timeout: 10_000 });
    const displayed = (await row.locator('td').last().textContent())?.trim() ?? '';

    const expected = new Date(withUtcOffsetIfMissing(lastPublishedAt as string)).toLocaleString('ja-JP', {
      timeZone,
    });
    expect(displayed).toBe(expected);
  }
);

// ------------------------------------------------------- issue #1366: 壁打ち一覧の作成日

/**
 * 壁打ちセッションのフィクスチャは`articlePlan.steps.ts`の既存Given/When
 * 「記事計画用のプロジェクトが用意されている」「記事計画画面を開く」
 * 「壁打ちで「X」と発言する」をシナリオ側でそのまま再利用する(`ctx.plan.projectId`が
 * 立つ)。`ArticlePlanSessionSummary.createdAt`はオフセット無し(Java `LocalDateTime`)
 * なので、他のフィクスチャと同じ理由(生成直後のエコー値が永続化値と1秒程度ずれ得る)で
 * `GET /api/projects/{id}/article-plan/sessions`から取り直す。
 *
 * 表示形式は`YYYYMMDD`(区切りなし、`ArticlePlanSessionList.tsx`)であり、他のTZ
 * シナリオ(`toLocaleString`のロケール文字列)と異なるため、期待値は
 * `formatDate.ts`の`formatDateYYYYMMDD`と同じアルゴリズム
 * (UTCとして正規化 → `Intl.DateTimeFormat(..., { timeZone }).formatToParts()`)で
 * 独自に組み立てる(SSH鍵ペア等の`withUtcOffsetIfMissing` + `toLocaleString`の再実装と
 * 同じ考え方)。
 */
function toYyyymmdd(iso: string, timeZone: string): string {
  const date = new Date(withUtcOffsetIfMissing(iso));
  const parts = new Intl.DateTimeFormat('ja-JP', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date);
  const get = (type: string) => parts.find((p) => p.type === type)?.value ?? '';
  return `${get('year')}${get('month')}${get('day')}`;
}

/**
 * セッションの`createdAt`は「今」(サーバ時刻、UTC)なので、固定のタイムゾーン名を
 * シナリオに書くと、UTCの暦日とたまたま一致する時間帯(実測: `Asia/Tokyo`なら
 * 15:00-23:59 UTC、`Pacific/Auckland`なら12:00-23:59 UTC)にシナリオを実行すると、
 * 直していない実装(生の`createdAt`の暦日をそのまま出すだけ)でもたまたま一致して
 * PASSしてしまい、RED/GREENの証拠にならない(レビュー指摘、2026-09-20実測)。
 *
 * `Pacific/Kiritimati`(UTC+14)と`Pacific/Niue`(UTC-11)の2つを候補にする。差が25時間
 * (24時間の1日より長い)あるため、この2つの暦日は互いに常に食い違う(検証は下記)。
 * さらに、それぞれ単独でもUTCの生の暦日と食い違う時間帯・一致する時間帯を持つが、
 * 2つの「一致する時間帯」が重ならないよう選んだ組み合わせなので、どの`createdAt`でも
 * 少なくとも一方は必ずUTCの生の暦日と食い違う。
 *
 *   UTC時刻hに対する暦日のズレ(0=UTCと同じ日、以下UTCからの日数):
 *     Kiritimati(+14): h<10 で 0(一致)、h>=10 で +1(食い違う)
 *     Niue(-11):        h<11 で -1(食い違う)、h>=11 で 0(一致)
 *   「一致する」区間は Kiritimati が [0,10)、Niue が [11,24) で重ならず、
 *   「食い違う」区間の和集合 [10,24) ∪ [0,11) は [0,24) 全体を覆う。
 *   つまりどのhでも少なくとも一方は必ず食い違う。
 *
 * 実際に作られたセッションの`createdAt`を取得した「後」に、その場でどちらが食い違うかを
 * 判定して使う(壁時計の「今」を先読みで仮定しない、レビュー指摘のとおり)。
 */
const DIVERGENT_TIMEZONE_A = 'Pacific/Kiritimati'; // UTC+14
const DIVERGENT_TIMEZONE_B = 'Pacific/Niue'; // UTC-11

/**
 * `chosen`は必ずUTCの生の暦日と食い違うタイムゾーン、`other`はもう一方(`chosen`とは
 * 常に暦日が食い違う、上記コメントの検証済み)。数式上「両方とも一致してしまう」ことは
 * 起こり得ないが、万一の実装ミスを無言でPASSさせないよう、その場合は例外で気付けるようにする
 * (レビュー指摘: 「暦日が一致する時間帯に無言でPASSする」ことを避ける)。
 */
function pickDivergentTimezone(createdAt: string): { chosen: string; other: string } {
  const raw = toYyyymmdd(createdAt, 'UTC');
  const a = toYyyymmdd(createdAt, DIVERGENT_TIMEZONE_A);
  if (a !== raw) {
    return { chosen: DIVERGENT_TIMEZONE_A, other: DIVERGENT_TIMEZONE_B };
  }
  const b = toYyyymmdd(createdAt, DIVERGENT_TIMEZONE_B);
  if (b === raw) {
    throw new Error(
      `想定外: createdAt=${createdAt} の生の暦日(UTC)=${raw} が、`
        + `${DIVERGENT_TIMEZONE_A}(${a})にも${DIVERGENT_TIMEZONE_B}(${b})にも一致してしまいました`
        + '(数式上あり得ないはずです。DIVERGENT_TIMEZONE_A/Bの選定を見直してください)'
    );
  }
  return { chosen: DIVERGENT_TIMEZONE_B, other: DIVERGENT_TIMEZONE_A };
}

interface PlanSessionFixture {
  id: number;
  createdAt: string;
}

/** `articlePlan.steps.ts`が作ったセッションの永続化された`createdAt`を取り直す。 */
async function fetchPlanSessionCreatedAt(
  request: APIRequestContext, projectId: number
): Promise<PlanSessionFixture> {
  const headers = await adminHeaders(request);
  const listResponse = await request.get(`/api/projects/${projectId}/article-plan/sessions`, { headers });
  expect(
    listResponse.ok(),
    `壁打ちセッション一覧の取得に失敗しました (status=${listResponse.status()}): ${await listResponse.text()}`
  ).toBe(true);
  const items = await parseJsonOrThrow<PlanSessionFixture[]>(listResponse, '壁打ちセッション一覧の取得');
  expect(items.length, `プロジェクト(id=${projectId})に壁打ちセッションが見つかりません`).toBeGreaterThan(0);
  return items[0];
}

/** `plan/page.tsx`の見出しは`{プロジェクト名} — 記事計画`(部分一致で拾える)。 */
async function openPlanPageWithTimezone(
  ctx: ScenarioContext, page: Page, projectId: number, timezoneId: string
): Promise<void> {
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
  ctx.mediaTzConsoleErrors = consoleErrors;
  ctx.panelTzPage = tzPage;

  await loginViaKeycloak(tzPage, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  await tzPage.goto(`/projects/${projectId}/plan`, { waitUntil: 'networkidle' });
  await expect(tzPage.getByRole('heading', { name: '記事計画', level: 1 })).toBeVisible({ timeout: 30_000 });
  await tzPage.waitForTimeout(1000);
}

When(
  'そのセッションの作成日が暦日をまたぐタイムゾーンを個人設定にし、対極のタイムゾーンをブラウザTZにして管理者としてログインし、記事計画を開く',
  async ({ ctx, page, request }) => {
    const projectId = (ctx.plan as { projectId: number }).projectId;
    const session = await fetchPlanSessionCreatedAt(request, projectId);
    const { chosen, other } = pickDivergentTimezone(session.createdAt);
    ctx.planSessionCreatedAt = session.createdAt;
    ctx.planDivergentTimezone = chosen;

    // 個人設定TZを`chosen`に変更する(media.steps.tsの「個人設定のタイムゾーンを「X」に
    // 変更する」と同じAPI呼び出しの形。restoreは同じフィールド名を使う既存のAfterフックに
    // 任せる)。
    const headers = await adminHeaders(request);
    const me = await request.get('/api/identity/me', { headers });
    expect(me.ok(), `自ユーザー情報の取得に失敗しました (status=${me.status()})`).toBe(true);
    const profile = await parseJsonOrThrow<{ locale: string | null; timezone: string | null }>(
      me,
      '自ユーザー情報の取得'
    );
    ctx.mediaOriginalTimezone = profile.timezone;
    ctx.mediaOriginalLocale = profile.locale;
    const patchResponse = await request.patch('/api/identity/me/preferences', {
      headers: { ...headers, 'Content-Type': 'application/json' },
      data: { locale: profile.locale ?? 'ja', timezone: chosen },
    });
    expect(
      patchResponse.ok(),
      `タイムゾーンの変更に失敗しました (status=${patchResponse.status()}): ${await patchResponse.text()}`
    ).toBe(true);

    await openPlanPageWithTimezone(ctx, page, projectId, other);
  }
);

When(
  'そのセッションの作成日が暦日をまたぐタイムゾーンをブラウザTZにして管理者としてログインし、記事計画を開く',
  async ({ ctx, page, request }) => {
    const projectId = (ctx.plan as { projectId: number }).projectId;
    const session = await fetchPlanSessionCreatedAt(request, projectId);
    const { chosen } = pickDivergentTimezone(session.createdAt);
    ctx.planSessionCreatedAt = session.createdAt;
    ctx.planDivergentTimezone = chosen;

    await openPlanPageWithTimezone(ctx, page, projectId, chosen);
  }
);

Then(
  '壁打ち一覧のセッションの作成日が、選んだタイムゾーンへの換算値のYYYYMMDDと一致する',
  async ({ ctx }) => {
    const tzPage = ctx.panelTzPage as Page;
    const createdAt = ctx.planSessionCreatedAt as string;
    const timeZone = ctx.planDivergentTimezone as string;

    // `ArticlePlanSessionList.tsx`のボタンは`{YYYYMMDD}-{title}`(GitHub Issue番号バッジは
    // このプロジェクトには紐づいていないため付かない)。
    const sessionButton = tzPage.locator('button', { hasText: /^\d{8}-/ }).first();
    await expect(sessionButton).toBeVisible({ timeout: 10_000 });
    const displayed = ((await sessionButton.textContent()) ?? '').trim();
    const displayedDate = displayed.slice(0, 8);

    const raw = toYyyymmdd(createdAt, 'UTC');
    const expected = toYyyymmdd(createdAt, timeZone);
    // `pickDivergentTimezone`の選定どおりであれば`raw !== expected`は常に成り立つはず。
    // ここが崩れていたら、たとえ`displayedDate === expected`でも「無言でPASSする」
    // レビュー指摘そのものになるため、検証の前提が壊れていないかを先に確かめる。
    expect(
      expected,
      `選んだタイムゾーン「${timeZone}」への換算値「${expected}」が生の暦日(UTC)「${raw}」と`
        + `一致してしまいました(createdAt=${createdAt})。pickDivergentTimezoneの前提が崩れています。`
    ).not.toBe(raw);
    expect(
      displayedDate,
      `表示された作成日「${displayedDate}」(全体: 「${displayed}」)が、`
        + `${timeZone}換算の期待値「${expected}」と一致しません(createdAt=${createdAt}、`
        + `生の暦日(UTC)=${raw})`
    ).toBe(expected);
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

  const siteFixture = ctx.panelTzSite as TzSiteFixture | undefined;
  if (siteFixture) {
    await request.delete(`/api/sites/${siteFixture.id}`, { headers });
  }

  const userFixture = ctx.panelTzUser as TzUserFixture | undefined;
  if (userFixture) {
    await request.delete(`/api/users/${userFixture.id}`, { headers });
  }

  const projectFixture = ctx.panelTzProject as TzProjectFixture | undefined;
  if (projectFixture) {
    await request.delete(`/api/projects/${projectFixture.id}`, { headers });
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

// ------------------------------------------------------- issue #1260: 操作ログ画面

/** `uiQuality.steps.ts`の「操作ログに記録される操作を1件実行しておく」が積む値を使う。 */
Then(
  /^操作ログの記録された操作の日時が「([^」]+)」への換算値と一致する$/,
  async ({ ctx }, timeZone: string) => {
    const tzPage = ctx.panelTzPage as Page;
    const iso = ctx.at18TimezoneEntryIso as string;
    const marker = ctx.at18TimezoneMarker as string;
    const expected = new Date(withUtcOffsetIfMissing(iso)).toLocaleString('ja-JP', {
      timeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    });
    const titleCell = tzPage.getByText(marker, { exact: true });
    await expect(titleCell).toBeVisible({ timeout: 15_000 });
    await expect(titleCell.locator('xpath=..')).toContainText(expected);
  }
);

Then(
  /^操作ログ画面に表示中のタイムゾーンとして「([^」]+)」が表記される$/,
  async ({ ctx }, timeZone: string) => {
    const tzPage = ctx.panelTzPage as Page;
    await expect(tzPage.getByTestId('operation-log-timezone')).toContainText(timeZone, {
      timeout: 15_000,
    });
  }
);

// issue #1437: 日時範囲フィルタがブラウザTZの壁時計で解釈されること。
When(
  /^操作ログの日時範囲を「([^」]+)」の壁時計で記録された操作の前後1分に指定して絞り込む$/,
  async ({ ctx }, timeZone: string) => {
    const tzPage = ctx.panelTzPage as Page;
    const created = new Date(withUtcOffsetIfMissing(ctx.at18TimezoneEntryIso as string)).getTime();
    const wall = (instant: number): string => {
      const parts = new Intl.DateTimeFormat('en-CA', {
        timeZone,
        hourCycle: 'h23',
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
      }).formatToParts(new Date(instant));
      const get = (type: string) => parts.find((part) => part.type === type)?.value ?? '';
      return `${get('year')}-${get('month')}-${get('day')}T${get('hour')}:${get('minute')}`;
    };
    await tzPage.locator('input[name="startDate"]').fill(wall(created - 60_000));
    await tzPage.locator('input[name="endDate"]').fill(wall(created + 60_000));
    await tzPage.getByRole('button', { name: '絞り込み' }).click();
    await tzPage.waitForLoadState('networkidle');
  }
);

Then('絞り込んだ操作ログに記録された操作が表示される', async ({ ctx }) => {
  const tzPage = ctx.panelTzPage as Page;
  const marker = ctx.at18TimezoneMarker as string;
  await expect(tzPage.getByText(marker, { exact: true })).toBeVisible({ timeout: 15_000 });
});

When('操作ログの最初の操作の「コピー」を押す', async ({ ctx }) => {
  const tzPage = ctx.panelTzPage as Page;
  const tzContext = ctx.panelTzContext as BrowserContext;
  await tzContext.grantPermissions(['clipboard-read', 'clipboard-write']);
  await tzPage.getByRole('button', { name: 'コピー' }).first().click();
  await expect(tzPage.getByRole('button', { name: 'コピーしました' }).first()).toBeVisible({
    timeout: 15_000,
  });
  ctx.operationLogCopiedText = await tzPage.evaluate(() => navigator.clipboard.readText());
});

Then('コピーされたトレース文字列の日時がすべてUTCのZ付きISO-8601形式である', async ({ ctx }) => {
  const text = ctx.operationLogCopiedText as string;
  const utc = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;
  const started = text.match(/^開始日時: (.+)$/m)?.[1] ?? '';
  expect(started, `開始日時がUTC形式でない: ${text}`).toMatch(utc);
  const calls = [...text.matchAll(/^\d+\. \[([^\]]+)\]/gm)].map((m) => m[1]);
  expect(calls.length, `呼び出し一覧が空: ${text}`).toBeGreaterThan(0);
  for (const value of calls) {
    expect(value).toMatch(utc);
  }
});
