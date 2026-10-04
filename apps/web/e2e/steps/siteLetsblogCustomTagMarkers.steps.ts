import { execFileSync } from 'node:child_process';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * カスタムタグの目印付き投稿と、プラグインの表示時の展開し直し(issue #1560)のステップ定義。
 * 「ステップ定義ファイルは相乗りしない」方針のため、フィクスチャのヘルパーはここに閉じて持つ。
 * 表示は WordPress の `the_content` フィルタを通した結果で確かめる(`wp eval`)。
 */

const WORDPRESS_CONTAINER = 'lbs-wordpress';
const POLL = { timeout: 180_000, intervals: [2_000, 3_000, 5_000] };

interface MarkerFixture {
  siteId: number;
  siteKey: string;
  projectId: number;
  tagId: number;
  tagName: string;
  className: string;
  postId: string;
  legacyPostId?: string;
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

function fixture(ctx: Record<string, unknown>): MarkerFixture {
  return ctx.markerFixture as MarkerFixture;
}

/** 投稿の本文を `the_content` フィルタに通した表示結果。filterOff が true なら展開のフィルタを外して通す。 */
function rendered(siteKey: string, postId: string, filterOff = false): string {
  const off = filterOff ? "remove_filter('the_content','letsblog_expand_custom_tags',9);" : '';
  return wpCli(siteKey, [
    'eval',
    `${off}echo apply_filters('the_content', get_post(${Number(postId)})->post_content);`,
  ]);
}

/** HTML コメントを除いた、画面に出る(ブラウザが描画する)側の HTML。 */
function visible(html: string): string {
  return html.replace(/<!--[\s\S]*?-->/g, '');
}

async function putTag(request: APIRequestContext, f: MarkerFixture, className: string): Promise<void> {
  const response = await request.put(`/api/custom-tags/${f.tagId}`, {
    headers: await adminHeaders(request),
    data: {
      tagName: f.tagName,
      htmlTemplate: `<div class="${className}" data-lv="{{attr:level}}">{{content}}</div>`,
      description: 'e2e1560 の検証用タグ',
      tagFormat: 'BLOCK',
      projectId: f.projectId,
    },
  });
  expect(response.ok(), `カスタムタグの変更に失敗しました (status=${response.status()}): ${await response.text()}`).toBe(true);
}

Given(
  '目印検証用に、カスタムタグを定義したプロジェクトのサイトへタグ入りの記事を投稿しておく',
  async ({ ctx, request }) => {
    const headers = await adminHeaders(request);
    const suffix = uniqueSuffix();

    const project = await request.post('/api/projects', {
      headers,
      data: { name: `E2E 1560 ${suffix}`, slug: `e2e1560-${suffix}` },
    });
    expect(project.ok(), `プロジェクトの作成に失敗しました: ${await project.text()}`).toBe(true);
    const projectId = ((await project.json()) as { id: number }).id;

    const siteKey = `e2e1560${suffix}`.toLowerCase().replace(/[^a-z0-9]/g, '');
    const site = await request.post('/api/sites/managed-wordpress', {
      headers,
      data: {
        name: `E2E 1560 ${suffix}`,
        siteKey,
        title: `E2E 1560 ${suffix}`,
        adminUser: 'e2e1560admin',
        adminEmail: `e2e-1560-${suffix}@letsblog.local`,
        adminPassword: `E2e1560#Marker${suffix}`,
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

    const tagName = `e2e1560t${suffix}`.toLowerCase();
    const className = `e2e1560-v1-${suffix}`;
    const tag = await request.post('/api/custom-tags', {
      headers,
      data: {
        tagName,
        htmlTemplate: `<div class="${className}" data-lv="{{attr:level}}">{{content}}</div>`,
        description: 'e2e1560 の検証用タグ',
        tagFormat: 'BLOCK',
        projectId,
      },
    });
    expect(tag.ok(), `カスタムタグの作成に失敗しました: ${await tag.text()}`).toBe(true);
    const tagId = ((await tag.json()) as { id: number }).id;

    const f: MarkerFixture = { siteId, siteKey, projectId, tagId, tagName, className, postId: '' };
    ctx.markerFixture = f;

    const published = await request.post('/api/posts/publish', {
      headers,
      multipart: {
        site: siteKey,
        title: `E2E-1560-${suffix}`,
        slug: `e2e-1560-${suffix}`,
        status: 'publish',
        markdown: `[${tagName} level="warn"]\n**太字**です\n[/${tagName}]\n`,
      },
      timeout: 120_000,
    });
    expect(published.ok(), `記事の投稿に失敗しました (status=${published.status()}): ${await published.text()}`).toBe(true);
    f.postId = ((await published.json()) as { wpPostId: string }).wpPostId;

    // 同期済みであること(展開し直しても投稿時点と同じ見た目になること)を待つ
    await expect
      .poll(() => rendered(siteKey, f.postId).includes(className), POLL)
      .toBe(true);
  }
);

Given('目印検証のサイトのプラグインを停止しておく', async ({ ctx }) => {
  wpCli(fixture(ctx).siteKey, ['plugin', 'deactivate', 'letsblog']);
});

Given('目印検証のサイトに目印のない既存記事を作成しておく', async ({ ctx }) => {
  const f = fixture(ctx);
  f.legacyPostId = wpCli(f.siteKey, [
    'post',
    'create',
    '--post_status=publish',
    '--post_title=E2E-1560 legacy',
    `--post_content=<div class="e2e1560-legacy"><strong>既存</strong>の記事</div>`,
    '--porcelain',
  ]);
});

When('目印検証のカスタムタグのテンプレートをアプリで変更する', async ({ ctx, request }) => {
  const f = fixture(ctx);
  await putTag(request, f, `e2e1560-v2-${f.className.split('-').pop()}`);
});

When('目印検証のカスタムタグをアプリで削除する', async ({ ctx, request }) => {
  const f = fixture(ctx);
  const response = await request.delete(`/api/custom-tags/${f.tagId}`, { headers: await adminHeaders(request) });
  expect(response.ok(), `カスタムタグの削除に失敗しました: ${await response.text()}`).toBe(true);
  f.tagId = 0;
});

function v2Class(f: MarkerFixture): string {
  return `e2e1560-v2-${f.className.split('-').pop()}`;
}

Then('目印検証の記事の表示が新しいテンプレートになる', async ({ ctx }) => {
  const f = fixture(ctx);
  await expect
    .poll(
      () => {
        const html = rendered(f.siteKey, f.postId);
        return html.includes(v2Class(f)) && !html.includes(f.className);
      },
      POLL
    )
    .toBe(true);
});

Then('目印検証の記事の表示に属性の値と本文の変換結果が保たれている', async ({ ctx }) => {
  const f = fixture(ctx);
  const html = rendered(f.siteKey, f.postId);
  expect(html).toContain('data-lv="warn"');
  expect(html).toContain('<strong>太字</strong>');
  expect(html).not.toContain(`[${f.tagName}`);
});

Then('目印検証の記事の表示は投稿時点の HTML のままで目印は画面に出ない', async ({ ctx }) => {
  const f = fixture(ctx);
  // 目印が実際に投稿 HTML に入っていること(入っていなければ「出ない」は意味がない)
  const stored = wpCli(f.siteKey, ['eval', `echo get_post(${Number(f.postId)})->post_content;`]);
  expect(stored).toContain('<!-- lbs:tag ');
  const html = rendered(f.siteKey, f.postId);
  expect(html).toContain(f.className);
  expect(html).toContain('<strong>太字</strong>');
  expect(visible(html)).not.toContain('lbs:tag');
  expect(visible(html)).not.toContain('"name"');
});

Then('目印検証の記事の表示は投稿時点の HTML に戻る', async ({ ctx }) => {
  const f = fixture(ctx);
  await expect
    .poll(
      () => {
        const html = rendered(f.siteKey, f.postId);
        return html.includes(f.className) && !html.includes(v2Class(f));
      },
      POLL
    )
    .toBe(true);
  const html = rendered(f.siteKey, f.postId);
  expect(html).toContain('<strong>太字</strong>');
  expect(visible(html)).not.toContain('lbs:tag');
});

Then('目印検証の既存記事の表示は展開の処理の有無で変わらない', async ({ ctx }) => {
  const f = fixture(ctx);
  const withFilter = rendered(f.siteKey, f.legacyPostId as string);
  const withoutFilter = rendered(f.siteKey, f.legacyPostId as string, true);
  expect(withFilter).toContain('<div class="e2e1560-legacy"><strong>既存</strong>の記事</div>');
  expect(withFilter).toBe(withoutFilter);
});

After({ tags: '@project' }, async ({ ctx, request }) => {
  const f = ctx.markerFixture as MarkerFixture | undefined;
  if (f === undefined) {
    return;
  }
  const headers = await adminHeaders(request);
  if (f.tagId !== 0) {
    await request.delete(`/api/custom-tags/${f.tagId}`, { headers });
  }
  await request.delete(`/api/sites/${f.siteId}`, { headers });
  await request.delete(`/api/projects/${f.projectId}`, { headers });
});
