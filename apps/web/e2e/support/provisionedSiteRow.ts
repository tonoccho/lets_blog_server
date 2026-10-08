import type { Page } from '@playwright/test';
import { expect } from './index';

/**
 * 画面から要求したサイト自動構築の完了を、サイト一覧にそのサイトの行が現れることで確かめる(issue #1696)。
 *
 * サイト自動構築はジョブとして受理され(`POST /api/sites/managed-wordpress/jobs`)、フォームは完了を待たない。
 * 完了後の一覧は自動では更新されないので、行が現れるまで一覧を読み込み直す。構築は実測で最大240秒かかる。
 */
export async function waitForProvisionedSiteRow(page: Page, siteKey: string, timeoutMs = 280_000): Promise<void> {
  await expect(async () => {
    await page.goto('/sites');
    await expect(page.locator(`tr:has-text("${siteKey}")`)).toBeVisible({ timeout: 5_000 });
  }).toPass({ timeout: timeoutMs, intervals: [3_000] });
}
