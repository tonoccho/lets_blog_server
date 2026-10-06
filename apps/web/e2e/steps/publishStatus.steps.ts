import { execFileSync } from 'node:child_process';
import path from 'node:path';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  createFixtureProject,
  deleteFixtureProject,
  expect,
  fetchAccessToken,
  loginAsAdmin,
} from '../support';

/**
 * 記事の取り下げと公開ステータス(下書き・予約)(issue #1172 / AT-6-2)のステップ定義。
 *
 * 兄弟issue(#932系列の子issue群)とステップ定義ファイルを共有しない方針
 * (`publishLifecycle.steps.ts`・`publishTaxonomy.steps.ts`・`publishPreview.steps.ts`と同様。
 * 相乗りしない)のため、必要なヘルパーはこのファイル内に閉じて持つ。
 *
 * ## 専用サイトを冪等に用意し、本番環境へ紐づける理由
 *
 * 「WordPress側から消える」「下書き状態で一般に見えない」「指定日時が設定される」の
 * 正しさは、実際のWordPress側の投稿の状態(wp-cli)を見ないと確かめられない。#1167の
 * プロビジョニング済みサイト共有フィクスチャ(`site-provisioning.steps.ts`)はサイトの識別子
 * だけを永続化しWordPress管理者の認証情報は残さないため、wp-cliで直接アクセスする必要が
 * ある本ファイルには使えない。そこで`publishLifecycle.steps.ts`(#1171)と同じ「固定siteKey
 * で冪等に用意し、実行をまたいで再利用する」パターンを踏襲する。予約投稿は本番サイトへの
 * 投稿でのみ有効(`PostPublishService#resolvePublishScheduledAt`、issue #520)なため、
 * テスト環境ではなく本番環境として紐づける。
 */

/** リポジトリルート(apps/web/e2e/steps から4階層上)。 */
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

/** 投稿ステータス検証用サイト。冪等に用意し、実行をまたいで再利用する。 */
const STATUS_SITE_KEY = 'at62publishprobe';
const STATUS_SITE_ADMIN_USER = 'at62publishadmin';

/** WordPress自動構築の待ち上限。分単位でかかりうる。 */
const PROVISION_TIMEOUT_MS = 600_000;

interface SiteFixture {
  id: number;
  siteKey: string;
}

interface PostPublishResponse {
  wpPostId: string;
  wpPostUrl: string;
  status: string;
}

interface PostStatusOption {
  value: string;
  label: string;
}

/** `WordPressSiteProvisioningService#normalizeSlug` と同じ正規化。 */
function wpSlug(siteKey: string): string {
  return siteKey.toLowerCase().replace(/[^a-z0-9-]/g, '-');
}

/**
 * WordPress コンテナで wp-cli を実行し、標準出力を返す。
 *
 * `docker compose exec` の cwd はコンテナの `WorkingDir` になるため、
 * サイトディレクトリへは `sh -c 'cd ... && ...'` で自分で移動する
 * (docs/ACCEPTANCE_TESTING.md §9「`working_dir` はマウント先にしない」)。
 */
function wpCli(slug: string, command: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'wordpress', 'sh', '-c', `cd /var/www/html/sites/${slug} && wp --allow-root ${command}`],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 180_000 }
  ).trim();
}

/** 投稿の指定フィールドをwp-cliで取得する。投稿が存在しなければnull。 */
function postField(slug: string, postId: string, field: string): string | null {
  try {
    return wpCli(slug, `post get ${postId} --field=${field}`);
  } catch {
    // `wp post get`は対象が存在しない場合に非ゼロ終了する。
    return null;
  }
}

/** 指定ステータスの投稿ID一覧をwp-cliで取得する。 */
function postIdsByStatus(slug: string, status: string): string[] {
  const output = wpCli(slug, `post list --post_type=post --post_status=${status} --format=ids`);
  return output.split(/\s+/).filter((id) => id !== '');
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await adminToken(request);
  return { Authorization: `Bearer ${token}` };
}

/** issue #765と同じ理由(並列実行時の衝突対策)でユニークな名前を作る。 */
function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

async function ensureManagedSite(request: APIRequestContext): Promise<SiteFixture> {
  const headers = await adminHeaders(request);
  const list = await request.get('/api/sites', { headers });
  expect(
    list.ok(),
    `サイト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`
  ).toBe(true);
  const existing = ((await list.json()) as SiteFixture[]).find((site) => site.siteKey === STATUS_SITE_KEY);
  if (existing) {
    return existing;
  }
  const created = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: 'AT6-2 publish status probe site',
      siteKey: STATUS_SITE_KEY,
      title: 'AT6-2 Publish Status Probe',
      adminUser: STATUS_SITE_ADMIN_USER,
      adminEmail: 'at62-publish-probe@letsblog.local',
      adminPassword: 'At62Publish#Passw0rd1',
    },
    timeout: PROVISION_TIMEOUT_MS,
  });
  if (created.status() === 409) {
    // provision-agent側には既に実体があるがDBには未登録(中断した前回実行の取り残し、または
    // このシナリオ自体を@mode:serialなしで並列実行してしまった場合)。
    // site-adoption.steps.tsと同じ「取り込み」で救う(site-adoption.feature参照)。
    return adoptExistingManagedSite(request, headers);
  }
  expect(
    created.ok(),
    `マネージドWordPressの構築に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  return (await created.json()) as SiteFixture;
}

async function adoptExistingManagedSite(
  request: APIRequestContext,
  headers: Record<string, string>
): Promise<SiteFixture> {
  const adopted = await request.post('/api/sites/managed-wordpress/adopt', {
    headers,
    data: {
      name: 'AT6-2 publish status probe site',
      siteKey: STATUS_SITE_KEY,
      adminUser: STATUS_SITE_ADMIN_USER,
    },
  });
  expect(
    adopted.ok(),
    `既存WordPressの取り込みに失敗しました (status=${adopted.status()}): ${await adopted.text()}`
  ).toBe(true);
  return (await adopted.json()) as SiteFixture;
}

interface PublishOptions {
  title: string;
  slug: string;
  markdown: string;
  status?: string;
  publishScheduledAt?: string;
}

async function publish(
  request: APIRequestContext,
  token: string,
  siteKey: string,
  options: PublishOptions
): Promise<PostPublishResponse> {
  const form = new FormData();
  form.append('site', siteKey);
  form.append('title', options.title);
  form.append('slug', options.slug);
  form.append('status', options.status ?? 'publish');
  form.append('markdown', options.markdown);
  if (options.publishScheduledAt) {
    form.append('publishScheduledAt', options.publishScheduledAt);
  }

  const response = await request.post('/api/posts/publish', {
    headers: { Authorization: `Bearer ${token}` },
    multipart: form,
    timeout: 120_000,
  });
  expect(
    response.ok(),
    `記事公開に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as PostPublishResponse;
}

async function deletePostRequest(
  request: APIRequestContext,
  token: string,
  siteKey: string,
  wpPostId: string
): Promise<void> {
  const response = await request.delete(`/api/posts/${siteKey}/${wpPostId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    response.status(),
    `記事の取り下げに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(204);
}

async function lookupPostStatus(
  request: APIRequestContext,
  token: string,
  siteKey: string,
  slug: string
): Promise<string> {
  const response = await request.get(`/api/posts/${siteKey}/by-slug/${slug}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    response.ok(),
    `既存投稿の照会に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return ((await response.json()) as { status: string }).status;
}

// ------------------------------------------------------- 背景

Given('投稿ステータス検証用のWordPressサイトがあり、プロジェクトの本番環境に紐づいている', async ({ ctx, request }) => {
  const site = await ensureManagedSite(request);
  const token = await adminToken(request);
  const project = await createFixtureProject(request, token, 'at62-publish-status');
  const headers = await adminHeaders(request);
  const bound = await request.post(`/api/projects/${project.id}/environments`, {
    headers,
    data: { environment: 'production', siteId: site.id },
  });
  expect(
    bound.ok(),
    `本番環境へのサイト紐付けに失敗しました (status=${bound.status()}): ${await bound.text()}`
  ).toBe(true);

  ctx.statusSiteKey = site.siteKey;
  ctx.statusSiteSlug = wpSlug(site.siteKey);
  ctx.statusProjectId = project.id;
  ctx.statusCleanupPostIds = [] as string[];
});

// ------------------------------------------------------- シナリオ1: 取り下げ(親シナリオ4)

When('記事を公開してから取り下げる', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const siteKey = ctx.statusSiteKey as string;
  const unique = uniqueSuffix();
  const title = `E2E-1172-Withdraw-${unique}`;
  const slug = `e2e-1172-withdraw-${unique}`;

  const published = await publish(request, token, siteKey, {
    title,
    slug,
    markdown: `# ${title}\n\nissue #1172のE2Eが取り下げ検証のために公開した記事です。\n`,
    status: 'publish',
  });
  (ctx.statusCleanupPostIds as string[]).push(published.wpPostId);

  await deletePostRequest(request, token, siteKey, published.wpPostId);

  ctx.withdrawnPostId = published.wpPostId;
  ctx.withdrawnSlug = slug;
});

Then('WordPress側でその記事はゴミ箱状態になっている', async ({ ctx }) => {
  const slug = ctx.statusSiteSlug as string;
  const postId = ctx.withdrawnPostId as string;
  const status = postField(slug, postId, 'post_status');
  expect(status, `取り下げ後のWordPress側の投稿(id=${postId})のステータスが取得できません`).toBe('trash');
});

Then('投稿履歴の状態が取り下げ済みとして更新されている', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const siteKey = ctx.statusSiteKey as string;
  const slug = ctx.withdrawnSlug as string;
  const status = await lookupPostStatus(request, token, siteKey, slug);
  expect(status, '取り下げ後の投稿履歴の状態がtrashに更新されていません').toBe('trash');
});

// ------------------------------------------------------- シナリオ2: 下書き公開(親シナリオ5)

When('記事を下書きとして公開する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const siteKey = ctx.statusSiteKey as string;
  const unique = uniqueSuffix();
  const title = `E2E-1172-Draft-${unique}`;
  const slug = `e2e-1172-draft-${unique}`;

  const result = await publish(request, token, siteKey, {
    title,
    slug,
    markdown: `# ${title}\n\nissue #1172のE2Eが下書き検証のために公開した記事です。\n`,
    status: 'draft',
  });

  ctx.draftPostId = result.wpPostId;
  ctx.draftSlug = slug;
  (ctx.statusCleanupPostIds as string[]).push(result.wpPostId);
});

Then('WordPress側でその記事は下書き状態になっている', async ({ ctx }) => {
  const slug = ctx.statusSiteSlug as string;
  const postId = ctx.draftPostId as string;
  const status = postField(slug, postId, 'post_status');
  expect(status, `下書き公開後のWordPress側の投稿(id=${postId})のステータスが取得できません`).toBe('draft');
});

Then('その記事はWordPress側の公開済み一覧に含まれない', async ({ ctx }) => {
  const slug = ctx.statusSiteSlug as string;
  const postId = ctx.draftPostId as string;
  const publishedIds = postIdsByStatus(slug, 'publish');
  expect(
    publishedIds.includes(postId),
    `下書き投稿(id=${postId})がWordPress側の公開済み一覧(status=publish)に含まれています: ${publishedIds.join(', ')}`
  ).toBe(false);
});

// ------------------------------------------------------- シナリオ3: 予約投稿(親シナリオ6)

When('記事を予約投稿する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const siteKey = ctx.statusSiteKey as string;
  const unique = uniqueSuffix();
  const title = `E2E-1172-Scheduled-${unique}`;
  const slug = `e2e-1172-scheduled-${unique}`;
  // 秒精度(WordPress側はミリ秒を保持しない)で、確実に未来となる2日後を指定する。
  const scheduledAt = new Date(Date.now() + 2 * 24 * 60 * 60 * 1000);
  scheduledAt.setMilliseconds(0);
  const publishScheduledAt = scheduledAt.toISOString();

  const result = await publish(request, token, siteKey, {
    title,
    slug,
    markdown: `# ${title}\n\nissue #1172のE2Eが予約投稿検証のために公開した記事です。\n`,
    status: 'publish',
    publishScheduledAt,
  });

  ctx.scheduledPostId = result.wpPostId;
  ctx.scheduledSlug = slug;
  ctx.scheduledAtIso = publishScheduledAt;
  (ctx.statusCleanupPostIds as string[]).push(result.wpPostId);
});

Then('WordPress側でその記事は予約状態になっている', async ({ ctx }) => {
  const slug = ctx.statusSiteSlug as string;
  const postId = ctx.scheduledPostId as string;
  const status = postField(slug, postId, 'post_status');
  expect(status, `予約投稿後のWordPress側の投稿(id=${postId})のステータスが取得できません`).toBe('future');
});

Then('WordPress側に設定された公開予定日時が指定した日時と一致する', async ({ ctx }) => {
  const slug = ctx.statusSiteSlug as string;
  const postId = ctx.scheduledPostId as string;
  const postDateGmt = postField(slug, postId, 'post_date_gmt');
  expect(postDateGmt, `予約投稿(id=${postId})のpost_date_gmtが取得できません`).not.toBeNull();

  const expected = (ctx.scheduledAtIso as string).slice(0, 19).replace('T', ' ');
  expect(postDateGmt, 'WordPress側に設定された公開予定日時(post_date_gmt)が指定した日時と一致しません').toBe(expected);
});

// ------------------------------------------------------- シナリオ4: post-statusesとUIの一致(親シナリオ7)

When('投稿ステータス一覧をAPIから取得する', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const response = await request.get('/api/metadata/post-statuses', { headers });
  expect(
    response.ok(),
    `投稿ステータス一覧の取得に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.apiPostStatuses = (await response.json()) as PostStatusOption[];
});

When('一括管理画面のポスト\\/ページタブでステータス変更の選択肢を開く', async ({ ctx, request, page }) => {
  const token = await adminToken(request);
  const siteKey = ctx.statusSiteKey as string;
  const unique = uniqueSuffix();
  const title = `E2E-1172-StatusList-${unique}`;
  const slug = `e2e-1172-statuslist-${unique}`;

  // 一括管理の「ポスト/ページ」タブは、比較対象の投稿が1件も無いと行(ステータス変更の
  // 選択肢を含むセレクト)自体が描画されないため、あらかじめ1件公開しておく。
  const result = await publish(request, token, siteKey, {
    title,
    slug,
    markdown: `# ${title}\n\nissue #1172のE2Eがステータス選択肢一致検証のために公開した記事です。\n`,
    status: 'publish',
  });
  (ctx.statusCleanupPostIds as string[]).push(result.wpPostId);

  await loginAsAdmin(page);
  await page.goto(`/projects/${ctx.statusProjectId as number}`);
  await page.getByRole('button', { name: 'メンテナンス', exact: true }).click();
  await page.getByRole('button', { name: 'ポスト/ページ', exact: true }).click();

  const row = page.locator('tbody tr').filter({ hasText: slug });
  await expect(row).toBeVisible({ timeout: 30000 });

  // このセレクトは`<option value="">ステータス変更…</option>`(プレースホルダー、
  // PostComparisonTable.tsx:181)を常時描画するため、options.first()がattachedになる
  // のを待つだけでは実データの到着を待てない。実選択肢はfetchPostStatusesAction()
  // (同32-36行)の解決後にまとめて追加されるので、プレースホルダー分(1件)を超えて
  // 選択肢が増えるまでポーリングする(#1359)。
  const options = row.locator('select option');
  await expect
    .poll(() => options.count(), {
      timeout: 15000,
      message:
        'ステータス変更セレクトの選択肢がプレースホルダーのみのまま増えない' +
        '(fetchPostStatusesActionの解決を待てていない可能性)',
    })
    .toBeGreaterThan(1);
  const uiOptions = await options.evaluateAll((elements) =>
    (elements as HTMLOptionElement[])
      .map((el) => ({ value: el.value, label: el.textContent ?? '' }))
      .filter((opt) => opt.value !== '')
  );
  expect(
    uiOptions.length,
    '選択肢の増加を確認した直後にもかかわらずUI側が空配列だった' +
      '(プレースホルダー以外の選択肢が描画されていない可能性)'
  ).toBeGreaterThan(0);
  ctx.uiPostStatusOptions = uiOptions;
});

Then('APIの投稿ステータス一覧とUIの選択肢が一致する', async ({ ctx }) => {
  const apiOptions = (ctx.apiPostStatuses as PostStatusOption[])
    .map((opt) => ({ value: opt.value, label: opt.label }))
    .sort((a, b) => a.value.localeCompare(b.value));
  const uiOptions = (ctx.uiPostStatusOptions as PostStatusOption[])
    .slice()
    .sort((a, b) => a.value.localeCompare(b.value));

  expect(uiOptions, 'UIのステータス選択肢がAPIの投稿ステータス一覧と一致しません').toEqual(apiOptions);
});

// ------------------------------------------------------- 後片付け

After({ tags: '@publishing' }, async ({ ctx, request }) => {
  const projectId = ctx.statusProjectId as number | undefined;
  if (projectId !== undefined) {
    const token = await adminToken(request);
    await deleteFixtureProject(request, token, projectId);
  }

  const slug = ctx.statusSiteSlug as string | undefined;
  const postIds = (ctx.statusCleanupPostIds as string[] | undefined) ?? [];
  if (slug) {
    for (const postId of postIds) {
      try {
        wpCli(slug, `post delete ${postId} --force`);
      } catch {
        // 既に削除済み等は後片付けの失敗としては扱わない。
      }
    }
  }
});
