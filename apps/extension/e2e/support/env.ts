/**
 * Layer 1(APIレベル受け入れテスト)の実行環境(issue #942 / AT-16)。
 *
 * - 拡張の設定(letsBlog.*)は 'vscode' スタブ(src/__mocks__/vscode.ts)へ差し込む。
 * - ExtensionContext は SecretStorage / workspaceState をメモリ上に再現したものを使う。
 *   保存・復元・期限切れ判定は拡張自身の config.ts を通す。
 * - ログインは拡張自身の deviceAuth.ts(デバイス認可)で行い、ブラウザでの承認だけを
 *   support/browser.ts が肩代わりする。
 */

import type * as vscode from 'vscode';
import { setConfiguration, resetMocks } from '../../src/__mocks__/vscode';
import { requestDeviceAuthorization, pollForToken } from '../../src/deviceAuth';
import { storeTokens, requireAccessToken } from '../../src/config';
import { clearResponseCache } from '../../src/apiClient';
import { approveDeviceAuthorization } from './browser';

/** 受け入れテストが接続するスタックの公開URL(リバースプロキシ)。 */
export const SERVER_URL = process.env.AT_SERVER_URL ?? 'https://localhost';

export const ADMIN_EMAIL = 'e2e-admin@letsblog.local';
export const TEST_EMAIL = 'e2e-test@letsblog.local';

/**
 * 資格情報は ~/.config/lets-blog-e2e.env(リポジトリ外・モード600)から export しておく
 * (docs/ACCEPTANCE_TESTING.md §10)。未設定はスキップ理由ではなく環境の不備なので、
 * 明示的な失敗にする(#843 の再発防止と同じ方針)。
 */
export function requireCredentials(): { adminPassword: string; testPassword: string } {
  const adminPassword = process.env.E2E_ADMIN_PASSWORD ?? '';
  const testPassword = process.env.E2E_TEST_PASSWORD ?? '';
  if (!adminPassword || !testPassword) {
    throw new Error(
      'E2E_ADMIN_PASSWORD / E2E_TEST_PASSWORD が未設定です。' +
        'source ~/.config/lets-blog-e2e.env を実行してから起動してください。'
    );
  }
  return { adminPassword, testPassword };
}

/** メモリ上の SecretStorage / workspaceState を持つ ExtensionContext 相当。 */
export interface FakeExtensionContext {
  secrets: {
    get(key: string): Promise<string | undefined>;
    store(key: string, value: string): Promise<void>;
    delete(key: string): Promise<void>;
    readonly store_: Map<string, string>;
  };
  workspaceState: {
    get<T>(key: string): T | undefined;
    update(key: string, value: unknown): Promise<void>;
  };
}

export function createContext(): FakeExtensionContext {
  const secrets = new Map<string, string>();
  const state = new Map<string, unknown>();
  return {
    secrets: {
      get: async (key) => secrets.get(key),
      store: async (key, value) => void secrets.set(key, value),
      delete: async (key) => void secrets.delete(key),
      store_: secrets,
    },
    workspaceState: {
      get: <T>(key: string) => state.get(key) as T | undefined,
      update: async (key, value) => void state.set(key, value),
    },
  };
}

/** 拡張の型(vscode.ExtensionContext)として渡すためのブリッジ。実際に使うのは上の2つだけ。 */
export function asExtensionContext(context: FakeExtensionContext): vscode.ExtensionContext {
  return context as unknown as vscode.ExtensionContext;
}

/**
 * 拡張の設定を初期化する。既定は「ローカルスタック + 自己署名証明書を許容」。
 * 証明書検証そのものを検証するシナリオだけが allowInsecureTls を false へ戻す。
 */
export function configureExtension(overrides: Record<string, unknown> = {}): void {
  resetMocks();
  clearResponseCache();
  setConfiguration('letsBlog.serverUrl', SERVER_URL);
  setConfiguration('letsBlog.allowInsecureTls', true);
  setConfiguration('letsBlog.requestTimeoutMs', 120_000);
  setConfiguration('letsBlog.aiProvider', '');
  for (const [key, value] of Object.entries(overrides)) {
    setConfiguration(key, value);
  }
}

/**
 * デバイス認可でログインし、トークンを SecretStorage へ保存する。
 * 拡張の login コマンドが行う処理そのもの(extension.ts commandLogin のUI以外)。
 */
export async function loginWithDeviceCode(
  context: FakeExtensionContext,
  email: string,
  password: string
): Promise<void> {
  const controller = new AbortController();
  const authorization = await requestDeviceAuthorization(SERVER_URL, true, controller.signal);
  await approveDeviceAuthorization(
    authorization.verificationUriComplete ??
      `${authorization.verificationUri}?user_code=${authorization.userCode}`,
    email,
    password
  );
  const outcome = await pollForToken(SERVER_URL, authorization.deviceCode, true, controller.signal);
  if (outcome.kind !== 'success') {
    throw new Error(`デバイス認可が完了しませんでした: ${outcome.kind}`);
  }
  await storeTokens(asExtensionContext(context), outcome.tokens);
}

/** 管理者としてログイン済みのコンテキストを作る。ログインは1回だけ行い、トークンを使い回す。 */
let cachedAdminTokens: { accessToken: string; refreshToken: string; expiresAt: number } | undefined;

export async function loggedInAdminContext(): Promise<FakeExtensionContext> {
  const { adminPassword } = requireCredentials();
  const context = createContext();
  if (cachedAdminTokens) {
    await context.secrets.store('letsBlog.tokens', JSON.stringify(cachedAdminTokens));
    // 期限切れならリフレッシュも拡張側の実装で行われる。
    await requireAccessToken(asExtensionContext(context));
    return context;
  }
  await loginWithDeviceCode(context, ADMIN_EMAIL, adminPassword);
  const stored = await context.secrets.get('letsBlog.tokens');
  cachedAdminTokens = stored ? JSON.parse(stored) : undefined;
  return context;
}

/** 管理者のアクセストークン(拡張の config.requireAccessToken 経由)。 */
export async function adminAccessToken(context: FakeExtensionContext): Promise<string> {
  return requireAccessToken(asExtensionContext(context));
}
