/** ログイン・接続設定のステップ(issue #942 / AT-16)。 */

import { Given, Then, When } from '../support/gherkin';
import { attempt, capturedError, ctx, w } from './common.steps';
import {
  ADMIN_EMAIL,
  SERVER_URL,
  asExtensionContext,
  configureExtension,
  createContext,
  loginWithDeviceCode,
  requireCredentials,
} from '../support/env';
import { setConfiguration } from '../../src/__mocks__/vscode';
import { requireAccessToken, logout } from '../../src/config';
import { refreshAccessToken, isRefreshTokenRevoked } from '../../src/deviceAuth';
import * as apiClient from '../../src/apiClient';

const TOKENS_SECRET = 'letsBlog.tokens';
const ACTOR_SECRET = 'letsBlog.actor';

When('デバイスコードフローで管理者としてログインする', async (world) => {
  const { adminPassword } = requireCredentials();
  await loginWithDeviceCode(w(world).context, ADMIN_EMAIL, adminPassword);
});

Then('アクセストークンが安全な保管領域へ保存される', async (world) => {
  const stored = await w(world).context.secrets.get(TOKENS_SECRET);
  if (!stored) throw new Error('SecretStorageにトークンが保存されていません');
  const tokens = JSON.parse(stored) as { accessToken: string; refreshToken: string; expiresAt: number };
  if (!tokens.accessToken || !tokens.refreshToken) {
    throw new Error(`保存されたトークンが不完全です: ${stored}`);
  }
  if (!(tokens.expiresAt > Date.now())) {
    throw new Error('保存されたアクセストークンの有効期限が過去です');
  }
});

Then('保存されたアクセストークンでプロジェクト一覧を取得できる', async (world) => {
  const token = await requireAccessToken(ctx(world));
  const projects = await apiClient.listProjects(token);
  if (!Array.isArray(projects)) throw new Error('プロジェクト一覧が配列ではありません');
});

When('保存されたアクセストークンの有効期限を過去にする', async (world) => {
  const scope = w(world);
  const stored = await scope.context.secrets.get(TOKENS_SECRET);
  if (!stored) throw new Error('ログイン済みではありません');
  const tokens = JSON.parse(stored) as { accessToken: string; refreshToken: string; expiresAt: number };
  (scope as unknown as { previousAccessToken: string }).previousAccessToken = tokens.accessToken;
  await scope.context.secrets.store(
    TOKENS_SECRET,
    JSON.stringify({ ...tokens, expiresAt: Date.now() - 1000 })
  );
});

When('アクセストークンを要求する', async (world) => {
  await attempt(world, () => requireAccessToken(ctx(world)));
});

Then('新しいアクセストークンへ更新されている', async (world) => {
  const scope = w(world);
  if (scope.error) throw scope.error;
  const refreshed = (scope as unknown as { result: string }).result;
  const previous = (scope as unknown as { previousAccessToken: string }).previousAccessToken;
  if (!refreshed) throw new Error('アクセストークンが返りませんでした');
  if (refreshed === previous) throw new Error('アクセストークンが更新されていません');
  const stored = await scope.context.secrets.get(TOKENS_SECRET);
  const tokens = JSON.parse(stored ?? '{}') as { expiresAt: number };
  if (!(tokens.expiresAt > Date.now())) throw new Error('更新後も有効期限が過去のままです');
});

Then('更新後のアクセストークンでプロジェクト一覧を取得できる', async (world) => {
  const token = (w(world) as unknown as { result: string }).result;
  const projects = await apiClient.listProjects(token);
  if (!Array.isArray(projects)) throw new Error('プロジェクト一覧が配列ではありません');
});

When('letsBlog.allowInsecureTls を false にしてプロジェクト一覧を取得する', async (world) => {
  setConfiguration('letsBlog.allowInsecureTls', false);
  const token = await requireAccessToken(asExtensionContext(w(world).context));
  await attempt(world, () => apiClient.listProjects(token));
});

Then('証明書の検証エラーとして失敗する', (world) => {
  const error = capturedError(world);
  // ネイティブfetchの失敗は TypeError('fetch failed') で包まれ、証明書の理由はその cause 側にある。
  const detail = describeCauseChain(error);
  if (!/certificate|CERT|self[- ]signed|SELF_SIGNED/i.test(detail)) {
    throw new Error(`証明書検証エラーではありませんでした: ${detail}`);
  }
});

/** cause を辿ってメッセージとコードを連結する。 */
function describeCauseChain(error: unknown): string {
  const parts: string[] = [];
  let current: unknown = error;
  for (let depth = 0; depth < 5 && current; depth += 1) {
    const node = current as { message?: string; code?: string; cause?: unknown };
    parts.push(`${node.message ?? ''} ${node.code ?? ''}`);
    current = node.cause;
  }
  return parts.join(' | ');
}

When('letsBlog.serverUrl を {string} にしてプロジェクト一覧を取得する', async (world, url) => {
  const token = await requireAccessToken(asExtensionContext(w(world).context));
  setConfiguration('letsBlog.serverUrl', url);
  await attempt(world, () => apiClient.listProjects(token));
});

Then('接続できなかったことが分かるエラーになる', (world) => {
  const error = capturedError(world);
  if (!/サーバー|接続|failed to reach/i.test(error.message)) {
    throw new Error(`接続失敗と分かるメッセージではありません: ${error.message}`);
  }
});

When('ログインしていない状態でアクセストークンを要求する', async (world) => {
  // ログイン済みのコンテキストと混ざらないよう、未ログインのコンテキストを新しく作る。
  w(world).context = createContext();
  await attempt(world, () => requireAccessToken(ctx(world)));
});

Then('ログインを促すエラーになる', (world) => {
  const error = capturedError(world);
  if (!error.message.includes('Login')) {
    throw new Error(`ログインを促すメッセージではありません: ${error.message}`);
  }
});

/**
 * issue #1099: ログアウト検証。「管理者としてログイン済みである」は複数シナリオで
 * リフレッシュトークンを使い回すため、それを失効させるこの検証専用に新しくログインする。
 */
Given('ログアウト確認用に新しくログインする', async (world) => {
  const { adminPassword } = requireCredentials();
  configureExtension();
  const scope = w(world);
  scope.context = createContext();
  await loginWithDeviceCode(scope.context, ADMIN_EMAIL, adminPassword);
});

Given('未ログイン状態である', (world) => {
  configureExtension();
  w(world).context = createContext();
});

When('ログアウトする', async (world) => {
  const scope = w(world);
  const stored = await scope.context.secrets.get(TOKENS_SECRET);
  if (stored) {
    const tokens = JSON.parse(stored) as { refreshToken: string };
    (scope as unknown as { previousRefreshToken?: string }).previousRefreshToken = tokens.refreshToken;
  }
  await attempt(world, () => logout(ctx(world)));
});

Then('SecretStorageからトークンとActorが削除されている', async (world) => {
  const scope = w(world);
  const tokens = await scope.context.secrets.get(TOKENS_SECRET);
  const actor = await scope.context.secrets.get(ACTOR_SECRET);
  if (tokens !== undefined) throw new Error('letsBlog.tokensが削除されていません');
  if (actor !== undefined) throw new Error('letsBlog.actorが削除されていません');
});

Then('ログアウト前のリフレッシュトークンでのトークン更新はKeycloakに拒否される', async (world) => {
  const previous = (w(world) as unknown as { previousRefreshToken?: string }).previousRefreshToken;
  if (!previous) throw new Error('ログアウト前のリフレッシュトークンが記録されていません');

  const controller = new AbortController();
  const error = await refreshAccessToken(SERVER_URL, previous, true, controller.signal).then(
    () => undefined,
    (e: unknown) => e
  );
  if (!error) throw new Error('ログアウト後も旧リフレッシュトークンでの更新が成功してしまいました');
  if (!isRefreshTokenRevoked(error)) {
    throw new Error(`失効として判定されないエラーでした: ${String(error)}`);
  }
});

Then('例外にならない', (world) => {
  const error = w(world).error;
  if (error) throw error instanceof Error ? error : new Error(String(error));
});
