import type * as vscode from 'vscode';
import {
  getServerUrl,
  getApiKey,
  setApiKey,
  requireApiKey,
  getActor,
  setActor,
  clearActor,
  requireActor,
  getProjectId,
  setProjectId,
  requireProjectId,
} from '../config';
import { resetMocks, setConfiguration, shownWarnings } from '../__mocks__/vscode';

/** SecretStorage と workspaceState を持つ最小のExtensionContextスタブ。 */
function createContext(): vscode.ExtensionContext & { secretValues: Map<string, string> } {
  const secretValues = new Map<string, string>();
  const workspaceValues = new Map<string, unknown>();
  return {
    secretValues,
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
      get: (key: string) => workspaceValues.get(key),
      update: (key: string, value: unknown) => {
        workspaceValues.set(key, value);
        return Promise.resolve();
      },
    },
  } as unknown as vscode.ExtensionContext & { secretValues: Map<string, string> };
}

const ACTOR = { id: 7, email: 'writer@example.com', role: 'EDITOR' };

beforeEach(() => {
  resetMocks();
});

describe('getServerUrl', () => {
  it('設定値を返す', () => {
    setConfiguration('letsBlog.serverUrl', 'https://api.example.test');
    expect(getServerUrl()).toBe('https://api.example.test');
  });

  it('未設定ならローカル既定値を返す', () => {
    expect(getServerUrl()).toBe('https://localhost');
  });

  it('末尾のスラッシュを除去する(パス連結時に//にならないようにする)', () => {
    setConfiguration('letsBlog.serverUrl', 'https://api.example.test///');
    expect(getServerUrl()).toBe('https://api.example.test');
  });

  it('設定値がundefinedでも既定値へフォールバックする', () => {
    setConfiguration('letsBlog.serverUrl', undefined);
    expect(getServerUrl()).toBe('https://localhost');
  });
});

describe('APIキー', () => {
  it('保存した値を読み出せる', async () => {
    const context = createContext();
    await setApiKey(context, 'key-123');
    await expect(getApiKey(context)).resolves.toBe('key-123');
  });

  it('未設定ならundefinedを返す', async () => {
    await expect(getApiKey(createContext())).resolves.toBeUndefined();
  });

  it('requireApiKeyは設定済みなら値を返す', async () => {
    const context = createContext();
    await setApiKey(context, 'key-123');
    await expect(requireApiKey(context)).resolves.toBe('key-123');
  });

  it('requireApiKeyは未設定なら対応方法を含む例外を投げる', async () => {
    await expect(requireApiKey(createContext())).rejects.toThrow('Set API Key');
  });
});

describe('Actor', () => {
  it('保存した値を読み出せる', async () => {
    const context = createContext();
    await setActor(context, ACTOR);
    await expect(getActor(context)).resolves.toEqual(ACTOR);
  });

  it('未設定ならundefinedを返す', async () => {
    await expect(getActor(createContext())).resolves.toBeUndefined();
  });

  it('JSONとして壊れている場合は破棄して警告する', async () => {
    const context = createContext();
    context.secretValues.set('letsBlog.actor', '{壊れたJSON');

    await expect(getActor(context)).resolves.toBeUndefined();
    expect(context.secretValues.has('letsBlog.actor')).toBe(false);
    expect(shownWarnings.join()).toContain('再ログイン');
  });

  it('必須項目が欠けている場合も破棄して警告する', async () => {
    const context = createContext();
    context.secretValues.set('letsBlog.actor', JSON.stringify({ id: 1, email: 'a@example.com' }));

    await expect(getActor(context)).resolves.toBeUndefined();
    expect(context.secretValues.has('letsBlog.actor')).toBe(false);
    expect(shownWarnings).toHaveLength(1);
  });

  it('clearActorで削除できる', async () => {
    const context = createContext();
    await setActor(context, ACTOR);
    await clearActor(context);
    await expect(getActor(context)).resolves.toBeUndefined();
  });

  it('requireActorはログイン済みなら値を返す', async () => {
    const context = createContext();
    await setActor(context, ACTOR);
    await expect(requireActor(context)).resolves.toEqual(ACTOR);
  });

  it('requireActorは未ログインなら対応方法を含む例外を投げる', async () => {
    await expect(requireActor(createContext())).rejects.toThrow('Login');
  });
});

describe('プロジェクトID', () => {
  it('保存した値を読み出せる', async () => {
    const context = createContext();
    await setProjectId(context, 42);
    expect(getProjectId(context)).toBe(42);
  });

  it('未選択ならundefinedを返す', () => {
    expect(getProjectId(createContext())).toBeUndefined();
  });

  it('requireProjectIdは選択済みなら値を返す', async () => {
    const context = createContext();
    await setProjectId(context, 42);
    expect(requireProjectId(context)).toBe(42);
  });

  it('requireProjectIdは未選択なら対応方法を含む例外を投げる', () => {
    expect(() => requireProjectId(createContext())).toThrow('Select Project');
  });
});
