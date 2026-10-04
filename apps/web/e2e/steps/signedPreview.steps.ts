import { execFileSync } from 'node:child_process';
import path from 'node:path';
import type { APIRequestContext } from '@playwright/test';
import { Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * 投稿を作らずに実テーマで表示する署名付きプレビュー URL(issue #1561)のステップ定義。
 *
 * 背景のステップ(プレビュー検証用サイトとプロジェクトの用意)と後片付けは
 * `publishPreview.steps.ts` が持つ。ここでは `ctx.previewProjectId` / `previewSiteId` /
 * `previewSiteSlug` を読むだけで、サイトの用意は繰り返さない。
 *
 * ## URL を wordpress コンテナ内から開く理由
 *
 * 返る URL は利用者のブラウザ向けの公開 URL(`https://localhost/...`)で、テストランナーからは
 * 自己署名証明書のために開けないことがある。`wp` と同じコンテナ内から、同じパスとクエリを
 * 内部のオリジンへ向けて開く。署名の検証は URL のクエリだけで決まり、オリジンには依存しない。
 */

const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

interface SignedPreviewUrlResponse {
  url: string;
  expiresAt: number;
}

interface PreviewFetchResult {
  status: number;
  body: string;
}

function inSite(slug: string, command: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'wordpress', 'sh', '-c', `cd /var/www/html/sites/${slug} && ${command}`],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 180_000 }
  ).trim();
}

/** `wp_posts` の全行数(投稿・リビジョン・添付など、投稿の種類を問わない)。 */
function postsRowCount(slug: string): number {
  const out = inSite(slug, `wp --allow-root eval 'global $wpdb; echo $wpdb->get_var("SELECT COUNT(*) FROM $wpdb->posts");'`);
  return Number(out);
}

/** プレビューの一時データ(transient)が `wp_options` に残っている行数。 */
function previewTransientRows(slug: string, tokenId: string): number {
  const out = inSite(
    slug,
    `wp --allow-root eval 'global $wpdb; echo $wpdb->get_var($wpdb->prepare("SELECT COUNT(*) FROM $wpdb->options WHERE option_name LIKE %s", "%letsblog_preview_${tokenId}%"));'`
  );
  return Number(out);
}

function tokenOf(url: string): string {
  const token = new URL(url).searchParams.get('letsblog_preview');
  expect(token, `URL にトークンがありません: ${url}`).toBeTruthy();
  return token as string;
}

function tokenIdOf(url: string): string {
  return tokenOf(url).split('.')[0];
}

/** URL のパスとクエリを、wordpress コンテナ内の内部オリジンで開く。 */
function openInsideContainer(slug: string, url: string): PreviewFetchResult {
  const parsed = new URL(url);
  const internal = `http://localhost${parsed.pathname}${parsed.search}`;
  const out = inSite(slug, `curl -sS -w '\\n%{http_code}' ${shellQuote(internal)}`);
  const idx = out.lastIndexOf('\n');
  return { status: Number(out.slice(idx + 1)), body: out.slice(0, idx) };
}

function shellQuote(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`;
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function issue(
  request: APIRequestContext,
  projectId: number,
  siteId: number,
  title: string,
  contentHtml: string,
  ttlSeconds?: number
): Promise<SignedPreviewUrlResponse> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  const response = await request.post(`/api/projects/${projectId}/preview/signed-url`, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      siteId,
      title,
      contentHtml,
      categories: ['Uncategorized'],
      tags: ['e2e-1561'],
      ...(ttlSeconds === undefined ? {} : { ttlSeconds }),
    },
    timeout: 120_000,
  });
  expect(
    response.ok(),
    `署名付きプレビュー URL の発行に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as SignedPreviewUrlResponse;
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

async function issueInto(
  ctx: Record<string, unknown>,
  request: APIRequestContext,
  ttlSeconds?: number
): Promise<void> {
  const unique = uniqueSuffix();
  ctx.signedTitle = `E2E-1561-Title-${unique}`;
  ctx.signedBodyMarker = `E2E-1561-Body-${unique}`;
  const issued = await issue(
    request,
    ctx.previewProjectId as number,
    ctx.previewSiteId as number,
    ctx.signedTitle as string,
    `<p>${ctx.signedBodyMarker}</p>`,
    ttlSeconds
  );
  ctx.signedIssued = issued;
  ctx.signedUrl = issued.url;
}

// ------------------------------------------------------- 前提

/**
 * プレビュー検証用サイトは実行をまたいで再利用する固定サイトで、プラグインの導入(#1556/#1557)より前に
 * 構築されたものはプラグインを持たない。サイト画面の「プラグインを再導入」と同じ API で冪等に導入する。
 */
Given('プレビュー検証用のサイトに letsblog プラグインが導入されている', async ({ ctx, request }) => {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  const response = await request.post(`/api/sites/${ctx.previewSiteId as number}/letsblog-plugin/install`, {
    headers: { Authorization: `Bearer ${token}` },
    timeout: 180_000,
  });
  expect(
    response.ok(),
    `プラグインの導入に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  expect(((await response.json()) as { state: string }).state, 'プラグインが導入済みになっていません').toBe('INSTALLED');
});

Given('対象サイトの wp_posts の行数を控えておく', async ({ ctx }) => {
  ctx.signedPostsBefore = postsRowCount(ctx.previewSiteSlug as string);
});

// ------------------------------------------------------- 発行

When('署名付きプレビュー URL を発行する', async ({ ctx, request }) => {
  await issueInto(ctx, request);
});

When('有効期限を {int} 秒にして署名付きプレビュー URL を発行する', async ({ ctx, request }, ttl: number) => {
  await issueInto(ctx, request, ttl);
  ctx.signedFirstTokenId = tokenIdOf(ctx.signedUrl as string);
});

When('署名付きプレビュー URL を発行し直す', async ({ ctx, request }) => {
  await issueInto(ctx, request);
});

When('期限が切れるまで待つ', async () => {
  await sleep(3_500);
});

// ------------------------------------------------------- 表示

When('署名付きプレビュー URL を開く', async ({ ctx }) => {
  ctx.signedFetch = openInsideContainer(ctx.previewSiteSlug as string, ctx.signedUrl as string);
});

When('トークンの署名を改ざんした URL を開く', async ({ ctx }) => {
  const url = new URL(ctx.signedUrl as string);
  const [id, expires, signature] = tokenOf(ctx.signedUrl as string).split('.');
  const flipped = (signature[0] === '0' ? '1' : '0') + signature.slice(1);
  url.searchParams.set('letsblog_preview', `${id}.${expires}.${flipped}`);
  ctx.signedFetch = openInsideContainer(ctx.previewSiteSlug as string, url.toString());
});

// ------------------------------------------------------- 検証

Then('署名付きプレビュー URL と将来の期限が返る', async ({ ctx }) => {
  const issued = ctx.signedIssued as SignedPreviewUrlResponse;
  expect(issued.url, 'URL が返っていません').toContain('letsblog_preview=');
  expect(issued.expiresAt, '期限が将来になっていません').toBeGreaterThan(Math.floor(Date.now() / 1000));
});

Then('応答は 200 でタイトルと本文が含まれる', async ({ ctx }) => {
  const result = ctx.signedFetch as PreviewFetchResult;
  expect(result.status, `応答が 200 ではありません: ${result.body.slice(0, 300)}`).toBe(200);
  expect(result.body, 'タイトルが含まれません').toContain(ctx.signedTitle as string);
  expect(result.body, '本文が含まれません').toContain(ctx.signedBodyMarker as string);
});

Then('応答にテーマのヘッダーとテーマの CSS が含まれる', async ({ ctx }) => {
  const body = (ctx.signedFetch as PreviewFetchResult).body;
  expect(/<header\b|wp-block-template-part|site-header/i.test(body), 'テーマのヘッダーが含まれません').toBe(true);
  expect(body, 'テーマの CSS への参照が含まれません').toMatch(/wp-content\/themes\/[^"']+\.css|<style[^>]*id=["'][^"']*(global-styles|theme)/i);
});

Then('応答に検索エンジンに載せない指定が含まれる', async ({ ctx }) => {
  expect((ctx.signedFetch as PreviewFetchResult).body).toMatch(/<meta name=['"]robots['"][^>]*noindex/i);
});

Then('応答に渡したカテゴリ名とタグ名が含まれる', async ({ ctx }) => {
  const body = (ctx.signedFetch as PreviewFetchResult).body;
  expect(body, 'カテゴリ名が含まれません').toContain('Uncategorized');
  expect(body, 'タグ名が含まれません').toContain('e2e-1561');
});

Then('対象サイトの wp_posts の行数が変わっていない', async ({ ctx }) => {
  expect(postsRowCount(ctx.previewSiteSlug as string)).toBe(ctx.signedPostsBefore);
});

Then('応答は 403 か 404 で本文を含まない', async ({ ctx }) => {
  const result = ctx.signedFetch as PreviewFetchResult;
  expect([403, 404], `応答が 403 か 404 ではありません (status=${result.status})`).toContain(result.status);
  expect(result.body, '本文が返っています').not.toContain(ctx.signedBodyMarker as string);
  expect(result.body, 'タイトルが返っています').not.toContain(ctx.signedTitle as string);
});

Then('対象サイトにそのプレビューの一時データが残っていない', async ({ ctx }) => {
  const tokenId = tokenIdOf(ctx.signedUrl as string);
  expect(previewTransientRows(ctx.previewSiteSlug as string, tokenId)).toBe(0);
});

Then('対象サイトに最初のプレビューの一時データが残っていない', async ({ ctx }) => {
  expect(previewTransientRows(ctx.previewSiteSlug as string, ctx.signedFirstTokenId as string)).toBe(0);
});
