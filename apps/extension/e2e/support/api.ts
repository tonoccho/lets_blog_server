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

/**
 * 公開シナリオ用のマネージドWordPressサイトを用意する(冪等)。
 * 構築には数十秒〜数分かかるため、これを使うシナリオには `@slow` を付ける。
 */
export async function ensureManagedSite(token: string): Promise<SiteFixture> {
  const list = await call('GET', '/api/sites', token);
  const existing = (list.json as SiteFixture[] | undefined)?.find((s) => s.siteKey === FIXTURE_SITE_KEY);
  if (existing) return existing;

  const created = await call('POST', '/api/sites/managed-wordpress', token, {
    name: 'AT16 probe site',
    siteKey: FIXTURE_SITE_KEY,
    title: 'AT16 Probe',
    adminUser: 'at16probeadmin',
    adminEmail: 'at16-probe@letsblog.local',
    adminPassword: 'At16Probe#Passw0rd1',
  });
  if (created.status >= 300) {
    throw new Error(`マネージドWordPressを構築できませんでした (HTTP ${created.status}): ${created.text}`);
  }
  return created.json as SiteFixture;
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
