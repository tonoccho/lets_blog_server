import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import type { Locator, Page } from '@playwright/test';
import { After, When, Then } from './fixtures';
import { expect, loginAsAdmin } from '../support';
import {
  BULK_INSTALL_ACCEPTED_NOTICE,
  findBulkInstallJob,
  latestBulkInstallJobId,
  waitForBulkInstallJobDone,
  type BulkInstallJobType,
} from '../support/bulkInstallJobs';
import { clickUntilVisible } from '../support/retryClick';

/**
 * Web画面の全環境インストール(slug / zip)の非同期化(issue #1702、`bulk-install-web-job.feature`)のステップ定義。
 * 足場「環境同期検証用のプロジェクトと2つのmanagedサイトがある」は既存のもの(`environmentSync.steps.ts`)を使う。
 * 実際にインストールを試みるので、完了待ちの上限を長く取る。
 */

const UI_TIMEOUT_MS = 30_000;
const TAB_LOAD_TIMEOUT_MS = 90_000;
const JOB_TIMEOUT_MS = 240_000;

const JOB_TYPE_BY_KIND: Record<'slug' | 'zip', BulkInstallJobType> = {
  slug: 'bulk_slug_install',
  zip: 'bulk_zip_install',
};
const QUEUE_LABEL_BY_TYPE: Record<BulkInstallJobType, string> = {
  bulk_slug_install: '全環境へのインストール',
  bulk_zip_install: 'zip の全環境インストール',
};
const SUBMIT_BUTTON_BY_KIND = { slug: 'インストール', zip: '全環境へインストール' } as const;

/** 空の zip(End of Central Directory レコードだけ)。ダミーのプラグイン zip として十分(受理の検証は拡張子と型だけ)。 */
const EMPTY_ZIP = Buffer.from([0x50, 0x4b, 0x05, 0x06, ...new Array(18).fill(0)]);

function queueItem(page: Page, jobId: number): Locator {
  return page.locator(`[data-testid="info-rail-queue-item"][data-job-id="${jobId}"]`);
}

async function openPluginTab(page: Page, projectId: number): Promise<void> {
  await page.goto(`/projects/${projectId}`);
  // タブ切り替えはべき等なので、ハイドレーション前の空振りに備えて期待する表示が出るまで押し直す(#1386)。
  const pluginTab = page.getByRole('button', { name: 'プラグイン', exact: true });
  await clickUntilVisible(page.getByRole('button', { name: 'メンテナンス', exact: true }), pluginTab);
  await clickUntilVisible(pluginTab, page.getByRole('button', { name: '全環境へインストール' }), { visibleTimeoutMs: 10_000 });
}

async function requestSlugInstall(page: Page): Promise<void> {
  // 一覧(各環境の取得)を読み終えると「新規インストール」が押せる。
  await page.getByRole('button', { name: '+ 新規インストール' }).click({ timeout: TAB_LOAD_TIMEOUT_MS });
  await page.getByLabel('対象環境').selectOption('__all__');
  // wordpress.org に存在しない一意な slug なので、各環境は決定的に「失敗」で返る。
  await page.getByPlaceholder('akismet').fill(`e2e-1702-no-such-plugin-${Date.now()}`);
  await page.getByRole('button', { name: SUBMIT_BUTTON_BY_KIND.slug, exact: true }).click();
}

async function requestZipInstall(page: Page, ctx: Record<string, unknown>): Promise<void> {
  const dir = await mkdtemp(join(tmpdir(), 'e2e-1702-'));
  ctx.bijTmpDir = dir;
  const zipPath = join(dir, `e2e-1702-${Date.now()}.zip`);
  await writeFile(zipPath, EMPTY_ZIP);
  await page.locator('input[type="file"]').setInputFiles(zipPath);
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: SUBMIT_BUTTON_BY_KIND.zip, exact: true }).click();
}

After({ tags: '@bulk-install-web-job' }, async ({ ctx }) => {
  const dir = ctx.bijTmpDir as string | undefined;
  if (dir) await rm(dir, { recursive: true, force: true });
});

When(/^一括管理の「(slug|zip)」から全環境インストールを要求する$/, async ({ page, request, ctx }, kind: 'slug' | 'zip') => {
  if (!ctx.esLoggedIn) {
    await loginAsAdmin(page);
    ctx.esLoggedIn = true;
  }
  const projectId = ctx.esProjectId as number;
  const type = JOB_TYPE_BY_KIND[kind];
  ctx.bijKind = kind;
  ctx.bijJobType = type;
  ctx.bijJobBefore = await latestBulkInstallJobId(request, projectId, type);
  await openPluginTab(page, projectId);
  if (kind === 'slug') {
    await requestSlugInstall(page);
  } else {
    await requestZipInstall(page, ctx);
  }
  // 受理前に離れると送信中の Server Action が中断され、ジョブが作られない。受理後の離脱を確かめるので、受理を待つ。
  await expect(page.getByText(BULK_INSTALL_ACCEPTED_NOTICE)).toBeVisible({ timeout: UI_TIMEOUT_MS });
});

When('ダッシュボードへ移動してから、全環境インストールを要求したプロジェクトの管理画面へ戻る', async ({ ctx, page }) => {
  await page.goto('/', { waitUntil: 'commit' });
  await page.goto(`/projects/${ctx.esProjectId}`, { waitUntil: 'commit' });
});

Then('全環境インストールの要求を受け付けた旨がパネルに示され、ボタンは押せる状態のままである', async ({ ctx, page }) => {
  await expect(page.getByText(BULK_INSTALL_ACCEPTED_NOTICE)).toBeVisible({ timeout: UI_TIMEOUT_MS });
  const kind = ctx.bijKind as 'slug' | 'zip';
  await expect(page.getByRole('button', { name: SUBMIT_BUTTON_BY_KIND[kind], exact: true })).toBeEnabled();
  // 完了を待たないので、環境ごとの結果はまだ出ない。
  await expect(page.getByTestId('bulk-job-result')).toHaveCount(0);
});

Then(/^処理キューにその「(bulk_slug_install|bulk_zip_install)」のジョブが現れる$/, async ({ ctx, page, request }, type: BulkInstallJobType) => {
  const job = await findBulkInstallJob(request, ctx.esProjectId as number, type, ctx.bijJobBefore as number);
  ctx.bijJobId = job.id;
  await expect(queueItem(page, job.id)).toBeVisible({ timeout: UI_TIMEOUT_MS });
  await expect(queueItem(page, job.id)).toContainText(QUEUE_LABEL_BY_TYPE[type]);
});

When('処理キューのその全環境インストールのジョブが完了するまで待つ', async ({ ctx, page, request }) => {
  const type = ctx.bijJobType as BulkInstallJobType;
  const job = await findBulkInstallJob(request, ctx.esProjectId as number, type, ctx.bijJobBefore as number);
  ctx.bijJobId = job.id;
  await expect(queueItem(page, job.id)).toContainText('完了', { timeout: JOB_TIMEOUT_MS });
  // 画面の表示だけでなく、ジョブ自体が done であることも確かめる(環境ごとの失敗は logs の FAILED で、ジョブは done)。
  await waitForBulkInstallJobDone(request, job.id);
});

When('処理キューのその全環境インストールのジョブの「結果を見る」を押す', async ({ ctx, page }) => {
  await queueItem(page, ctx.bijJobId as number)
    .getByRole('link', { name: '結果を見る' })
    .click();
});

Then('メンテナンスタブにローカル、テストの順で各環境の成否が表示される', async ({ ctx, page }) => {
  await expect(page).toHaveURL(
    new RegExp(`/projects/${ctx.esProjectId}\\?tab=maintenance&bulkJob=${ctx.bijJobId}`),
    { timeout: UI_TIMEOUT_MS }
  );
  const result = page.getByTestId('bulk-job-result');
  await expect(result).toBeVisible({ timeout: UI_TIMEOUT_MS });
  const items = result.getByRole('listitem');
  await expect(items).toHaveCount(2, { timeout: UI_TIMEOUT_MS });
  // 成否は環境により異なりうる(成功・失敗・スキップ)ので問わず、環境の順と、成否が示されることを確かめる。
  await expect(items.nth(0)).toHaveText(/^ローカル: (成功|失敗|スキップ)/);
  await expect(items.nth(1)).toHaveText(/^テスト: (成功|失敗|スキップ)/);
});
