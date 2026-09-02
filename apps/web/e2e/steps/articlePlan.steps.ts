import { execFileSync } from 'node:child_process';
import type { APIRequestContext, Locator, Page } from '@playwright/test';
import { After, Given, Step, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';
import { STUB_URLS } from '../support/stubs';

/**
 * 記事プランとGitHub Issue連携のステップ定義(issue #935 / AT-9)。
 *
 * ## GitHub は実サービスを叩かない
 *
 * ai-service の `GITHUB_API_BASE_URL` は `docker-compose.e2e-stubs.yml` が
 * `http://github-stub:8080`(`infra/e2e-stubs/github`)へ差し替える。ここのステップが
 * 「GitHub側がどうなったか」を確かめるときも、そのスタブをホスト側の公開ポート
 * ({@link STUB_URLS}.github)から読む。実 GitHub へ向けると、テストのたびに本リポジトリの
 * Issue が作られ担当者が書き換わるため、検証専用リポジトリではなくスタブを採る
 * (2026-09-01 に #935 で確定)。
 *
 * ## スタブの状態を共有することへの配慮
 *
 * GitHub スタブは書き込みで状態が変わる。シナリオごとに**触る Issue 番号を分けて**あり、
 * `__control/reset` は呼ばない(並列に走る他のシナリオのシードを巻き戻してしまうため)。
 *
 *   #101 構成案の反映(body を上書き)
 *   #102 本文の読み取り・セッションの引き当て(読み取りのみ)
 *   #103 担当者の割り当て(assignees を上書き)
 *
 * ## フィクスチャ
 *
 * プロジェクトは1シナリオにつき1つ作り、{@link After} で必ず消す。既存カテゴリ・タグの
 * シナリオだけは公開先(マネージドWordPress)も要るため、サイトも作って消す
 * (実測 8 秒程度。`@slow` を付けるほどではない)。
 */

/** GitHub スタブが持つ固定リポジトリ。`infra/e2e-stubs/github/server.js` のシードと対応する。 */
const STUB_REPOSITORY = 'e2e-stub/acceptance';
/** スタブが 200 を返すトークン。無効・読み取り専用は別の固定値が割り当ててある。 */
const STUB_VALID_TOKEN = 'e2e-stub-token';
/** スタブが 401 を返すトークン。 */
const STUB_INVALID_TOKEN = 'e2e-stub-invalid-token';

/** 公開先に作る既存分類。親子関係とタグの検証に使う。 */
const FIXTURE_PARENT_CATEGORY = 'AT9親カテゴリ';
const FIXTURE_CHILD_CATEGORY = 'AT9子カテゴリ';
const FIXTURE_TAG = 'AT9タグ';

const WORDPRESS_CONTAINER = 'lbs-wordpress';

interface PlanFixture {
  projectId: number;
  /** マネージドWordPressサイトを作った場合のみ。後片付けで消す。 */
  siteId?: number;
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

function unique(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

/**
 * 記事プラン用のプロジェクトを API で作る。
 *
 * Web UI の作成フォームでも作れるが、1件あたり20往復以上サーバーレンダリングが走り、
 * gateway の api-global バケット(グローバル100req/分)をすぐ使い切る(helpers.ts の
 * {@code createFixtureProject} と同じ理由)。
 */
async function createPlanProject(request: APIRequestContext, token: string): Promise<number> {
  const suffix = unique();
  const response = await request.post('/api/projects', {
    headers: { Authorization: `Bearer ${token}` },
    data: { name: `E2E AT9 ${suffix}`, slug: `e2e-at9-${suffix}` },
  });
  expect(
    response.ok(),
    `記事プラン用プロジェクトの作成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return ((await response.json()) as { id: number }).id;
}

async function setGithubRepository(
  request: APIRequestContext, token: string, projectId: number
): Promise<void> {
  const response = await request.put(`/api/projects/${projectId}/github-repository`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { githubRepository: STUB_REPOSITORY },
  });
  expect(
    response.ok(),
    `GitHubリポジトリの紐付けに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
}

async function setGithubToken(
  request: APIRequestContext, token: string, projectId: number, githubToken: string
): Promise<void> {
  const response = await request.put(`/api/projects/${projectId}/api-keys/github-token`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { githubToken },
  });
  expect(
    response.ok(),
    `GitHubトークンの設定に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
}

/** WordPress のコンテナ内で wp-cli を実行する。既存分類を作るためだけに使う。 */
function wpCli(siteKey: string, args: string[]): string {
  return execFileSync(
    'docker',
    ['exec', WORDPRESS_CONTAINER, 'wp', '--allow-root', `--path=/var/www/html/sites/${siteKey}`, ...args],
    { encoding: 'utf8', timeout: 120_000 }
  ).trim();
}

/**
 * 公開先(マネージドWordPress)を用意し、親子関係のあるカテゴリとタグを作る。
 *
 * 既存タクソノミーの検証は「サイトに実際に何があるか」に依存するため、他のテストが
 * 作ったサイトを借りず、このシナリオ専用のサイトを作る。WordPress を新規構築するのは
 * AT-5(#931)の担当だが、ここでは**分類の参照先**として要るだけで、プロビジョニング
 * そのものを検証しているわけではない。
 */
async function createTaxonomySite(
  request: APIRequestContext, token: string, projectId: number
): Promise<number> {
  const siteKey = `at9tx${unique()}`.toLowerCase().replace(/[^a-z0-9]/g, '');
  const created = await request.post('/api/sites/managed-wordpress', {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      name: `E2E AT9 taxonomy ${siteKey}`,
      siteKey,
      title: 'E2E AT9 taxonomy',
      adminUser: 'at9admin',
      adminEmail: 'at9@letsblog.local',
      adminPassword: 'At9Fixture!Pass123',
      locale: 'ja',
    },
    timeout: 120_000,
  });
  expect(
    created.ok(),
    `公開先サイトの作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  const siteId = ((await created.json()) as { id: number }).id;

  const bound = await request.post(`/api/projects/${projectId}/environments`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { environment: 'test', siteId },
  });
  expect(
    bound.ok(),
    `公開先サイトの紐付けに失敗しました (status=${bound.status()}): ${await bound.text()}`
  ).toBe(true);

  const parentId = wpCli(siteKey, ['term', 'create', 'category', FIXTURE_PARENT_CATEGORY, '--porcelain']);
  wpCli(siteKey, ['term', 'create', 'category', FIXTURE_CHILD_CATEGORY, `--parent=${parentId}`, '--porcelain']);
  wpCli(siteKey, ['term', 'create', 'post_tag', FIXTURE_TAG, '--porcelain']);

  return siteId;
}

/** GitHub スタブが今持っている Issue を読む。「GitHub側がどうなったか」の確認に使う。 */
async function readStubIssue(issueNumber: number): Promise<{ body: string; assignees: { login: string }[] }> {
  const response = await fetch(`${STUB_URLS.github}/repos/${STUB_REPOSITORY}/issues/${issueNumber}`, {
    headers: { Authorization: `Bearer ${STUB_VALID_TOKEN}` },
  });
  expect(response.ok, `GitHubスタブの Issue #${issueNumber} を読めませんでした`).toBeTruthy();
  return (await response.json()) as { body: string; assignees: { login: string }[] };
}

// --------------------------------------------------------------- 画面の部品

/** `h2` の見出しで記事計画画面のカードを特定する。DOM構造ではなく画面の見出しに寄せる。 */
function card(page: Page, heading: string | RegExp): Locator {
  return page.locator('div.rounded-lg').filter({ has: page.locator('h2', { hasText: heading }) });
}

const sessionCard = (page: Page): Locator => card(page, '壁打ち一覧');
const chatCard = (page: Page): Locator => card(page, 'AI との壁打ち');
const titleCard = (page: Page): Locator => card(page, '記事タイトル提案');
const structureCard = (page: Page): Locator => card(page, /^記事構成の提案/);
const issueCard = (page: Page): Locator => card(page, '登録済み記事(Issue)一覧');

/** 会話履歴の吹き出し。`あなた:` / `AI:` のラベルで数える。 */
const aiMessages = (page: Page): Locator => chatCard(page).locator('strong', { hasText: /^AI:$/ });

async function openPlanPage(page: Page, projectId: number, issueNumber?: number): Promise<void> {
  const query = issueNumber === undefined ? '' : `?issue=${issueNumber}`;
  await page.goto(`/projects/${projectId}/plan${query}`);
  await expect(page.getByRole('heading', { name: /記事計画$/ })).toBeVisible({ timeout: 30_000 });
}

/**
 * 壁打ちで1回発言し、応答かエラーのどちらかが出るまで待つ。
 *
 * 送信ボタンは送信後も disabled のまま(入力欄が空になるため)なので、ボタンの活性では
 * 完了を判定できない。「AIの吹き出しが増えた」か「エラーが出た」かで判定する。
 */
async function sendChatMessage(page: Page, message: string): Promise<void> {
  const chat = chatCard(page);
  const before = await aiMessages(page).count();
  await chat.locator('input[type="text"]').fill(message);
  await chat.getByRole('button', { name: /^送信/ }).click();

  await expect
    .poll(
      async () =>
        (await aiMessages(page).count()) > before
        || (await chat.locator('p.text-red-600').count()) > 0,
      { timeout: 90_000, message: 'AIの応答もエラーも表示されませんでした' }
    )
    .toBe(true);
}

// --------------------------------------------------------------- 前提(フィクスチャ)

Given('記事計画用のプロジェクトが用意されている', async ({ ctx, request }) => {
  const token = await adminToken(request);
  ctx.plan = { projectId: await createPlanProject(request, token) } satisfies PlanFixture;
});

Given('GitHub連携が設定されたプロジェクトが用意されている', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const projectId = await createPlanProject(request, token);
  await setGithubRepository(request, token, projectId);
  await setGithubToken(request, token, projectId, STUB_VALID_TOKEN);
  ctx.plan = { projectId } satisfies PlanFixture;
});

Given('GitHubトークンが未設定のプロジェクトが用意されている', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const projectId = await createPlanProject(request, token);
  // リポジトリだけ紐付けてトークンは設定しない。操作者本人のユーザー設定にも
  // GitHubトークンは無いため、project-service は 409 で「未設定」を返す。
  await setGithubRepository(request, token, projectId);
  ctx.plan = { projectId } satisfies PlanFixture;
});

Given('無効なGitHubトークンが設定されたプロジェクトが用意されている', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const projectId = await createPlanProject(request, token);
  await setGithubRepository(request, token, projectId);
  await setGithubToken(request, token, projectId, STUB_INVALID_TOKEN);
  ctx.plan = { projectId } satisfies PlanFixture;
});

Given('公開先に既存の分類があるプロジェクトが用意されている', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const projectId = await createPlanProject(request, token);
  const siteId = await createTaxonomySite(request, token, projectId);
  ctx.plan = { projectId, siteId } satisfies PlanFixture;
});

After({ tags: '@plan' }, async ({ ctx, request }) => {
  const fixture = ctx.plan as PlanFixture | undefined;
  if (!fixture) {
    return;
  }
  const token = await adminToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  await request.delete(`/api/projects/${fixture.projectId}`, { headers });
  if (fixture.siteId !== undefined) {
    await request.delete(`/api/sites/${fixture.siteId}`, { headers, timeout: 120_000 });
  }
});

// --------------------------------------------------------------- 画面操作

Step('記事計画画面を開く', async ({ ctx, page }) => {
  await openPlanPage(page, (ctx.plan as PlanFixture).projectId);
});

Step('記事計画画面を開き直す', async ({ ctx, page }) => {
  await openPlanPage(page, (ctx.plan as PlanFixture).projectId);
});

Step(/^Issue #(\d+) の記事計画画面を開く$/, async ({ ctx, page }, issueNumber: string) => {
  await openPlanPage(page, (ctx.plan as PlanFixture).projectId, Number(issueNumber));
});

Step(/^壁打ちで「(.+)」と発言する$/, async ({ page }, message: string) => {
  await sendChatMessage(page, message);
});

Step('タイトル提案を取得する', async ({ page }) => {
  const proposals = titleCard(page);
  await proposals.getByRole('button', { name: /^タイトル提案を取得/ }).click();
  await expect(proposals.getByRole('button', { name: '提案取得中…' })).toHaveCount(0, {
    timeout: 90_000,
  });
});

Step('記事の構成提案を取得する', async ({ page }) => {
  const structure = structureCard(page);
  await structure.getByRole('button', { name: /^記事の構成を提案/ }).click();
  await expect(structure.getByRole('button', { name: '提案取得中…' })).toHaveCount(0, {
    timeout: 90_000,
  });
  await expect(structure.locator('textarea')).toBeVisible();
});

When('先頭のタイトル案を選んで計画を受け入れる', async ({ ctx, page }) => {
  const proposals = titleCard(page);
  const firstOption = proposals.locator('label').first();
  ctx.selectedTitle = (await firstOption.innerText()).trim();
  await firstOption.locator('input[type="checkbox"]').check();
  await proposals.getByRole('button', { name: /^選択した記事/ }).click();
  await expect(proposals.getByRole('button', { name: '登録中…' })).toHaveCount(0, { timeout: 90_000 });
});

When('この内容でIssueを更新する', async ({ ctx, page }) => {
  const structure = structureCard(page);
  ctx.structure = await structure.locator('textarea').inputValue();
  await structure.getByRole('button', { name: /^この内容でIssueを更新/ }).click();
  await expect(structure.getByRole('button', { name: '更新中…' })).toHaveCount(0, { timeout: 90_000 });
});

When('壁打ち一覧の先頭のセッションを開く', async ({ page }) => {
  await sessionCard(page).locator('button.rounded-full').first().click();
  await expect(aiMessages(page).first()).toBeVisible({ timeout: 30_000 });
});

When(/^Issue一覧の表示条件を「(.+)」に変える$/, async ({ page }, label: string) => {
  await issueCard(page).locator('select').selectOption({ label });
});

// --------------------------------------------------------------- 画面の確認

Then('AIの応答がチャットに表示される', async ({ page }) => {
  const messages = aiMessages(page);
  await expect(messages).not.toHaveCount(0);
  await expect(messages.last().locator('..')).toContainText('E2Eスタブ');
});

Then(/^AIの応答が「(.+)」を踏まえたものになる$/, async ({ page }, expected: string) => {
  await expect(aiMessages(page).last().locator('..')).toContainText(expected);
});

Then(/^チャットに「(.+)」が表示される$/, async ({ page }, message: string) => {
  await expect(chatCard(page)).toContainText(message, { timeout: 30_000 });
});

Then('チャットにエラーが表示される', async ({ page }) => {
  await expect(chatCard(page).locator('p.text-red-600')).toBeVisible();
});

Then(/^壁打ち一覧にセッションが (\d+) 件表示される$/, async ({ page }, countText: string) => {
  const expected = Number(countText);
  if (expected === 0) {
    await expect(sessionCard(page)).toContainText('まだ壁打ちセッションはありません。');
    return;
  }
  await expect(sessionCard(page).locator('button.rounded-full')).toHaveCount(expected, {
    timeout: 30_000,
  });
});

Then(/^タイトル案が (\d+) 件表示される$/, async ({ page }, countText: string) => {
  await expect(titleCard(page).locator('input[type="checkbox"]')).toHaveCount(Number(countText));
});

Then('選んだタイトルがIssueとして登録されたと表示される', async ({ ctx, page }) => {
  const proposals = titleCard(page);
  await expect(proposals).toContainText(ctx.selectedTitle as string);
  await expect(proposals).toContainText('として登録しました');
});

Then('構成案が見出し階層になっている', async ({ page }) => {
  const structure = await structureCard(page).locator('textarea').inputValue();
  const lines = structure.split('\n').map((line) => line.trim());
  expect(lines.filter((line) => line.startsWith('## ')).length, `構成案に第2階層の見出しが無い: ${structure}`)
    .toBeGreaterThan(0);
  expect(lines.filter((line) => line.startsWith('### ')).length, `構成案に第3階層の見出しが無い: ${structure}`)
    .toBeGreaterThan(0);
});

Then(/^Issue一覧に Issue #(\d+) と Issue #(\d+) が表示される$/, async ({ page }, first: string, second: string) => {
  const issues = issueCard(page);
  await expect(issues).toContainText(`#${first}`);
  await expect(issues).toContainText(`#${second}`);
});

Then(/^構成案の入力欄にIssue本文「(.+)」が読み込まれている$/, async ({ page }, body: string) => {
  await expect(structureCard(page).locator('textarea')).toHaveValue(body, { timeout: 30_000 });
});

Then('Issueを更新したと表示される', async ({ page }) => {
  await expect(structureCard(page)).toContainText('を更新しました');
});

Then(/^Issue一覧に「(.+)」というエラーが表示される$/, async ({ page }, message: string) => {
  await expect(issueCard(page).locator('p.text-red-600')).toContainText(message, { timeout: 30_000 });
});

// --------------------------------------------------------------- GitHubスタブ側の確認

Then(/^GitHubスタブの Issue #(\d+) の本文が構成案に置き換わっている$/, async ({ ctx }, issueNumber: string) => {
  const issue = await readStubIssue(Number(issueNumber));
  expect(issue.body, 'GitHub側のIssue本文が構成案に置き換わっていません').toBe(ctx.structure as string);
});

Then(/^GitHubスタブの Issue #(\d+) の担当者が「(.+)」になっている$/,
  async ({}, issueNumber: string, login: string) => {
    const issue = await readStubIssue(Number(issueNumber));
    expect(issue.assignees.map((a) => a.login), 'GitHub側のIssueに担当者が付いていません').toContain(login);
  });

// --------------------------------------------------------------- API 経由の確認

/** メタデータ提案・既存分類の参照は Web UI に導線が無い(VSCode拡張が使う)ため `@api` で確かめる。 */
const PLAN_HISTORY = [
  { role: 'user', content: 'AT9のメタデータ提案を試すテーマ' },
  { role: 'assistant', content: 'もう少し具体的に教えてください。' },
];

When('メタデータ提案を取得する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const projectId = (ctx.plan as PlanFixture).projectId;
  const response = await request.post(
    `/api/projects/${projectId}/article-plan/suggest-metadata`,
    { headers: { Authorization: `Bearer ${token}` }, data: { history: PLAN_HISTORY }, timeout: 120_000 }
  );
  expect(
    response.ok(),
    `メタデータ提案に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.metadata = await response.json();

  const categories = await request.get(`/api/projects/${projectId}/article-plan/categories`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(categories.ok(), '既存カテゴリの取得に失敗しました').toBe(true);
  ctx.existingCategories = await categories.json();
});

Then('提案されたカテゴリは公開先の既存カテゴリだけで構成される', async ({ ctx }) => {
  const metadata = ctx.metadata as { categories: string[] };
  const existing = ctx.existingCategories as string[];
  expect(existing, '公開先に既存カテゴリがありません(フィクスチャの不備)').not.toHaveLength(0);
  expect(metadata.categories, '提案されたカテゴリが空です').not.toHaveLength(0);
  for (const category of metadata.categories) {
    expect(existing, `提案されたカテゴリ「${category}」が公開先に存在しません`).toContain(category);
  }
  expect([...metadata.categories].sort(), '公開先の既存カテゴリが提案に反映されていません')
    .toEqual([...existing].sort());
});

Then('提案にタイトル案とタグ案が含まれる', async ({ ctx }) => {
  const metadata = ctx.metadata as { titles: string[]; slugs: string[]; tags: string[] };
  expect(metadata.titles, 'タイトル案が返っていません').not.toHaveLength(0);
  expect(metadata.slugs, 'スラッグ案が返っていません').not.toHaveLength(0);
  expect(metadata.tags, 'タグ案が返っていません').not.toHaveLength(0);
});

When('公開先の既存分類を取得する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const projectId = (ctx.plan as PlanFixture).projectId;
  const headers = { Authorization: `Bearer ${token}` };

  const hierarchy = await request.get(
    `/api/projects/${projectId}/article-plan/categories/hierarchy`, { headers }
  );
  expect(hierarchy.ok(), 'カテゴリ階層の取得に失敗しました').toBe(true);
  ctx.categoryHierarchy = await hierarchy.json();

  const tags = await request.get(`/api/projects/${projectId}/article-plan/tags`, { headers });
  expect(tags.ok(), '既存タグの取得に失敗しました').toBe(true);
  ctx.existingTags = await tags.json();
});

Then('カテゴリ階層に親カテゴリと子カテゴリの対応が含まれる', async ({ ctx }) => {
  const hierarchy = ctx.categoryHierarchy as { name: string; parentName: string | null }[];
  expect(hierarchy, `親カテゴリ「${FIXTURE_PARENT_CATEGORY}」が階層に現れません`)
    .toContainEqual({ name: FIXTURE_PARENT_CATEGORY, parentName: null });
  expect(hierarchy, `子カテゴリ「${FIXTURE_CHILD_CATEGORY}」に親が対応付いていません`)
    .toContainEqual({ name: FIXTURE_CHILD_CATEGORY, parentName: FIXTURE_PARENT_CATEGORY });
});

Then('既存タグ一覧に公開先のタグが含まれる', async ({ ctx }) => {
  expect(ctx.existingTags as string[], `公開先のタグ「${FIXTURE_TAG}」が返っていません`).toContain(FIXTURE_TAG);
});

When(/^Issue #(\d+) を担当者へ割り当てる$/, async ({ ctx, request }, issueNumber: string) => {
  const token = await adminToken(request);
  const projectId = (ctx.plan as PlanFixture).projectId;
  const response = await request.post(
    `/api/projects/${projectId}/article-plan/issues/${issueNumber}/assign`,
    { headers: { Authorization: `Bearer ${token}` }, timeout: 60_000 }
  );
  expect(
    response.ok(),
    `Issueの割り当てに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.assignResult = await response.json();
});

Then(/^割り当て結果の担当者が「(.+)」になる$/, async ({ ctx }, login: string) => {
  const result = ctx.assignResult as { assignedLogin: string };
  expect(result.assignedLogin, '割り当て結果の担当者が想定と違います').toBe(login);
});
