import { execFileSync } from 'node:child_process';
import path from 'node:path';
import type { APIRequestContext } from '@playwright/test';
import { expect } from './index';
import { adminHeaders } from './responseBudgetFixtures';

/**
 * ガベージコレクション・一括管理の比較取得の Server Action の3秒予算シナリオ(issue #1477)が使う、
 * 実 WordPress(`lbs-wordpress` コンテナ)を相手にした準備と後片付け。
 *
 * サイトとプロジェクトは AT-7(`bulkComparison.steps.ts`)が固定キーで冪等に用意しているものをそのまま使う
 * (無ければ同じ引数で構築する。構築は分単位)。サイトには一意な名前のカテゴリ・プラグイン・投稿・
 * メディアだけを作り、シナリオの後で必ず消す。
 */

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

export const MASTER_SITE_KEY = 'at7cmpmaster';
export const TARGET_SITE_KEY = 'at7cmptarget';

const PROVISION_TIMEOUT_MS = 600_000;

/** `WordPressSiteProvisioningService#normalizeSlug` と同じ正規化。 */
export function wpSlug(siteKey: string): string {
  return siteKey.toLowerCase().replace(/[^a-z0-9-]/g, '-');
}

export function wpShell(siteSlug: string, script: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'wordpress', 'sh', '-c', `cd /var/www/html/sites/${siteSlug} && ${script}`],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 180_000 }
  ).trim();
}

export function wpCli(siteSlug: string, command: string): string {
  return wpShell(siteSlug, `wp --allow-root ${command}`);
}

interface SiteFixture {
  id: number;
  siteKey: string;
}

export async function ensureManagedSite(request: APIRequestContext, siteKey: string): Promise<SiteFixture> {
  const headers = await adminHeaders(request);
  const list = await request.get('/api/sites', { headers });
  expect(list.ok(), `サイト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`).toBe(true);
  const existing = ((await list.json()) as SiteFixture[]).find((site) => site.siteKey === siteKey);
  if (existing) return existing;
  const created = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: `AT7 comparison ${siteKey}`,
      siteKey,
      title: `AT7 ${siteKey}`,
      adminUser: `${siteKey}admin`.slice(0, 30),
      adminEmail: `${siteKey}@letsblog.local`,
      adminPassword: 'At7Comparison#Passw0rd1',
    },
    timeout: PROVISION_TIMEOUT_MS,
  });
  expect(created.ok(), `マネージドWordPressの構築に失敗しました (status=${created.status()}): ${await created.text()}`).toBe(
    true
  );
  return (await created.json()) as SiteFixture;
}

/** AT-7 が使う比較用プロジェクトの固定 slug(`bulkComparison.steps.ts`)。 */
const AT7_PROJECT_SLUG = 'e2e-at7-cmp';

export const BULK_PROJECT_KEY = 'responseBudgetBulkProjectId';

/**
 * AT-7 の比較用プロジェクト(test = マスター、local = 対象の実 WordPress を紐付け済み)を冪等に用意し、
 * その id を `ctx.responseBudgetBulkProjectId` に置く。
 *
 * 使い捨てのプロジェクトにしない理由: サイトは同時に1つのプロジェクトにしか紐付けられない(409)ので、
 * AT-7 が持つ固定のサイトを別のプロジェクトへは紐付けられず、サイトを増やすと分単位の構築が増える。
 * このプロジェクトとサイトは共有資源で、シナリオが作るのは一意な名前のリソースだけ(後で消す)。
 * 共通の After が削除する `responseBudgetProjectId` には**置かない**(共有のプロジェクトを消さない)。
 */
export async function useBulkProject(request: APIRequestContext, ctx: Record<string, unknown>): Promise<void> {
  const headers = await adminHeaders(request);
  const master = await ensureManagedSite(request, MASTER_SITE_KEY);
  const target = await ensureManagedSite(request, TARGET_SITE_KEY);
  const list = await request.get('/api/projects', { headers });
  expect(list.ok(), `プロジェクト一覧の取得に失敗しました (status=${list.status()})`).toBe(true);
  let id = ((await list.json()) as { id: number; slug: string }[]).find((p) => p.slug === AT7_PROJECT_SLUG)?.id;
  if (id === undefined) {
    const created = await request.post('/api/projects', {
      headers,
      data: { name: 'E2E AT7 Category Comparison', slug: AT7_PROJECT_SLUG },
    });
    expect(created.ok(), `プロジェクトの作成に失敗しました (status=${created.status()}): ${await created.text()}`).toBe(
      true
    );
    id = ((await created.json()) as { id: number }).id;
  }
  for (const [environment, site] of [['test', master], ['local', target]] as const) {
    const bound = await request.post(`/api/projects/${id}/environments`, {
      headers,
      data: { environment, siteId: site.id },
    });
    expect(bound.ok(), `${environment}環境の紐付けに失敗しました (status=${bound.status()}): ${await bound.text()}`).toBe(true);
  }
  ctx[BULK_PROJECT_KEY] = id;
}

/** 1x1の透明PNG。メディアの中身は判定に関係しないので固定バイト列で足りる。 */
const PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

/** サイトへ添付ファイルを1件作り、その添付IDを返す(どの投稿からも参照されない=未参照メディア)。 */
export function importUnreferencedMedia(site: string, title: string): string {
  const file = `/tmp/${title}.png`;
  execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'wordpress', 'sh', '-c', `printf '%s' '${PNG_BASE64}' | base64 -d > ${file}`],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 60_000 }
  );
  return wpCli(site, `media import ${file} --title='${title}' --porcelain`);
}

export function deleteMedia(site: string, id: string): void {
  try {
    wpCli(site, `post delete ${id} --force`);
  } catch {
    // GC で消えていれば何もしない。
  }
}
