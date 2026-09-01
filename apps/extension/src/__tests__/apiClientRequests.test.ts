import { setConfiguration, resetMocks } from '../__mocks__/vscode';
import * as apiClient from '../apiClient';
import { httpRequest } from '../httpClient';
import { ApiError, ResponseValidationError } from '../errorHandler';

jest.mock('../httpClient', () => ({ httpRequest: jest.fn() }));

const mockedRequest = httpRequest as jest.MockedFunction<typeof httpRequest>;

/**
 * 拡張がサーバーへ「何を」送るかの検証(issue #942 / AT-16 Layer 2)。
 *
 * Layer 1(e2e/features)は実スタックに対する応答を確認するが、リクエストの中身
 * ——校正チェックが本文以外を送っていないこと、セクション生成が見出しの文脈を含めること——は
 * サーバー越しには観測できない。受け入れ基準のうちその部分をここで担保する。
 */
describe('apiClientが組み立てるリクエスト', () => {
  interface Recorded {
    url: string;
    method: string;
    headers: Record<string, string>;
    body: unknown;
  }

  function respondWith(payload: unknown, status = 200, headers: Record<string, string> = {}): void {
    mockedRequest.mockImplementation(async (url, options) => {
      recorded.push({
        url,
        method: options.method,
        headers: options.headers,
        body: typeof options.body === 'string' ? JSON.parse(options.body) : options.body,
      });
      return {
        status,
        ok: status >= 200 && status < 300,
        statusText: 'OK',
        header: (name: string) => headers[name],
        text: async () => JSON.stringify(payload),
        json: async () => payload,
        arrayBuffer: async () => new ArrayBuffer(0),
      };
    });
  }

  let recorded: Recorded[];

  beforeEach(() => {
    recorded = [];
    mockedRequest.mockReset();
    resetMocks();
    apiClient.clearResponseCache();
    setConfiguration('letsBlog.serverUrl', 'https://stack.test');
    setConfiguration('letsBlog.allowInsecureTls', true);
  });

  it('校正チェックは本文とプロバイダーだけを送る', async () => {
    respondWith({ issues: [] });

    await apiClient.proofreadContent('token', undefined, '校正したい本文', 'OPENAI');

    expect(recorded).toHaveLength(1);
    expect(recorded[0].url).toBe('https://stack.test/api/ai/proofread');
    expect(recorded[0].method).toBe('POST');
    expect(recorded[0].body).toEqual({ text: '校正したい本文', provider: 'OPENAI' });
    expect(recorded[0].headers.Authorization).toBe('Bearer token');
  });

  it('プロバイダー未指定の校正チェックはproviderを送らない(サーバー既定を使う)', async () => {
    respondWith({ issues: [] });

    await apiClient.proofreadContent('token', undefined, '本文');

    expect(recorded[0].body).toEqual({ text: '本文' });
  });

  it('セクション生成は見出しと直前の文脈・記事タイトルを含めて送る', async () => {
    respondWith({ result: '生成結果', sources: [] });

    await apiClient.generateSection('token', undefined, {
      mode: 'body',
      heading: '背景',
      precedingContext: '## はじめに\n\n導入',
      articleTitle: '入門記事',
      subsectionHeadings: ['前提', '構成'],
    });

    expect(recorded[0].url).toBe('https://stack.test/api/ai/section');
    expect(recorded[0].body).toEqual({
      mode: 'body',
      heading: '背景',
      precedingContext: '## はじめに\n\n導入',
      articleTitle: '入門記事',
      subsectionHeadings: ['前提', '構成'],
    });
  });

  it('AIプロバイダーは指定した値がそのままリクエストへ載る', async () => {
    respondWith({ result: '生成結果', sources: [] });

    await apiClient.askAi('token', 'draft', '本文', undefined, 'CLAUDE');

    expect(recorded[0].body).toEqual({ mode: 'draft', text: '本文', provider: 'CLAUDE' });
  });

  it('参照系は同じ問い合わせをキャッシュし、サーバーへ再送しない', async () => {
    respondWith([{ id: 1, name: 'サイト', siteKey: 'site' }]);

    await apiClient.listSites('token');
    await apiClient.listSites('token');

    expect(recorded).toHaveLength(1);
  });

  it('プロジェクト単位のキャッシュは更新操作で破棄される', async () => {
    respondWith([{ id: 1, projectId: 7, name: '図', createdAt: '2026-01-01T00:00:00', updatedAt: '2026-01-01T00:00:00' }]);
    await apiClient.listDiagrams('token', undefined, 7);
    await apiClient.listDiagrams('token', undefined, 7);
    expect(recorded).toHaveLength(1);

    apiClient.invalidateProjectCache(7);
    await apiClient.listDiagrams('token', undefined, 7);
    expect(recorded).toHaveLength(2);
  });

  it('エラー応答は状態コード・本文・相関IDを持つApiErrorになる', async () => {
    respondWith({ error: '失敗' }, 502, { 'X-Correlation-Id': 'corr-1' });

    await expect(apiClient.publishPost('token', {
      site: 'site',
      title: 'タイトル',
      markdown: '本文',
      images: [],
    })).rejects.toMatchObject({
      status: 502,
      correlationId: 'corr-1',
    });
  });

  it('想定と異なる形の応答はResponseValidationErrorになる', async () => {
    respondWith({ wpPostId: 1 });

    await expect(
      apiClient.publishPost('token', { site: 'site', title: 'タイトル', markdown: '本文', images: [] })
    ).rejects.toBeInstanceOf(ResponseValidationError);
  });

  it('公開はmultipart/form-dataで送り、front matter由来の項目をフィールドとして並べる', async () => {
    mockedRequest.mockImplementation(async (url, options) => {
      recorded.push({
        url,
        method: options.method,
        headers: options.headers,
        body: options.body instanceof Buffer ? options.body.toString('utf-8') : options.body,
      });
      return {
        status: 200,
        ok: true,
        statusText: 'OK',
        header: () => undefined,
        text: async () => '',
        json: async () => ({ wpPostId: '1', wpPostUrl: 'https://site/1', status: 'draft' }),
        arrayBuffer: async () => new ArrayBuffer(0),
      };
    });

    await apiClient.publishPost('token', {
      site: 'site',
      title: 'タイトル',
      slug: 'slug',
      status: 'draft',
      categories: ['カテゴリ'],
      tags: ['タグ1', 'タグ2'],
      markdown: '本文',
      images: [],
      publishScheduledAt: '2026-12-25T09:00:00Z',
    });

    const body = String(recorded[0].body);
    expect(recorded[0].headers['Content-Type']).toContain('multipart/form-data; boundary=');
    for (const field of ['site', 'title', 'slug', 'status', 'categories', 'tags', 'markdown', 'publishScheduledAt']) {
      expect(body).toContain(`name="${field}"`);
    }
    expect(body).toContain('タグ1');
    expect(body).toContain('タグ2');
  });

  it('既存投稿の照会は404を「未投稿」として扱う', async () => {
    respondWith({ error: 'not found' }, 404);

    await expect(apiClient.lookupExistingPost('token', 'site', 'slug')).resolves.toBeUndefined();
  });

  it('既存投稿の照会は404以外のエラーはそのまま投げる', async () => {
    respondWith({ error: 'boom' }, 403);

    await expect(apiClient.lookupExistingPost('token', 'site', 'slug')).rejects.toBeInstanceOf(ApiError);
  });
});
