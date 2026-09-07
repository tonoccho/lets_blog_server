import { execFileSync } from 'node:child_process';
import path from 'node:path';
import type { APIRequestContext } from '@playwright/test';
import { After, Given, Then, When } from './fixtures';
import { E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD, expect, fetchAccessToken } from '../support';

/**
 * メディアのガベージコレクション(issue #936 / AT-10、AC-IMG-013)のステップ定義。
 *
 * ## media.steps.ts と分けている理由
 *
 * ここだけ前提と後片付けの形が大きく違う。**実在の WordPress サイトを構築し**、
 * そこへメディアと投稿を作り、実際に削除する。media.steps.ts の他のステップは
 * WordPress をまったく必要としないので、混ぜると「画像設定を1つ見るだけ」の
 * シナリオまで WordPress の構築を待つ構造になりかねない。
 *
 * ## フィクスチャを wp-cli で作る理由
 *
 * メディアの用意に `POST /api/media/upload` を使わない。このエンドポイントは gateway の
 * upload-endpoint バケット(**プロセス全体で1時間に10回**)に属しており、3シナリオで
 * 6回消費すると、同じ1時間に走る画像生成のシナリオを巻き添えで429にする
 * (docs/ACCEPTANCE_TESTING.md §11)。ここで確かめたいのはメディアの**作り方**ではなく
 * **GC がどれを未参照と判定するか**なので、用意の経路は問わない。
 *
 * ## 検証しているのは「消せること」より「消さないこと」
 *
 * 誤削除は取り消せない。3つ目のシナリオが、参照中のメディアがスキャンにも上がらず
 * GC 実行後も残っていることを独立に確かめる。
 */

/** リポジトリルート(apps/web/e2e/steps から4階層上)。 */
const REPO_ROOT = path.resolve(__dirname, '..', '..', '..', '..');

/** GC 検証用のプロジェクトとサイト。冪等に用意し、実行をまたいで再利用する。 */
const GC_PROJECT_SLUG = 'e2e-at10-gc';
const GC_SITE_KEY = 'at10gcprobe';

/** `WordPressSiteProvisioningService#normalizeSlug` と同じ正規化。 */
function wpSlug(siteKey: string): string {
  return siteKey.toLowerCase().replace(/[^a-z0-9-]/g, '-');
}

/** 1x1の透明PNG。メディアの中身は判定に関係しないので固定バイト列で足りる。 */
const PNG_BASE64 =
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==';

/** サイト構築とGCジョブの待ち上限。WordPressの新規構築は分単位でかかりうる。 */
const PROVISION_TIMEOUT_MS = 600_000;
const JOB_TIMEOUT_MS = 300_000;

interface SiteFixture {
  id: number;
  siteKey: string;
}

interface UnreferencedMediaItem {
  mediaId: string;
  guid: string;
  title: string;
}

interface ScanResult {
  items: UnreferencedMediaItem[];
  totalMediaCount: number;
  referencedMediaCount: number;
  unreferencedMediaCount: number;
}

async function adminToken(request: APIRequestContext): Promise<string> {
  return fetchAccessToken(request, E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD);
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

/** サイトへ添付ファイルを1件作り、その添付IDを返す。 */
function importMedia(slug: string, title: string): string {
  const file = `/tmp/${title}.png`;
  execFileSync(
    'docker',
    [
      'compose',
      'exec',
      '-T',
      'wordpress',
      'sh',
      '-c',
      `printf '%s' '${PNG_BASE64}' | base64 -d > ${file}`,
    ],
    { cwd: REPO_ROOT, encoding: 'utf8', timeout: 60_000 }
  );
  return wpCli(slug, `media import ${file} --title='${title}' --porcelain`);
}

/** 与えた添付IDを本文から参照する投稿を作り、その投稿IDを返す。 */
function createReferencingPost(slug: string, mediaId: string, title: string): string {
  // wp-image-<id> は WordPress のブロックエディタが実際に付けるクラスで、
  // MediaGarbageCollectionService#extractContentReferences が一次判定に使う形そのもの。
  const content = `<img class="wp-image-${mediaId}" alt="${title}" />`;
  return wpCli(
    slug,
    `post create --post_type=post --post_status=publish --post_title='${title}' --post_content='${content}' --porcelain`
  );
}

/** 添付が存在するか(GC 実行後に「消えた/残った」を確かめる)。 */
function mediaExists(slug: string, mediaId: string): boolean {
  const ids = wpCli(slug, 'post list --post_type=attachment --format=ids');
  return ids.split(/\s+/).filter(Boolean).includes(mediaId);
}

async function ensureProject(request: APIRequestContext): Promise<number> {
  const token = await adminToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const list = await request.get('/api/projects', { headers });
  expect(
    list.ok(),
    `プロジェクト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`
  ).toBe(true);
  const existing = ((await list.json()) as { id: number; slug: string }[]).find(
    (project) => project.slug === GC_PROJECT_SLUG
  );
  if (existing) {
    return existing.id;
  }
  const created = await request.post('/api/projects', {
    headers,
    data: { name: 'E2E AT10 GC', slug: GC_PROJECT_SLUG },
  });
  expect(
    created.ok(),
    `プロジェクトの作成に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  return ((await created.json()) as { id: number }).id;
}

async function ensureManagedSite(request: APIRequestContext): Promise<SiteFixture> {
  const token = await adminToken(request);
  const headers = { Authorization: `Bearer ${token}` };
  const list = await request.get('/api/sites', { headers });
  expect(
    list.ok(),
    `サイト一覧の取得に失敗しました (status=${list.status()}): ${await list.text()}`
  ).toBe(true);
  const existing = ((await list.json()) as SiteFixture[]).find((site) => site.siteKey === GC_SITE_KEY);
  if (existing) {
    return existing;
  }
  const created = await request.post('/api/sites/managed-wordpress', {
    headers,
    data: {
      name: 'AT10 GC probe site',
      siteKey: GC_SITE_KEY,
      title: 'AT10 GC Probe',
      adminUser: 'at10gcadmin',
      adminEmail: 'at10-gc-probe@letsblog.local',
      adminPassword: 'At10GcProbe#Passw0rd1',
    },
    timeout: PROVISION_TIMEOUT_MS,
  });
  expect(
    created.ok(),
    `マネージドWordPressの構築に失敗しました (status=${created.status()}): ${await created.text()}`
  ).toBe(true);
  return (await created.json()) as SiteFixture;
}

async function bindLocalEnvironment(
  request: APIRequestContext,
  projectId: number,
  siteId: number
): Promise<void> {
  const token = await adminToken(request);
  const response = await request.post(`/api/projects/${projectId}/environments`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { environment: 'local', siteId },
  });
  expect(
    response.ok(),
    `local環境へのサイト紐付けに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
}

async function scan(request: APIRequestContext, projectId: number): Promise<ScanResult> {
  const token = await adminToken(request);
  const response = await request.get(
    `/api/projects/${projectId}/media-garbage-collection/scan?environment=local`,
    { headers: { Authorization: `Bearer ${token}` }, timeout: 120_000 }
  );
  expect(
    response.ok(),
    `メディアのスキャンに失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  return (await response.json()) as ScanResult;
}

async function waitForJob(
  request: APIRequestContext,
  jobId: number
): Promise<{ status: string; resultPayload: string | null }> {
  const token = await adminToken(request);
  const deadline = Date.now() + JOB_TIMEOUT_MS;
  let last: { status: string; resultPayload: string | null } = { status: 'unknown', resultPayload: null };
  while (Date.now() < deadline) {
    const response = await request.get(`/api/generation-jobs/${jobId}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(
      response.ok(),
      `ジョブの取得に失敗しました (status=${response.status()}): ${await response.text()}`
    ).toBe(true);
    last = (await response.json()) as { status: string; resultPayload: string | null };
    if (last.status !== 'running' && last.status !== 'pending') {
      return last;
    }
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  throw new Error(`GCの削除ジョブ ${jobId} が ${JOB_TIMEOUT_MS}ms 以内に終わりませんでした`);
}

Given('ガベージコレクションを確かめるためのWordPressサイトを持つプロジェクトがある', async ({ ctx, request }) => {
  const projectId = await ensureProject(request);
  const site = await ensureManagedSite(request);
  await bindLocalEnvironment(request, projectId, site.id);
  ctx.gcProjectId = projectId;
  ctx.gcSlug = wpSlug(site.siteKey);
});

Given('どの記事からも参照されていない画像がサイトにある', async ({ ctx }) => {
  const title = `at10-gc-unreferenced-${Date.now().toString(36)}`;
  ctx.gcUnreferencedMediaId = importMedia(ctx.gcSlug as string, title);
  ctx.gcUnreferencedTitle = title;
});

Given('記事から参照されている画像がサイトにある', async ({ ctx }) => {
  const slug = ctx.gcSlug as string;
  const title = `at10-gc-referenced-${Date.now().toString(36)}`;
  const mediaId = importMedia(slug, title);
  ctx.gcReferencedMediaId = mediaId;
  ctx.gcReferencedTitle = title;
  ctx.gcReferencingPostId = createReferencingPost(slug, mediaId, title);
});

When('そのプロジェクトのlocal環境のメディアをスキャンする', async ({ ctx, request }) => {
  ctx.gcScan = await scan(request, ctx.gcProjectId as number);
});

Then('スキャン結果に未参照の画像が現れる', async ({ ctx }) => {
  const result = ctx.gcScan as ScanResult;
  expect(
    result.items.map((item) => item.mediaId),
    '用意した未参照の画像がスキャン結果に現れません'
  ).toContain(ctx.gcUnreferencedMediaId);
});

Then('スキャン結果の全件数は参照ありと未参照の合計に一致する', async ({ ctx }) => {
  const result = ctx.gcScan as ScanResult;
  expect(result.referencedMediaCount + result.unreferencedMediaCount).toBe(result.totalMediaCount);
  expect(result.unreferencedMediaCount).toBe(result.items.length);
});

Then('スキャン結果に参照中の画像は現れない', async ({ ctx }) => {
  const result = ctx.gcScan as ScanResult;
  expect(
    result.items.map((item) => item.mediaId),
    '記事から参照されている画像が未参照として検出されています(削除されるおそれがあります)'
  ).not.toContain(ctx.gcReferencedMediaId);
});

When('未参照の画像を選んでガベージコレクションを実行する', async ({ ctx, request }) => {
  const token = await adminToken(request);
  const response = await request.post(
    `/api/projects/${ctx.gcProjectId}/media-garbage-collection/delete?environment=local`,
    {
      headers: { Authorization: `Bearer ${token}` },
      data: { mediaIds: [ctx.gcUnreferencedMediaId] },
    }
  );
  expect(
    response.ok(),
    `ガベージコレクションの開始に失敗しました (status=${response.status()}): ${await response.text()}`
  ).toBe(true);
  ctx.gcDeleteJob = await waitForJob(request, ((await response.json()) as { id: number }).id);
});

Then('ガベージコレクションのジョブは成功で終わる', async ({ ctx }) => {
  const job = ctx.gcDeleteJob as { status: string; resultPayload: string | null };
  expect(job.status, `GCの削除ジョブが失敗しました: ${job.resultPayload}`).toBe('done');
  const payload = JSON.parse(job.resultPayload ?? '{}') as { failedCount?: number };
  expect(payload.failedCount ?? 0, `削除に失敗したメディアがあります: ${job.resultPayload}`).toBe(0);
});

Then('未参照の画像はサイトから消えている', async ({ ctx }) => {
  expect(
    mediaExists(ctx.gcSlug as string, ctx.gcUnreferencedMediaId as string),
    '未参照の画像がサイトに残っています'
  ).toBe(false);
  ctx.gcUnreferencedMediaId = undefined;
});

Then('再スキャンの未参照一覧に、削除した画像はもう現れない', async ({ ctx, request }) => {
  const result = await scan(request, ctx.gcProjectId as number);
  expect(result.items.map((item) => item.mediaId)).not.toContain(ctx.gcUnreferencedMediaId);
});

Then('参照中の画像はサイトに残っている', async ({ ctx }) => {
  expect(
    mediaExists(ctx.gcSlug as string, ctx.gcReferencedMediaId as string),
    '記事から参照されている画像が削除されています'
  ).toBe(true);
});

/**
 * シナリオが作ったメディアと投稿を消す。サイトそのものは残す —
 * 構築に分単位かかり、次の実行が同じサイトを冪等に再利用するため
 * (`apps/extension/e2e/support/api.ts` の `ensureManagedSite` と同じ方針)。
 * まっさらな状態が要るときは `npm run test:at:clean` がボリュームごと破棄する。
 */
After({ tags: '@destructive' }, async ({ ctx }) => {
  const slug = ctx.gcSlug as string | undefined;
  if (slug === undefined) {
    return;
  }
  for (const id of [ctx.gcReferencingPostId, ctx.gcReferencedMediaId, ctx.gcUnreferencedMediaId]) {
    if (id === undefined) {
      continue;
    }
    try {
      wpCli(slug, `post delete ${id as string} --force`);
    } catch {
      // 既に消えているものを消そうとした場合(GC がそのメディアを削除したシナリオ)は
      // wp-cli が失敗する。後片付けの目的は「残さないこと」なので、これは正常な結末である。
    }
  }
});
