import type { APIRequestContext } from '@playwright/test';
import { Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  E2E_TEST_EMAIL,
  E2E_TEST_PASSWORD,
  expect,
  fetchAccessToken,
} from '../support';
import { STUB_URLS } from '../support/stubs';

/**
 * PR の head から記事一式を取得する API の受け入れシナリオを支えるステップ定義(issue #1338)。
 *
 * プロジェクトのフィクスチャは記事プランの `articlePlan.steps.ts`(`ctx.plan`)が用意する。
 * ここでは GitHub スタブへ PR・ブランチ・ファイルを一意な名前で作り(実 GitHub と同じ
 * `POST /pulls` と `PUT /contents/{path}`)、記事取得 API を叩いて応答を確かめる。
 * 1MB 超のファイルはスタブのシード PR #201 の large.png を使う(#1334)。
 */

const REPO = '/repos/e2e-stub/acceptance';
const STUB_TOKEN = 'e2e-stub-token';
const LARGE_PNG_BYTES = 1024 * 1024 + 1024;

interface ArticleResponse {
  status: number;
  text: string;
  json: any;
}

interface ArticleFixture {
  prNumber: number;
  head: string;
  slug?: string;
}

async function stub(method: string, pathname: string, body?: unknown): Promise<{ status: number; json: any }> {
  const res = await fetch(`${STUB_URLS.github}${pathname}`, {
    method,
    headers: {
      Authorization: `Bearer ${STUB_TOKEN}`,
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  return { status: res.status, json: text ? JSON.parse(text) : null };
}

function uniqueHead(): string {
  return `article/e2e-fetch-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
}

async function createPullRequest(head: string): Promise<number> {
  const res = await stub('POST', `${REPO}/pulls`, {
    title: `E2Eスタブ: ${head}`,
    head,
    base: 'main',
    body: '記事取得APIの受け入れテスト',
  });
  expect(res.status, `スタブへのPR作成に失敗: ${JSON.stringify(res.json)}`).toBe(201);
  return res.json.number as number;
}

async function putFile(head: string, path: string, content: string): Promise<void> {
  const res = await stub('PUT', `${REPO}/contents/${path}`, {
    message: `E2E: ${path}`,
    branch: head,
    content: Buffer.from(content, 'utf8').toString('base64'),
  });
  expect(res.status, `スタブへのファイル配置に失敗(${path}): ${JSON.stringify(res.json)}`).toBeLessThan(300);
}

function articleMarkdown(slug: string, title: string): string {
  return [
    '---',
    `title: ${title}`,
    `slug: ${slug}`,
    'status: draft',
    'category: news',
    'tags: [alpha, beta]',
    'featured_image: assets/cover.png',
    'publish_scheduled_at: "2026-12-25T09:00:00Z"',
    'wp_post_id: 99',
    '---',
    '# 見出し',
    '',
    '取得サンプルの本文です',
    '',
  ].join('\n');
}

function projectIdOf(ctx: Record<string, unknown>): number {
  const plan = ctx.plan as { projectId: number } | undefined;
  if (!plan) {
    throw new Error('先にプロジェクトを用意するステップを実行すること');
  }
  return plan.projectId;
}

function fixtureOf(ctx: Record<string, unknown>): ArticleFixture {
  const fixture = ctx.articleFixture as ArticleFixture | undefined;
  if (!fixture) {
    throw new Error('先にPRをスタブに用意するステップを実行すること');
  }
  return fixture;
}

function lastOf(ctx: Record<string, unknown>): ArticleResponse {
  const last = ctx.articleResponse as ArticleResponse | undefined;
  if (!last) {
    throw new Error('先に記事を取得するステップを実行すること');
  }
  return last;
}

async function fetchArticle(
  request: APIRequestContext,
  projectId: number,
  prNumber: number,
  email: string,
  password: string
): Promise<ArticleResponse> {
  const token = await fetchAccessToken(request, email, password);
  const response = await request.get(
    `/api/projects/${projectId}/article-review/pull-requests/${prNumber}/article`,
    { headers: { Authorization: `Bearer ${token}` } }
  );
  const text = await response.text();
  let json: any = null;
  try {
    json = JSON.parse(text);
  } catch {
    json = null;
  }
  return { status: response.status(), text, json };
}

Given(
  /^記事「(.+)」をfront matter付きで含むPRがスタブに用意されている$/,
  async ({ ctx }, slug: string) => {
    const head = uniqueHead();
    const prNumber = await createPullRequest(head);
    await putFile(head, `articles/${slug}/article.md`, articleMarkdown(slug, '取得サンプル'));
    await putFile(head, `articles/${slug}/assets/cover.png`, 'PNG');
    ctx.articleFixture = { prNumber, head, slug } satisfies ArticleFixture;
  }
);

Given('記事ディレクトリを含まないPRがスタブに用意されている', async ({ ctx }) => {
  const head = uniqueHead();
  const prNumber = await createPullRequest(head);
  await putFile(head, 'docs/notes.md', 'これは記事ではない');
  ctx.articleFixture = { prNumber, head } satisfies ArticleFixture;
});

Given(/^記事「(.+)」と「(.+)」を含むPRがスタブに用意されている$/, async ({ ctx }, first: string, second: string) => {
  const head = uniqueHead();
  const prNumber = await createPullRequest(head);
  await putFile(head, `articles/${first}/article.md`, articleMarkdown(first, '一本目'));
  await putFile(head, `articles/${second}/article.md`, articleMarkdown(second, '二本目'));
  ctx.articleFixture = { prNumber, head } satisfies ArticleFixture;
});

Given(
  /^記事「(.+)」のtitleが「(.+)」であるPRがスタブに用意されている$/,
  async ({ ctx }, slug: string, title: string) => {
    const head = uniqueHead();
    const prNumber = await createPullRequest(head);
    await putFile(head, `articles/${slug}/article.md`, articleMarkdown(slug, title));
    ctx.articleFixture = { prNumber, head, slug } satisfies ArticleFixture;
  }
);

When('管理者がそのPRの記事を取得する', async ({ ctx, request }) => {
  ctx.articleResponse = await fetchArticle(
    request, projectIdOf(ctx), fixtureOf(ctx).prNumber, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
});

When(/^管理者がシードのPR「(\d+)」の記事を取得する$/, async ({ ctx, request }, number: string) => {
  ctx.articleResponse = await fetchArticle(
    request, projectIdOf(ctx), Number(number), E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
});

When(
  /^メンバーではない一般利用者がシードのPR「(\d+)」の記事を取得する$/,
  async ({ ctx, request }, number: string) => {
    ctx.articleResponse = await fetchArticle(
      request, projectIdOf(ctx), Number(number), E2E_TEST_EMAIL, E2E_TEST_PASSWORD);
  }
);

When(/^そのPRのheadブランチへtitleが「(.+)」のコミットを積む$/, async ({ ctx }, title: string) => {
  const fixture = fixtureOf(ctx);
  await putFile(fixture.head, `articles/${fixture.slug}/article.md`, articleMarkdown(fixture.slug!, title));
});

Then('記事の取得は成功する', async ({ ctx }) => {
  const last = lastOf(ctx);
  expect(last.status, `記事の取得に失敗した: ${last.text}`).toBe(200);
});

Then(/^記事の取得は「(\d+)」で失敗し、エラーに「(.+)」が含まれる$/, async ({ ctx }, status: string, fragment: string) => {
  const last = lastOf(ctx);
  expect(last.status, `期待した失敗にならなかった: ${last.text}`).toBe(Number(status));
  expect(last.text).toContain(fragment);
});

Then(/^記事の取得は「(\d+)」で失敗する$/, async ({ ctx }, status: string) => {
  const last = lastOf(ctx);
  expect(last.status, `期待した失敗にならなかった: ${last.text}`).toBe(Number(status));
});

Then(/^記事のスラッグは「(.+)」である$/, async ({ ctx }, slug: string) => {
  expect(lastOf(ctx).json.slug).toBe(slug);
});

Then(/^front matterのtitleは「([^」]+)」でstatusは「([^」]+)」である$/, async ({ ctx }, title: string, status: string) => {
  const fm = lastOf(ctx).json.frontMatter;
  expect(fm.title).toBe(title);
  expect(fm.status).toBe(status);
});

Then(/^front matterのtitleは「([^」]+)」である$/, async ({ ctx }, title: string) => {
  expect(lastOf(ctx).json.frontMatter.title).toBe(title);
});

Then(
  /^front matterのcategoriesは「(.+)」で、tagsは「(.+)」である$/,
  async ({ ctx }, categories: string, tags: string) => {
    const fm = lastOf(ctx).json.frontMatter;
    // 拡張と同じく、単数形の category も categories へ寄せられる。
    expect(fm.categories).toEqual(categories.split(','));
    expect(fm.tags).toEqual(tags.split(','));
  }
);

Then(
  /^front matterのfeaturedImageは「(.+)」でpublishScheduledAtは「(.+)」である$/,
  async ({ ctx }, image: string, scheduled: string) => {
    const fm = lastOf(ctx).json.frontMatter;
    expect(fm.featuredImage).toBe(image);
    expect(fm.publishScheduledAt).toBe(scheduled);
  }
);

Then(/^本文Markdownに「(.+)」が含まれ、front matterの区切りは含まれない$/, async ({ ctx }, fragment: string) => {
  const body = lastOf(ctx).json.body as string;
  expect(body).toContain(fragment);
  expect(body).not.toContain('title:');
  expect(body.startsWith('---')).toBe(false);
});

Then(/^assetsに「(.+)」と「(.+)」が列挙される$/, async ({ ctx }, a: string, b: string) => {
  const names = (lastOf(ctx).json.assets as { name: string }[]).map((x) => x.name);
  expect(names).toContain(a);
  expect(names).toContain(b);
});

Then(/^assetsの「(.+)」のサイズはスタブが持つ実サイズの1MBを超えるバイト数と一致する$/, async ({ ctx }, name: string) => {
  const asset = (lastOf(ctx).json.assets as { name: string; size: number }[]).find((x) => x.name === name);
  expect(asset, `assets に ${name} が無い: ${lastOf(ctx).text}`).toBeDefined();
  expect(asset!.size).toBeGreaterThan(1024 * 1024);
  expect(asset!.size).toBe(LARGE_PNG_BYTES);
});
