import { expect, type APIRequestContext, type Locator, type Page } from '@playwright/test';
import { Given, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, fetchAccessToken } from '../support';
import { measureServerActionRoundTrip, recordResponseTime } from '../support/responseBudget';
import { clickUntilVisible } from '../support/retryClick';
import messagesJa from '../../messages/ja.json';

/**
 * 3秒予算(`docs/ACCEPTANCE_CRITERIA.md` §10)のうち、#1544 で一覧へ足した画面・Server Action の
 * 計測ステップ。計測点は #1476 の共通ヘルパー(`measureServerActionRoundTrip`)で、
 * 結果は `recordResponseTime` で共通のキーへ渡す(`ならば` 側は `responseBudget.steps.ts`)。
 * 「/projects/{projectId}」の `{projectId}` は検証用プロジェクトの id に置き換える。
 */

function resolve(ctx: Record<string, unknown>, path: string): string {
  return path.replace('{projectId}', String(ctx.responseBudgetProjectId));
}

function record(ctx: Record<string, unknown>, roundTripMs: number, operation: string): void {
  recordResponseTime(ctx, roundTripMs, `${operation}(Server Action)の往復`);
}

// ---- 情報表示レール(fetchQueueJobsAction / fetchRecentOperationLogsAction) ----

When(/^「(.+)」を開く$/, async ({ page, ctx }, path: string) => {
  await page.goto(resolve(ctx, path));
});

When(
  /^「(.+)」を開いて情報表示レールの Server Action の往復を計測する$/,
  async ({ page, ctx }, path: string) => {
    const timing = await measureServerActionRoundTrip(page, async () => {
      await page.goto(resolve(ctx, path));
    });
    record(ctx, timing.roundTripMs, '情報表示レールの処理キュー取得');
  }
);

When('情報表示レールの「操作ログ」タブを選んで Server Action の往復を計測する', async ({ page, ctx }) => {
  const rail = page.getByTestId('info-rail');
  await expect(rail).toBeVisible({ timeout: 15_000 });
  const tab = rail.getByRole('button', { name: messagesJa.infoRail.logsTab, exact: true });
  const timing = await measureServerActionRoundTrip(page, () =>
    clickUntilVisible(tab, page.getByTestId('info-rail-logs-panel'))
  );
  record(ctx, timing.roundTripMs, '情報表示レールの操作ログ取得');
});

// ---- AI・アセットタブ(fetchAiConnectionsAction / fetchProjectConnectionsAction ほか) ----

const claudeHeading = (page: Page): Locator => page.getByRole('heading', { name: 'Claudeの接続情報' });
const section = (page: Page, title: string): Locator =>
  page.locator('section', { has: page.getByRole('heading', { name: title }) });

/** 「AI・アセット」タブを開き、接続情報が見えるまでクリックを再試行する(タブを開くのはべき等)。 */
async function selectAiTab(page: Page): Promise<void> {
  const aiTab = page.getByRole('button', { name: 'AI・アセット', exact: true });
  await expect(aiTab).toBeVisible({ timeout: 30_000 });
  await clickUntilVisible(aiTab, claudeHeading(page), { timeoutMs: 30_000 });
}

When(
  /^「(.+)」を開いて AI・アセットタブを選び Server Action の往復を計測する$/,
  async ({ page, ctx }, path: string) => {
    await page.goto(resolve(ctx, path), { waitUntil: 'commit' });
    const timing = await measureServerActionRoundTrip(page, () => selectAiTab(page));
    record(ctx, timing.roundTripMs, 'AI接続情報の取得');
  }
);

When(/^「(.+)」のAI・アセットタブを開いておく$/, async ({ page, ctx }, path: string) => {
  await page.goto(resolve(ctx, path), { waitUntil: 'commit' });
  await selectAiTab(page);
  // 取得(接続状態の表示)が終わってから操作する。
  await expect(section(page, 'Claudeの接続情報').locator('dt:has-text("接続状態") + dd')).toBeVisible({
    timeout: 30_000,
  });
});

Given(
  /^応答時間予算の検証用のプロジェクトに「(claude-api-key|openai-api-key)」が「(.+)」で設定されている$/,
  async ({ ctx, request }, kind: string, apiKey: string) => {
    const response = await request.put(`/api/projects/${ctx.responseBudgetProjectId}/api-keys/${kind}`, {
      headers: { Authorization: `Bearer ${await adminToken(request)}` },
      data: { apiKey },
    });
    expect(response.ok(), `キーの設定に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
  }
);

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

When(
  /^Anthropic APIキー欄に「(.+)」を入れて接続し Server Action の往復を計測する$/,
  async ({ page, ctx }, value: string) => {
    await page.getByLabel('Anthropic APIキー').fill(value);
    const timing = await measureServerActionRoundTrip(page, () =>
      section(page, 'Claudeの接続情報').getByRole('button', { name: '接続', exact: true }).click()
    );
    record(ctx, timing.roundTripMs, 'ClaudeのAPIキー保存');
  }
);

When(/^(Claude|ChatGPT) の接続を解除して Server Action の往復を計測する$/, async ({ page, ctx }, name: string) => {
  const button = section(page, `${name}の接続情報`).getByRole('button', { name: '接続を解除' });
  await expect(button).toBeVisible({ timeout: 15_000 });
  const timing = await measureServerActionRoundTrip(page, () => button.click());
  record(ctx, timing.roundTripMs, `${name}の接続解除`);
});

When(
  /^OpenAI APIキー欄に「(.+)」を入れて接続し Server Action の往復を計測する$/,
  async ({ page, ctx }, value: string) => {
    await page.getByLabel('OpenAI APIキー').fill(value);
    const timing = await measureServerActionRoundTrip(page, () =>
      section(page, 'ChatGPTの接続情報').getByRole('button', { name: '接続', exact: true }).click()
    );
    record(ctx, timing.roundTripMs, 'ChatGPTのAPIキー保存');
  }
);

When(
  /^Ollama の接続先URLに「(.+)」を入れて保存し Server Action の往復を計測する$/,
  async ({ page, ctx }, url: string) => {
    await page.getByLabel('Ollamaの接続先URL(このプロジェクトで上書き)').fill(url);
    const timing = await measureServerActionRoundTrip(page, () =>
      section(page, 'Ollamaの接続情報').getByRole('button', { name: '保存', exact: true }).click()
    );
    record(ctx, timing.roundTripMs, 'Ollamaの接続先URL保存');
  }
);

// ---- ギャラリー(fetchGalleryImagesPageAction) ----

When(
  /^ギャラリーをタグ「([^」]+)」で絞り込んで Server Action の往復を計測する$/,
  async ({ page, ctx }, tag: string) => {
    await expect(page.locator('button img[src^="/image-gallery/"]').first()).toBeVisible({ timeout: 30_000 });
    const chip = page.getByRole('button', { name: tag, exact: true });
    // 選択状態(黒地)になるまでクリックし直す(media.steps.ts のタグ絞り込みと同じ)。計測するのは
    // 実際に送られた Server Action の往復だけで、空振りは含まれない。
    const timing = await measureServerActionRoundTrip(page, async () => {
      await expect(async () => {
        await chip.click();
        await expect(chip).toHaveClass(/bg-neutral-900/, { timeout: 2_000 });
      }).toPass({ timeout: 30_000 });
    });
    record(ctx, timing.roundTripMs, 'ギャラリーのタグ絞り込み');
  }
);
