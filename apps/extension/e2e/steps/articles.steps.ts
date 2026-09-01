/** 記事の作成・プレビュー・公開のステップ(issue #942 / AT-16)。 */

import { Given, Then, When } from '../support/gherkin';
import { w } from './common.steps';
import * as apiClient from '../../src/apiClient';
import { parseArticle, stringifyArticle } from '../../src/frontMatter';

/** 公開シナリオが使う記事本文。front matter付きのMarkdownを拡張の実装で組み立てる。 */
function article(title: string, slug: string, body: string): string {
  return stringifyArticle({
    data: { title, slug, status: 'draft', categories: ['未分類'], tags: ['at16'] },
    content: body,
  });
}

When('記事のテーマ {string} から構成案を生成する', async (world, theme) => {
  const scope = w(world);
  (scope as { structure?: unknown }).structure = await apiClient.suggestArticleStructure(
    scope.token,
    scope.actor,
    scope.project.id,
    [{ role: 'user', content: theme }]
  );
});

Then('構成案に見出しが含まれる', (world) => {
  const structure = (world as { structure?: { structure?: string } }).structure?.structure ?? '';
  if (!/^#{1,3} /m.test(structure)) {
    throw new Error(`構成案に見出しがありません: ${structure}`);
  }
});

When('次のMarkdownのプレビューHTMLを取得する', async (world, markdown) => {
  const scope = w(world);
  (scope as { html?: string }).html = await apiClient.renderPreviewHtml(
    scope.token,
    scope.actor,
    scope.project.id,
    markdown
  );
});

Then('プレビューHTMLに見出しと本文が含まれる', (world) => {
  const html = (world as { html?: string }).html ?? '';
  if (!/<h1[^>]*>見出し<\/h1>/.test(html) || !html.includes('本文です。')) {
    throw new Error(`プレビューHTMLが想定と異なります: ${html}`);
  }
});

async function publish(world: unknown, slug: string, body: string, scheduledAt?: string) {
  const scope = w(world as never);
  const source = article(`AT16 ${slug}`, slug, body);
  const parsed = parseArticle(source);
  const result = await apiClient.publishPost(scope.token, {
    site: scope.site.siteKey,
    title: String(parsed.data.title),
    slug,
    status: 'draft',
    categories: parsed.data.categories,
    tags: parsed.data.tags,
    wpPostId: (scope as { wpPostId?: string }).wpPostId ?? null,
    markdown: parsed.content,
    images: [],
    publishScheduledAt: scheduledAt,
  }, scope.actor);
  (scope as { published?: unknown }).published = result;
  (scope as { slug?: string }).slug = slug;
  return result;
}

Given('スラッグ {string} の記事を下書きとして公開する', async (world, slug) => {
  await publish(world, slug, '受け入れテストで作成した記事本文です。');
});

Then('WordPress投稿のIDと公開URLが返る', (world) => {
  const published = (world as { published?: { wpPostId?: string; wpPostUrl?: string } }).published;
  if (!published?.wpPostId || !published.wpPostUrl?.startsWith('http')) {
    throw new Error(`投稿結果が不正です: ${JSON.stringify(published)}`);
  }
});

Then('公開した記事はスラッグから照会できる', async (world) => {
  const scope = w(world);
  const slug = (scope as unknown as { slug: string }).slug;
  const expected = (scope as unknown as { published: { wpPostId: string } }).published.wpPostId;
  const lookup = await apiClient.lookupExistingPost(scope.token, scope.site.siteKey, slug, scope.actor);
  if (lookup?.wpPostId !== expected) {
    throw new Error(`照会結果が一致しません: ${JSON.stringify(lookup)} != ${expected}`);
  }
});

When('同じスラッグの記事を本文を変えて再度公開する', async (world) => {
  const scope = w(world);
  const slug = (scope as unknown as { slug: string }).slug;
  const first = (scope as unknown as { published: { wpPostId: string } }).published;
  (scope as { firstWpPostId?: string }).firstWpPostId = first.wpPostId;
  const lookup = await apiClient.lookupExistingPost(scope.token, scope.site.siteKey, slug, scope.actor);
  (scope as { wpPostId?: string }).wpPostId = lookup?.wpPostId;
  await publish(world, slug, '受け入れテストで更新した記事本文です。');
});

Then('同じWordPress投稿IDが返る', (world) => {
  const scope = world as { firstWpPostId?: string; published?: { wpPostId?: string } };
  if (!scope.firstWpPostId || scope.published?.wpPostId !== scope.firstWpPostId) {
    throw new Error(
      `投稿IDが変わりました: ${String(scope.firstWpPostId)} -> ${String(scope.published?.wpPostId)}`
    );
  }
});

When('スラッグ {string} の記事を公開予定日時付きで公開する', async (world, slug) => {
  const scheduledAt = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString().replace(/\.\d+Z$/, 'Z');
  const scope = w(world);
  const lookup = await apiClient.lookupExistingPost(scope.token, scope.site.siteKey, slug, scope.actor);
  (scope as { wpPostId?: string }).wpPostId = lookup?.wpPostId;
  await publish(world, slug, '受け入れテストの予約投稿です。', scheduledAt);
});

Then('投稿ステータスが {string} になる', (world, expected) => {
  const published = (world as { published?: { status?: string } }).published;
  if (published?.status !== expected) {
    throw new Error(`ステータスが ${String(published?.status)} でした(期待: ${expected})`);
  }
});
