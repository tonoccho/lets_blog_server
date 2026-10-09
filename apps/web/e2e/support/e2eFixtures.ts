import type { APIRequestContext, Locator } from '@playwright/test';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from './index';

/**
 * 複数のステップ定義が使う、小さな検証用の補助(プレーンなモジュール。ステップを登録しない)。
 *
 * 元は応答時間(3秒予算)の受け入れテスト用の `responseBudgetFixtures.ts` にあったが、その受け入れテストは
 * 削除された(#1708)。性能系以外のステップ定義が使う関数だけをここへ移した。
 */

export function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

export async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD)}` };
}

/**
 * 対象の要素が React にハイドレートされるまで待つ。
 *
 * フォームの `action={formAction}` は、ハイドレーション前に送信するとブラウザの通常の
 * フルページ POST になり、Server Action として処理されない。React は DOM 要素に
 * `__reactProps$...` を付けるので、それが現れるまで待てば、Server Action を呼ぶ送信だけを行える。
 */
export async function waitForHydrated(target: Locator): Promise<void> {
  await expect
    .poll(
      () => target.first().evaluate((el) => Object.keys(el).some((key) => key.startsWith('__reactProps$'))),
      { timeout: 30_000 }
    )
    .toBe(true);
}
