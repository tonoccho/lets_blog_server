import type { Browser, Page } from '@playwright/test';
import { loginAsAdmin, loginAsUser } from '../helpers';
import { PAGE_INVENTORY, type PageFixtures, type PageRole, type PageSpec } from './pageInventory';

export interface PageVisitFailure {
  pageId: string;
  reason: string;
}

async function withRoleContext<T>(
  browser: Browser,
  role: PageRole,
  run: (page: Page) => Promise<T>
): Promise<T> {
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  try {
    const page = await context.newPage();
    if (role === 'user') {
      await loginAsUser(page);
    } else if (role === 'admin') {
      await loginAsAdmin(page);
    }
    return await run(page);
  } finally {
    await context.close();
  }
}

/**
 * 全ページを、それぞれ必要なログイン権限で開いて `visit` を実行する(issue #944 / AT-18)。
 *
 * ロール(none/user/admin)ごとにブラウザコンテキストをまとめることで、ログイン回数を
 * 24回ではなく最大3回に抑える。1ページの失敗が残りのページの検証を止めないよう、
 * `visit` が投げた例外は集めて返す(認可マトリクスのシナリオ(`cross-cutting.steps.ts`)と
 * 同じ「一斉に確かめて、まとめて報告する」設計)。
 */
export async function sweepAllPages(
  browser: Browser,
  fixtures: PageFixtures,
  visit: (page: Page, spec: PageSpec) => Promise<void>,
  viewport?: { width: number; height: number },
  pages: readonly PageSpec[] = PAGE_INVENTORY
): Promise<PageVisitFailure[]> {
  const failures: PageVisitFailure[] = [];
  const byRole: Record<PageRole, PageSpec[]> = { none: [], user: [], admin: [] };
  for (const spec of pages) {
    byRole[spec.role].push(spec);
  }

  for (const role of ['none', 'user', 'admin'] as const) {
    const specs = byRole[role];
    if (specs.length === 0) {
      continue;
    }
    await withRoleContext(browser, role, async (page) => {
      if (viewport) {
        await page.setViewportSize(viewport);
      }
      if (role === 'none') {
        // 未ログインのページ(/login・/setup)はクライアントJSがKeycloakへリダイレクトする。
        // 以前は next dev のコンパイルが遅く、リダイレクト前に走査が済んでいた(#1743)。本番ビルドでは
        // JSが即座に動いて走査中にナビゲーションが起き、`Execution context was destroyed` になる。
        // 走査したいのはリダイレクト前のサーバーレンダリング結果なので、バンドルを配信しない。
        await page.route('**/_next/static/**/*.js', (route) => route.abort());
      }
      for (const spec of specs) {
        try {
          // 開発サーバーのHMR等と重なると稀に net::ERR_ABORTED で終わることがある
          // (中断されたナビゲーション自体は製品の不具合ではない)。1回だけ取り直す。
          try {
            await page.goto(spec.path(fixtures), { waitUntil: 'load' });
          } catch (err) {
            if (err instanceof Error && err.message.includes('ERR_ABORTED')) {
              await page.goto(spec.path(fixtures), { waitUntil: 'load' });
            } else {
              throw err;
            }
          }
          await visit(page, spec);
        } catch (err) {
          failures.push({ pageId: spec.id, reason: err instanceof Error ? err.message : String(err) });
        }
      }
    });
  }
  return failures;
}

export function describeFailures(failures: PageVisitFailure[]): string[] {
  return failures.map((failure) => `${failure.pageId}: ${failure.reason}`);
}
