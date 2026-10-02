import { execFileSync } from 'node:child_process';
import type { APIRequestContext, APIResponse, Page } from '@playwright/test';
import { parseApiDateTime } from '../support/apiDateTime';
import { After, Given, Step, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  expect,
  fetchAccessToken,
} from '../support';
import { SERVICE_CONTROL_TIMEOUT_MS, startService, stopService } from '../support/serviceControl';

/**
 * ログ(操作ログ・監査ログ・フロントエンドエラーログ)と非同期経路のステップ定義
 * (issue #941 / AT-15)。
 *
 * ## 待ち方について
 *
 * ログの書き込みは非同期である。発行元 → RabbitMQ(`letsblog.logs`)→ log-writer の
 * コンシューマ → `lbs_log` と段を踏むため、操作した直後に読み取っても入っていない。
 * 固定の sleep で待つと、速いときは無駄に待ち、遅いときは落ちる。
 * {@link pollFor} で「現れるまで」待ち、現れなければ**最後に見えていた状態を添えて**落とす。
 *
 * ## 読み取りに `request` を使う理由
 *
 * 操作ログを書くのは Web の BFF だけである(apps/web/src/lib/apiClient.ts の
 * `recordOperationLog`)。gateway を直接叩く `request` の呼び出しは記録されない。
 * したがって「操作」は必ず `page`(ブラウザ)から行い、「確認」は `request` から行う。
 * 確認を画面から行うと、確認そのものが新しい操作ログを生んで観測対象を汚す。
 */

// ------------------------------------------------------------------ 共通ヘルパー

/** ログが現れるまでの上限。4段の非同期を跨ぐので、単一サービスの応答待ちより長く取る。 */
const LOG_POLL_TIMEOUT_MS = 60_000;
/**
 * ポーリング間隔。gateway の api-global バケットは外部クライアント1IPあたり
 * 100req/分(services/gateway の RateLimitProperties)で、受け入れテストの
 * `request` 呼び出しは全て同じIPから出る。間隔を詰めるとログではなくレート制限を
 * 観測することになるため、2秒に取る。
 */
const LOG_POLL_INTERVAL_MS = 2_000;

/** 「起きないこと」を確かめるための待ち時間。起きるまで待つのと違い、上限がそのまま所要時間になる。 */
const ABSENCE_WINDOW_MS = 20_000;

/**
 * 429を待ち直す上限。gateway の api-global は 100req/**60秒**で補充されるので、
 * 1回の補充周期より少し長く取る({@link sendWithRateLimitRetry})。
 */
const RATE_LIMIT_RETRY_WINDOW_MS = 90_000;

/** `Retry-After` ヘッダが無いときの待ち直し間隔。 */
const RATE_LIMIT_RETRY_INTERVAL_MS = 5_000;

/** 操作ログ1ページの取得件数。並列実行中の他シナリオの記録に押し出されない程度に大きく取る。 */
const LOG_PAGE_SIZE = 200;

function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

/**
 * アクセストークンの `sub` クレーム。監査ログ・操作ログの `actorKeycloakSub` は
 * これと一致していなければならない(issue #569 / #906 / #916)。
 * 署名は検証しない。ここで見たいのは「誰として記録されたか」だけで、
 * 検証はサーバー側(各サービスの JwtDecoderConfig)が既に行っている。
 */
function keycloakSubOf(accessToken: string): string {
  const payload = accessToken.split('.')[1] ?? '';
  const json = Buffer.from(payload.replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8');
  const sub = (JSON.parse(json) as { sub?: string }).sub;
  expect(sub, 'アクセストークンに sub クレームがありません').toBeTruthy();
  return sub as string;
}

/**
 * `check` が値を返すまで繰り返す。返らないまま制限時間に達したら、`describe` と
 * 最後に見えていた状態を添えて落とす。
 *
 * 「1回読んで無いことを結論にしない」ためのもの。`support/gateway.ts` の
 * {@code waitForContainerLog} と同じ考え方だが、こちらは見つけた値を返す。
 */
async function pollFor<T>(
  describe: string,
  check: () => Promise<T | null>,
  options: { timeoutMs?: number; intervalMs?: number } = {}
): Promise<T> {
  const timeoutMs = options.timeoutMs ?? LOG_POLL_TIMEOUT_MS;
  const intervalMs = options.intervalMs ?? LOG_POLL_INTERVAL_MS;
  const deadline = Date.now() + timeoutMs;
  let attempts = 0;
  for (;;) {
    attempts += 1;
    const found = await check();
    if (found !== null) {
      return found;
    }
    if (Date.now() >= deadline) {
      throw new Error(
        `${describe}が ${timeoutMs}ms(${attempts}回の確認)以内に現れませんでした。`
        + 'ログの非同期経路(発行元 → RabbitMQ → log-writer のコンシューマ → lbs_log)の'
        + 'どこかで落ちている可能性があります。'
      );
    }
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
}

interface OperationLogEntry {
  id: number;
  operationId: string;
  userId: number | null;
  actorKeycloakSub: string | null;
  method: string;
  path: string;
  statusCode: number | null;
  durationMs: number;
  success: boolean;
  errorMessage: string | null;
  createdAt: string;
}

interface AuditLogEntry {
  id: number;
  userId: number | null;
  actorKeycloakSub: string | null;
  action: string;
  resourceType: string | null;
  resourceId: number | null;
  changes: string | null;
  remoteIp: string | null;
  userAgent: string | null;
  createdAt: string;
}

interface FrontendErrorLogEntry {
  id: number;
  message: string;
  level: string;
  userId: number | null;
  actorKeycloakSub: string | null;
  url: string | null;
  createdAt: string;
}

interface UnifiedLogEntry {
  sourceType: 'OPERATION' | 'AI_JOB' | 'AUDIT';
  id: number;
  createdAt: string;
  title: string;
  detail: string | null;
  status: string | null;
  operationId: string | null;
  actorKeycloakSub: string | null;
}

/**
 * 429(レート制限)なら枠が空くのを待って送り直す。
 *
 * gateway の `api-global` バケットは**外部クライアント1IPあたり100req/分**で
 * (services/gateway の RateLimitProperties、docs/API_RATE_LIMITING.md)、
 * 受け入れテストの `request` 呼び出しは全シナリオが同じホストIPから出る。
 * ここのシナリオは「ログが現れるまで」ポーリングするため、他のシナリオと並列に走ると
 * この共有バケットに当たる(実測: 167シナリオをワーカー2で流したとき
 * `GET /api/operation-logs` が429になった)。
 *
 * 429は「ログが無い」ことでも「操作が失敗した」ことでもない。**見に行けなかっただけ**である。
 * 素通しすると、無関係な混雑が製品の不具合として報告される。バケットの枯渇そのものは
 * `features/cross-cutting/rate-limit.feature` と #464 の担当で、ここでは扱わない。
 */
async function sendWithRateLimitRetry(send: () => Promise<APIResponse>): Promise<APIResponse> {
  const deadline = Date.now() + RATE_LIMIT_RETRY_WINDOW_MS;
  for (;;) {
    const response = await send();
    if (response.status() !== 429 || Date.now() >= deadline) {
      return response;
    }
    const retryAfter = Number(response.headers()['retry-after']);
    const waitMs = Number.isFinite(retryAfter) && retryAfter > 0
      ? Math.min(retryAfter * 1000, RATE_LIMIT_RETRY_WINDOW_MS)
      : RATE_LIMIT_RETRY_INTERVAL_MS;
    await new Promise((resolve) => setTimeout(resolve, waitMs));
  }
}

async function getJson<T>(
  request: APIRequestContext,
  token: string,
  path: string,
  what: string
): Promise<T> {
  const response = await sendWithRateLimitRetry(() =>
    request.get(path, { headers: { Authorization: `Bearer ${token}` } })
  );
  expect(
    response.ok(),
    `${what}の取得に失敗しました (GET ${path}, status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as T;
}

function listOperationLogs(request: APIRequestContext, token: string): Promise<{ content: OperationLogEntry[] }> {
  return getJson(
    request,
    token,
    `/api/operation-logs?page=0&size=${LOG_PAGE_SIZE}&sort=createdAt,desc`,
    '操作ログ'
  );
}

function listAuditLogs(
  request: APIRequestContext,
  token: string,
  query = ''
): Promise<{ content: AuditLogEntry[] }> {
  const suffix = query ? `&${query}` : '';
  return getJson(
    request,
    token,
    `/api/audit-logs?page=0&size=${LOG_PAGE_SIZE}&sort=createdAt,desc${suffix}`,
    '監査ログ'
  );
}

function listErrorLogs(
  request: APIRequestContext,
  token: string,
  query = ''
): Promise<{ content: FrontendErrorLogEntry[] }> {
  const suffix = query ? `&${query}` : '';
  return getJson(request, token, `/api/logs/errors?page=0&size=50${suffix}`, 'エラーログ');
}

function listUnifiedLogs(
  request: APIRequestContext,
  token: string,
  query = ''
): Promise<{ content: UnifiedLogEntry[] }> {
  const suffix = query ? `&${query}` : '';
  return getJson(request, token, `/api/operation-logs/unified?page=0&size=100${suffix}`, '統合ログ');
}

/** シナリオが作ったプロジェクトを後始末の対象として覚えておく。 */
function rememberProject(ctx: Record<string, unknown>, projectId: number): void {
  ctx.at15ProjectIds = [...((ctx.at15ProjectIds as number[] | undefined) ?? []), projectId];
}

/** シナリオが作ったmanaged WordPressサイトを後始末の対象として覚えておく。 */
function rememberSite(ctx: Record<string, unknown>, siteId: number): void {
  ctx.at15SiteIds = [...((ctx.at15SiteIds as number[] | undefined) ?? []), siteId];
}

function uniqueSuffix(): string {
  return `${Date.now().toString().slice(-9)}${Math.random().toString(36).slice(2, 6)}`;
}

/** `/api/users` から合成アカウントの userId を引く(admin限定)。 */
async function findUserId(request: APIRequestContext, token: string, email: string): Promise<number> {
  const users = await getJson<{ id: number; email: string }[]>(request, token, '/api/users', 'ユーザー一覧');
  const user = users.find((candidate) => candidate.email === email);
  expect(user, `${email} が users に存在しません。合成アカウントの発行を確認してください`).toBeTruthy();
  return (user as { id: number }).id;
}

// ------------------------------------------------------- 操作ログ(operation-log.feature)

Given('現時点の操作ログを控えておく', async ({ ctx, request }) => {
  // 「新しく増えた記録」を後から見分けるための基準。時刻ではなく operationId の集合で
  // 取るのは、log-writer コンテナと実行ホストのタイムゾーンが一致する保証が無いため。
  const token = await adminToken(request);
  const before = await listOperationLogs(request, token);
  ctx.at15KnownOperationIds = new Set(before.content.map((entry) => entry.operationId));
});

When('画面からプロジェクトを作成する', async ({ ctx, page }) => {
  await createProjectFromScreen(ctx, page);
});

Then('画面からプロジェクトを作成する操作は成功する', async ({ ctx, page }) => {
  await createProjectFromScreen(ctx, page);
});

/**
 * `/projects` の作成フォームから作る。**API を直接叩かない。**
 * 操作ログを書くのは BFF だけなので、gateway を直接叩くと記録が生まれず、
 * 「記録されること」を検証できない。
 */
async function createProjectFromScreen(ctx: Record<string, unknown>, page: Page): Promise<void> {
  const unique = uniqueSuffix();
  const slug = `at15-log-${unique}`;
  await page.goto('/projects');
  const form = page.locator('#project-form');
  await expect(form.locator('input[name="name"]')).toBeVisible({ timeout: 30_000 });
  await form.locator('input[name="name"]').fill(`AT15 ログ ${unique}`);
  // 名前の入力で slug が自動補完されるため、こちらは後から上書きする。
  await form.locator('input[name="slug"]').fill(slug);
  await form.locator('button[type="submit"]').click();
  await expect(
    form.getByText('作成しました。'),
    'プロジェクトの作成が画面上で成功していません'
  ).toBeVisible({ timeout: 60_000 });
  ctx.at15ProjectSlug = slug;
}

Then('その操作が操作ログに現れるまで待つ', async ({ ctx, request, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + LOG_POLL_TIMEOUT_MS);
  const token = await adminToken(request);
  const known = ctx.at15KnownOperationIds as Set<string>;

  const entry = await pollFor('画面から行ったプロジェクト作成の操作ログ', async () => {
    const logs = await listOperationLogs(request, token);
    return (
      logs.content.find(
        (candidate) =>
          !known.has(candidate.operationId)
          && candidate.method === 'POST'
          && candidate.path === '/api/projects'
      ) ?? null
    );
  });

  ctx.at15OperationLogEntry = entry;
});

Then('その操作は操作ログ画面に表示される', async ({ page }) => {
  // 種別とキーワードで絞り込んでから見る。並列実行中の他シナリオの記録に押し出されて
  // 1ページ目から消えることがあり、絞らないと「表示されない」が誤検知になる。
  await page.goto(`/operation-logs?type=OPERATION&q=${encodeURIComponent('POST /api/projects')}`);
  await expect(page.locator('h1:has-text("操作ログ")')).toBeVisible();
  await expect(
    page.locator('form + div').getByText('POST /api/projects', { exact: true }).first(),
    '作成操作が /operation-logs に出ていません'
  ).toBeVisible({ timeout: 30_000 });
});

Then('その記録には操作者・日時・対象・結果が揃っている', async ({ ctx, request }) => {
  const entry = ctx.at15OperationLogEntry as OperationLogEntry;
  const token = await adminToken(request);

  // 誰が: ローカルの userId と Keycloak の sub の両方。sub が欠けると #906 / #916 の再発。
  expect(entry.userId, '操作ログに操作者(userId)が入っていません').not.toBeNull();
  expect(entry.actorKeycloakSub, '操作ログの操作者がログイン中の利用者と一致しません')
    .toBe(keycloakSubOf(token));

  // いつ: 解釈可能な日時であること。
  expect(entry.createdAt, '操作ログに日時がありません').toBeTruthy();
  expect(
    Number.isNaN(parseApiDateTime(entry.createdAt)),
    `操作ログの日時を解釈できません: ${entry.createdAt}`
  ).toBe(false);

  // 何に対して: メソッドとパス。
  expect(entry.method).toBe('POST');
  expect(entry.path).toBe('/api/projects');

  // 結果はどうだったか: 成否とステータスコード。
  expect(entry.success, '成功した操作が失敗として記録されています').toBe(true);
  expect(entry.statusCode, '操作ログにステータスコードがありません').not.toBeNull();
  expect(entry.statusCode).toBeGreaterThanOrEqual(200);
  expect(entry.statusCode).toBeLessThan(300);
  expect(entry.errorMessage).toBeNull();
});

Step('ダッシュボードを開く', async ({ page }) => {
  // 1回の画面表示で6本のAPIを呼ぶ(apps/web/src/app/page.tsx)。
  // 「1操作 = 複数のAPI呼び出し」を作るのにちょうどよい。
  await page.goto('/');
  await expect(page.locator('h1:has-text("ダッシュボード")')).toBeVisible({ timeout: 30_000 });
});

Step('1画面分の複数のAPI呼び出しが1つのoperationIdにまとまるまで待つ', async ({ ctx, request, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + LOG_POLL_TIMEOUT_MS);
  const token = await adminToken(request);
  const known = ctx.at15KnownOperationIds as Set<string>;

  const operationId = await pollFor('1画面分をまとめた operationId', async () => {
    const logs = await listOperationLogs(request, token);
    const grouped = new Map<string, OperationLogEntry[]>();
    for (const entry of logs.content) {
      if (known.has(entry.operationId)) continue;
      grouped.set(entry.operationId, [...(grouped.get(entry.operationId) ?? []), entry]);
    }
    for (const [id, entries] of grouped) {
      // 「一連のログ」なので2件以上あること。1件だけの操作は束ねられていることの証拠にならない。
      if (entries.length >= 2) return id;
    }
    return null;
  });

  ctx.at15OperationId = operationId;
});

Then('そのoperationIdのトレースは、同じ操作のログだけを古い順に返す', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const operationId = ctx.at15OperationId as string;

  const trace = await getJson<OperationLogEntry[]>(
    request,
    token,
    `/api/operation-logs/${encodeURIComponent(operationId)}`,
    '操作トレース'
  );

  expect(trace.length, 'トレースが1件も返りません').toBeGreaterThanOrEqual(2);
  for (const entry of trace) {
    expect(entry.operationId, 'トレースに別の操作のログが混ざっています').toBe(operationId);
    expect(entry.actorKeycloakSub, 'トレースに別の利用者のログが混ざっています')
      .toBe(keycloakSubOf(token));
  }

  const times = trace.map((entry) => parseApiDateTime(entry.createdAt));
  const ascending = [...times].sort((a, b) => a - b);
  expect(times, 'トレースが古い順に並んでいません').toEqual(ascending);
});

Then('統合ビューにその操作の操作ログと監査ログの双方が現れるまで待つ', async ({ ctx, request, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + LOG_POLL_TIMEOUT_MS);
  const token = await adminToken(request);
  const entry = ctx.at15OperationLogEntry as OperationLogEntry;

  // 監査ログ側の目印になる projectId を、作成したプロジェクトの slug から引く。
  const projects = await getJson<{ id: number; slug: string }[]>(
    request, token, '/api/projects', 'プロジェクト一覧'
  );
  const created = projects.find((project) => project.slug === ctx.at15ProjectSlug);
  expect(created, `作成したプロジェクト(slug=${ctx.at15ProjectSlug})が見つかりません`).toBeTruthy();
  const projectId = (created as { id: number }).id;
  rememberProject(ctx, projectId);

  const unified = await pollFor('統合ビューの操作ログと監査ログ', async () => {
    const page = await listUnifiedLogs(request, token);
    const operation = page.content.find(
      (candidate) => candidate.sourceType === 'OPERATION' && candidate.operationId === entry.operationId
    );
    // 監査ログの発行元は project-service、操作ログの発行元は log-writer 自身。
    // この2件が揃うことが「複数サービスにまたがる1操作」であることの証拠になる。
    const audit = page.content.find(
      (candidate) =>
        candidate.sourceType === 'AUDIT' && candidate.title === `PROJECT_CREATED PROJECT #${projectId}`
    );
    return operation && audit ? page.content : null;
  });

  ctx.at15UnifiedContent = unified;
});

Then('統合ビューは新しい順に並んでいる', async ({ ctx }) => {
  const content = ctx.at15UnifiedContent as UnifiedLogEntry[];
  const times = content.map((entry) => parseApiDateTime(entry.createdAt));
  const descending = [...times].sort((a, b) => b - a);
  expect(times, '統合ビューが新しい順に並んでいません').toEqual(descending);
});

// ------------------------------------------------------------ 監査ログ(audit-log.feature)

Given('監査ログ検証用のプロジェクトがある', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const unique = uniqueSuffix();
  const response = await sendWithRateLimitRetry(() => request.post('/api/projects', {
    headers: { Authorization: `Bearer ${token}` },
    data: { name: `AT15 監査 ${unique}`, slug: `at15-audit-${unique}` },
  }));
  expect(
    response.ok(),
    `検証用プロジェクトの作成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const projectId = ((await response.json()) as { id: number }).id;
  ctx.at15AuditProjectId = projectId;
  rememberProject(ctx, projectId);
});

Given('現時点の監査ログを控えておく', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const before = await listAuditLogs(request, token);
  ctx.at15KnownAuditIds = new Set(before.content.map((entry) => entry.id));
});

async function addProjectMember(
  request: APIRequestContext, token: string, projectId: number, userId: number, wpRole: string
): Promise<void> {
  const response = await sendWithRateLimitRetry(() => request.post(`/api/projects/${projectId}/users`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { userId, wpRole },
  }));
  expect(
    response.ok(),
    `メンバー追加に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
}

Given('そのプロジェクトに一般ユーザーをメンバーとして追加する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const projectId = ctx.at15AuditProjectId as number;
  const userId = await findUserId(request, token, E2E_TEST_EMAIL);
  ctx.at15MemberUserId = userId;
  await addProjectMember(request, token, projectId, userId, 'author');
});

When('そのプロジェクトに一般ユーザーをメンバーとして追加し、ロールを変更し、外す', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const projectId = ctx.at15AuditProjectId as number;
  const userId = await findUserId(request, token, E2E_TEST_EMAIL);
  ctx.at15MemberUserId = userId;

  await addProjectMember(request, token, projectId, userId, 'author');

  const updated = await sendWithRateLimitRetry(() => request.put(`/api/projects/${projectId}/users/${userId}`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { wpRole: 'editor' },
  }));
  expect(
    updated.ok(),
    `ロール変更に失敗しました (status=${updated.status()}): ${await updated.text()}`
  ).toBe(true);

  const removed = await sendWithRateLimitRetry(() => request.delete(`/api/projects/${projectId}/users/${userId}`, {
    headers: { Authorization: `Bearer ${token}` },
  }));
  expect(
    removed.ok(),
    `メンバー剥奪に失敗しました (status=${removed.status()}): ${await removed.text()}`
  ).toBe(true);
});

/** そのプロジェクトに対する、指定アクションの新しい監査ログを1件返す(無ければ null)。 */
function findNewAuditLog(
  logs: AuditLogEntry[], known: Set<number>, projectId: number, action: string
): AuditLogEntry | null {
  return (
    logs.find(
      (entry) => !known.has(entry.id) && entry.action === action && entry.resourceId === projectId
    ) ?? null
  );
}

Then('ロールの付与・変更・剥奪の3件が監査ログに現れるまで待つ', async ({ ctx, request, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + LOG_POLL_TIMEOUT_MS);
  const token = await adminToken(request);
  const projectId = ctx.at15AuditProjectId as number;
  const known = ctx.at15KnownAuditIds as Set<number>;

  const found = await pollFor('プロジェクトメンバーのロール付与・変更・剥奪の監査ログ3件', async () => {
    const logs = (await listAuditLogs(request, token)).content;
    const entries = [
      findNewAuditLog(logs, known, projectId, 'PROJECT_USER_ADDED'),
      findNewAuditLog(logs, known, projectId, 'PROJECT_USER_ROLE_UPDATED'),
      findNewAuditLog(logs, known, projectId, 'PROJECT_USER_REMOVED'),
    ];
    return entries.every((entry) => entry !== null) ? (entries as AuditLogEntry[]) : null;
  });

  ctx.at15AuditEntries = found;
});

/**
 * issue #1137: プロジェクトメンバー操作(resourceType=PROJECT_USER)とユーザー操作
 * (resourceType=USER)の両方から使う共通の検証ステップ。`ctx.at15AuditExpectedResourceType`/
 * `ctx.at15AuditExpectedResourceId` が設定されていればそちらを使い、無ければ
 * 従来どおりプロジェクトメンバーのシナリオ(PROJECT_USER / at15AuditProjectId)とみなす。
 */
Then('監査ログの各件には操作者・日時・対象・操作種別が揃っている', async ({ ctx, request }) => {
  const entries = ctx.at15AuditEntries as AuditLogEntry[];
  const expectedResourceType = (ctx.at15AuditExpectedResourceType as string | undefined) ?? 'PROJECT_USER';
  const expectedResourceId = (ctx.at15AuditExpectedResourceId as number | undefined)
    ?? (ctx.at15AuditProjectId as number);
  const sub = keycloakSubOf(await adminToken(request));

  for (const entry of entries) {
    expect(entry.userId, `監査ログ ${entry.action} に操作者(userId)がありません`).not.toBeNull();
    expect(entry.actorKeycloakSub, `監査ログ ${entry.action} の操作者が実際の操作者と一致しません`)
      .toBe(sub);
    expect(
      Number.isNaN(parseApiDateTime(entry.createdAt)),
      `監査ログ ${entry.action} の日時を解釈できません: ${entry.createdAt}`
    ).toBe(false);
    expect(entry.resourceType, `監査ログ ${entry.action} に対象種別がありません`).toBe(expectedResourceType);
    expect(entry.resourceId, `監査ログ ${entry.action} の対象が違います`).toBe(expectedResourceId);
  }
});

Given('ロール付与の監査ログが1件記録されるまで待つ', async ({ ctx, request, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + LOG_POLL_TIMEOUT_MS);
  const token = await adminToken(request);
  const projectId = ctx.at15AuditProjectId as number;

  const entry = await pollFor('ロール付与の監査ログ', async () => {
    const logs = (await listAuditLogs(request, token)).content;
    return (
      logs.find(
        (candidate) => candidate.action === 'PROJECT_USER_ADDED' && candidate.resourceId === projectId
      ) ?? null
    );
  });

  ctx.at15AuditEntry = entry;
});

When('管理者が監査ログの更新と削除を試みる', async ({ ctx, request }) => {
  // 監査ログの改変・削除は「権限が足りない」のではなく「窓口が無い」ことで防いでいる。
  // 最も権限の強い admin で試して、それでも通らないことを確かめる。
  const token = await adminToken(request);
  const entry = ctx.at15AuditEntry as AuditLogEntry;
  const headers = { Authorization: `Bearer ${token}` };
  const tampered = { action: 'AT15_TAMPERED', resourceType: 'PROJECT_USER', resourceId: 0 };

  const attempts = [
    // 429を待ち直すのはここでも要る。429も4xxなので、そのまま数えると
    // 「混雑していたから拒否された」を「改変できなかった」と読み違える。
    { how: `PUT /api/audit-logs/${entry.id}`, response: await sendWithRateLimitRetry(() => request.put(`/api/audit-logs/${entry.id}`, { headers, data: tampered })) },
    { how: `PATCH /api/audit-logs/${entry.id}`, response: await sendWithRateLimitRetry(() => request.patch(`/api/audit-logs/${entry.id}`, { headers, data: tampered })) },
    { how: `DELETE /api/audit-logs/${entry.id}`, response: await sendWithRateLimitRetry(() => request.delete(`/api/audit-logs/${entry.id}`, { headers })) },
    { how: 'POST /api/audit-logs', response: await sendWithRateLimitRetry(() => request.post('/api/audit-logs', { headers, data: tampered })) },
    { how: 'DELETE /api/audit-logs', response: await sendWithRateLimitRetry(() => request.delete('/api/audit-logs', { headers })) },
  ];

  ctx.at15TamperAttempts = attempts.map((attempt) => ({
    how: attempt.how,
    status: attempt.response.status(),
  }));
});

Then('いずれの試みも成功しない', async ({ ctx }) => {
  const attempts = ctx.at15TamperAttempts as { how: string; status: number }[];
  for (const attempt of attempts) {
    expect(
      attempt.status,
      `${attempt.how} が成功しました。監査ログを利用者から改変できてはいけません`
    ).toBeGreaterThanOrEqual(400);
  }
});

Then('その監査ログは元のまま残っている', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const before = ctx.at15AuditEntry as AuditLogEntry;
  const logs = (await listAuditLogs(request, token)).content;
  const after = logs.find((entry) => entry.id === before.id);

  expect(after, `監査ログ ${before.id} が消えています`).toBeTruthy();
  expect(after, '監査ログの内容が書き換わっています').toEqual(before);
});

// --------------------------------------- 一括削除(bulk-management delete-all、issue #1137)

/**
 * `articlePlan.steps.ts`の`createTaxonomySite`(issue #935/AT-9)と同じ手順
 * (managed WordPressサイトをProjectの`test`環境へ紐付け、wp-cliで実カテゴリ・実タグを作る)を
 * このfeature専用に再現したもの。各stepファイルは自己完結という既存の慣習
 * (`environmentSync.steps.ts`も同様にwp-cli呼び出しを自前で持つ)に倣い、ここでも別ファイルの
 * 非公開ヘルパーを import せず独立に持つ。
 *
 * スラッグは固定値でよい。フィクスチャはシナリオごとに新しいサイトを作るため、
 * 同一サイト内での重複を心配する必要が無い。
 */
const WORDPRESS_CONTAINER = 'lbs-wordpress';
const BULK_DELETE_CATEGORY_SLUG = 'at15-bulk-category';
const BULK_DELETE_TAG_SLUG = 'at15-bulk-tag';

function wpCli(siteKey: string, args: string[]): string {
  return execFileSync(
    'docker',
    ['exec', WORDPRESS_CONTAINER, 'wp', '--allow-root', `--path=/var/www/html/sites/${siteKey}`, ...args],
    { encoding: 'utf8', timeout: 120_000 }
  ).trim();
}

async function createBulkDeleteTaxonomySite(
  request: APIRequestContext, token: string, projectId: number
): Promise<{ siteId: number; siteKey: string }> {
  const siteKey = `at15bd${uniqueSuffix()}`.toLowerCase().replace(/[^a-z0-9]/g, '').slice(0, 32);
  const created = await sendWithRateLimitRetry(() => request.post('/api/sites/managed-wordpress', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      name: `E2E AT15 bulk-delete ${siteKey}`,
      siteKey,
      title: 'E2E AT15 bulk-delete',
      adminUser: 'at15admin',
      adminEmail: 'at15@letsblog.local',
      adminPassword: 'At15Fixture!Pass123',
      locale: 'ja',
    },
    timeout: 120_000,
  }));
  expect(
    created.ok(),
    `一括削除検証用の公開先サイト作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const siteId = ((await created.json()) as { id: number }).id;

  const bound = await sendWithRateLimitRetry(() => request.post(`/api/projects/${projectId}/environments`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { environment: 'test', siteId },
  }));
  expect(
    bound.ok(),
    `一括削除検証用の公開先サイト紐付けに失敗しました (status=${bound.status()}): ${await bound.text()}`
  ).toBe(true);

  wpCli(siteKey, [
    'term', 'create', 'category', 'AT15 一括削除カテゴリ', `--slug=${BULK_DELETE_CATEGORY_SLUG}`, '--porcelain',
  ]);
  wpCli(siteKey, [
    'term', 'create', 'post_tag', 'AT15 一括削除タグ', `--slug=${BULK_DELETE_TAG_SLUG}`, '--porcelain',
  ]);

  return { siteId, siteKey };
}

Given('監査ログ検証用の、公開先に実カテゴリと実タグを持つプロジェクトがある', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const unique = uniqueSuffix();
  const response = await sendWithRateLimitRetry(() => request.post('/api/projects', {
    headers: { Authorization: `Bearer ${token}` },
    data: { name: `AT15 一括削除 ${unique}`, slug: `at15-bulkdelete-${unique}` },
  }));
  expect(
    response.ok(),
    `一括削除検証用プロジェクトの作成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const projectId = ((await response.json()) as { id: number }).id;
  ctx.at15AuditProjectId = projectId;
  rememberProject(ctx, projectId);

  const { siteId } = await createBulkDeleteTaxonomySite(request, token, projectId);
  rememberSite(ctx, siteId);
});

// --------------------------------------- プラグイン・テーマ・投稿の一括削除(issue #1323)

/**
 * 実プラグイン・実テーマ・実投稿を公開先に用意する。外部ネットワークに依存しないよう、
 * プラグインは`wp scaffold plugin`、テーマは`wp scaffold child-theme`(親は導入済みテーマ)、
 * 投稿は`wp post create`(AT-10の`createReferencingPost`と同じ手順)で作る。
 */
const BULK_DELETE_PLUGIN_SLUG = 'at15-bulk-plugin';
const BULK_DELETE_THEME_SLUG = 'at15-bulk-theme';
const BULK_DELETE_POST_SLUG = 'at15-bulk-post';

Given('監査ログ検証用の、公開先に実プラグイン・実テーマ・実投稿を持つプロジェクトがある', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const unique = uniqueSuffix();
  const response = await sendWithRateLimitRetry(() => request.post('/api/projects', {
    headers: { Authorization: `Bearer ${token}` },
    data: { name: `AT15 一括削除2 ${unique}`, slug: `at15-bulkdelete2-${unique}` },
  }));
  expect(
    response.ok(),
    `一括削除検証用プロジェクトの作成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const projectId = ((await response.json()) as { id: number }).id;
  ctx.at15AuditProjectId = projectId;
  rememberProject(ctx, projectId);

  const { siteId, siteKey } = await createBulkDeleteTaxonomySite(request, token, projectId);
  rememberSite(ctx, siteId);

  wpCli(siteKey, ['scaffold', 'plugin', BULK_DELETE_PLUGIN_SLUG, '--skip-tests', '--activate']);
  const parent = wpCli(siteKey, ['theme', 'list', '--field=name']).split(/\s+/).filter(Boolean)[0];
  wpCli(siteKey, ['scaffold', 'child-theme', BULK_DELETE_THEME_SLUG, `--parent_theme=${parent}`]);
  wpCli(siteKey, [
    'post', 'create', '--post_type=post', '--post_status=publish',
    '--post_title=AT15 一括削除投稿', `--post_name=${BULK_DELETE_POST_SLUG}`, '--porcelain',
  ]);
});

async function bulkDeleteAll(
  request: APIRequestContext, projectId: number, path: string, slug: string, label: string
): Promise<void> {
  const token = await adminToken(request);
  const response = await sendWithRateLimitRetry(() => request.post(
    `/api/projects/${projectId}/bulk-management/${path}/delete-all`,
    { headers: { Authorization: `Bearer ${token}` }, data: { slug }, timeout: 120_000 }
  ));
  expect(
    response.ok(),
    `${label}の一括削除に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
}

When('そのプラグインを一括削除する', async ({ ctx, request }) => {
  await bulkDeleteAll(request, ctx.at15AuditProjectId as number, 'plugins', BULK_DELETE_PLUGIN_SLUG, 'プラグイン');
});

When('そのテーマを一括削除する', async ({ ctx, request }) => {
  await bulkDeleteAll(request, ctx.at15AuditProjectId as number, 'themes', BULK_DELETE_THEME_SLUG, 'テーマ');
});

When('その投稿を一括削除する', async ({ ctx, request }) => {
  await bulkDeleteAll(request, ctx.at15AuditProjectId as number, 'posts', BULK_DELETE_POST_SLUG, '投稿');
});

async function waitForBulkDeleteAudit(
  ctx: Record<string, unknown>, request: APIRequestContext, testInfo: { setTimeout(n: number): void; timeout: number },
  label: string, action: string, resourceType: string
): Promise<void> {
  testInfo.setTimeout(testInfo.timeout + LOG_POLL_TIMEOUT_MS);
  const token = await adminToken(request);
  const projectId = ctx.at15AuditProjectId as number;
  const known = ctx.at15KnownAuditIds as Set<number>;
  const entry = await pollFor(`${label}一括削除の監査ログ`, async () => {
    const logs = (await listAuditLogs(request, token)).content;
    return findNewAuditLog(logs, known, projectId, action);
  });
  ctx.at15AuditEntries = [entry];
  ctx.at15AuditExpectedResourceType = resourceType;
  ctx.at15AuditExpectedResourceId = projectId;
}

Then('プラグインの一括削除が監査ログに現れるまで待つ', async ({ ctx, request, $testInfo }) => {
  await waitForBulkDeleteAudit(ctx, request, $testInfo, 'プラグイン', 'PLUGIN_BULK_DELETED', 'PLUGIN');
});

Then('テーマの一括削除が監査ログに現れるまで待つ', async ({ ctx, request, $testInfo }) => {
  await waitForBulkDeleteAudit(ctx, request, $testInfo, 'テーマ', 'THEME_BULK_DELETED', 'THEME');
});

Then('投稿の一括削除が監査ログに現れるまで待つ', async ({ ctx, request, $testInfo }) => {
  await waitForBulkDeleteAudit(ctx, request, $testInfo, '投稿', 'POST_BULK_DELETED', 'POST');
});

When('そのカテゴリを一括削除する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const projectId = ctx.at15AuditProjectId as number;
  const response = await sendWithRateLimitRetry(() => request.post(
    `/api/projects/${projectId}/bulk-management/categories/delete-all`,
    { headers: { Authorization: `Bearer ${token}` }, data: { slug: BULK_DELETE_CATEGORY_SLUG }, timeout: 120_000 }
  ));
  expect(
    response.ok(),
    `カテゴリの一括削除に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

When('そのタグを一括削除する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const projectId = ctx.at15AuditProjectId as number;
  const response = await sendWithRateLimitRetry(() => request.post(
    `/api/projects/${projectId}/bulk-management/tags/delete-all`,
    { headers: { Authorization: `Bearer ${token}` }, data: { slug: BULK_DELETE_TAG_SLUG }, timeout: 120_000 }
  ));
  expect(
    response.ok(),
    `タグの一括削除に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
});

Then('カテゴリの一括削除が監査ログに現れるまで待つ', async ({ ctx, request, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + LOG_POLL_TIMEOUT_MS);
  const token = await adminToken(request);
  const projectId = ctx.at15AuditProjectId as number;
  const known = ctx.at15KnownAuditIds as Set<number>;

  const entry = await pollFor('カテゴリ一括削除の監査ログ', async () => {
    const logs = (await listAuditLogs(request, token)).content;
    return findNewAuditLog(logs, known, projectId, 'CATEGORY_BULK_DELETED');
  });

  ctx.at15AuditEntries = [entry];
  ctx.at15AuditExpectedResourceType = 'CATEGORY';
  ctx.at15AuditExpectedResourceId = projectId;
});

Then('タグの一括削除が監査ログに現れるまで待つ', async ({ ctx, request, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + LOG_POLL_TIMEOUT_MS);
  const token = await adminToken(request);
  const projectId = ctx.at15AuditProjectId as number;
  const known = ctx.at15KnownAuditIds as Set<number>;

  const entry = await pollFor('タグ一括削除の監査ログ', async () => {
    const logs = (await listAuditLogs(request, token)).content;
    return findNewAuditLog(logs, known, projectId, 'TAG_BULK_DELETED');
  });

  ctx.at15AuditEntries = [entry];
  ctx.at15AuditExpectedResourceType = 'TAG';
  ctx.at15AuditExpectedResourceId = projectId;
});

// --------------------------------------- ユーザーの無効化・role変更・削除(issue #1137)

/**
 * 検証用に使い捨てるユーザーを作る。共有アカウント({@link E2E_TEST_EMAIL})を
 * 無効化・削除すると他シナリオを巻き込むため、この一連のシナリオ専用に1人作る。
 */
Given('監査ログ検証用の使い捨てユーザーがいる', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const unique = uniqueSuffix();
  const response = await sendWithRateLimitRetry(() => request.post('/api/users', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      email: `at15-disposable-${unique}@letsblog.local`,
      password: `At15Disposable-${unique}`,
      role: 'user',
    },
  }));
  expect(
    response.ok(),
    `使い捨てユーザーの作成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.at15DisposableUserId = ((await response.json()) as { id: number }).id;
});

When(
  'そのユーザーを無効化し、再有効化し、roleをadminへ変更し、削除する',
  async ({ ctx, request }) => {
    const token = await adminToken(request);
    const userId = ctx.at15DisposableUserId as number;
    const headers = { Authorization: `Bearer ${token}` };

    const deactivated = await sendWithRateLimitRetry(() =>
      request.post(`/api/users/${userId}/deactivate`, { headers })
    );
    expect(
      deactivated.ok(),
      `無効化に失敗しました (status=${deactivated.status()}): ${await deactivated.text()}`
    ).toBe(true);

    const reactivated = await sendWithRateLimitRetry(() =>
      request.post(`/api/users/${userId}/reactivate`, { headers })
    );
    expect(
      reactivated.ok(),
      `再有効化に失敗しました (status=${reactivated.status()}): ${await reactivated.text()}`
    ).toBe(true);

    const roleUpdated = await sendWithRateLimitRetry(() =>
      request.patch(`/api/users/${userId}`, { headers, data: { role: 'admin' } })
    );
    expect(
      roleUpdated.ok(),
      `role変更に失敗しました (status=${roleUpdated.status()}): ${await roleUpdated.text()}`
    ).toBe(true);

    const deleted = await sendWithRateLimitRetry(() =>
      request.delete(`/api/users/${userId}`, { headers })
    );
    expect(
      deleted.ok(),
      `削除に失敗しました (status=${deleted.status()}): ${await deleted.text()}`
    ).toBe(true);
  }
);

Then('無効化・再有効化・role変更・削除の4件が監査ログに現れるまで待つ', async ({ ctx, request, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + LOG_POLL_TIMEOUT_MS);
  const token = await adminToken(request);
  const userId = ctx.at15DisposableUserId as number;
  const known = ctx.at15KnownAuditIds as Set<number>;

  const found = await pollFor('ユーザー無効化・再有効化・role変更・削除の監査ログ4件', async () => {
    const logs = (await listAuditLogs(request, token)).content;
    const entries = [
      findNewAuditLog(logs, known, userId, 'USER_DEACTIVATED'),
      findNewAuditLog(logs, known, userId, 'USER_REACTIVATED'),
      findNewAuditLog(logs, known, userId, 'USER_ROLE_UPDATED'),
      findNewAuditLog(logs, known, userId, 'USER_DELETED'),
    ];
    return entries.every((entry) => entry !== null) ? (entries as AuditLogEntry[]) : null;
  });

  ctx.at15AuditEntries = found;
  ctx.at15AuditExpectedResourceType = 'USER';
  ctx.at15AuditExpectedResourceId = userId;
});

Then('role変更の監査ログのchangesから変更前後のroleが読み取れる', async ({ ctx }) => {
  const entries = ctx.at15AuditEntries as AuditLogEntry[];
  const roleUpdated = entries.find((entry) => entry.action === 'USER_ROLE_UPDATED');
  expect(roleUpdated, 'USER_ROLE_UPDATEDの監査ログが見つかりません').toBeTruthy();
  expect(roleUpdated?.changes, 'role変更の監査ログにchangesがありません').toBeTruthy();

  const changes = JSON.parse(roleUpdated?.changes as string) as { role?: { from?: string; to?: string } };
  expect(changes.role?.from, 'changesに変更前のroleがありません').toBe('user');
  expect(changes.role?.to, 'changesに変更後のroleがありません').toBe('admin');
});

// ------------------------------------------- フロントエンドエラーログ(frontend-error-log.feature)

Given('log-writerのエラーログ記録APIは未認証では受け付けない', async ({ request }) => {
  // #791 の退行検知はこのゲートが有効であることが前提。ゲートが外れると、
  // ブラウザからの直叩きに戻しても記録が通ってしまい、下のシナリオが退行を見逃す。
  //
  // 本文は送らない。未認証なら SecurityConfig が先に弾くので RabbitMQ へは何も出ないが、
  // 万一ゲートが外れていた場合に message 欠落の毒メッセージ(#1059)を作らないための用心。
  const response = await sendWithRateLimitRetry(() => request.post('/api/logs/errors', {
    headers: { 'Content-Type': 'application/json' },
    data: { message: 'AT-15 unauthenticated probe', level: 'error', timestamp: new Date().toISOString() },
  }));
  expect(
    response.status(),
    'POST /api/logs/errors が未認証で通っています(ADR-0008 の認証ゲートが外れている)'
  ).toBe(401);
});

/**
 * 製品の error boundary(apps/web/src/app/error.tsx)を**本物の経路で**発火させる。
 *
 * ## どうやって発火させるか
 *
 * ダッシュボードの稼働状況パネル(apps/web/src/app/ConnectedServiceStatusPanel.tsx)は、
 * SSE で受け取った `status` イベントの中身を検証せずそのまま state に入れ、描画時に
 * `statuses.map(...)` する。配列でない中身を配ると描画中に `TypeError` が投げられ、
 * これが React の error boundary へ伝播する。**利用者の操作では作れないが、
 * サーバーが想定外の形を返せば実際に起きる**種類の不具合であり、
 * error boundary とエラーログはまさにこれを拾うために在る。
 *
 * サーバー側の障害でページごと落とす手(下流サービスの停止)を採らないのは、
 * 一覧系の画面がいずれも取得失敗を `catch` して空表示へ縮退するため
 * (`app/projects/page.tsx` ほか)、そもそも error boundary に到達しないからである。
 * `/projects/{存在しないID}` も `notFound()` になり、404画面が出るだけで例外は出ない。
 *
 * ## なぜ `page.evaluate` で `/client-errors` を叩かないか
 *
 * それでは **errorLogger.ts がどこへ送っているか**を検証できない。#791 で落ちたのは
 * まさにそこで、送信先が gateway 直叩きのままだと認証ゲートで401になり、
 * error boundary の try/catch がそれを握りつぶす。製品自身に送信させる必要がある。
 *
 * ## 目印
 *
 * エラーログの `url` は `window.location.href` である。毎回違うクエリを付けて開くことで、
 * 並列実行中の他シナリオのエラーログと取り違えない。
 */
async function triggerClientSideError(ctx: Record<string, unknown>, page: Page): Promise<void> {
  const marker = uniqueSuffix();
  ctx.at15ErrorPageUrl = `https://localhost/?at15=${marker}`;

  await page.route('**/api/dashboard/service-status/stream', (route) =>
    route.fulfill({
      status: 200,
      headers: { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' },
      body: 'event: status\ndata: {"notAnArray":true}\n\n',
    })
  );

  await page.goto(`/?at15=${marker}`);
  await expect(
    page.getByRole('heading', { name: 'エラーが発生しました' }),
    'エラー画面(error boundary)が出ていません。クライアント側エラーが発生していません'
  ).toBeVisible({ timeout: 60_000 });
}

When('画面でクライアント側エラーを発生させる', async ({ ctx, page, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + LOG_POLL_TIMEOUT_MS);
  await triggerClientSideError(ctx, page);
});

When('ブラウザの送信先を記録しながら、画面でクライアント側エラーを発生させる', async ({ ctx, page, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + LOG_POLL_TIMEOUT_MS);
  const observed: { method: string; url: string }[] = [];
  page.on('request', (req) => observed.push({ method: req.method(), url: req.url() }));
  ctx.at15ObservedRequests = observed;

  await triggerClientSideError(ctx, page);

  // ビーコンは error boundary の useEffect から飛ぶ。描画の完了とは別に待つ必要がある。
  // 届かなければここでは落とさず、次の「ならば」に判定させる(何が起きたかを assert 側で語らせる)。
  await page
    .waitForRequest(
      (req) => req.method() === 'POST' && new URL(req.url()).pathname === '/client-errors',
      { timeout: 20_000 }
    )
    .catch(() => undefined);
});

Then('ブラウザは同一オリジンのBFFへ送信し、gatewayのエラーログAPIを直接は叩かない', async ({ ctx }) => {
  const observed = ctx.at15ObservedRequests as { method: string; url: string }[];

  const toBff = observed.filter(
    (req) => req.method === 'POST' && new URL(req.url).pathname === '/client-errors'
  );
  expect(
    toBff.length,
    'ブラウザが同一オリジンのBFF(/client-errors)へエラーログを送っていません。'
      + '#791 の修正(errorLogger.ts の送信先)が戻っている可能性があります。'
      + `観測した送信: ${JSON.stringify(observed.filter((req) => req.method === 'POST'))}`
  ).toBeGreaterThanOrEqual(1);

  const toGateway = observed.filter((req) => new URL(req.url).pathname === '/api/logs/errors');
  expect(
    toGateway,
    'ブラウザが gateway の /api/logs/errors を直接叩いています。'
      + '認証ヘッダーが付かないため認証ゲートで401になり、エラーログが無言で全滅します(#791)'
  ).toEqual([]);
});

Then('そのエラーがエラーログ取得APIに現れるまで待つ', async ({ ctx, request, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + LOG_POLL_TIMEOUT_MS);
  const token = await adminToken(request);
  const url = ctx.at15ErrorPageUrl as string;

  const entry = await pollFor(`エラー画面(${url})のフロントエンドエラーログ`, async () => {
    const logs = await listErrorLogs(request, token, `url=${encodeURIComponent(url)}`);
    return logs.content[0] ?? null;
  });

  ctx.at15ErrorLogEntry = entry;
});

Then('そのエラーログには発生画面のURLと操作者が記録されている', async ({ ctx, request }) => {
  const entry = ctx.at15ErrorLogEntry as FrontendErrorLogEntry;
  const token = await adminToken(request);

  expect(entry.url, 'エラーログに発生画面のURLがありません').toBe(ctx.at15ErrorPageUrl);
  expect(entry.level, 'エラーログのレベルが ERROR ではありません').toBe('ERROR');
  expect(entry.message, 'エラーログにメッセージがありません').toBeTruthy();
  // 未認証の経路で送られていたら userId も sub も null になる(#791 の状態)。
  expect(entry.userId, 'エラーログに操作者(userId)がありません').not.toBeNull();
  expect(entry.actorKeycloakSub, 'エラーログの操作者がログイン中の利用者と一致しません')
    .toBe(keycloakSubOf(token));
});

// ------------------------------------------------------ 閲覧と認可(log-viewing.feature)

/** 操作ログ画面の一覧(絞り込みフォームの直後のブロック)。ヘッダやフォームを巻き込まない。 */
function operationLogList(page: Page) {
  return page.locator('form + div');
}

Then('操作ログ画面を種別「操作」で絞ると、操作ログ以外の種別は表示されない', async ({ page }) => {
  await page.goto('/operation-logs?type=OPERATION');
  const list = operationLogList(page);
  await expect(list.getByText('操作', { exact: true }).first()).toBeVisible({ timeout: 30_000 });
  await expect(list.getByText('監査', { exact: true })).toHaveCount(0);
  await expect(list.getByText('AI', { exact: true })).toHaveCount(0);
});

Then('操作ログ画面をキーワードで絞ると、そのキーワードを含む行だけが表示される', async ({ page }) => {
  // ダッシュボードの表示で必ず呼ばれる2本のパスを使う(apps/web/src/app/page.tsx)。
  const keyword = '/api/dashboard/service-status';
  const excluded = '/api/sites';

  await page.goto('/operation-logs?type=OPERATION');
  const list = operationLogList(page);
  await expect(
    list.getByText(keyword).first(), '絞り込み前にキーワードの行が見当たりません'
  ).toBeVisible({ timeout: 30_000 });

  await page.goto(`/operation-logs?type=OPERATION&q=${encodeURIComponent(keyword)}`);
  const filtered = operationLogList(page);
  await expect(filtered.getByText(keyword).first()).toBeVisible({ timeout: 30_000 });
  await expect(
    filtered.getByText(excluded, { exact: false }),
    `キーワード「${keyword}」で絞ったのに ${excluded} の行が残っています`
  ).toHaveCount(0);
});

/** 画面の日時表示(`formatOperationLogDateTime`: "2026/09/10 09:30:15")を datetime-local の値へ直す。 */
function toDateTimeLocalValue(text: string, shiftMinutes: number): string {
  const match = text.trim().match(/^(\d{4})\/(\d{2})\/(\d{2}) (\d{2}):(\d{2}):(\d{2})$/);
  expect(match, `画面の日時表示を解釈できません: "${text}"`).not.toBeNull();
  const [, y, mo, d, h, mi] = match as RegExpMatchArray;
  // 壁時計値のまま加減算する(タイムゾーンは画面表示と入力で揃っているので触らない)。
  const wall = Date.UTC(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi)) + shiftMinutes * 60_000;
  return new Date(wall).toISOString().slice(0, 16);
}

/** 一覧に表示されている各行の日時表示(`formatOperationLogDateTime` の出力)。 */
async function displayedTimestamps(page: Page): Promise<string[]> {
  const texts = await operationLogList(page).locator('span.w-36').allTextContents();
  return texts.map((text) => text.trim());
}

Then('操作ログ画面を日時の範囲で絞ると、その範囲の行だけが表示される', async ({ page }) => {
  // 基準は画面に出ている最新の行の日時。ホストと閲覧者のタイムゾーンが違っても、
  // 「画面が表示した壁時計」をそのまま入力へ戻すので換算がずれない。
  await page.goto('/operation-logs?type=OPERATION');
  const list = operationLogList(page);
  await expect(list.locator('span.w-36').first()).toBeVisible({ timeout: 30_000 });
  const newest = (await displayedTimestamps(page))[0];
  const start = toDateTimeLocalValue(newest, -2);
  const end = toDateTimeLocalValue(newest, 2);

  const applyRange = async (from: string, to: string) => {
    await page.goto('/operation-logs?type=OPERATION');
    await page.locator('input[name="startDate"]').fill(from);
    await page.locator('input[name="endDate"]').fill(to);
    await page.getByRole('button', { name: '絞り込み' }).click();
    await expect(page).toHaveURL(/startDate=/);
  };

  // 範囲内: 最新の行が残り、範囲外の日時の行は1つも無い。
  await applyRange(start, end);
  await expect(operationLogList(page).getByText(newest).first()).toBeVisible({ timeout: 30_000 });
  for (const shown of await displayedTimestamps(page)) {
    const local = toDateTimeLocalValue(shown, 0);
    expect(
      local >= start && local <= end,
      `範囲 ${start} 〜 ${end} の外の行「${shown}」が表示されています`
    ).toBe(true);
  }

  // 範囲外(未来の1日): 何も表示されない。
  await applyRange(toDateTimeLocalValue(newest, 24 * 60), toDateTimeLocalValue(newest, 48 * 60));
  await expect(page.getByText('該当するログはありません')).toBeVisible({ timeout: 30_000 });
});

Then('その監査ログは日時の範囲で絞り込める', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const entry = ctx.at15AuditEntry as AuditLogEntry;

  // 基準はサーバーが記録した createdAt 自身。実行ホストと log-writer コンテナの
  // タイムゾーンが一致する保証が無いので、ホストの現在時刻からは範囲を作らない。
  //
  // entry.createdAt はゾーン情報の無いISO文字列(例: "2026-09-15T21:33:54")で、
  // バックエンド(LocalDateTime.now().toString() で送出・LocalDateTime.parse() で
  // 保存・LocalDateTime で受けるクエリパラメータ)は一貫してこれをUTCの壁時計値として
  // 扱っている(#1314)。`Date.parse` はゾーン無し文字列を**実行ホストのローカル
  // タイムゾーン**として解釈するため(ECMAScript仕様)、そのまま渡すとホストの
  // タイムゾーンがUTCでない場合に範囲がずれる。'Z' を明示的に付けてUTCとして解釈させる。
  const at = parseApiDateTime(entry.createdAt);
  const iso = (millis: number) => new Date(millis).toISOString().replace('Z', '');

  const inRange = await listAuditLogs(
    request, token,
    `startDate=${iso(at - 60_000)}&endDate=${iso(at + 60_000)}`
  );
  expect(
    inRange.content.some((candidate) => candidate.id === entry.id),
    '日時の範囲に含まれるはずの監査ログが返りません'
  ).toBe(true);

  const outOfRange = await listAuditLogs(
    request, token,
    `startDate=${iso(at - 3 * 86_400_000)}&endDate=${iso(at - 2 * 86_400_000)}`
  );
  expect(
    outOfRange.content.some((candidate) => candidate.id === entry.id),
    '日時の範囲外の監査ログが返っています(絞り込みが効いていません)'
  ).toBe(false);
});

Then('その監査ログは利用者で絞り込める', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const entry = ctx.at15AuditEntry as AuditLogEntry;
  const adminUserId = entry.userId as number;
  const otherUserId = ctx.at15MemberUserId as number;

  const mine = await listAuditLogs(request, token, `userId=${adminUserId}`);
  expect(
    mine.content.some((candidate) => candidate.id === entry.id),
    '操作者で絞り込むと、その操作者の監査ログが返るはずです'
  ).toBe(true);
  expect(
    mine.content.every((candidate) => candidate.userId === adminUserId),
    '操作者で絞り込んだのに別の操作者の監査ログが混ざっています'
  ).toBe(true);

  const others = await listAuditLogs(request, token, `userId=${otherUserId}`);
  expect(
    others.content.some((candidate) => candidate.id === entry.id),
    '別の操作者で絞り込んだのに、この監査ログが返っています'
  ).toBe(false);
});

/**
 * 一般ユーザーのトークンは `steps/auth.steps.ts` の
 * 「一般ユーザーのアクセストークンを取得する」が `ctx.userToken` へ入れる。
 * 同じ意味のステップを二重に定義すると playwright-bdd が
 * "Multiple definitions matched scenario step" で生成を拒否するため、ここでは再定義しない。
 */
function viewerToken(ctx: Record<string, unknown>): string {
  const token = ctx.userToken as string | undefined;
  expect(token, '一般ユーザーのアクセストークンが未取得です').toBeTruthy();
  return token as string;
}

Then('一般ユーザーの操作ログ一覧に管理者の操作は含まれない', async ({ ctx, request }) => {
  const token = viewerToken(ctx);
  const adminOperationId = ctx.at15OperationId as string;

  const logs = await listOperationLogs(request, token);
  expect(
    logs.content.some((entry) => entry.operationId === adminOperationId),
    '一般ユーザーの操作ログ一覧に管理者の操作が混ざっています'
  ).toBe(false);
  expect(
    logs.content.every((entry) => entry.actorKeycloakSub === null
      || entry.actorKeycloakSub === keycloakSubOf(token)),
    '一般ユーザーの操作ログ一覧に他人の操作が混ざっています'
  ).toBe(true);
});

Then('一般ユーザーは管理者のoperationIdでトレースを取得できない', async ({ ctx, request }) => {
  const token = viewerToken(ctx);
  const adminOperationId = ctx.at15OperationId as string;

  const response = await sendWithRateLimitRetry(() =>
    request.get(`/api/operation-logs/${encodeURIComponent(adminOperationId)}`, {
      headers: { Authorization: `Bearer ${token}` },
    })
  );
  expect(response.ok(), `トレース取得が失敗しました (status=${response.status()})`).toBe(true);
  expect(
    await response.json(),
    '他人の operationId を指定すると、その操作のログが読めてしまっています'
  ).toEqual([]);
});

Then('一般ユーザーは監査ログAPIとエラーログ取得APIを拒否される', async ({ ctx, request }) => {
  const token = viewerToken(ctx);
  const headers = { Authorization: `Bearer ${token}` };

  const audit = await sendWithRateLimitRetry(() => request.get('/api/audit-logs', { headers }));
  expect(audit.status(), '一般ユーザーが監査ログを読めています').toBe(403);

  const errors = await sendWithRateLimitRetry(() => request.get('/api/logs/errors', { headers }));
  expect(errors.status(), '一般ユーザーがフロントエンドエラーログを読めています').toBe(403);
});

Then('一般ユーザーの統合ビューには監査ログが含まれない', async ({ ctx, request }) => {
  const token = viewerToken(ctx);
  const unified = await listUnifiedLogs(request, token);
  expect(
    unified.content.filter((entry) => entry.sourceType === 'AUDIT'),
    '一般ユーザーの統合ビューに監査ログが含まれています'
  ).toEqual([]);
});

// ---------------------------------------------------------- 非同期経路(async-path.feature)

When('RabbitMQを停止する', async ({ ctx, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + SERVICE_CONTROL_TIMEOUT_MS);
  stopService(ctx, 'rabbitmq');
});

When('RabbitMQを復旧させる', async ({ ctx, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + SERVICE_CONTROL_TIMEOUT_MS);
  startService(ctx, 'rabbitmq');
});

When('log-writerコンテナを停止する', async ({ ctx, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + SERVICE_CONTROL_TIMEOUT_MS);
  stopService(ctx, 'log-writer');
});

Then('業務操作\\(プロジェクトの作成・一覧・削除)は成功する', async ({ request, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + SERVICE_CONTROL_TIMEOUT_MS);
  const token = await adminToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const unique = uniqueSuffix();

  // RabbitMQ が落ちていると発行側は接続の確立で待たされうる。応答が遅いことは
  // 「失敗する」ことと違うので、待ち時間は長めに取ったうえで成否だけを見る。
  const created = await sendWithRateLimitRetry(() => request.post('/api/projects', {
    headers,
    data: { name: `AT15 MQ停止 ${unique}`, slug: `at15-mqdown-${unique}` },
    timeout: 180_000,
  }));
  expect(
    created.ok(),
    `RabbitMQ の停止が業務操作(プロジェクト作成)を止めています (status=${created.status()}): `
      + `${await created.text()}`
  ).toBe(true);
  // このシナリオは削除まで自分で行うので、後始末の対象には積まない
  // (積むと After が既に消えたIDを消そうとして404の警告が出る)。
  const projectId = ((await created.json()) as { id: number }).id;

  const listed = await sendWithRateLimitRetry(() => request.get('/api/projects', { headers, timeout: 180_000 }));
  expect(
    listed.ok(),
    `RabbitMQ の停止が業務操作(プロジェクト一覧)を止めています (status=${listed.status()})`
  ).toBe(true);

  const deleted = await sendWithRateLimitRetry(() =>
    request.delete(`/api/projects/${projectId}`, { headers, timeout: 180_000 })
  );
  expect(
    deleted.ok(),
    `RabbitMQ の停止が業務操作(プロジェクト削除)を止めています (status=${deleted.status()})`
  ).toBe(true);
});

Then('画面はエラーにならずに表示される', async ({ page }) => {
  await page.goto('/projects');
  await expect(page.locator('h1:has-text("プロジェクト")')).toBeVisible({ timeout: 60_000 });
  await expect(page.getByText('エラーが発生しました')).toHaveCount(0);
  await expect(page.getByText('プロジェクトの読み込みに失敗しました')).toHaveCount(0);
});

When('停止中に操作ログ・エラーログ・監査ログをそれぞれ1件発生させる', async ({ ctx, request, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + SERVICE_CONTROL_TIMEOUT_MS);
  const token = await adminToken(request);
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  const unique = uniqueSuffix();

  // 1) 操作ログ。BFF が使うのと同じ窓口へ直接送る(log-writer 自身が発行元になる経路)。
  const operationId = `at15-mqdown-${unique}`;
  ctx.at15DownOperationId = operationId;
  const operation = await sendWithRateLimitRetry(() => request.post('/api/operation-logs', {
    headers,
    data: {
      operationId,
      method: 'POST',
      path: '/at15/rabbitmq-down',
      statusCode: 201,
      durationMs: 1,
      success: true,
    },
    timeout: 180_000,
  }));
  expect(
    operation.ok(),
    `RabbitMQ 停止中の操作ログ記録が失敗しました (status=${operation.status()}): ${await operation.text()}`
  ).toBe(true);

  // 2) フロントエンドエラーログ。message は必ず入れる(欠けると #1059 の毒メッセージになる)。
  const errorUrl = `https://localhost/at15/rabbitmq-down/${unique}`;
  ctx.at15DownErrorUrl = errorUrl;
  const errorLog = await sendWithRateLimitRetry(() => request.post('/api/logs/errors', {
    headers,
    data: {
      message: `AT-15 RabbitMQ 停止中のエラー ${unique}`,
      level: 'error',
      url: errorUrl,
      timestamp: new Date().toISOString(),
    },
    timeout: 180_000,
  }));
  expect(
    errorLog.ok(),
    `RabbitMQ 停止中のエラーログ記録が失敗しました (status=${errorLog.status()}): ${await errorLog.text()}`
  ).toBe(true);

  // 3) 監査ログ。発行元は project-service で、lbs_log へは書けない(ADR-0004)。
  const created = await sendWithRateLimitRetry(() => request.post('/api/projects', {
    headers,
    data: { name: `AT15 MQ停止監査 ${unique}`, slug: `at15-mqdown-audit-${unique}` },
    timeout: 180_000,
  }));
  expect(
    created.ok(),
    `RabbitMQ 停止中のプロジェクト作成が失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const projectId = ((await created.json()) as { id: number }).id;
  ctx.at15DownAuditProjectId = projectId;
  rememberProject(ctx, projectId);
});

Then('停止中の操作ログとエラーログは残っている', async ({ ctx, request, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + LOG_POLL_TIMEOUT_MS);
  const token = await adminToken(request);

  // 同期DB書き込みへのフォールバックなので、復旧を待たずとも既に入っているはずである。
  // それでもポーリングにするのは、復旧直後にキュー経由の分が届く可能性を排除しないため。
  const operationId = ctx.at15DownOperationId as string;
  const trace = await pollFor('RabbitMQ 停止中に記録した操作ログ', async () => {
    const entries = await getJson<OperationLogEntry[]>(
      request, token, `/api/operation-logs/${encodeURIComponent(operationId)}`, '操作トレース'
    );
    return entries.length > 0 ? entries : null;
  });
  expect(trace[0].path, '停止中の操作ログの内容が違います').toBe('/at15/rabbitmq-down');

  const errorUrl = ctx.at15DownErrorUrl as string;
  await pollFor('RabbitMQ 停止中に記録したエラーログ', async () => {
    const logs = await listErrorLogs(request, token, `url=${encodeURIComponent(errorUrl)}`);
    return logs.content[0] ?? null;
  });
});

Then('停止中の監査ログは記録されていない', async ({ ctx, request, $testInfo }) => {
  $testInfo.setTimeout($testInfo.timeout + ABSENCE_WINDOW_MS + LOG_POLL_TIMEOUT_MS);
  const token = await adminToken(request);
  const projectId = ctx.at15DownAuditProjectId as number;

  // 「まだ届いていないだけ」と区別するために、復旧後しばらく観測し続ける。
  // outbox が無い以上ここで現れることはないが、現れたら仕様の方が変わったということなので
  // このシナリオが落ちて気付ける。
  const deadline = Date.now() + ABSENCE_WINDOW_MS;
  do {
    const logs = (await listAuditLogs(request, token)).content;
    const leaked = logs.find(
      (entry) => entry.action === 'PROJECT_CREATED' && entry.resourceId === projectId
    );
    expect(
      leaked,
      'RabbitMQ 停止中の監査ログが記録されていました。'
        + '発行元(project-service)は lbs_log へ書けないため失われる、という前提が変わっています。'
        + 'async-path.feature の表を実装に合わせて更新してください'
    ).toBeUndefined();
    await new Promise((resolve) => setTimeout(resolve, LOG_POLL_INTERVAL_MS));
  } while (Date.now() < deadline);
});

// ------------------------------------------------------------------------ 後始末

After({ tags: '@logging' }, async ({ ctx, request }) => {
  const projectIds = (ctx.at15ProjectIds as number[] | undefined) ?? [];
  const siteIds = (ctx.at15SiteIds as number[] | undefined) ?? [];
  if (projectIds.length === 0 && siteIds.length === 0) {
    return;
  }
  // 停止したサービスの復旧は degradation.steps.ts の @destructive フックが行う。
  // ここではそれに依存せず、失敗しても後続のシナリオを巻き込まないようにする。
  let token: string;
  try {
    token = await adminToken(request);
  } catch (error) {
    console.warn(`[AT-15] 後始末のトークン取得に失敗しました: ${String(error)}`);
    return;
  }
  for (const projectId of projectIds) {
    const response = await sendWithRateLimitRetry(() =>
      request.delete(`/api/projects/${projectId}`, {
        headers: { Authorization: `Bearer ${token}` },
        timeout: 180_000,
      })
    ).catch(() => null);
    if (response === null || !response.ok()) {
      console.warn(
        `[AT-15] 検証用プロジェクト ${projectId} を削除できませんでした`
        + `${response ? ` (status=${response.status()})` : ''}`
      );
    }
  }
  ctx.at15ProjectIds = [];
  // プロジェクトを先に消す(articlePlan.steps.ts の後始末と同じ順序)。managed WordPress
  // サイト自体はプロジェクト削除に連動しないため、別に消す必要がある。
  for (const siteId of siteIds) {
    const response = await sendWithRateLimitRetry(() =>
      request.delete(`/api/sites/${siteId}`, {
        headers: { Authorization: `Bearer ${token}` },
        timeout: 120_000,
      })
    ).catch(() => null);
    if (response === null || !response.ok()) {
      console.warn(
        `[AT-15] 検証用サイト ${siteId} を削除できませんでした`
        + `${response ? ` (status=${response.status()})` : ''}`
      );
    }
  }
  ctx.at15SiteIds = [];
});
