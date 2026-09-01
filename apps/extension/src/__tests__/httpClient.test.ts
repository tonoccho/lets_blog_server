import { httpRequest } from '../httpClient';

/**
 * ネイティブfetch経路のトランスポートに対する最小限の検証。
 * issue #585で追加した応答ヘッダの読み取り(gatewayの相関IDをエラーメッセージへ載せるために使う)が
 * 実際に機能することを確認する。
 */
describe('httpRequest(fetch経路)', () => {
  const originalFetch = global.fetch;

  afterEach(() => {
    global.fetch = originalFetch;
  });

  function stubFetch(response: Response): void {
    global.fetch = jest.fn().mockResolvedValue(response) as unknown as typeof fetch;
  }

  it('応答ヘッダをヘッダ名の大文字小文字を問わず読める', async () => {
    stubFetch(
      new Response('{}', {
        status: 503,
        headers: { 'X-Correlation-Id': 'corr-abc' },
      })
    );

    const res = await httpRequest('https://localhost/api/ai/draft', {
      method: 'GET',
      headers: {},
      signal: new AbortController().signal,
      allowInsecureTls: false,
    });

    expect(res.status).toBe(503);
    expect(res.ok).toBe(false);
    expect(res.header('X-Correlation-Id')).toBe('corr-abc');
    expect(res.header('x-correlation-id')).toBe('corr-abc');
  });

  it('存在しないヘッダはundefinedを返す(nullを拡張内部へ持ち込まない)', async () => {
    stubFetch(new Response('{}', { status: 200 }));

    const res = await httpRequest('https://localhost/api/sites', {
      method: 'GET',
      headers: {},
      signal: new AbortController().signal,
      allowInsecureTls: false,
    });

    expect(res.header('X-Correlation-Id')).toBeUndefined();
  });
});
