import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * 組み込みタグデザイン編集画面(`TagDesignSettingsPanel`/`TagDesignEditor`、
 * `/projects/{id}/tags`の「組み込みタグのデザイン」タブ)のハイドレーション競合対策
 * (issue #1144)を支えるステップ定義。
 *
 * このIssueのスコープは`TagDesignEditor`のCSS欄のmountedガードのみなので、
 * このファイルもそこに narrowly-scoped させる。#1155(フォールバック/独立保存の
 * 意味論)のような広いシナリオはここに含めない。
 */

type ScenarioState = Record<string, unknown>;

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

Given('タグデザインの動作を確かめるためのプロジェクトがある', async ({ ctx, request }) => {
  const suffix = uniqueSuffix();
  const response = await request.post('/api/projects', {
    headers: await adminHeaders(request),
    data: { name: `E2E 1144 ${suffix}`, slug: `e2e-1144-${suffix}` },
  });
  expect(
    response.ok(),
    `プロジェクトの作成に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  const project = (await response.json()) as { id: number };
  (ctx as ScenarioState).tagDesignProjectId = project.id;
});

When('その組み込みタグデザイン画面を開く', async ({ ctx, page }) => {
  const projectId = (ctx as ScenarioState).tagDesignProjectId as number;
  // 'commit' は応答を受け取った直後(=SSRされたHTMLが届いた直後)で止まり、
  // クライアントバンドルの読み込み・実行(ハイドレーション)の完了を待たない。
  // ここから先のステップが「ハイドレーション完了前」を確かめる土台になる。
  await page.goto(`/projects/${projectId}/tags`, { waitUntil: 'commit' });
});

Then('ハイドレーション完了前のCSS欄は無効化されていて入力を受け付けない', async ({ page }) => {
  const textarea = page.locator('textarea[name="customCss"]');
  // locator.evaluate()はactionability(visible/enabled/stable)を待たず、DOMにあれば
  // 即座に評価する。ここで待ち合わせを挟むとハイドレーションが先に終わってしまい、
  // 「開いた直後」を確かめられなくなる。
  const disabledRightAfterCommit = await textarea.evaluate(
    (el) => (el as HTMLTextAreaElement).disabled
  );
  expect(
    disabledRightAfterCommit,
    '画面を開いた直後、ハイドレーション完了前のCSS欄がdisabledになっていません。' +
      'mountedガードが外れていると、ネイティブな入力を受け付けてしまい#1144の競合が再発します。'
  ).toBe(true);
});

When(
  /^ハイドレーション完了後にCSS欄へ「([^」]*)」と入力して保存する$/,
  async ({ page }, value: string) => {
    const textarea = page.locator('textarea[name="customCss"]');
    // ハイドレーション完了(mounted=true)を待つ。CI環境でも数秒あれば十分すぎる余裕を見る。
    await expect(textarea).toBeEnabled({ timeout: 15_000 });
    await textarea.fill(value);

    const submit = page.locator('form').filter({ has: textarea }).locator('button[type="submit"]');
    await submit.click();
    await expect(page.getByText('保存しました。')).toBeVisible({ timeout: 15_000 });
  }
);

Then(
  /^CSS欄には「([^」]*)」だけがそのまま表示され、標準CSSの残骸と混ざっていない$/,
  async ({ page }, expectedValue: string) => {
    const textarea = page.locator('textarea[name="customCss"]');
    await expect(textarea).toBeEnabled({ timeout: 15_000 });
    await expect(textarea).toHaveValue(expectedValue);
  }
);

// issue #1143: AI生成フォームのプロンプト欄。aria-labelもnameも無いのでプレースホルダで選ぶ。
// 「生成」は押さない(押すと処理キューにジョブが積まれ、LLMスタブが要る。#1586の範囲)。
const AI_PROMPT_PLACEHOLDER = /背景を淡いグレーにして/;

Then('ハイドレーション完了前のAI生成プロンプト欄は無効化されていて入力を受け付けない', async ({ page }) => {
  const textarea = page.getByPlaceholder(AI_PROMPT_PLACEHOLDER);
  // evaluate()はactionabilityを待たない(上のCSS欄のステップと同じ理由)。
  const disabledRightAfterCommit = await textarea.evaluate(
    (el) => (el as HTMLTextAreaElement).disabled
  );
  expect(
    disabledRightAfterCommit,
    '画面を開いた直後、ハイドレーション完了前のAI生成プロンプト欄がdisabledになっていません。' +
      'mountedガードが外れていると、ネイティブな入力がReactのstateに入らず#1143の競合が再発します。'
  ).toBe(true);
});

When(
  /^ハイドレーション完了後にAI生成プロンプト欄へ「([^」]*)」と入力する$/,
  async ({ page }, value: string) => {
    const textarea = page.getByPlaceholder(AI_PROMPT_PLACEHOLDER);
    await expect(textarea).toBeEnabled({ timeout: 15_000 });
    await textarea.fill(value);
  }
);

Then(
  /^AI生成プロンプト欄には「([^」]*)」がそのまま残り、生成ボタンが有効になっている$/,
  async ({ page }, expectedValue: string) => {
    await expect(page.getByPlaceholder(AI_PROMPT_PLACEHOLDER)).toHaveValue(expectedValue);
    await expect(page.getByRole('button', { name: '生成', exact: true })).toBeEnabled({
      timeout: 15_000,
    });
  }
);

After({ tags: '@project' }, async ({ ctx, request }) => {
  const projectId = (ctx as ScenarioState).tagDesignProjectId as number | undefined;
  if (projectId === undefined) {
    return;
  }
  await request.delete(`/api/projects/${projectId}`, { headers: await adminHeaders(request) });
});
