/** 記事の作成・プレビュー・公開・削除のステップ(issue #942 / AT-16、issue #1001)。 */

import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { Given, Then, When } from '../support/gherkin';
import { w } from './common.steps';
import * as apiClient from '../../src/apiClient';
import { httpRequest } from '../../src/httpClient';
import { LocalImageReference, parseArticle, stringifyArticle } from '../../src/frontMatter';
import { deleteUnreferencedMedia, MediaDeletionOutcome, scanUnreferencedMedia } from '../support/api';

/** 公開シナリオが使う記事本文。front matter付きのMarkdownを拡張の実装で組み立てる。 */
function article(title: string, slug: string, body: string, status: string): string {
  return stringifyArticle({
    data: { title, slug, status, categories: ['未分類'], tags: ['at16'] },
    content: body,
  });
}

/**
 * アイキャッチ画像シナリオが使う16x16のPNG。publishPostはディスク上の実ファイルを要求するため、
 * 一時ディレクトリへ書き出してから渡す(リポジトリにバイナリのフィクスチャを置かない)。
 */
const FIXTURE_PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAABlklEQVR4nA3LQQEAIQgAQRvQwAY0sIENaEADnvuzAQ1s' +
  'YAMb0MAmd/Of1hrS6A1tjMZsWMMb0ViNbOzGadxGNV6jNUGELqgwhCmY4EIIS0hhC0e4QglP/tCRTu9oZ3Rmxzreic7q' +
  'ZGd3Tud2qvP6HxRRuqLKUKZiiiuhLCWVrRzlKqU8/cNABn2ggzGYAxv4IAZrkIM9OIM7qMEbf5jIpE90MiZzYhOfxGRN' +
  'crInZ3InNXnzD4YY3VBjGNMww40wlpHGNo5xjTKe/cERpzvqDGc65rgTznLS2c5xrlPO8z8EEvRAgxHMwAIPIlhBBjs4' +
  'wQ0qePGHhSz6QhdjMRe28EUs1iIXe3EWd1GLt/6QSNITTUYyE0s8iWQlmezkJDep5OUfNrLpG92MzdzYxjexWZvc7M3Z' +
  '3E1t3v7DQQ79oIdxmAc7+CEO65CHfTiHe6jDO3+4yKVf9DIu82IXv8RlXfKyL+dyL3V59w+FFL3QYhSzsMKLKFaRxS5O' +
  'cYsqXv3hIY/+0Md4zIc9/BGP9cjHfpzHfdTjPT7YZWEQrVj3PAAAAABJRU5ErkJggg==';

function writeFixtureImage(reference: string): string {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'at16-image-'));
  const absolutePath = path.join(dir, reference);
  fs.writeFileSync(absolutePath, Buffer.from(FIXTURE_PNG_BASE64, 'base64'));
  return absolutePath;
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

// ------------------------------------------------------------------ プレビュー用アイキャッチ(issue #1240)

/**
 * 非公開投稿としてプレビューを実表示する経路(renderPreviewSkeleton)へ、front matter相当の
 * アイキャッチをdata URIとして渡す。managed WordPressのSSH/agentトランスポート限定の経路のため、
 * サイトは「公開先のマネージドWordPressサイトが用意されている」で用意したものを使う。
 */
When('PNGのアイキャッチ画像付きでタイトル {string} の記事をプレビューする', async (world, title) => {
  const scope = w(world);
  const featuredImageDataUri = `data:image/png;base64,${FIXTURE_PNG_BASE64}`;
  const skeleton = await apiClient.renderPreviewSkeleton(
    scope.token,
    scope.actor,
    scope.project.id,
    scope.site.id,
    title,
    '<p>受け入れテストで作成した記事本文です。</p>',
    featuredImageDataUri,
    undefined
  );
  (scope as { skeleton?: unknown }).skeleton = skeleton;
});

Then('プレビュー結果にアイキャッチの警告が含まれない', (world) => {
  const skeleton = (world as { skeleton?: { available: boolean; warning?: string | null } }).skeleton;
  if (!skeleton?.available) {
    throw new Error(`プレビューが利用できません: ${JSON.stringify(skeleton)}`);
  }
  if (skeleton.warning) {
    throw new Error(`アイキャッチの警告が返りました: ${skeleton.warning}`);
  }
});

Then('プレビューHTMLにアップロードした画像が表示される', (world) => {
  const skeleton = (world as { skeleton?: { html?: string | null } }).skeleton;
  const html = skeleton?.html ?? '';
  if (!/wp-content\/uploads\/[^"']*\.png/i.test(html)) {
    throw new Error(`アップロードした画像(.png)がプレビューHTMLに見つかりません: ${html}`);
  }
});

interface PublishOptions {
  status?: string;
  scheduledAt?: string;
  images?: LocalImageReference[];
  featuredImageFilename?: string;
}

async function publish(world: unknown, slug: string, body: string, options: PublishOptions = {}) {
  const scope = w(world as never);
  const status = options.status ?? 'draft';
  const source = article(`AT16 ${slug}`, slug, body, status);
  const parsed = parseArticle(source);
  const result = await apiClient.publishPost(scope.token, {
    site: scope.site.siteKey,
    title: String(parsed.data.title),
    slug,
    status,
    categories: parsed.data.categories,
    tags: parsed.data.tags,
    wpPostId: (scope as { wpPostId?: string }).wpPostId ?? null,
    markdown: parsed.content,
    images: options.images ?? [],
    featuredImageFilename: options.featuredImageFilename,
    publishScheduledAt: options.scheduledAt,
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

// ------------------------------------------------------------------ 予約投稿(issue #1003)

Given('スラッグ {string} の記事を公開予定日時 {string} で公開する', async (world, slug, scheduledAt) => {
  await publish(world, slug, '受け入れテストで作成した記事本文です。', { scheduledAt });
});

When('同じスラッグの記事の公開予定日時を {string} に変更して再公開する', async (world, scheduledAt) => {
  const scope = w(world);
  const slug = (scope as unknown as { slug: string }).slug;
  const lookup = await apiClient.lookupExistingPost(scope.token, scope.site.siteKey, slug, scope.actor);
  (scope as { wpPostId?: string }).wpPostId = lookup?.wpPostId;
  await publish(world, slug, '受け入れテストで更新した記事本文です。', { scheduledAt });
});

Then('応答のstatusが {string} になる', (world, expectedStatus) => {
  const published = (world as { published?: { status?: string } }).published;
  if (published?.status !== expectedStatus) {
    throw new Error(`ステータスが一致しません: ${JSON.stringify(published)} (期待値: ${expectedStatus})`);
  }
});

// ------------------------------------------------------------------ 記事の削除(issue #1001)

Given('スラッグ {string} の記事を公開状態で投稿する', async (world, slug) => {
  await publish(world, slug, '受け入れテストで作成した記事本文です。', { status: 'publish' });
});

Given('スラッグ {string} の記事をアイキャッチ画像付きで公開状態で投稿する', async (world, slug) => {
  const reference = `${slug}-eyecatch.png`;
  const image: LocalImageReference = { reference, absolutePath: writeFixtureImage(reference) };
  await publish(world, slug, `![アイキャッチ](${reference})\n\n受け入れテストで作成した記事本文です。`, {
    status: 'publish',
    images: [image],
    featuredImageFilename: reference,
  });
});

/** 公開URL(WordPressのguid)へ素で要求する。ゴミ箱へ移った投稿は404になる。 */
async function fetchPublicPost(world: unknown): Promise<number> {
  const published = (world as { published?: { wpPostUrl?: string } }).published;
  const url = published?.wpPostUrl ?? '';
  if (!url) {
    throw new Error(`公開URLが分かりません: ${JSON.stringify(published)}`);
  }
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 30_000);
  try {
    const res = await httpRequest(url, {
      method: 'GET',
      headers: {},
      signal: controller.signal,
      allowInsecureTls: true,
    });
    return res.status;
  } finally {
    clearTimeout(timer);
  }
}

Given('公開した記事の公開URLは記事を返す', async (world) => {
  const status = await fetchPublicPost(world);
  if (status >= 400) {
    throw new Error(`公開URLが記事を返しません: HTTP ${status}`);
  }
});

Then('公開した記事の公開URLは記事を返さない', async (world) => {
  const status = await fetchPublicPost(world);
  if (status !== 404) {
    throw new Error(`公開URLがまだ記事を返します: HTTP ${status}`);
  }
});

When('公開した記事を削除する', async (world) => {
  const scope = w(world);
  const published = (scope as unknown as { published: { wpPostId: string } }).published;
  await apiClient.deletePost(scope.token, scope.actor, scope.site.siteKey, published.wpPostId);
});

Then('削除した記事はスラッグ照会で公開済みとして返らない', async (world) => {
  const scope = w(world);
  const slug = (scope as unknown as { slug: string }).slug;
  const lookup = await apiClient.lookupExistingPost(scope.token, scope.site.siteKey, slug, scope.actor);
  if (lookup && lookup.status !== 'trash') {
    throw new Error(`削除した記事がまだ公開済みとして返ります: ${JSON.stringify(lookup)}`);
  }
});

/**
 * 記事の削除で未参照になったメディア(このシナリオが上げたアイキャッチ)だけを対象にする。
 * アップロード後のファイル名は `letsblog-media-<乱数>-<スラッグ>-0001.jpg` の形になるため、
 * 前方一致ではなくスラッグを含むかで絞る。
 */
async function unreferencedFixtureMedia(world: unknown): Promise<string[]> {
  const scope = w(world as never);
  const slug = (scope as unknown as { slug: string }).slug;
  const items = await scanUnreferencedMedia(scope.token, scope.project.id, 'test');
  return items
    .filter((item) => item.title.includes(slug) || item.guid.includes(slug))
    .map((item) => item.mediaId);
}

When('未参照になったメディアをガベージコレクションで削除する', async (world) => {
  const scope = w(world);
  const mediaIds = await unreferencedFixtureMedia(world);
  if (mediaIds.length === 0) {
    throw new Error('記事の削除で未参照になるはずのアイキャッチ画像が見つかりません');
  }
  (scope as { deletedMediaIds?: string[] }).deletedMediaIds = mediaIds;
  (scope as { mediaDeletion?: MediaDeletionOutcome }).mediaDeletion =
    await deleteUnreferencedMedia(scope.token, scope.project.id, 'test', mediaIds);
});

Then('メディアの削除は失敗なく完了する', (world) => {
  const scope = world as { deletedMediaIds?: string[]; mediaDeletion?: MediaDeletionOutcome };
  const outcome = scope.mediaDeletion;
  const expected = scope.deletedMediaIds?.length ?? 0;
  if (!outcome || outcome.status !== 'done' || outcome.failedCount !== 0
      || outcome.deletedCount !== expected) {
    throw new Error(`メディアの削除が失敗しました: ${JSON.stringify(outcome)}`);
  }
});

Then('削除したメディアは未参照メディアの一覧から消える', async (world) => {
  const remaining = await unreferencedFixtureMedia(world);
  if (remaining.length > 0) {
    throw new Error(`削除したはずのメディアが残っています: ${remaining.join(', ')}`);
  }
});

Then('同じWordPress投稿IDが返る', (world) => {
  const scope = world as { firstWpPostId?: string; published?: { wpPostId?: string } };
  if (!scope.firstWpPostId || scope.published?.wpPostId !== scope.firstWpPostId) {
    throw new Error(
      `投稿IDが変わりました: ${String(scope.firstWpPostId)} -> ${String(scope.published?.wpPostId)}`
    );
  }
});
