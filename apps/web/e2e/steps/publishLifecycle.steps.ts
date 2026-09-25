import { execFileSync } from 'node:child_process';
import path from 'node:path';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import {
  E2E_ADMIN_EMAIL,
  E2E_ADMIN_PASSWORD,
  createFixtureProject,
  deleteFixtureProject,
  expect,
  fetchAccessToken,
  loginAsAdmin,
} from '../support';

/**
 * 記事の新規公開と再公開による更新(issue #1171 / AT-6-1)のステップ定義。
 *
 * 兄弟issue(#932系列の子issue群)とステップ定義ファイルを共有しない方針
 * (`publishTaxonomy.steps.ts`・`publishAuthor.steps.ts`・`publishPreview.steps.ts`と同様。
 * 相乗りしない)のため、必要なヘルパーはこのファイル内に閉じて持つ。
 *
 * ## 専用サイトを冪等に用意する理由
 *
 * 「本文・タイトル・アイキャッチが一致する」「再公開で新規作成されない」の正しさは、
 * 実際のWordPress側の投稿の状態(wp-cli)を見ないと確かめられない。#1167のプロビジョニング
 * 済みサイト共有フィクスチャ(`site-provisioning.steps.ts`)はサイトの識別子だけを永続化し
 * WordPress管理者の認証情報は残さないため、wp-cliで直接アクセスする必要がある本ファイルには
 * 使えない。そこで`publishTaxonomy.steps.ts`(#1174)と同じ「固定siteKeyで冪等に用意し、
 * 実行をまたいで再利用する」パターンを踏襲する。
 */

/** リポジトリルート(apps/web/e2e/steps から4階層上)。 */
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

/** 公開検証用サイト。冪等に用意し、実行をまたいで再利用する。 */
const PUBLISH_SITE_KEY = 'at61publishprobe';
const PUBLISH_SITE_ADMIN_USER = 'at61publishadmin';

/** WordPress自動構築の待ち上限。分単位でかかりうる。 */
const PROVISION_TIMEOUT_MS = 600_000;

/** 1x1の透明PNG(`media.steps.ts`と同じ固定バイト列)。アイキャッチの実在・種別確認に使う。 */
const EYECATCH_PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

interface SiteFixture {
  id: number;
  siteKey: string;
}

interface PostPublishResponse {
  wpPostId: string;
  wpPostUrl: string;
  status: string;
}

/** `WordPressSiteProvisioningService#normalizeSlug` と同じ正規化。 */
function wpSlug(siteKey: string): string {
  return siteKey.toLowerCase().replace(/[^a-z0-9-]/g, '-');
}

function shellQuote(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`;
}

/**
 * WordPress コンテナで wp-cli を実行し、標準出力を返す。
 *
 * `docker compose exec` の cwd はコンテナの `WorkingDir` になるため、
 * サイトディレクトリへは `sh -c 'cd ... && ...'` で自分で移動する
 * (docs/ACCEPTANCE_TESTING.md §9「`working_dir` はマウント先にしない」)。
 */
function wpCli(slug: string, command: string): string {
  return execFileSync(
    'docker',
    ['compose', 'exec', '-T', 'wordpress', 'sh', '-c', `cd /var/www/html/sites/${slug} && wp --allow-root ${command}`],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 180_000 }
  ).trim();
}

/** 投稿の指定フィールドをwp-cliで取得する。投稿が存在しなければnull。 */
function postField(slug: string, postId: string, field: string): string | null {
  try {
    return wpCli(slug, `post get ${postId} --field=${field}`);
  } catch {
    // `wp post get`は対象が存在しない場合に非ゼロ終了する。
    return null;
  }
}

interface AttachmentInfo {
  mimeType: string;
  width: number;
  height: number;
}

/**
 * アイキャッチ(メディア添付)の種別・寸法をwp-cliで取得する。`getimagesize`はWordPress
 * コンテナ内のPHP(GD)で実行するため、テスト側で画像デコード用の依存を追加する必要がない。
 */
function attachmentInfo(slug: string, attachmentId: string): AttachmentInfo | null {
  const mimeType = postField(slug, attachmentId, 'post_mime_type');
  if (mimeType === null) {
    return null;
  }
  const phpCode = `echo json_encode(getimagesize(get_attached_file(${Number(attachmentId)})));`;
  const sizeJson = wpCli(slug, `eval ${shellQuote(phpCode)}`);
  const size = JSON.parse(sizeJson) as Record<string, number | string>;
  return { mimeType, width: Number(size['0']), height: Number(size['1']) };
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
}

async function adminHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  const token = await adminToken(request);
  return { Authorization: `Bearer ${token}` };
}

/** issue #765と同じ理由(並列実行時の衝突対策)でユニークな名前を作る。 */
function uniqueSuffix(): string {
  return `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`;
}

async function ensureManagedSite(request: APIRequestContext): Promise<SiteFixture> {
  const headers = await adminHeaders(request);
  const list = await request.get('/api/sites', { headers });
  expect(
    list.ok(),
    `サイト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`
  ).toBe(true);
  const existing = ((await list.json()) as SiteFixture[]).find((site) => site.siteKey === PUBLISH_SITE_KEY);
  if (existing) {
    return existing;
  }
  const created = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: 'AT6-1 publish probe site',
      siteKey: PUBLISH_SITE_KEY,
      title: 'AT6-1 Publish Probe',
      adminUser: PUBLISH_SITE_ADMIN_USER,
      adminEmail: 'at61-publish-probe@letsblog.local',
      adminPassword: 'At61Publish#Passw0rd1',
    },
    timeout: PROVISION_TIMEOUT_MS,
  });
  if (created.status() === 409) {
    // provision-agent側には既に実体があるがDBには未登録(中断した前回実行の取り残し、または
    // このシナリオ自体を@mode:serialなしで並列実行してしまった場合)。
    // site-adoption.steps.tsと同じ「取り込み」で救う(site-adoption.feature参照)。
    return adoptExistingManagedSite(request, headers);
  }
  expect(
    created.ok(),
    `マネージドWordPressの構築に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  return (await created.json()) as SiteFixture;
}

async function adoptExistingManagedSite(
  request: APIRequestContext,
  headers: Record<string, string>
): Promise<SiteFixture> {
  const adopted = await request.post('/api/sites/managed-wordpress/adopt', {
    headers,
    data: {
      name: 'AT6-1 publish probe site',
      siteKey: PUBLISH_SITE_KEY,
      adminUser: PUBLISH_SITE_ADMIN_USER,
    },
  });
  expect(
    adopted.ok(),
    `既存WordPressの取り込みに失敗しました (status=${adopted.status()}): ${await adopted.text()}`
  ).toBe(true);
  return (await adopted.json()) as SiteFixture;
}

interface PublishOptions {
  title: string;
  slug: string;
  markdown: string;
  wpPostId?: string;
  image?: { name: string; mimeType: string; buffer: Buffer };
  featuredImageFilename?: string;
  imageReferences?: string[];
}

/**
 * `imageReferences`(サーバー側は`List<String>`)を配列で送る必要があるため、Playwrightの
 * `multipart`にプレーンオブジェクトを渡す方式(1キー=1パートしか送れない)ではなく、
 * Node標準の`FormData`/`File`(グローバル)へ`append`する方式を使う。Playwrightは
 * `options.multipart instanceof FormData`の場合、同名で複数回`append`したエントリを
 * それぞれ独立したパートとして送るため、配列値をそのまま渡そうとして失敗する
 * (プレーンオブジェクト経路の`toFormField`は配列をReadStreamと誤認して例外になる)問題を避けられる。
 */
async function publish(
  request: APIRequestContext,
  token: string,
  siteKey: string,
  options: PublishOptions
): Promise<PostPublishResponse> {
  const form = new FormData();
  form.append('site', siteKey);
  form.append('title', options.title);
  form.append('slug', options.slug);
  form.append('status', 'publish');
  form.append('markdown', options.markdown);
  if (options.wpPostId) {
    form.append('wpPostId', options.wpPostId);
  }
  if (options.image) {
    form.append(
      'images',
      new File([new Uint8Array(options.image.buffer)], options.image.name, { type: options.image.mimeType })
    );
  }
  if (options.featuredImageFilename) {
    form.append('featuredImageFilename', options.featuredImageFilename);
  }
  if (options.imageReferences) {
    for (const reference of options.imageReferences) {
      form.append('imageReferences', reference);
    }
  }

  const response = await request.post('/api/posts/publish', {
    headers: { Authorization: `Bearer ${token}` },
    multipart: form,
    timeout: 120_000,
  });
  expect(
    response.ok(),
    `記事公開に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as PostPublishResponse;
}

// ------------------------------------------------------- 背景

Given('公開検証用のWordPressサイトがあり、プロジェクトのテスト環境に紐づいている', async ({ ctx, request }) => {
  const site = await ensureManagedSite(request);
  const token = await adminToken(request);
  const project = await createFixtureProject(request, token, 'at61-publish');
  const headers = await adminHeaders(request);
  const bound = await request.post(`/api/projects/${project.id}/environments`, {
    headers,
    data: { environment: 'test', siteId: site.id },
  });
  expect(
    bound.ok(),
    `テスト環境へのサイト紐付けに失敗しました (status=${bound.status()}): ${await bound.text()}`
  ).toBe(true);

  ctx.publishSiteKey = site.siteKey;
  ctx.publishSiteSlug = wpSlug(site.siteKey);
  ctx.publishProjectId = project.id;
  ctx.publishCleanupPostIds = [] as string[];
});

// ------------------------------------------------------- シナリオ1: 新規公開→投稿履歴(AC1)

When('記事を新規公開する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const unique = uniqueSuffix();
  const title = `E2E-1171-New-${unique}`;
  const slug = `e2e-1171-new-${unique}`;

  const result = await publish(request, token, ctx.publishSiteKey as string, {
    title,
    slug,
    markdown: `# ${title}\n\nissue #1171のE2Eが新規公開した検証記事です。\n`,
  });

  ctx.newPostTitle = title;
  ctx.newPostSlug = slug;
  ctx.newPostId = result.wpPostId;
  (ctx.publishCleanupPostIds as string[]).push(result.wpPostId);
});

Then('WordPress側にその記事が公開状態で作成されている', async ({ ctx }) => {
  const slug = ctx.publishSiteSlug as string;
  const postId = ctx.newPostId as string;
  const status = postField(slug, postId, 'post_status');
  expect(status, `WordPress側に投稿(id=${postId})が見つかりません`).toBe('publish');
});

Then('投稿履歴ページにその記事が表示される', async ({ ctx, page }) => {
  await loginAsAdmin(page);
  await page.goto('/posts');

  const postSlug = ctx.newPostSlug as string;
  const postId = ctx.newPostId as string;
  const historyRow = page.locator('tbody tr').filter({ hasText: postSlug });
  await expect(historyRow).toBeVisible({ timeout: 15000 });
  await expect(historyRow).toContainText(String(postId));
});

// ------------------------------------------------------- シナリオ2: 本文・タイトル・アイキャッチの一致(AC2)

When('アイキャッチ画像付きで記事を新規公開する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const unique = uniqueSuffix();
  const title = `E2E-1171-Eyecatch-${unique}`;
  const slug = `e2e-1171-eyecatch-${unique}`;
  const bodyMarker = `issue-1171-body-marker-${unique}`;
  const imageReference = 'eyecatch.png';

  const result = await publish(request, token, ctx.publishSiteKey as string, {
    title,
    slug,
    markdown: `# ${title}\n\n![eyecatch](${imageReference})\n\n${bodyMarker}\n`,
    image: {
      name: imageReference,
      mimeType: 'image/png',
      buffer: Buffer.from(EYECATCH_PNG_BASE64, 'base64'),
    },
    featuredImageFilename: imageReference,
    imageReferences: [imageReference],
  });

  ctx.eyecatchPostTitle = title;
  ctx.eyecatchBodyMarker = bodyMarker;
  ctx.eyecatchPostId = result.wpPostId;
  (ctx.publishCleanupPostIds as string[]).push(result.wpPostId);
});

Then('WordPress側のその記事のタイトルと本文が送信した内容と一致する', async ({ ctx }) => {
  const slug = ctx.publishSiteSlug as string;
  const postId = ctx.eyecatchPostId as string;

  const actualTitle = postField(slug, postId, 'post_title');
  expect(actualTitle, `投稿(id=${postId})のタイトルが取得できません`).toBe(ctx.eyecatchPostTitle as string);

  const actualContent = postField(slug, postId, 'post_content');
  expect(
    actualContent?.includes(ctx.eyecatchBodyMarker as string),
    `投稿(id=${postId})の本文に送信したマーカー文字列が含まれません: ${actualContent}`
  ).toBe(true);
});

Then('WordPress側のその記事のアイキャッチが送信した画像と一致する', async ({ ctx }) => {
  const slug = ctx.publishSiteSlug as string;
  const postId = ctx.eyecatchPostId as string;

  const thumbnailId = wpCli(slug, `post meta get ${postId} _thumbnail_id`);
  expect(thumbnailId, `投稿(id=${postId})にアイキャッチ(_thumbnail_id)が設定されていません`).toBeTruthy();

  const info = attachmentInfo(slug, thumbnailId);
  expect(info, `アイキャッチ(id=${thumbnailId})の情報が取得できません`).not.toBeNull();
  expect((info as AttachmentInfo).mimeType, 'アイキャッチのMIMEタイプが送信画像と一致しません').toBe('image/png');
  expect((info as AttachmentInfo).width, 'アイキャッチの幅が送信画像と一致しません').toBe(1);
  expect((info as AttachmentInfo).height, 'アイキャッチの高さが送信画像と一致しません').toBe(1);
});

// ------------------------------------------------------- シナリオ3: 再公開による更新(AC3)

When('記事を新規公開してから内容を変えて再公開する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const siteKey = ctx.publishSiteKey as string;
  const unique = uniqueSuffix();
  const title1 = `E2E-1171-Republish-${unique}`;
  const slug = `e2e-1171-republish-${unique}`;
  const marker1 = `issue-1171-republish-first-${unique}`;

  const first = await publish(request, token, siteKey, {
    title: title1,
    slug,
    markdown: `# ${title1}\n\n${marker1}\n`,
  });
  (ctx.publishCleanupPostIds as string[]).push(first.wpPostId);

  // VSCode拡張と同じ経路(GET /api/posts/{site}/by-slug/{slug})で既存投稿のwpPostIdを解決してから
  // 再公開する(issue #505、AC-POST-008)。
  const lookup = await request.get(`/api/posts/${siteKey}/by-slug/${slug}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(
    lookup.ok(),
    `既存投稿の照会に失敗しました (status=${lookup.status()}): ${await lookup.text()}`
  ).toBe(true);
  const existingWpPostId = ((await lookup.json()) as { wpPostId: string }).wpPostId;
  expect(existingWpPostId, '既存投稿の照会結果にwpPostIdが含まれません').toBe(first.wpPostId);

  const title2 = `E2E-1171-Republish-Updated-${unique}`;
  const marker2 = `issue-1171-republish-second-${unique}`;
  const second = await publish(request, token, siteKey, {
    title: title2,
    slug,
    markdown: `# ${title2}\n\n${marker2}\n`,
    wpPostId: existingWpPostId,
  });

  ctx.republishSlug = slug;
  ctx.republishFirstPostId = first.wpPostId;
  ctx.republishSecondPostId = second.wpPostId;
  ctx.republishUpdatedTitle = title2;
  ctx.republishUpdatedMarker = marker2;
});

Then('WordPress側の記事は投稿IDとスラッグを変えずに本文だけが更新されている', async ({ ctx }) => {
  const slug = ctx.publishSiteSlug as string;
  const firstPostId = ctx.republishFirstPostId as string;
  const secondPostId = ctx.republishSecondPostId as string;

  expect(secondPostId, '再公開のレスポンスの投稿IDが初回公開と一致しません(新規作成された疑いがあります)').toBe(
    firstPostId
  );

  const actualSlug = postField(slug, firstPostId, 'post_name');
  expect(actualSlug, `投稿(id=${firstPostId})のスラッグが取得できません`).toBe(ctx.republishSlug as string);

  const actualTitle = postField(slug, firstPostId, 'post_title');
  expect(actualTitle, '再公開後のタイトルが更新後の内容と一致しません').toBe(ctx.republishUpdatedTitle as string);

  const actualContent = postField(slug, firstPostId, 'post_content');
  expect(
    actualContent?.includes(ctx.republishUpdatedMarker as string),
    `投稿(id=${firstPostId})の本文が更新後のマーカー文字列を含みません: ${actualContent}`
  ).toBe(true);
});

Then('そのスラッグの記事はWordPress側に1件だけ存在する', async ({ ctx }) => {
  const slug = ctx.publishSiteSlug as string;
  const postSlug = ctx.republishSlug as string;

  const output = wpCli(slug, `post list --post_type=post --name=${postSlug} --format=json`);
  const posts = JSON.parse(output || '[]') as { ID: number | string }[];
  expect(
    posts,
    `スラッグ '${postSlug}' の投稿がWordPress側に1件だけ存在しません(重複投稿の疑いがあります): ${output}`
  ).toHaveLength(1);
  expect(String(posts[0].ID)).toBe(ctx.republishFirstPostId as string);
});

// ------------------------------------------------------- 後片付け

After({ tags: '@publishing' }, async ({ ctx, request }) => {
  const projectId = ctx.publishProjectId as number | undefined;
  if (projectId !== undefined) {
    const token = await adminToken(request);
    await deleteFixtureProject(request, token, projectId);
  }

  const slug = ctx.publishSiteSlug as string | undefined;
  const postIds = (ctx.publishCleanupPostIds as string[] | undefined) ?? [];
  if (slug) {
    for (const postId of postIds) {
      try {
        wpCli(slug, `post delete ${postId} --force`);
      } catch {
        // 既に削除済み等は後片付けの失敗としては扱わない。
      }
    }
  }
});
