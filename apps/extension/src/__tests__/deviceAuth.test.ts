import {
  realmBaseUrl,
  parseDeviceAuthorization,
  parseTokenResult,
  classifyPollResponse,
  computeExpiresAt,
} from '../deviceAuth';

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
