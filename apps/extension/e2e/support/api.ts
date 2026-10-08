/**
 * シナリオの前提条件を用意するための最小のHTTP呼び出し(issue #942 / AT-16)。
 *
 * 検証対象の呼び出しは必ず拡張自身の apiClient を通す(受け入れ基準)。ここにあるのは
 * 拡張が API を持たない「環境の作り込み」——プロジェクトの作成、マネージドWordPressの構築、
 * 環境の紐付け——だけで、scripts/seed-acceptance-env.sh に相当する。それでも
 * トランスポートは拡張自身の httpClient を使い、テスト専用のHTTP実装は増やさない。
 */

import { httpRequest } from '../../src/httpClient';
import { SERVER_URL } from './env';

async function call(
  method: string,
  path: string,
  token: string,
  body?: unknown,
  timeoutMs = 600_000
): Promise<{ status: number; json: unknown; text: string }> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await httpRequest(`${SERVER_URL}${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${token}`,
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: controller.signal,
      allowInsecureTls: true,
    });
    const text = await res.text();
    let json: unknown;
    try {
      json = JSON.parse(text);
    } catch {
      json = undefined;
    }
    return { status: res.status, json, text };
  } finally {
    clearTimeout(timer);
  }
}

export interface ProjectFixture {
  id: number;
  name: string;
  slug: string;
}

export const FIXTURE_PROJECT_SLUG = 'at16-extension';
export const FIXTURE_PROJECT_NAME = 'AT16 拡張受け入れテスト';
export const FIXTURE_SITE_KEY = 'at16probe';
export const FIXTURE_PROD_SITE_KEY = 'at16probeprod';

/** 受け入れテスト用プロジェクトを用意する(冪等)。 */
export async function ensureProject(token: string): Promise<ProjectFixture> {
  const list = await call('GET', '/api/projects', token);
  const existing = (list.json as ProjectFixture[] | undefined)?.find(
    (p) => p.slug === FIXTURE_PROJECT_SLUG
  );
  if (existing) return existing;

  const created = await call('POST', '/api/projects', token, {
    name: FIXTURE_PROJECT_NAME,
    slug: FIXTURE_PROJECT_SLUG,
  });
  if (created.status >= 300) {
    throw new Error(`プロジェクトを作成できませんでした (HTTP ${created.status}): ${created.text}`);
  }
  return created.json as ProjectFixture;
}

export interface SiteFixture {
  id: number;
  name: string;
  siteKey: string;
}

async function createManagedSite(
  token: string,
  siteKey: string,
  siteName: string,
  siteTitle: string,
  adminUser: string,
  adminEmail: string
): Promise<SiteFixture> {
  const created = await call('POST', '/api/sites/managed-wordpress', token, {
    name: siteName,
    siteKey,
    title: siteTitle,
    adminUser,
    adminEmail,
    adminPassword: 'At16Probe#Passw0rd1',
  });
  if (created.status >= 300) {
    throw new Error(`マネージドWordPressを構築できませんでした (HTTP ${created.status}): ${created.text}`);
  }
  return created.json as SiteFixture;
}

/**
 * 公開シナリオ用のマネージドWordPressサイトを用意する(冪等)。
 * 構築には数十秒〜数分かかるため、呼び出し元は acceptance.test.ts の beforeAll だけにする
 * (全シナリオの前に1回。シナリオやステップの中では呼ばない)。ステップは
 * `findManagedSite` で探して紐付けるだけにし、サイトの構築をシナリオに持ち込まない。
 */
export async function ensureManagedSite(token: string): Promise<SiteFixture> {
  const existing = await findManagedSite(token);
  if (existing) return existing;
  return createManagedSite(
    token, FIXTURE_SITE_KEY, 'AT16 probe site', 'AT16 Probe', 'at16probeadmin', 'at16-probe@letsblog.local'
  );
}

/** `ensureManagedSite` が用意したサイトを探す。構築はしない(無ければ undefined)。 */
export async function findManagedSite(token: string): Promise<SiteFixture | undefined> {
  const list = await call('GET', '/api/sites', token);
  return (list.json as SiteFixture[] | undefined)?.find((s) => s.siteKey === FIXTURE_SITE_KEY);
}

/**
 * 予約投稿(publishScheduledAt)はプロジェクトの本番(production)環境サイトでのみ有効になる
 * (issue #520)。`ensureManagedSite`が用意する`test`環境サイトとは別サイトを本番として
 * 用意し、既存シナリオの前提(そのサイトは本番ではない)を壊さない(issue #1003)。
 */
export async function ensureProductionManagedSite(token: string, projectId: number): Promise<SiteFixture> {
  const list = await call('GET', '/api/sites', token);
  let site = (list.json as SiteFixture[] | undefined)?.find((s) => s.siteKey === FIXTURE_PROD_SITE_KEY);
  if (!site) {
    site = await createManagedSite(
      token,
      FIXTURE_PROD_SITE_KEY,
      'AT16 probe site (production)',
      'AT16 Probe Prod',
      'at16probeprodadmin',
      'at16-probe-prod@letsblog.local'
    );
  }
  await bindEnvironment(token, projectId, 'production', site.id);
  return site;
}

export interface UnreferencedMedia {
  mediaId: string;
  guid: string;
  title: string;
  mimeType: string;
}

/**
 * どの投稿からも参照されていないメディアを列挙する(issue #500 のガベージコレクション)。
 *
 * 拡張にはメディアのガベージコレクションを呼ぶコマンドが無い(Web管理画面の機能)ため、
 * ここだけは拡張の apiClient を通せない。それでもトランスポートは拡張自身の httpClient に
 * 揃える(このファイルの方針)。issue #1001 の受け入れ基準「アイキャッチ付き記事の
 * メディア削除経路も同じエラーにならない」を確かめるために使う。
 */
export async function scanUnreferencedMedia(
  token: string,
  projectId: number,
  environment: string
): Promise<UnreferencedMedia[]> {
  const result = await call(
    'GET',
    `/api/projects/${projectId}/media-garbage-collection/scan?environment=${environment}`,
    token
  );
  if (result.status >= 300) {
    throw new Error(`未参照メディアの走査に失敗しました (HTTP ${result.status}): ${result.text}`);
  }
  return ((result.json as { items?: UnreferencedMedia[] } | undefined)?.items ?? []);
}

export interface MediaDeletionOutcome {
  status: string;
  deletedCount: number;
  failedCount: number;
  failures: Record<string, string>;
}

/**
 * 未参照メディアの削除を要求し、非同期ジョブの完了まで待つ。
 * 削除は generation_jobs のジョブとして走るため、完了は GET /api/generation-jobs/{id} で見る。
 */
export async function deleteUnreferencedMedia(
  token: string,
  projectId: number,
  environment: string,
  mediaIds: string[]
): Promise<MediaDeletionOutcome> {
  const started = await call(
    'POST',
    `/api/projects/${projectId}/media-garbage-collection/delete?environment=${environment}`,
    token,
    { mediaIds }
  );
  if (started.status >= 300) {
    throw new Error(`メディア削除の開始に失敗しました (HTTP ${started.status}): ${started.text}`);
  }
  const jobId = (started.json as { id?: number } | undefined)?.id;
  if (jobId === undefined) {
    throw new Error(`メディア削除ジョブのIDが返りませんでした: ${started.text}`);
  }

  const deadline = Date.now() + 120_000;
  for (;;) {
    const job = await call('GET', `/api/generation-jobs/${jobId}`, token);
    if (job.status >= 300) {
      throw new Error(`メディア削除ジョブの照会に失敗しました (HTTP ${job.status}): ${job.text}`);
    }
    const detail = job.json as { status?: string; resultPayload?: string | null } | undefined;
    const jobStatus = detail?.status ?? '';
    if (jobStatus === 'done' || jobStatus === 'failed') {
      const payload = JSON.parse(detail?.resultPayload || '{}') as {
        deletedCount?: number;
        failedCount?: number;
        failures?: Record<string, string>;
      };
      return {
        status: jobStatus,
        deletedCount: payload.deletedCount ?? 0,
        failedCount: payload.failedCount ?? 0,
        failures: payload.failures ?? {},
      };
    }
    if (Date.now() > deadline) {
      throw new Error(`メディア削除ジョブが終わりませんでした (jobId=${jobId}, status=${jobStatus})`);
    }
    await new Promise((resolve) => setTimeout(resolve, 1_000));
  }
}

/** プロジェクトの環境(local/test/production)へサイトを紐付ける(冪等)。 */
export async function bindEnvironment(
  token: string,
  projectId: number,
  environment: string,
  siteId: number
): Promise<void> {
  const result = await call('POST', `/api/projects/${projectId}/environments`, token, {
    environment,
    siteId,
  });
  if (result.status >= 300) {
    throw new Error(`環境の紐付けに失敗しました (HTTP ${result.status}): ${result.text}`);
  }
}

/** GitHub スタブが持つ固定リポジトリと、スタブが 200 を返すトークン(infra/e2e-stubs/github/server.js)。 */
const GITHUB_STUB_REPOSITORY = 'e2e-stub/acceptance';
const GITHUB_STUB_TOKEN = 'e2e-stub-token';
const GITHUB_STUB_URL = 'http://127.0.0.1:18086';

/**
 * GitHub 連携(リポジトリ+トークン)がスタブへ向いたプロジェクトを新しく作る(issue #1342)。
 * 提出 API はプロジェクトの GitHub 連携で PR を作るため、既定のフィクスチャプロジェクトは使えない。
 */
export async function createGithubLinkedProject(token: string): Promise<ProjectFixture> {
  const suffix = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
  const created = await call('POST', '/api/projects', token, { name: `AT1342 ${suffix}`, slug: `at1342-${suffix}` });
  if (created.status >= 300) {
    throw new Error(`プロジェクトを作成できませんでした (HTTP ${created.status}): ${created.text}`);
  }
  const project = created.json as ProjectFixture;
  const repo = await call('PUT', `/api/projects/${project.id}/github-repository`, token, {
    githubRepository: GITHUB_STUB_REPOSITORY,
  });
  if (repo.status >= 300) throw new Error(`GitHubリポジトリを紐付けられませんでした (HTTP ${repo.status}): ${repo.text}`);
  const key = await call('PUT', `/api/projects/${project.id}/api-keys/github-token`, token, {
    githubToken: GITHUB_STUB_TOKEN,
  });
  if (key.status >= 300) throw new Error(`GitHubトークンを設定できませんでした (HTTP ${key.status}): ${key.text}`);
  return project;
}

async function stubCall(method: string, pathname: string, body?: unknown): Promise<{ status: number; json: any }> {
  const res = await httpRequest(`${GITHUB_STUB_URL}${pathname}`, {
    method,
    headers: {
      Authorization: `Bearer ${GITHUB_STUB_TOKEN}`,
      ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(30_000),
    allowInsecureTls: true,
  });
  const text = await res.text();
  return { status: res.status, json: text ? JSON.parse(text) : null };
}

/**
 * 「push 済みのブランチ」を GitHub スタブへ用意する。スタブは git サーバではなく、ブランチを直接作る
 * API も無い。PR 作成はヘッド不在なら空のブランチを作り、マージは PR を閉じてブランチを残すので、
 * それで開いている PR の無いブランチを作る(apps/web の articleReviewSubmission.steps.ts と同じ手順)。
 */
export async function pushedBranchOnGithubStub(head: string): Promise<void> {
  const created = await stubCall('POST', `/repos/${GITHUB_STUB_REPOSITORY}/pulls`, {
    title: `E2Eスタブ: ${head}`,
    head,
    base: 'main',
    body: '提出の受け入れテスト用の過去のPR',
  });
  if (created.status !== 201) throw new Error(`スタブへのPR作成に失敗: ${JSON.stringify(created.json)}`);
  const merged = await stubCall('PUT', `/repos/${GITHUB_STUB_REPOSITORY}/pulls/${created.json.number}/merge`, {});
  if (merged.status !== 200) throw new Error(`スタブでのPRマージに失敗: ${JSON.stringify(merged.json)}`);
}

/** スタブ上で、head を持つ開いている PR の URL 一覧。 */
export async function openPullRequestUrlsFor(head: string): Promise<string[]> {
  const res = await stubCall('GET', `/repos/${GITHUB_STUB_REPOSITORY}/pulls?state=open`);
  return (res.json as any[]).filter((pr) => pr.head.ref === head).map((pr) => String(pr.html_url));
}
