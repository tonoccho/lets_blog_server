import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  createFixtureProject,
  deleteFixtureProject,
  expect,
  fetchAccessToken,
} from '../support';

/**
 * 旧プレビュー経路の削除(issue #1564)と、プレビュー系シナリオ共通の背景のステップ定義。
 *
 * 背景のステップ(プレビュー検証用サイトとプロジェクトの用意)と後片付けは、
 * `signedPreview.steps.ts`(issue #1561)も使う。サイトは`publishTaxonomy.steps.ts`(issue #1174)と
 * 同じ「固定siteKeyで冪等に用意し、実行をまたいで再利用する」パターンで用意する。
 */

/** プレビュー検証用サイト。冪等に用意し、実行をまたいで再利用する。 */
const PREVIEW_SITE_KEY = 'at65previewprobe';
const PREVIEW_SITE_ADMIN_USER = 'at65previewadmin';

/** WordPress自動構築の待ち上限。分単位でかかりうる。 */
const PROVISION_TIMEOUT_MS = 600_000;

interface SiteFixture {
  id: number;
  siteKey: string;
}

/** `WordPressSiteProvisioningService#normalizeSlug` と同じ正規化。 */
function wpSlug(siteKey: string): string {
  return siteKey.toLowerCase().replace(/[^a-z0-9-]/g, '-');
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await adminToken(request);
  return { Authorization: `Bearer ${token}` };
}

async function ensureManagedSite(request: APIRequestContext): Promise<SiteFixture> {
  const headers = await adminHeaders(request);
  const list = await request.get('/api/sites', { headers });
  expect(
    list.ok(),
    `サイト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`
  ).toBe(true);
  const existing = ((await list.json()) as SiteFixture[]).find((site) => site.siteKey === PREVIEW_SITE_KEY);
  if (existing) {
    return existing;
  }
  const created = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: 'AT6-5 preview probe site',
      siteKey: PREVIEW_SITE_KEY,
      title: 'AT6-5 Preview Probe',
      adminUser: PREVIEW_SITE_ADMIN_USER,
      adminEmail: 'at65-preview-probe@letsblog.local',
      adminPassword: 'At65Preview#Passw0rd1',
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
      name: 'AT6-5 preview probe site',
      siteKey: PREVIEW_SITE_KEY,
      adminUser: PREVIEW_SITE_ADMIN_USER,
    },
  });
  expect(
    adopted.ok(),
    `既存WordPressの取り込みに失敗しました (status=${adopted.status()}): ${await adopted.text()}`
  ).toBe(true);
  return (await adopted.json()) as SiteFixture;
}

// ------------------------------------------------------- 背景

Given('プレビュー検証用のWordPressサイトがあり、プロジェクトのテスト環境に紐づいている', async ({ ctx, request }) => {
  const site = await ensureManagedSite(request);
  const token = await adminToken(request);
  const project = await createFixtureProject(request, token, 'at65-preview');
  const headers = await adminHeaders(request);
  const bound = await request.post(`/api/projects/${project.id}/environments`, {
    headers,
    data: { environment: 'test', siteId: site.id },
  });
  expect(
    bound.ok(),
    `テスト環境へのサイト紐付けに失敗しました (status=${bound.status()}): ${await bound.text()}`
  ).toBe(true);

  ctx.previewSiteId = site.id;
  ctx.previewSiteSlug = wpSlug(site.siteKey);
  ctx.previewProjectId = project.id;
});

// ------------------------------------------------------- 旧経路は存在しない(#1564)

/** 旧プレビュー経路のパスごとの、呼び出しに必要な最小のクエリ・本文(以前は必須だったもの)。 */
const LEGACY_PREVIEW_REQUESTS: Record<string, { params?: Record<string, string | number>; data?: unknown }> = {
  'theme-css': { params: {} },
  skeleton: { data: { title: 'E2E-1564', contentHtml: '<p>E2E-1564</p>' } },
  'preview-post': { params: { postId: '1' } },
};

When(
  '旧プレビュー経路の「{word}」「{word}」を呼ぶ',
  async ({ ctx, request }, method: string, legacyPath: string) => {
    const headers = await adminHeaders(request);
    const spec = LEGACY_PREVIEW_REQUESTS[legacyPath];
    const params = { ...(spec.params ?? {}), siteId: ctx.previewSiteId as number };
    const url = `/api/projects/${ctx.previewProjectId as number}/preview/${legacyPath}`;
    const options = {
      headers,
      params,
      ...(spec.data === undefined ? {} : { data: { ...(spec.data as object), siteId: ctx.previewSiteId } }),
    };
    ctx.legacyPreviewStatus = (await request.fetch(url, { method, ...options })).status();
  }
);

Then('応答は 404 になる', async ({ ctx }) => {
  expect(ctx.legacyPreviewStatus, '旧プレビュー経路が応答しています').toBe(404);
});

// ------------------------------------------------------- 後片付け

After({ tags: '@publishing' }, async ({ ctx, request }) => {
  const projectId = ctx.previewProjectId as number | undefined;
  if (projectId === undefined) {
    return;
  }
  const token = await adminToken(request);
  await deleteFixtureProject(request, token, projectId);
});
