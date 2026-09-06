import {
  realmBaseUrl,
  parseDeviceAuthorization,
  parseTokenResult,
  classifyPollResponse,
  computeExpiresAt,
  requestDeviceAuthorization,
  pollForToken,
  refreshAccessToken,
  isRevokedRefreshResponse,
  isRefreshTokenRevoked,
  DEVICE_CLIENT_ID,
} from '../deviceAuth';
import { httpRequest } from '../httpClient';
import { ApiError, NetworkError, TimeoutError, isRetryable } from '../errorHandler';

jest.mock('../httpClient', () => ({ httpRequest: jest.fn() }));

const mockedRequest = httpRequest as jest.MockedFunction<typeof httpRequest>;

describe('realmBaseUrl', () => {
  it('letsBlog.serverUrlからKeycloakのrealmエンドポイントのベースを組み立てる', () => {
    expect(realmBaseUrl('https://localhost')).toBe('https://localhost/auth/realms/letsblog');
  });
});

describe('parseDeviceAuthorization', () => {
  const VALID_RESPONSE = {
    device_code: 'device-abc',
    user_code: 'ABCD-1234',
    verification_uri: 'https://localhost/auth/realms/letsblog/device',
    expires_in: 600,
    interval: 5,
  };

  it('必須項目を持つ応答をパースできる', () => {
    expect(parseDeviceAuthorization(VALID_RESPONSE)).toEqual({
      deviceCode: 'device-abc',
      userCode: 'ABCD-1234',
      verificationUri: 'https://localhost/auth/realms/letsblog/device',
      verificationUriComplete: undefined,
      expiresIn: 600,
      interval: 5,
    });
  });

  it('verification_uri_completeがあれば取り込む', () => {
    const result = parseDeviceAuthorization({
      ...VALID_RESPONSE,
      verification_uri_complete: 'https://localhost/auth/realms/letsblog/device?user_code=ABCD-1234',
    });
    expect(result.verificationUriComplete).toBe(
      'https://localhost/auth/realms/letsblog/device?user_code=ABCD-1234'
    );
  });

  it('オブジェクトでない応答は例外を投げる', () => {
    expect(() => parseDeviceAuthorization(null)).toThrow('デバイス認可の応答が不正です。');
    expect(() => parseDeviceAuthorization('not-an-object')).toThrow('デバイス認可の応答が不正です。');
  });

  it('必須項目が欠けている場合は例外を投げる', () => {
    const { device_code: _omit, ...withoutDeviceCode } = VALID_RESPONSE;
    expect(() => parseDeviceAuthorization(withoutDeviceCode)).toThrow(
      'デバイス認可の応答に必須項目が欠けています。'
    );
  });
});

describe('parseTokenResult', () => {
  it('必須項目を持つ応答をパースできる', () => {
    expect(
      parseTokenResult({ access_token: 'a', refresh_token: 'r', expires_in: 300 })
    ).toEqual({ accessToken: 'a', refreshToken: 'r', expiresIn: 300 });
  });

  it('オブジェクトでない応答は例外を投げる', () => {
    expect(() => parseTokenResult(undefined)).toThrow('トークン応答が不正です。');
  });

  it('必須項目が欠けている場合は例外を投げる', () => {
    expect(() => parseTokenResult({ access_token: 'a' })).toThrow('トークン応答に必須項目が欠けています。');
  });
});

describe('classifyPollResponse', () => {
  it('2xxはsuccessとしてトークンを含む', () => {
    const outcome = classifyPollResponse(200, { access_token: 'a', refresh_token: 'r', expires_in: 300 });
    expect(outcome).toEqual({
      kind: 'success',
      tokens: { accessToken: 'a', refreshToken: 'r', expiresIn: 300 },
    });
  });

  it('authorization_pendingはpending', () => {
    expect(classifyPollResponse(400, { error: 'authorization_pending' })).toEqual({ kind: 'pending' });
  });

  it('slow_downはslow_down', () => {
    expect(classifyPollResponse(400, { error: 'slow_down' })).toEqual({ kind: 'slow_down' });
  });

  it('access_deniedはdenied', () => {
    expect(classifyPollResponse(400, { error: 'access_denied' })).toEqual({ kind: 'denied' });
  });

  it('expired_tokenはexpired', () => {
    expect(classifyPollResponse(400, { error: 'expired_token' })).toEqual({ kind: 'expired' });
  });

  it('未知のエラーコード/ボディなしの失敗は例外を投げる', () => {
    expect(() => classifyPollResponse(500, undefined)).toThrow('トークンの取得に失敗しました (HTTP 500)');
    expect(() => classifyPollResponse(400, { error: 'something_else' })).toThrow(
      'トークンの取得に失敗しました (HTTP 400)'
    );
  });
});

describe('computeExpiresAt', () => {
  it('expiresIn(秒)を現在時刻に加算したエポックミリ秒を返す', () => {
    expect(computeExpiresAt(600, 1_000_000)).toBe(1_000_000 + 600_000);
  });

  it('nowを省略した場合はDate.now()を基準にする', () => {
    const before = Date.now();
    const result = computeExpiresAt(60);
    const after = Date.now();
    expect(result).toBeGreaterThanOrEqual(before + 60_000);
    expect(result).toBeLessThanOrEqual(after + 60_000);
  });
});

/**
 * トークンエンドポイントへ「何を送るか」と、失敗を「どう分類するか」の検証(issue #1098)。
 *
 * 拡張の強制ログアウトには2つの原因があった。(1) デバイス認可でoffline_accessを要求しないため
 * リフレッシュトークンがSSOセッション(idle 30分/最大10時間)に縛られる。(2) リフレッシュの失敗を
 * 種類で区別しないため、瞬断でも保存済みトークンを捨てる。ここではその両方を、
 * 実HTTPを介さずに(httpClientをモックして)観測する。
 */
describe('トークンエンドポイントへ送るリクエスト(issue #1098)', () => {
  interface Recorded {
    url: string;
    method: string;
    body: string;
  }

  let recorded: Recorded[];

  /** 指定のステータス/本文を返すhttpRequestを仕込み、送られたリクエストを記録する。 */
  function respondWith(status: number, payload: unknown, text?: () => Promise<string>): void {
    mockedRequest.mockImplementation(async (url, options) => {
      recorded.push({ url, method: options.method, body: String(options.body ?? '') });
      return {
        status,
        ok: status >= 200 && status < 300,
        statusText: 'Service Unavailable',
        header: () => undefined,
        text: text ?? (async () => JSON.stringify(payload)),
        json: async () => payload,
        arrayBuffer: async () => new ArrayBuffer(0),
      };
    });
  }

  /** httpRequest自体が失敗する(サーバーへ到達できない)状況を仕込む。 */
  function failWith(error: Error): void {
    mockedRequest.mockImplementation(async () => {
      throw error;
    });
  }

  const TOKEN_PAYLOAD = { access_token: 'access-1', refresh_token: 'refresh-1', expires_in: 300 };

  beforeEach(() => {
    recorded = [];
    mockedRequest.mockReset();
  });

  function bodyOf(index: number): URLSearchParams {
    return new URLSearchParams(recorded[index].body);
  }

  describe('scopeの要求(R1)', () => {
    it('requestDeviceAuthorizationはoffline_accessとclient_idを送る', async () => {
      respondWith(200, {
        device_code: 'device-abc',
        user_code: 'ABCD-1234',
        verification_uri: 'https://stack.test/auth/realms/letsblog/device',
        expires_in: 600,
        interval: 5,
      });

      await requestDeviceAuthorization('https://stack.test', false, new AbortController().signal);

      expect(recorded[0].url).toBe('https://stack.test/auth/realms/letsblog/protocol/openid-connect/auth/device');
      expect(bodyOf(0).get('scope')).toBe('offline_access');
      expect(bodyOf(0).get('client_id')).toBe(DEVICE_CLIENT_ID);
    });

    it('requestDeviceAuthorizationは失敗ステータスで例外を投げる', async () => {
      respondWith(500, undefined);

      await expect(
        requestDeviceAuthorization('https://stack.test', false, new AbortController().signal)
      ).rejects.toThrow('デバイス認可のリクエストに失敗しました (HTTP 500)');
    });

    it('pollForTokenはscopeを送らない(RFC 8628 §3.4のパラメータのみ)', async () => {
      respondWith(200, TOKEN_PAYLOAD);

      await pollForToken('https://stack.test', 'device-abc', false, new AbortController().signal);

      expect(bodyOf(0).has('scope')).toBe(false);
      expect(bodyOf(0).get('grant_type')).toBe('urn:ietf:params:oauth:grant-type:device_code');
      expect(bodyOf(0).get('device_code')).toBe('device-abc');
    });

    it('refreshAccessTokenはscopeを送らない(RFC 6749 §6、送ると元の許諾を絞り込んでしまう)', async () => {
      respondWith(200, TOKEN_PAYLOAD);

      await expect(
        refreshAccessToken('https://stack.test', 'refresh-old', false, new AbortController().signal)
      ).resolves.toEqual({ accessToken: 'access-1', refreshToken: 'refresh-1', expiresIn: 300 });

      expect(bodyOf(0).has('scope')).toBe(false);
      expect(bodyOf(0).get('grant_type')).toBe('refresh_token');
      expect(bodyOf(0).get('refresh_token')).toBe('refresh-old');
      expect(bodyOf(0).get('client_id')).toBe(DEVICE_CLIENT_ID);
    });
  });

  describe('リフレッシュ失敗の分類(R2)', () => {
    async function refreshFailure(): Promise<unknown> {
      return refreshAccessToken('https://stack.test', 'refresh-old', false, new AbortController().signal).then(
        () => {
          throw new Error('例外が投げられませんでした');
        },
        (error: unknown) => error
      );
    }

    it('400 invalid_grantは確定的な失効として判別でき、再試行対象にしない', async () => {
      respondWith(400, { error: 'invalid_grant', error_description: 'Token is not active' });

      const error = await refreshFailure();

      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).status).toBe(400);
      expect((error as ApiError).responseBody).toContain('invalid_grant');
      expect(isRefreshTokenRevoked(error)).toBe(true);
      expect(isRetryable(error)).toBe(false);
    });

    it.each([503, 500, 429])('HTTP %sはApiErrorとして再試行可能で、失効とは判定しない', async (status) => {
      respondWith(status, { error: 'temporarily_unavailable' });

      const error = await refreshFailure();

      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).status).toBe(status);
      expect(isRetryable(error)).toBe(true);
      expect(isRefreshTokenRevoked(error)).toBe(false);
    });

    it('本文を読めない失敗応答はstatusTextを本文として保持する', async () => {
      respondWith(503, undefined, async () => {
        throw new Error('body stream error');
      });

      const error = await refreshFailure();

      expect((error as ApiError).responseBody).toBe('Service Unavailable');
      expect(isRefreshTokenRevoked(error)).toBe(false);
    });

    it('サーバーへ到達できない場合はNetworkErrorを投げ、再試行対象になる', async () => {
      failWith(Object.assign(new Error('connect ECONNREFUSED'), { code: 'ECONNREFUSED' }));

      const error = await refreshFailure();

      expect(error).toBeInstanceOf(NetworkError);
      expect(isRetryable(error)).toBe(true);
      expect(isRefreshTokenRevoked(error)).toBe(false);
    });

    it('AbortSignalによる中断はTimeoutErrorを投げ、再試行対象になる', async () => {
      const controller = new AbortController();
      mockedRequest.mockImplementation(async () => {
        controller.abort();
        const aborted = new Error('The operation was aborted');
        aborted.name = 'AbortError';
        throw aborted;
      });

      const error = await refreshAccessToken('https://stack.test', 'refresh-old', false, controller.signal).then(
        () => {
          throw new Error('例外が投げられませんでした');
        },
        (e: unknown) => e
      );

      expect(error).toBeInstanceOf(TimeoutError);
      expect(isRetryable(error)).toBe(true);
      expect(isRefreshTokenRevoked(error)).toBe(false);
    });
  });
});

/**
 * 「確定的な失効」の判定はHTTPステータスだけでなく、Keycloakが400応答本文で返すerror値
 * (RFC 6749 §5.2)を見て行う(issue #1098 R2)。純関数として単体で検証する。
 */
describe('isRevokedRefreshResponse(issue #1098)', () => {
  it('400 invalid_grantは失効と判定する(文字列本文)', () => {
    expect(isRevokedRefreshResponse(400, '{"error":"invalid_grant"}')).toBe(true);
  });

  it('400 invalid_grantは失効と判定する(パース済みオブジェクト本文)', () => {
    expect(isRevokedRefreshResponse(400, { error: 'invalid_grant' })).toBe(true);
  });

  it.each(['invalid_client', 'unauthorized_client'])('%sも失効として扱う', (code) => {
    expect(isRevokedRefreshResponse(400, { error: code })).toBe(true);
  });

  it('5xxは本文にinvalid_grantがあっても失効と判定しない', () => {
    expect(isRevokedRefreshResponse(503, { error: 'invalid_grant' })).toBe(false);
  });

  it('2xx・3xxは失効と判定しない', () => {
    expect(isRevokedRefreshResponse(200, { error: 'invalid_grant' })).toBe(false);
  });

  it('未知のerror値は失効と判定しない', () => {
    expect(isRevokedRefreshResponse(400, { error: 'temporarily_unavailable' })).toBe(false);
  });

  it('JSONとして解釈できない本文は失効と判定しない', () => {
    expect(isRevokedRefreshResponse(400, '<html>502 Bad Gateway</html>')).toBe(false);
  });

  it('本文が無い/オブジェクトでない場合は失効と判定しない', () => {
    expect(isRevokedRefreshResponse(400, undefined)).toBe(false);
    expect(isRevokedRefreshResponse(400, 42)).toBe(false);
  });

  it('errorが文字列でない場合は失効と判定しない', () => {
    expect(isRevokedRefreshResponse(400, { error: { code: 'invalid_grant' } })).toBe(false);
  });
});

describe('isRefreshTokenRevoked(issue #1098)', () => {
  it('ApiError以外は失効と判定しない', () => {
    expect(isRefreshTokenRevoked(new Error('invalid_grant'))).toBe(false);
    expect(isRefreshTokenRevoked(undefined)).toBe(false);
  });

  it('ApiErrorの status と responseBody を見て判定する', () => {
    const url = 'https://stack.test/auth/realms/letsblog/protocol/openid-connect/token';
    expect(isRefreshTokenRevoked(new ApiError('失敗', 400, '{"error":"invalid_grant"}', url))).toBe(true);
    expect(isRefreshTokenRevoked(new ApiError('失敗', 503, '', url))).toBe(false);
  });
});
