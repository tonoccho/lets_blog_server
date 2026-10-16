/**
 * 拡張ホストが渡す ExtensionContext の代替と、ログイン・プロジェクト選択済みの状態を作る補助(issue #1840)。
 *
 * ExtensionContext の `secrets`(SecretStorage)と `workspaceState`(Memento)は VS Code 拡張ホストにしか
 * 実体が無い実行基盤なので、メモリ上の Map で代替する。それ以外(config.ts のトークン管理・apiClient.ts)は
 * 実物のまま使い、ログイン状態は config.ts の `storeTokens` / `setActor` / `setProjectId` で作る。
 */
import * as path from 'path';
import type * as vscode from 'vscode';
import { Actor, setActor, setProjectId, storeTokens } from '../../config';

export type TestContext = vscode.ExtensionContext & {
  secretValues: Map<string, string>;
  stateValues: Map<string, unknown>;
};

/** 拡張のルート(webviews/ の資材がある場所)。Webview パネルは実際の HTML 資材を読むので、実在のパスを指す。 */
const EXTENSION_ROOT = path.resolve(__dirname, '..', '..', '..');

export function createContext(extensionUri: unknown = `file://${EXTENSION_ROOT}`): TestContext {
  const secretValues = new Map<string, string>();
  const stateValues = new Map<string, unknown>();
  return {
    secretValues,
    stateValues,
    extensionUri,
    subscriptions: [],
    secrets: {
      get: (key: string) => Promise.resolve(secretValues.get(key)),
      store: (key: string, value: string) => {
        secretValues.set(key, value);
        return Promise.resolve();
      },
      delete: (key: string) => {
        secretValues.delete(key);
        return Promise.resolve();
      },
    },
    workspaceState: {
      get: (key: string, fallback?: unknown) => (stateValues.has(key) ? stateValues.get(key) : fallback),
      update: (key: string, value: unknown) => {
        stateValues.set(key, value);
        return Promise.resolve();
      },
    },
    globalState: {
      get: (key: string, fallback?: unknown) => (stateValues.has(`global:${key}`) ? stateValues.get(`global:${key}`) : fallback),
      update: (key: string, value: unknown) => {
        stateValues.set(`global:${key}`, value);
        return Promise.resolve();
      },
    },
  } as unknown as TestContext;
}

export const TEST_ACTOR: Actor = { id: 1, email: 'writer@example.com', role: 'EDITOR' };
export const TEST_TOKEN = 'access-token';

/** ログイン済み(有効なアクセストークンと Actor を保存済み)にする。actor に null を渡すと Actor は保存しない。 */
export async function loginAs(context: vscode.ExtensionContext, actor: Actor | null = TEST_ACTOR): Promise<void> {
  await storeTokens(context, { accessToken: TEST_TOKEN, refreshToken: 'refresh', expiresIn: 3600 });
  if (actor) await setActor(context, actor);
}

/** プロジェクト選択済みにする。 */
export async function selectProject(context: vscode.ExtensionContext, projectId: number): Promise<void> {
  await setProjectId(context, projectId);
}
