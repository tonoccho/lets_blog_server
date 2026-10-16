import { resetMocks, setConfiguration } from '../__mocks__/vscode';
import * as apiClient from '../apiClient';
import { ApiError } from '../errorHandler';
import { LocalHttpServer, jsonReply } from './support/localHttpServer';

/** 署名付きプレビューURLの発行(issue #1562)。プラグインが使えないサイトの409は案内に変える。 */
describe('createSignedPreviewUrl', () => {
  let server: LocalHttpServer;

  function respondWith(payload: unknown, status = 200): void {
    server.respondWith(jsonReply(payload, status));
  }

  /** 受信した要求を、本文を JSON に戻した形で返す。 */
  function requests(): { path: string; method: string; body: unknown }[] {
    return server.recorded.map((r) => ({ path: r.path, method: r.method, body: JSON.parse(r.body) }));
  }

  const input = {
    siteId: 3,
    title: '記事',
    contentHtml: '<p>本文</p>',
    categories: ['a'],
    tags: ['b'],
    featuredImageDataUri: 'data:image/png;base64,AAAA',
  };

  beforeEach(async () => {
    server = await LocalHttpServer.start();
    resetMocks();
    setConfiguration('letsBlog.serverUrl', server.url);
    setConfiguration('letsBlog.allowInsecureTls', true);
  });

  afterEach(async () => {
    await server.stop();
  });

  it('プロジェクトのsigned-url APIへ記事の内容を送り、URLと期限を返す', async () => {
    respondWith({ url: 'https://blog.example.com/?letsblog_preview=x', expiresAt: 1234 });

    const result = await apiClient.createSignedPreviewUrl('token', undefined, 7, input);

    expect(requests()).toHaveLength(1);
    expect(requests()[0].method).toBe('POST');
    expect(requests()[0].path).toBe('/api/projects/7/preview/signed-url');
    expect(requests()[0].body).toEqual(input);
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

    expect(requests()).toHaveLength(2);
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
    server.respondWith({ status: 409, body: 'letsblog プラグイン 要更新' });

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

  it('コードがあってもmessage/errorが文字列でない409は、本文そのものを案内の文言にする', async () => {
    const body = { details: { code: 'LETSBLOG_PLUGIN_NOT_INSTALLED' } };
    respondWith(body, 409);

    const result = await apiClient.createSignedPreviewUrl('token', undefined, 7, input);

    expect(result).toMatchObject({ kind: 'pluginUnavailable', needsUpdate: false, message: JSON.stringify(body) });
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
