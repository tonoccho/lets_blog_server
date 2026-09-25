/**
 * ドメイン横断の共通ステップ(issue #942 / AT-16)。
 * 検証対象の呼び出しは拡張自身の apiClient / config / deviceAuth を通す。
 */

import { Given, Then, World } from '../support/gherkin';
import {
  ADMIN_EMAIL,
  asExtensionContext,
  configureExtension,
  createContext,
  FakeExtensionContext,
  loggedInAdminContext,
  adminAccessToken,
} from '../support/env';
import {
  ensureManagedSite,
  ensureProductionManagedSite,
  ensureProject,
  bindEnvironment,
  ProjectFixture,
  SiteFixture,
} from '../support/api';
import * as apiClient from '../../src/apiClient';
import type { Actor } from '../../src/schemas';

/** シナリオが共有する値。ステップ間の受け渡しはすべてここを経由する。 */
export interface ExtensionWorld extends World {
  context: FakeExtensionContext;
  token: string;
  actor: Actor;
  project: ProjectFixture;
  site: SiteFixture;
  error: unknown;
}

export function w(world: World): ExtensionWorld {
  return world as ExtensionWorld;
}

/** 例外を投げうる操作を実行し、結果か例外を world へ残す。 */
export async function attempt(world: World, action: () => Promise<unknown>): Promise<void> {
  const scope = w(world);
  scope.error = undefined;
  try {
    (scope as World).result = await action();
  } catch (error) {
    scope.error = error;
  }
}

/** world に残った例外を返す。例外が起きていなければ失敗させる。 */
export function capturedError(world: World): Error {
  const error = w(world).error;
  if (!(error instanceof Error)) {
    throw new Error(`エラーが発生しませんでした(結果: ${JSON.stringify((world as World).result)})`);
  }
  return error;
}

Given('拡張の設定が既定値である', (world) => {
  configureExtension();
  w(world).context = createContext();
});

/**
 * 実行ごとに1回だけ解決すればよい前提(ログインユーザー・プロジェクト・サイト)。
 *
 * gateway のレート制限はクライアントIPあたり 100req/分
 * (services/gateway/src/main/resources/application.yml の api-global)。背景ステップが
 * シナリオごとに一覧APIを叩くと、それだけで上限へ届いて 429 になる。
 */
let cachedActor: Actor | undefined;
let cachedProject: ProjectFixture | undefined;
let cachedSite: SiteFixture | undefined;
let cachedProdSite: SiteFixture | undefined;

Given('管理者としてログイン済みである', async (world) => {
  const scope = w(world);
  scope.context = await loggedInAdminContext();
  scope.token = await adminAccessToken(scope.context);
  if (!cachedActor) {
    const users = await apiClient.listUsers(scope.token);
    cachedActor = users.find((u) => u.email === ADMIN_EMAIL);
    if (!cachedActor) throw new Error(`${ADMIN_EMAIL} が見つかりません。シードを実行してください。`);
  }
  scope.actor = cachedActor;
});

Given('受け入れテスト用のプロジェクトが存在する', async (world) => {
  const scope = w(world);
  cachedProject = cachedProject ?? (await ensureProject(scope.token));
  scope.project = cachedProject;
});

Given('公開先のマネージドWordPressサイトが用意されている', async (world) => {
  const scope = w(world);
  if (!cachedSite) {
    cachedSite = await ensureManagedSite(scope.token);
    await bindEnvironment(scope.token, cachedProject!.id, 'test', cachedSite.id);
  }
  scope.site = cachedSite;
});

/** issue #1003: 予約投稿(publishScheduledAt)は本番(production)環境サイトでのみ有効になる。 */
Given('予約投稿の検証に使う本番マネージドWordPressサイトが用意されている', async (world) => {
  const scope = w(world);
  cachedProdSite = cachedProdSite ?? (await ensureProductionManagedSite(scope.token, cachedProject!.id));
  scope.site = cachedProdSite;
});

Then('エラーメッセージに接続先のURLが含まれる', (world) => {
  const error = capturedError(world);
  const url = (error as { url?: string }).url ?? '';
  if (!url && !/https?:\/\//.test(error.message)) {
    throw new Error(`接続先URLがエラーに含まれていません: ${error.message}`);
  }
});

Then('AIの生成結果が返る', (world) => {
  const result = (world as { result?: { result?: string } }).result;
  if (!result?.result || result.result.trim() === '') {
    throw new Error(`生成結果が空です: ${JSON.stringify(result)}`);
  }
});

/** ExtensionContext を拡張の関数へ渡すための短縮。 */
export function ctx(world: World) {
  return asExtensionContext(w(world).context);
}
