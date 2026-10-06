import { execFileSync } from 'node:child_process';
import type { APIRequestContext, Page } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * 告知文テンプレートの編集と、本番サイトのプラグインによる告知文の組み立て(issue #1583)のステップ定義。
 * 「ステップ定義ファイルは相乗りしない」方針のため、フィクスチャのヘルパーはここに閉じて持つ。
 * X の API は x スタブ(ホストからは 127.0.0.1:18088、WordPress からは http://x-stub:8080)。
 * 文字数の上限は X の重み付き(全角は 2、URL は 23)で数え、その数え方はここに自前で持つ(プラグインの実装を写さない)。
 */

const WORDPRESS_CONTAINER = 'lbs-wordpress';
const X_STUB_HOST_URL = 'http://127.0.0.1:18088';
const X_STUB_NETWORK_URL = 'http://x-stub:8080';
const X_LIMIT = 280;
const X_URL_WEIGHT = 23;
const POLL = { timeout: 120_000, intervals: [1_000, 2_000, 3_000] };

interface TemplatesFixture {
  siteId: number;
  siteKey: string;
  projectId: number;
  title: string;
  postId: string;
  suffix: string;
}

type Ctx = Record<string, unknown>;

function fixture(ctx: Ctx): TemplatesFixture {
  return ctx.templatesFixture as TemplatesFixture;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

function wpCli(siteKey: string, args: string[], input?: string): string {
  return execFileSync(
    'docker',
    ['exec', '-i', WORDPRESS_CONTAINER, 'wp', '--allow-root', `--path=/var/www/html/sites/${siteKey}`, ...args],
    { encoding: 'utf8', timeout: 120_000, input: input ?? '' }
  ).trim();
}

async function xStub(path: string, init?: RequestInit): Promise<Response> {
  const response = await fetch(`${X_STUB_HOST_URL}${path}`, init);
  expect(response.ok, `x スタブ ${path} が失敗しました (status=${response.status})。x-stub が起動していますか`).toBe(true);
  return response;
}

async function tweets(): Promise<string[]> {
  const state = (await (await xStub('/__control/state')).json()) as { tweets: string[] };
  return state.tweets;
}

function permalink(f: TemplatesFixture): string {
  return wpCli(f.siteKey, ['eval', `echo get_permalink(${Number(f.postId)});`]);
}

/** X の重み付きの長さ。URL は 23、U+0000-U+10FF・U+2000-U+200D・U+2010-U+201F・U+2032-U+2037 は 1、それ以外(全角など)は 2。 */
function xWeightedLength(text: string): number {
  let total = 0;
  const withoutUrls = text.replace(/https?:\/\/\S+/g, () => {
    total += X_URL_WEIGHT;
    return '';
  });
  for (const ch of withoutUrls) {
    const code = ch.codePointAt(0) ?? 0;
    const light =
      code <= 0x10ff || (code >= 0x2000 && code <= 0x200d) || (code >= 0x2010 && code <= 0x201f) || (code >= 0x2032 && code <= 0x2037);
    total += light ? 1 : 2;
  }
  return total;
}

async function createFixture(ctx: Ctx, request: APIRequestContext): Promise<TemplatesFixture> {
  const headers = await adminHeaders(request);
  const suffix = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  await xStub('/__control/reset', { method: 'POST' });

  const project = await request.post('/api/projects', {
    headers,
    data: { name: `E2E 1583 ${suffix}`, slug: `e2e1583-${suffix}` },
  });
  expect(project.ok(), `プロジェクトの作成に失敗しました: ${await project.text()}`).toBe(true);
  const projectId = ((await project.json()) as { id: number }).id;

  const siteKey = `e2e1583${suffix}`.toLowerCase().replace(/[^a-z0-9]/g, '');
  const site = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: `E2E 1583 ${suffix}`,
      siteKey,
      title: `E2E 1583 ${suffix}`,
      adminUser: 'e2e1583admin',
      adminEmail: `e2e-1583-${suffix}@letsblog.local`,
      adminPassword: `E2e1583#Tpl${suffix}`,
      locale: 'ja',
    },
    timeout: 120_000,
  });
  expect(site.ok(), `managedサイトの作成に失敗しました: ${await site.text()}`).toBe(true);
  const siteId = ((await site.json()) as { id: number }).id;
  const bound = await request.post(`/api/projects/${projectId}/environments`, {
    headers,
    data: { environment: 'production', siteId },
  });
  expect(bound.ok(), `環境への紐付けに失敗しました: ${await bound.text()}`).toBe(true);

  wpCli(siteKey, ['config', 'set', 'LETSBLOG_X_API_BASE_URL', X_STUB_NETWORK_URL, '--type=constant']);
  wpCli(
    siteKey,
    ['letsblog', 'sns', 'config', 'set'],
    JSON.stringify({
      sns: 'x',
      client_id: 'e2e-client-id',
      client_secret: 'e2e-client-secret',
      access_token: 'e2e-x-access-valid',
      refresh_token: 'e2e-x-refresh-valid',
      expires_at: Math.floor(Date.now() / 1000) + 3600,
      account_name: 'LetsBlogOfficial',
    })
  );

  const f: TemplatesFixture = { siteId, siteKey, projectId, title: `E2E-1583-${suffix}`, postId: '', suffix };
  ctx.templatesFixture = f;
  return f;
}

/** textarea を確実に目的の値にする。`fill('')` は既定値が入った textarea で先頭1文字しか消えないことがあるため、全選択して消してから入れる。 */
async function setTextarea(page: Page, label: string, value: string): Promise<void> {
  const box = page.getByLabel(label);
  await box.click();
  await page.keyboard.press('Control+A');
  await page.keyboard.press('Backspace');
  await expect(box).toHaveValue('');
  if (value !== '') {
    await box.fill(value);
  }
  await expect(box).toHaveValue(value);
}

async function saveTemplatesThroughUi(page: Page, f: TemplatesFixture, publish: string, pv: string): Promise<void> {
  await page.goto(`/projects/${f.projectId}/settings/sns`);
  await expect(page.getByTestId('sns-templates-section')).toBeVisible({ timeout: 30000 });
  await setTextarea(page, '公開時の告知文', publish);
  await setTextarea(page, 'PV 達成時の告知文', pv);
  await page.getByRole('button', { name: 'テンプレートを保存' }).click();
  // 直前の保存の「送信済み」が残っていても通らないよう、プラグインの値が今回の保存と一致するまで待つ。
  // フォーム送信(FormData)は textarea の改行を CRLF にして送るため、改行の種類は区別せず比較する(要件は改行の種類を定めない)。
  const lf = (text: string): string => text.replace(/\r\n/g, '\n');
  await expect
    .poll(
      () => {
        const stored = pluginTemplates(f.siteKey);
        return { publish: lf(stored.publish), pv: lf(stored.pv) };
      },
      { timeout: 60000, intervals: [500, 1000, 2000] }
    )
    .toEqual({ publish: lf(publish), pv: lf(pv) });
  await expect(page.getByTestId('sns-templates-send-status')).toContainText(/送信済み|送信失敗/, { timeout: 60000 });
}

function pluginTemplates(siteKey: string): { publish: string; pv: string } {
  let raw = '';
  try {
    raw = wpCli(siteKey, ['option', 'get', 'letsblog_sns_templates', '--format=json']);
  } catch {
    return { publish: '', pv: '' };
  }
  return raw === '' ? { publish: '', pv: '' } : (JSON.parse(raw) as { publish: string; pv: string });
}

Given('告知文検証用に、X を接続した本番サイトを持つプロジェクトを用意する', async ({ ctx, request }) => {
  await createFixture(ctx, request);
});

Given(
  '告知文検証の SNS 告知設定画面で公開時のテンプレートに{string}、PV 達成時のテンプレートに{string}を保存してある',
  async ({ ctx, page }, publish: string, pv: string) => {
    await saveTemplatesThroughUi(page, fixture(ctx), publish, pv);
  }
);

Given(
  '告知文検証の SNS 告知設定画面で、タイトルと全角300文字の本文と URL を並べた公開時のテンプレートを保存してある',
  async ({ ctx, page }) => {
    await saveTemplatesThroughUi(page, fixture(ctx), `{title}\n${'あ'.repeat(300)}\n{url}`, '');
  }
);

When(
  '告知文検証の SNS 告知設定画面で公開時のテンプレートに{string}、PV 達成時のテンプレートに{string}を保存する',
  async ({ ctx, page }, publish: string, pv: string) => {
    await saveTemplatesThroughUi(page, fixture(ctx), publish, pv);
  }
);

When('告知文検証の記事を Let\'s Blog から即時公開する', async ({ ctx, request }) => {
  const f = fixture(ctx);
  const response = await request.post('/api/posts/publish', {
    headers: await adminHeaders(request),
    multipart: {
      site: f.siteKey,
      title: f.title,
      slug: `e2e-1583-${f.suffix}`,
      status: 'publish',
      markdown: '本文です\n',
    },
    timeout: 120_000,
  });
  expect(response.ok(), `記事の投稿に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
  f.postId = ((await response.json()) as { wpPostId: string }).wpPostId;
});

Then('告知文検証の SNS 告知設定画面のテンプレートの送信状態は{string}と表示される', async ({ page }, text: string) => {
  await expect(page.getByTestId('sns-templates-send-status')).toContainText(text, { timeout: 60000 });
});

Then('告知文検証の本番サイトのプラグインの公開時のテンプレートは{string}である', async ({ ctx }, expected: string) => {
  await expect.poll(() => pluginTemplates(fixture(ctx).siteKey).publish, { timeout: 30000 }).toBe(expected);
});

Then('告知文検証の本番サイトのプラグインの PV 達成時のテンプレートは{string}である', async ({ ctx }, expected: string) => {
  await expect.poll(() => pluginTemplates(fixture(ctx).siteKey).pv, { timeout: 30000 }).toBe(expected);
});

Then(
  '告知文検証の X のスタブに{string}に続けてタイトルと URL を空白で区切った本文が1回だけ投稿されている',
  async ({ ctx }, prefix: string) => {
    const f = fixture(ctx);
    await expect.poll(async () => (await tweets()).length, POLL).toBeGreaterThanOrEqual(1);
    const posted = await tweets();
    expect(posted).toHaveLength(1);
    expect(posted[0]).toBe(`${prefix}${f.title} ${permalink(f)}`);
  }
);

Then('告知文検証の X のスタブにタイトルと URL が改行で区切られて1回だけ投稿されている', async ({ ctx }) => {
  const f = fixture(ctx);
  await expect.poll(async () => (await tweets()).length, POLL).toBeGreaterThanOrEqual(1);
  const posted = await tweets();
  expect(posted).toHaveLength(1);
  expect(posted[0]).toBe(`${f.title}\n${permalink(f)}`);
});

Then('告知文検証の X のスタブの投稿は上限の280を超えず、末尾に URL が残り、切り詰めの印を含む', async ({ ctx }) => {
  const f = fixture(ctx);
  await expect.poll(async () => (await tweets()).length, POLL).toBeGreaterThanOrEqual(1);
  const posted = await tweets();
  expect(posted).toHaveLength(1);
  expect(xWeightedLength(posted[0])).toBeLessThanOrEqual(X_LIMIT);
  expect(posted[0].endsWith(permalink(f))).toBe(true);
  expect(posted[0]).toContain('…');
});

After({ tags: '@project' }, async ({ ctx, request }) => {
  const f = ctx.templatesFixture as TemplatesFixture | undefined;
  if (f === undefined) {
    return;
  }
  const headers = await adminHeaders(request);
  await xStub('/__control/reset', { method: 'POST' }).catch(() => undefined);
  await request.delete(`/api/sites/${f.siteId}`, { headers });
  await request.delete(`/api/projects/${f.projectId}`, { headers });
});
