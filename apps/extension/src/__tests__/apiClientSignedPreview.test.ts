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

  it('プラグインが使えないサイト(409)では例外にせず、案内用の結果を返す', async () => {
    respondWith(
      { message: 'このサイトでは letsblog プラグインが使えない(未導入)ため、投稿とプレビューはできません。' },
      409
    );

    const result = await apiClient.createSignedPreviewUrl('token', undefined, 7, input);

    expect(result.kind).toBe('pluginUnavailable');
    expect(result).toMatchObject({ message: expect.stringContaining('未導入') });
  });

  it('要更新の409は要更新の案内になる', async () => {
    respondWith({ message: 'このサイトでは letsblog プラグインが使えない(要更新)ため' }, 409);

    const result = await apiClient.createSignedPreviewUrl('token', undefined, 7, input);

    expect(result).toMatchObject({ kind: 'pluginUnavailable', needsUpdate: true });
  });

  it('409の本文にmessageが無ければ、本文をそのまま案内の文言にする', async () => {
    respondWith({ detail: 'letsblog プラグインが未導入です' }, 409);

    const result = await apiClient.createSignedPreviewUrl('token', undefined, 7, input);

    expect(result).toMatchObject({
      kind: 'pluginUnavailable',
      message: JSON.stringify({ detail: 'letsblog プラグインが未導入です' }),
    });
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
