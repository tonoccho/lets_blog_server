import type * as vscode from 'vscode';
import {
  getConfiguredAiProvider,
  setConfiguredAiProvider,
  getServerUrl,
  allowsInsecureTls,
  requireAccessToken,
  getAccessToken,
  storeTokens,
  getActor,
  setActor,
  clearActor,
  requireActor,
  getProjectId,
  setProjectId,
  requireProjectId,
} from '../config';
import * as deviceAuth from '../deviceAuth';
import { ApiError, NetworkError, TimeoutError } from '../errorHandler';
import {
  ConfigurationTarget,
  configurationUpdates,
  resetMocks,
  setConfiguration,
  shownWarnings,
} from '../__mocks__/vscode';

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

describe('allowsInsecureTls', () => {
  it('未設定なら既定のfalseを返す', () => {
    expect(allowsInsecureTls()).toBe(false);
  });

  it('letsBlog.allowInsecureTlsがtrueならtrueを返す', () => {
    setConfiguration('letsBlog.allowInsecureTls', true);
    expect(allowsInsecureTls()).toBe(true);
  });
});

describe('アクセストークン(issue #565: Device Authorization Grant)', () => {
  const FUTURE_TOKENS = { accessToken: 'access-1', refreshToken: 'refresh-1', expiresAt: Date.now() + 3_600_000 };
  const EXPIRED_TOKENS = { accessToken: 'access-old', refreshToken: 'refresh-old', expiresAt: Date.now() - 1_000 };
  const TOKEN_URL = 'https://localhost/auth/realms/letsblog/protocol/openid-connect/token';

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it('requireAccessTokenは未ログインなら対応方法を含む例外を投げる', async () => {
    await expect(requireAccessToken(createContext())).rejects.toThrow('Login');
  });

  it('requireAccessTokenは有効期限内ならリフレッシュせずアクセストークンを返す', async () => {
    const context = createContext();
    context.secretValues.set('letsBlog.tokens', JSON.stringify(FUTURE_TOKENS));
    const refreshSpy = jest.spyOn(deviceAuth, 'refreshAccessToken');

    await expect(requireAccessToken(context)).resolves.toBe(FUTURE_TOKENS.accessToken);
    expect(refreshSpy).not.toHaveBeenCalled();
  });

  it('requireAccessTokenは期限切れの場合リフレッシュして新しいトークンを保存する', async () => {
    const context = createContext();
    context.secretValues.set('letsBlog.tokens', JSON.stringify(EXPIRED_TOKENS));
    jest.spyOn(deviceAuth, 'refreshAccessToken').mockResolvedValue({
      accessToken: 'access-new',
      refreshToken: 'refresh-new',
      expiresIn: 3600,
    });

    await expect(requireAccessToken(context)).resolves.toBe('access-new');
    const stored = JSON.parse(context.secretValues.get('letsBlog.tokens') as string);
    expect(stored.accessToken).toBe('access-new');
    expect(stored.refreshToken).toBe('refresh-new');
  });

  // issue #1098: 破棄するのは「確定的な失効」に限る。Keycloakはリフレッシュトークンが
  // 失効・取り消しされた場合に 400 + {"error":"invalid_grant"} を返す。
  it('requireAccessTokenは確定的な失効なら保存済みトークンを破棄し再ログインを促す例外を投げる', async () => {
    const context = createContext();
    context.secretValues.set('letsBlog.tokens', JSON.stringify(EXPIRED_TOKENS));
    jest
      .spyOn(deviceAuth, 'refreshAccessToken')
      .mockRejectedValue(new ApiError('トークンの更新に失敗しました (HTTP 400)', 400, '{"error":"invalid_grant"}', TOKEN_URL));

    await expect(requireAccessToken(context)).rejects.toThrow('Login');
    expect(context.secretValues.has('letsBlog.tokens')).toBe(false);
  });

  /**
   * issue #1098: サーバー再起動や瞬断のような一過性の失敗で保存済みトークンを捨てると、
   * まだ有効なリフレッシュトークンを失って強制ログアウトになる。
   */
  describe('一過性のリフレッシュ失敗ではトークンを保持する(issue #1098)', () => {
    async function failRefreshWith(error: unknown): Promise<{
      context: ReturnType<typeof createContext>;
      thrown: Error;
    }> {
      const context = createContext();
      context.secretValues.set('letsBlog.tokens', JSON.stringify(EXPIRED_TOKENS));
      jest.spyOn(deviceAuth, 'refreshAccessToken').mockRejectedValue(error);
      const thrown = await requireAccessToken(context).then(
        () => {
          throw new Error('例外が投げられませんでした');
        },
        (e: Error) => e
      );
      return { context, thrown };
    }

    it('HTTP 503ではletsBlog.tokensがSecretStorageに残る', async () => {
      const { context } = await failRefreshWith(
        new ApiError('トークンの更新に失敗しました (HTTP 503)', 503, '', TOKEN_URL)
      );

      expect(context.secretValues.get('letsBlog.tokens')).toBe(JSON.stringify(EXPIRED_TOKENS));
    });

    it('NetworkErrorではletsBlog.tokensがSecretStorageに残る', async () => {
      const { context } = await failRefreshWith(
        new NetworkError('トークンの更新でサーバーへ到達できませんでした', TOKEN_URL, new Error('ECONNREFUSED'))
      );

      expect(context.secretValues.get('letsBlog.tokens')).toBe(JSON.stringify(EXPIRED_TOKENS));
    });

    it('一過性の失敗の例外は再ログインを案内せず、一時的な失敗であることを伝える', async () => {
      const { thrown } = await failRefreshWith(
        new ApiError('トークンの更新に失敗しました (HTTP 503)', 503, '', TOKEN_URL)
      );

      expect(thrown.message).not.toContain('Login');
      expect(thrown.message).not.toContain('再ログイン');
      expect(thrown.message).toContain('一時的');
      expect(thrown.message).toContain('保持');
    });

    it('復旧後は同じcontextで再ログインなしに更新が成功する', async () => {
      const context = createContext();
      context.secretValues.set('letsBlog.tokens', JSON.stringify(EXPIRED_TOKENS));
      jest
        .spyOn(deviceAuth, 'refreshAccessToken')
        .mockRejectedValueOnce(new ApiError('トークンの更新に失敗しました (HTTP 503)', 503, '', TOKEN_URL))
        .mockResolvedValueOnce({ accessToken: 'access-new', refreshToken: 'refresh-new', expiresIn: 3600 });

      await expect(requireAccessToken(context)).rejects.toThrow();
      await expect(requireAccessToken(context)).resolves.toBe('access-new');

      const stored = JSON.parse(context.secretValues.get('letsBlog.tokens') as string);
      expect(stored.accessToken).toBe('access-new');
      expect(stored.refreshToken).toBe('refresh-new');
    });

    it('getAccessTokenは一過性の失敗でも従来どおりundefinedを返す', async () => {
      const context = createContext();
      context.secretValues.set('letsBlog.tokens', JSON.stringify(EXPIRED_TOKENS));
      jest
        .spyOn(deviceAuth, 'refreshAccessToken')
        .mockRejectedValue(new ApiError('トークンの更新に失敗しました (HTTP 503)', 503, '', TOKEN_URL));

      await expect(getAccessToken(context)).resolves.toBeUndefined();
      expect(context.secretValues.has('letsBlog.tokens')).toBe(true);
    });
  });

  it('壊れたトークンJSONの場合は破棄して再ログインを促す例外を投げる', async () => {
    const context = createContext();
    context.secretValues.set('letsBlog.tokens', '{壊れたJSON');

    await expect(requireAccessToken(context)).rejects.toThrow('Login');
    expect(context.secretValues.has('letsBlog.tokens')).toBe(false);
  });

  it('storeTokensで保存した値をrequireAccessTokenで取得できる', async () => {
    const context = createContext();
    await storeTokens(context, { accessToken: 'access-2', refreshToken: 'refresh-2', expiresIn: 3600 });
    await expect(requireAccessToken(context)).resolves.toBe('access-2');
  });

  it('getAccessTokenは未ログイン等の失敗時にundefinedを返す', async () => {
    await expect(getAccessToken(createContext())).resolves.toBeUndefined();
  });

  it('getAccessTokenはrequireAccessTokenの成功結果を返す', async () => {
    const context = createContext();
    context.secretValues.set('letsBlog.tokens', JSON.stringify(FUTURE_TOKENS));
    await expect(getAccessToken(context)).resolves.toBe(FUTURE_TOKENS.accessToken);
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

  it('idを持たない値も読み出せる(issue #565: Device Authorization Grantで復元するActorはidを持たない)', async () => {
    const context = createContext();
    const actorWithoutId = { email: 'writer@example.com', role: 'ROLE_EDITOR' };
    await setActor(context, actorWithoutId);
    await expect(getActor(context)).resolves.toEqual(actorWithoutId);
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

describe('AIプロバイダー設定(issue #530 / カバレッジ補完 issue #775)', () => {
  it('未設定なら空文字を返す(サーバー側の既定値に委ねることを意味する)', () => {
    expect(getConfiguredAiProvider()).toBe('');
  });

  it('設定済みならその値を返す', () => {
    setConfiguration('letsBlog.aiProvider', 'OPENAI');
    expect(getConfiguredAiProvider()).toBe('OPENAI');
  });

  it('setConfiguredAiProviderはuser設定(Global)へ書き込む', async () => {
    await setConfiguredAiProvider('CLAUDE');
    expect(configurationUpdates).toEqual([
      { key: 'letsBlog.aiProvider', value: 'CLAUDE', target: ConfigurationTarget.Global },
    ]);
  });

  it('書き込んだ値はgetConfiguredAiProviderから読み出せる', async () => {
    await setConfiguredAiProvider('OLLAMA');
    expect(getConfiguredAiProvider()).toBe('OLLAMA');
  });

  it('空文字を書き込むと「サーバーの既定値を使う」状態へ戻せる', async () => {
    await setConfiguredAiProvider('OPENAI');
    await setConfiguredAiProvider('');
    expect(getConfiguredAiProvider()).toBe('');
  });
});

describe('アクセストークン更新のタイムアウト(カバレッジ補完 issue #775)', () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  // requireAccessTokenはリフレッシュ要求に30秒のタイムアウトを設けており、
  // 期限を過ぎるとAbortControllerで中断する(config.ts:133)。応答が返らないKeycloakを
  // 相手に拡張が無期限に固まらないための経路なので、実際に発火させて検証する。
  it('リフレッシュが応答しない場合は中断するが、保存済みトークンは破棄しない(issue #1098)', async () => {
    jest.useFakeTimers();
    try {
      const context = createContext();
      const expiredTokens = {
        accessToken: 'access-old',
        refreshToken: 'refresh-old',
        expiresAt: Date.now() - 1_000,
      };
      context.secretValues.set('letsBlog.tokens', JSON.stringify(expiredTokens));

      let observedSignal: AbortSignal | undefined;
      jest
        .spyOn(deviceAuth, 'refreshAccessToken')
        .mockImplementation((serverUrl, _refreshToken, _allowInsecureTls, signal) => {
          observedSignal = signal;
          return new Promise((_resolve, reject) => {
            // 中断時にdeviceAuth.refreshAccessTokenが投げるのと同じ例外型で再現する。
            signal.addEventListener('abort', () =>
              reject(new TimeoutError('トークンの更新がタイムアウトしました', serverUrl, 30_000))
            );
          });
        });

      const pending = requireAccessToken(context);
      const rejection = pending.then(
        () => {
          throw new Error('例外が投げられませんでした');
        },
        (error: Error) => error
      );

      await jest.advanceTimersByTimeAsync(30_000);
      const thrown = await rejection;

      expect(observedSignal?.aborted).toBe(true);
      // タイムアウトは一過性の失敗なので、まだ有効なリフレッシュトークンを捨てない(issue #1098)。
      expect(context.secretValues.get('letsBlog.tokens')).toBe(JSON.stringify(expiredTokens));
      expect(thrown.message).not.toContain('Login');
      expect(thrown.message).toContain('一時的');
    } finally {
      jest.useRealTimers();
    }
  });
});
