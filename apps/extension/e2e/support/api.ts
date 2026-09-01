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
