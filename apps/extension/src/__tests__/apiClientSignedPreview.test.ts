import { resetMocks, setConfiguration } from '../__mocks__/vscode';
import * as apiClient from '../apiClient';
import { httpRequest } from '../httpClient';
import { ApiError } from '../errorHandler';

jest.mock('../httpClient', () => ({ httpRequest: jest.fn() }));

const mockedRequest = httpRequest as jest.MockedFunction<typeof httpRequest>;

/** 署名付きプレビューURLの発行(issue #1562)。プラグインが使えないサイトの409は案内に変える。 */
describe('createSignedPreviewUrl', () => {
  const recorded: { url: string; method: string; body: unknown }[] = [];

  function respondWith(payload: unknown, status = 200): void {
    mockedRequest.mockImplementation(async (url, options) => {
      recorded.push({
        url,
        method: options.method,
        body: typeof options.body === 'string' ? JSON.parse(options.body) : options.body,
      });
      return {
        status,
        ok: status >= 200 && status < 300,
        statusText: 'ERR',
        header: () => undefined,
        text: async () => JSON.stringify(payload),
        json: async () => payload,
        arrayBuffer: async () => new ArrayBuffer(0),
      };
    });
  }

  const input = {
    siteId: 3,
    title: '記事',
    contentHtml: '<p>本文</p>',
    categories: ['a'],
    tags: ['b'],
    featuredImageDataUri: 'data:image/png;base64,AAAA',
  };

  beforeEach(() => {
    recorded.length = 0;
    mockedRequest.mockReset();
    resetMocks();
    setConfiguration('letsBlog.serverUrl', 'https://stack.test');
    setConfiguration('letsBlog.allowInsecureTls', true);
  });

  it('プロジェクトのsigned-url APIへ記事の内容を送り、URLと期限を返す', async () => {
    respondWith({ url: 'https://blog.example.com/?letsblog_preview=x', expiresAt: 1234 });

    const result = await apiClient.createSignedPreviewUrl('token', undefined, 7, input);

    expect(recorded).toHaveLength(1);
    expect(recorded[0].method).toBe('POST');
    expect(recorded[0].url).toBe('https://stack.test/api/projects/7/preview/signed-url');
    expect(recorded[0].body).toEqual(input);
    expect(result).toEqual({
      kind: 'ready',
      url: 'https://blog.example.com/?letsblog_preview=x',
      expiresAt: 1234,
    });
  });

  it('再プレビューのたびに新しいURLを取得し直す(キャッシュしない)', async () => {
    respondWith({ url: 'https://blog.example.com/?t=1', expiresAt: 1 });
    await apiClient.createSignedPreviewUrl('token', undefined, 7, input);
    respondWith({ url: 'https://blog.example.com/?t=2', expiresAt: 2 });
    const second = await apiClient.createSignedPreviewUrl('token', undefined, 7, input);

    expect(recorded).toHaveLength(2);
    expect(second).toMatchObject({ kind: 'ready', url: 'https://blog.example.com/?t=2' });
  });

  const unavailableBody = (code: string, error: string): unknown => ({ error, details: { code } });

  it('未導入のコードの409は例外にせず、案内用の結果を返す(文言には依存しない)', async () => {
    respondWith(unavailableBody('LETSBLOG_PLUGIN_NOT_INSTALLED', '全く別の文言'), 409);

    const result = await apiClient.createSignedPreviewUrl('token', undefined, 7, input);

    expect(result).toMatchObject({ kind: 'pluginUnavailable', needsUpdate: false, message: '全く別の文言' });
  });

  it('要更新のコードの409は、文言に関係なく要更新の案内になる', async () => {
    respondWith(unavailableBody('LETSBLOG_PLUGIN_NEEDS_UPDATE', 'foo'), 409);

    const result = await apiClient.createSignedPreviewUrl('token', undefined, 7, input);

    expect(result).toMatchObject({ kind: 'pluginUnavailable', needsUpdate: true });
  });

  it('コードが無ければ、旧文言を含む409でもプラグイン案内にしない', async () => {
    respondWith({ message: 'このサイトでは letsblog プラグインが使えない(要更新)ため' }, 409);

    await expect(apiClient.createSignedPreviewUrl('token', undefined, 7, input)).rejects.toBeInstanceOf(ApiError);
  });

  it('本文がJSONでない409はApiErrorで失敗する', async () => {
    mockedRequest.mockImplementation(async () => ({
      status: 409,
      ok: false,
      statusText: 'ERR',
      header: () => undefined,
      text: async () => 'letsblog プラグイン 要更新',
      json: async () => ({}),
      arrayBuffer: async () => new ArrayBuffer(0),
    }));

    await expect(apiClient.createSignedPreviewUrl('token', undefined, 7, input)).rejects.toBeInstanceOf(ApiError);
  });

  it('detailsがあってもcodeが無い・別コードの409はApiErrorで失敗する', async () => {
    respondWith({ error: 'x', details: {} }, 409);
    await expect(apiClient.createSignedPreviewUrl('token', undefined, 7, input)).rejects.toBeInstanceOf(ApiError);
    respondWith({ error: 'x', details: { code: 'OTHER' } }, 409);
    await expect(apiClient.createSignedPreviewUrl('token', undefined, 7, input)).rejects.toBeInstanceOf(ApiError);
    respondWith(null, 409);
    await expect(apiClient.createSignedPreviewUrl('token', undefined, 7, input)).rejects.toBeInstanceOf(ApiError);
  });

  it('プラグインと無関係な409はそのままApiErrorで失敗する', async () => {
    respondWith({ message: '対象リソースの状態が競合しています' }, 409);

    await expect(apiClient.createSignedPreviewUrl('token', undefined, 7, input)).rejects.toBeInstanceOf(ApiError);
  });

  it('409以外のエラーはApiErrorで失敗する', async () => {
    respondWith({ message: 'boom' }, 500);

    await expect(apiClient.createSignedPreviewUrl('token', undefined, 7, input)).rejects.toMatchObject({
      status: 500,
    });
  });
});
