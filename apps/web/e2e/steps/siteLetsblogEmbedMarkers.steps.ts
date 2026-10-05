import { execFileSync } from 'node:child_process';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * 組み込みタグ(ブログカード・Amazon・目次)の目印付き投稿と、プラグインの表示時の適用し直し(issue #1563)の
 * ステップ定義。「ステップ定義ファイルは相乗りしない」方針のため、フィクスチャのヘルパーはここに閉じて持つ。
 * 表示は WordPress の `the_content` フィルタを通した結果で確かめる(`wp eval`)。
 */

const WORDPRESS_CONTAINER = 'lbs-wordpress';
const POLL = { timeout: 180_000, intervals: [2_000, 3_000, 5_000] };
const BLOGCARD_URL = 'https://example.com/';

interface EmbedFixture {
  siteId: number;
  siteKey: string;
  projectId: number;
  suffix: string;
  postId: string;
  amazonPostId?: string;
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
  return { Authorization: `Bearer ${token}` };
}

function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

function wpCli(siteKey: string, args: string[]): string {
  return execFileSync(
    'docker',
    ['exec', WORDPRESS_CONTAINER, 'wp', '--allow-root', `--path=/var/www/html/sites/${siteKey}`, ...args],
    { encoding: 'utf8', timeout: 120_000 }
  ).trim();
}

function fixture(ctx: Record<string, unknown>): EmbedFixture {
  return ctx.embedFixture as EmbedFixture;
}

/** 投稿の本文を `the_content` フィルタに通した表示結果。 */
function rendered(siteKey: string, postId: string): string {
  return wpCli(siteKey, ['eval', `echo apply_filters('the_content', get_post(${Number(postId)})->post_content);`]);
}

/** HTML コメントを除いた、画面に出る(ブラウザが描画する)側の HTML。 */
function visible(html: string): string {
  return html.replace(/<!--[\s\S]*?-->/g, '');
}

async function putDesign(
  request: APIRequestContext,
  projectId: number,
  tagType: 'BLOGCARD' | 'AMAZON' | 'TOC',
  htmlTemplate: string
): Promise<void> {
  const response = await request.put(`/api/projects/${projectId}/tag-design-settings/${tagType}`, {
    headers: await adminHeaders(request),
    data: {
      presetId: 'light',
      backgroundColor: '#ffffff',
      textColor: '#1a1a1a',
      accentColor: '#2563eb',
      htmlTemplate,
    },
  });
  expect(response.ok(), `デザインの保存に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
}

const blogcardClass = (f: EmbedFixture): string => `e2e1563-bc-${f.suffix}`;
const amazonClass = (f: EmbedFixture): string => `e2e1563-az-${f.suffix}`;
const tocClass = (f: EmbedFixture): string => `e2e1563-toc-${f.suffix}`;

Given('組み込み目印検証用に、ブログカードと目次を含む記事を投稿しておく', async ({ ctx, request }) => {
  const headers = await adminHeaders(request);
  const suffix = uniqueSuffix();

  const project = await request.post('/api/projects', {
    headers,
    data: { name: `E2E 1563 ${suffix}`, slug: `e2e1563-${suffix}` },
  });
  expect(project.ok(), `プロジェクトの作成に失敗しました: ${await project.text()}`).toBe(true);
  const projectId = ((await project.json()) as { id: number }).id;

  const siteKey = `e2e1563${suffix}`.toLowerCase().replace(/[^a-z0-9]/g, '');
  const site = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: `E2E 1563 ${suffix}`,
      siteKey,
      title: `E2E 1563 ${suffix}`,
      adminUser: 'e2e1563admin',
      adminEmail: `e2e-1563-${suffix}@letsblog.local`,
      adminPassword: `E2e1563#Embed${suffix}`,
      locale: 'ja',
    },
    timeout: 120_000,
  });
  expect(site.ok(), `managedサイトの作成に失敗しました: ${await site.text()}`).toBe(true);
  const siteId = ((await site.json()) as { id: number }).id;
  const bound = await request.post(`/api/projects/${projectId}/environments`, {
    headers,
    data: { environment: 'local', siteId },
  });
  expect(bound.ok(), `環境への紐付けに失敗しました: ${await bound.text()}`).toBe(true);

  const f: EmbedFixture = { siteId, siteKey, projectId, suffix, postId: '' };
  ctx.embedFixture = f;

  const published = await request.post('/api/posts/publish', {
    headers,
    multipart: {
      site: siteKey,
      title: `E2E-1563-${suffix}`,
      slug: `e2e-1563-${suffix}`,
      status: 'publish',
      markdown: `[toc]\n\n## 第一章\n\n本文\n\n### 第一節\n\n本文\n\n## 第二章\n\n[blogcard ${BLOGCARD_URL}]\n`,
    },
    timeout: 180_000,
  });
  expect(published.ok(), `記事の投稿に失敗しました (status=${published.status()}): ${await published.text()}`).toBe(true);
  f.postId = ((await published.json()) as { wpPostId: string }).wpPostId;

  // 投稿時点の HTML に、組み込みタグの目印と既定の見た目が入っていること
  const stored = wpCli(siteKey, ['eval', `echo get_post(${Number(f.postId)})->post_content;`]);
  expect(stored).toContain('<!-- lbs:embed ');
  expect(stored).toContain('lb-blogcard');
  expect(stored).toContain('lb-toc-list');
});

Given('組み込み目印検証のサイトのプラグインを停止しておく', async ({ ctx }) => {
  wpCli(fixture(ctx).siteKey, ['plugin', 'deactivate', 'letsblog']);
});

Given('組み込み目印検証のサイトに Amazon の目印つきの記事を作成しておく', async ({ ctx }) => {
  const f = fixture(ctx);
  const data = {
    productName: '検証用の商品 & 名',
    price: '￥1,000',
    summary: '検証用の概要',
    productUrl: 'https://www.amazon.co.jp/dp/E2E1563',
    imageUrl: 'https://m.media-amazon.com/images/I/e2e1563.jpg',
    priceTimestamp: '2026/10/05 00:00時点の価格です',
  };
  const json = JSON.stringify({ type: 'AMAZON', data })
    .replace(/-/g, '\\u002d')
    .replace(/</g, '\\u003c')
    .replace(/>/g, '\\u003e')
    .replace(/\[/g, '\\u005b')
    .replace(/]/g, '\\u005d');
  const content = `<!-- lbs:embed ${json} -->\n\n<a class="lb-amazon-card" href="${data.productUrl}">投稿時点</a>\n\n<!-- /lbs:embed -->`;
  f.amazonPostId = wpCli(f.siteKey, [
    'post',
    'create',
    '--post_status=publish',
    '--post_title=E2E-1563 amazon',
    `--post_content=${content}`,
    '--porcelain',
  ]);
});

When('組み込み目印検証のブログカードのデザインテンプレートをアプリで変更する', async ({ ctx, request }) => {
  const f = fixture(ctx);
  await putDesign(request, f.projectId, 'BLOGCARD', `<section class="${blogcardClass(f)}"><h3>{{title}}</h3><a href="{{url}}">{{siteName}}</a></section>`);
});

When('組み込み目印検証の Amazon のデザインテンプレートをアプリで変更する', async ({ ctx, request }) => {
  const f = fixture(ctx);
  await putDesign(request, f.projectId, 'AMAZON', `<div class="${amazonClass(f)}"><b>{{productName}}</b><i>{{price}}</i><a href="{{productUrl}}">{{summary}}</a></div>`);
});

When('組み込み目印検証の目次のデザインテンプレートをアプリで変更する', async ({ ctx, request }) => {
  const f = fixture(ctx);
  await putDesign(request, f.projectId, 'TOC', `<nav class="${tocClass(f)}">{{toc}}</nav>`);
});

Then('組み込み目印検証の記事のブログカードが新しいテンプレートで表示される', async ({ ctx }) => {
  const f = fixture(ctx);
  await expect
    .poll(
      () => {
        const html = rendered(f.siteKey, f.postId);
        return html.includes(`class="${blogcardClass(f)}"`) && !html.includes('class="lb-blogcard"');
      },
      POLL
    )
    .toBe(true);
  expect(rendered(f.siteKey, f.postId)).toContain('<h3>Example Domain</h3>');
});

Then('組み込み目印検証の Amazon の記事のカードが新しいテンプレートで表示される', async ({ ctx }) => {
  const f = fixture(ctx);
  await expect
    .poll(
      () => {
        const html = rendered(f.siteKey, f.amazonPostId as string);
        return html.includes(`class="${amazonClass(f)}"`) && !html.includes('lb-amazon-card');
      },
      POLL
    )
    .toBe(true);
  const html = rendered(f.siteKey, f.amazonPostId as string);
  // 取得したデータは表示時にエスケープされて差し込まれる
  expect(html).toContain('<b>検証用の商品 &amp; 名</b>');
  expect(html).toContain('<a href="https://www.amazon.co.jp/dp/E2E1563">検証用の概要</a>');
  expect(visible(html)).not.toContain('lbs:embed');
});

Then('組み込み目印検証の記事の目次が新しいテンプレートで見出しの構造のまま表示される', async ({ ctx }) => {
  const f = fixture(ctx);
  await expect
    .poll(() => rendered(f.siteKey, f.postId).includes(`<nav class="${tocClass(f)}">`), POLL)
    .toBe(true);
  // the_content の wpautop はタグの間に改行を入れるため、タグ間の空白は構造の比較に含めない
  const html = rendered(f.siteKey, f.postId).replace(/>\s+</g, '><');
  expect(html).toMatch(/<nav class="[^"]+"><ul class="lb-toc-list"><li><a href="#[^"]+">第一章<\/a><ul><li><a href="#[^"]+">第一節<\/a><\/li><\/ul><\/li><li><a href="#[^"]+">第二章<\/a><\/li><\/ul><\/nav>/);
  expect(visible(html)).not.toContain('lbs:embed');
});

Then('組み込み目印検証の記事の目次は変わらない', async ({ ctx }) => {
  const f = fixture(ctx);
  const html = rendered(f.siteKey, f.postId);
  expect(html).toContain('<ul class="lb-toc-list">');
  expect(html).not.toContain(tocClass(f));
});

Then('組み込み目印検証の記事のブログカードは変わらない', async ({ ctx }) => {
  const f = fixture(ctx);
  const html = rendered(f.siteKey, f.postId);
  expect(html).toContain('class="lb-blogcard"');
  expect(html).not.toContain(blogcardClass(f));
});

Then('組み込み目印検証の記事を表示しても外部への通信は起きず、投稿時に取得したタイトルが表示される', async ({ ctx }) => {
  const f = fixture(ctx);
  // 表示中の HTTP 要求を数える。要求があれば失敗させ、件数を末尾に出す。
  const out = wpCli(f.siteKey, [
    'eval',
    `$GLOBALS['lbs_http']=0;add_filter('pre_http_request',function(){$GLOBALS['lbs_http']++;return new WP_Error('blocked','blocked');},1,3);` +
      `echo apply_filters('the_content', get_post(${Number(f.postId)})->post_content);echo "\\nHTTP_CALLS=".$GLOBALS['lbs_http'];`,
  ]);
  expect(out).toContain('HTTP_CALLS=0');
  expect(out).toContain('<h3>Example Domain</h3>');
});

Then('組み込み目印検証の記事の表示は投稿時点の HTML のままで目印は画面に出ない', async ({ ctx }) => {
  const f = fixture(ctx);
  const stored = wpCli(f.siteKey, ['eval', `echo get_post(${Number(f.postId)})->post_content;`]);
  expect(stored).toContain('<!-- lbs:embed ');
  const html = rendered(f.siteKey, f.postId);
  expect(html).toContain('class="lb-blogcard"');
  expect(html).toContain('<ul class="lb-toc-list">');
  expect(visible(html)).not.toContain('lbs:embed');
  expect(visible(html)).not.toContain('"type"');
});

After({ tags: '@project' }, async ({ ctx, request }) => {
  const f = ctx.embedFixture as EmbedFixture | undefined;
  if (f === undefined) {
    return;
  }
  const headers = await adminHeaders(request);
  await request.delete(`/api/sites/${f.siteId}`, { headers });
  await request.delete(`/api/projects/${f.projectId}`, { headers });
});
