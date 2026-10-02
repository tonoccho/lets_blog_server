import { setConfiguration, resetMocks } from '../__mocks__/vscode';
import * as apiClient from '../apiClient';
import { httpRequest } from '../httpClient';
import { ApiError, CancelledError, NetworkError, ResponseValidationError, TimeoutError } from '../errorHandler';

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

  it('レビューステップの指摘生成は、projectIdとステップキーをパスに、本文だけをボディに送る', async () => {
    respondWith({
      suggestions: [{ id: 'a', stepKey: 'JAPANESE', originalText: '誤', message: '直す' }],
      skipped: false,
    });

    const result = await apiClient.reviewStepSuggestions('token', undefined, 42, 'READER_PERSPECTIVE', '本文です');

    expect(recorded).toHaveLength(1);
    expect(recorded[0].method).toBe('POST');
    expect(recorded[0].url).toBe('https://stack.test/api/projects/42/ai/review-steps/READER_PERSPECTIVE/suggestions');
    expect(recorded[0].body).toEqual({ text: '本文です' });
    expect(result.suggestions[0]).toMatchObject({ originalText: '誤', message: '直す', suggestion: null, sources: [] });
    expect(result.skipped).toBe(false);
  });

  it('レビューステップの応答でsuggestionsが欠けていれば空配列として扱う', async () => {
    respondWith({});

    const result = await apiClient.reviewStepSuggestions('token', undefined, 1, 'FACT_CHECK', '本文');

    expect(result.suggestions).toEqual([]);
    expect(result.skipped).toBe(false);
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

/**
 * 画像生成の戻り値と、共通リクエスト経路(タイムアウト・中断・省略可能な引数)の検証。
 *
 * batch sizeで指定した枚数はサーバーが`AiImageBatchResponse.images`として全件返す。
 * 拡張がそこから何枚を取り出すかはサーバー越しには観測できないため、ここで固定する
 * (issue #1104)。
 */
describe('apiClientが解釈するレスポンス', () => {
  interface Recorded {
    url: string;
    method: string;
    headers: Record<string, string>;
    body: unknown;
  }

  let recorded: Recorded[];

  function respondWith(payload: unknown, status = 200, textBody?: string): void {
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
        statusText: 'Server Error',
        header: () => undefined,
        text: async () => (textBody !== undefined ? textBody : JSON.stringify(payload)),
        json: async () => payload,
        arrayBuffer: async () => new ArrayBuffer(0),
      };
    });
  }

  beforeEach(() => {
    recorded = [];
    mockedRequest.mockReset();
    resetMocks();
    apiClient.clearResponseCache();
    setConfiguration('letsBlog.serverUrl', 'https://stack.test');
    setConfiguration('letsBlog.allowInsecureTls', true);
  });

  it('画像生成はbatch sizeで生成された全枚数を返す', async () => {
    respondWith({
      images: [
        { id: 1, fileName: 'a.png', dataBase64: 'QUFB', mimeType: 'image/png' },
        { id: 2, fileName: 'b.png', dataBase64: 'QkJC', mimeType: 'image/png' },
      ],
    });

    const images = await apiClient.generateImage('token', undefined, 7, { prompt: '猫', batchSize: 2 });

    expect(images).toHaveLength(2);
    expect(images.map((image) => image.fileName)).toEqual(['a.png', 'b.png']);
    expect(images[1].dataBase64).toBe('QkJC');
    expect(recorded[0].body).toEqual({ projectId: 7, prompt: '猫', batchSize: 2 });
  });

  it('画像生成はbatch sizeとbatch countの双方を送る(issue #1105)', async () => {
    respondWith({
      images: Array.from({ length: 6 }, (_, i) => ({
        id: i + 1,
        fileName: `image-${i}.png`,
        dataBase64: 'QUFB',
        mimeType: 'image/png',
      })),
    });

    const images = await apiClient.generateImage('token', undefined, 7, {
      prompt: '猫',
      batchSize: 2,
      batchCount: 3,
    });

    expect(images).toHaveLength(6);
    expect(recorded[0].body).toEqual({ projectId: 7, prompt: '猫', batchSize: 2, batchCount: 3 });
  });

  it('画像生成が1枚だけ返した場合も1要素の配列になる', async () => {
    respondWith({ images: [{ id: 1, fileName: 'only.png', dataBase64: 'QUFB', mimeType: 'image/png' }] });

    const images = await apiClient.generateImage('token', undefined, undefined, { prompt: '犬' });

    expect(images).toHaveLength(1);
    expect(images[0].fileName).toBe('only.png');
  });

  it('画像生成オプションはprojectIdの有無でURLとキャッシュキーを変える', async () => {
    respondWith({ checkpoints: ['sd'], selectedCheckpoint: 'sd', samplers: [], schedulers: [], loras: [] });

    await apiClient.getImageGenerationOptions('token', 7);
    await apiClient.getImageGenerationOptions('token');

    expect(recorded.map((r) => r.url)).toEqual([
      'https://stack.test/api/ai/image-options?projectId=7',
      'https://stack.test/api/ai/image-options',
    ]);
  });

  it('未割り当てissueの抽出はassignees未定義と空配列の双方を残す', async () => {
    respondWith([
      { number: 1, title: '未割り当て', htmlUrl: 'https://x/1', state: 'open' },
      { number: 2, title: '空配列', htmlUrl: 'https://x/2', state: 'open', assignees: [] },
      { number: 3, title: '割り当て済み', htmlUrl: 'https://x/3', state: 'open', assignees: ['someone'] },
    ]);

    const issues = await apiClient.listUnassignedIssues('token', { id: 1, email: 'a@b', name: 'a' } as never, 7);

    expect(issues.map((i) => i.number)).toEqual([1, 2]);
    expect(recorded[0].url).toContain('?state=open');
  });

  it('Issue本文が未記入(null)なら空文字を返す', async () => {
    respondWith({ body: null });
    const empty = await apiClient.getIssueDescription('token', { id: 1, email: 'a@b', name: 'a' } as never, 7, 3);
    expect(empty).toBe('');

    apiClient.clearResponseCache();
    respondWith({ body: '本文' });
    const filled = await apiClient.getIssueDescription('token', { id: 1, email: 'a@b', name: 'a' } as never, 7, 4);
    expect(filled).toBe('本文');
  });

  it('テーマCSSはsiteIdの有無でURLを変える', async () => {
    respondWith({ css: 'body{}', available: true });

    await apiClient.getThemeCss('token', undefined, 7, 3);
    await apiClient.getThemeCss('token', undefined, 7);

    expect(recorded.map((r) => r.url)).toEqual([
      'https://stack.test/api/projects/7/preview/theme-css?siteId=3',
      'https://stack.test/api/projects/7/preview/theme-css',
    ]);
  });

  it('issueの割り当てはContent-Typeヘッダを付けて送る', async () => {
    respondWith({ issueNumber: 3, htmlUrl: 'https://x/3', assignedLogin: 'me' });

    await apiClient.assignIssue('token', { id: 1, email: 'a@b', name: 'a' } as never, 7, 3);

    expect(recorded[0].headers['Content-Type']).toBe('application/json');
  });

  it('AIプロバイダー未指定の依頼はproviderキー自体を送らない', async () => {
    respondWith({ result: '結果', sources: [] });

    await apiClient.askAi('token', 'draft', '本文');
    await apiClient.askAiSearch('token', undefined, '質問');
    await apiClient.askAiSearch('token', undefined, '質問', 'OPENAI');

    expect(recorded[0].body).toEqual({ mode: 'draft', text: '本文' });
    expect(recorded[1].body).toEqual({ question: '質問' });
    expect(recorded[2].body).toEqual({ question: '質問', provider: 'OPENAI' });
  });

  it('projectIdを指定した執筆支援の依頼はprojectIdを本文へ載せ、未指定なら載せない(issue #1495)', async () => {
    respondWith({ result: '結果', sources: [], issues: [] });

    await apiClient.askAi('token', 'draft', '本文', undefined, 'CLAUDE', 7);
    await apiClient.askAiSearch('token', undefined, '質問', undefined, undefined, 7);
    await apiClient.generateSection('token', undefined, { mode: 'body', heading: '背景', projectId: 7 });
    await apiClient.generateSection('token', undefined, { mode: 'body', heading: '背景' });

    expect(recorded[0].body).toEqual({ mode: 'draft', text: '本文', provider: 'CLAUDE', projectId: 7 });
    expect(recorded[1].body).toEqual({ question: '質問', projectId: 7 });
    expect(recorded[2].body).toEqual({ mode: 'body', heading: '背景', projectId: 7 });
    expect(recorded[3].body).toEqual({ mode: 'body', heading: '背景' });
  });

  it('タグ提案は指定されたproviderとprojectIdだけを載せる', async () => {
    respondWith({ categories: [], tags: ['タグ'] });

    await apiClient.suggestTags('token', '本文');
    await apiClient.suggestTags('token', '本文', undefined, 'CLAUDE', 7);

    expect(recorded[0].body).toEqual({ text: '本文' });
    expect(recorded[1].body).toEqual({ text: '本文', provider: 'CLAUDE', projectId: 7 });
  });

  it('画像プロンプト生成は指定されたproviderだけを載せる', async () => {
    respondWith({ prompt: 'a cat' });

    await apiClient.generateImagePrompt('token', undefined, 7, [], '猫');
    await apiClient.generateImagePrompt('token', undefined, 7, [], '猫', undefined, 'OLLAMA');

    expect(recorded[0].body).toEqual({ history: [], message: '猫' });
    expect(recorded[1].body).toEqual({ history: [], message: '猫', provider: 'OLLAMA' });
  });

  it('公開はwpPostIdとアイキャッチファイル名も同梱する', async () => {
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
      markdown: '本文',
      images: [],
      wpPostId: '42',
      featuredImageFilename: 'eyecatch.png',
    });

    const body = String(recorded[0].body);
    expect(body).toContain('name="wpPostId"');
    expect(body).toContain('name="featuredImageFilename"');
    expect(body).toContain('eyecatch.png');
  });

  it('タイムアウト設定が不正な値なら既定のタイムアウトを使う', async () => {
    setConfiguration('letsBlog.requestTimeoutMs', 0);
    respondWith([]);

    await expect(apiClient.listSites('token')).resolves.toEqual([]);
  });

  it('既にキャンセル済みのシグナルを渡された場合もリクエスト自体は組み立てる', async () => {
    respondWith({ images: [{ id: 1, fileName: 'a.png', dataBase64: 'QUFB', mimeType: 'image/png' }] });
    const controller = new AbortController();
    controller.abort();

    await apiClient.generateImage('token', undefined, 7, { prompt: '猫' }, controller.signal);

    expect(recorded).toHaveLength(1);
  });

  it('送信中にキャンセルされた場合はCancelledErrorになる', async () => {
    const controller = new AbortController();
    mockedRequest.mockImplementation(
      () =>
        new Promise((_resolve, reject) => {
          controller.signal.addEventListener('abort', () => reject(new Error('aborted')));
          controller.abort();
        })
    );

    await expect(
      apiClient.generateImage('token', undefined, 7, { prompt: '猫' }, controller.signal)
    ).rejects.toBeInstanceOf(CancelledError);
  });

  // 画像生成は要求枚数ぶんの下限タイムアウトを持つようになったため(issue #1105)、
  // 「利用者設定の1ミリ秒で即座に切れる」ことはもう成立しない。下限を持たない
  // 参照系のエンドポイントで、タイムアウトがTimeoutErrorになることを確かめる
  // (画像生成側の下限そのものは apiClientImageTimeout.test.ts が受け持つ)。
  it('タイムアウトした場合はTimeoutErrorになる', async () => {
    setConfiguration('letsBlog.requestTimeoutMs', 1);
    mockedRequest.mockImplementation(
      (_url, options) =>
        new Promise((_resolve, reject) => {
          options.signal?.addEventListener('abort', () => reject(new Error('aborted')));
        })
    );

    await expect(apiClient.listSites('token')).rejects.toBeInstanceOf(TimeoutError);
  });

  it('接続自体に失敗した場合はNetworkErrorになる', async () => {
    mockedRequest.mockImplementation(() => Promise.reject(new Error('ECONNREFUSED')));

    await expect(apiClient.generateImage('token', undefined, 7, { prompt: '猫' })).rejects.toBeInstanceOf(
      NetworkError
    );
  });

  it('本文が空のエラー応答はステータステキストを詳細として使う', async () => {
    respondWith(undefined, 500, '');

    await expect(apiClient.generateImage('token', undefined, 7, { prompt: '猫' })).rejects.toMatchObject({
      responseBody: 'Server Error',
    });
  });

  it('ルート要素が想定と異なる応答は(root)として報告される', async () => {
    respondWith({ notAnArray: true });

    await expect(apiClient.listSites('token')).rejects.toMatchObject({
      issues: [expect.stringContaining('(root)')],
    });
  });

  it('記事の提出は、projectIdをパスに、ブランチ・Issue番号・スラッグをボディに送り、PRのURLを返す', async () => {
    respondWith({ prNumber: 9, url: 'https://github.test/o/r/pull/9', state: 'SUBMITTED', submittedByUserId: 1, created: true });

    const result = await apiClient.submitArticleReview('token', undefined, 42, {
      headBranch: 'article/7-my-post',
      githubIssueNumber: 7,
      articleSlug: 'my-post',
    });

    expect(recorded).toHaveLength(1);
    expect(recorded[0].method).toBe('POST');
    expect(recorded[0].url).toBe('https://stack.test/api/projects/42/article-review/submissions');
    expect(recorded[0].body).toEqual({ headBranch: 'article/7-my-post', githubIssueNumber: 7, articleSlug: 'my-post' });
    expect(result).toEqual({ prNumber: 9, url: 'https://github.test/o/r/pull/9', created: true });
  });

  it('記事の提出でブランチがGitHubに無い(404)場合はApiErrorで失敗し、再試行しない', async () => {
    respondWith({ message: 'branch not found' }, 404);

    await expect(
      apiClient.submitArticleReview('token', undefined, 42, { headBranch: 'b', githubIssueNumber: 1, articleSlug: 's' })
    ).rejects.toBeInstanceOf(ApiError);
    expect(recorded).toHaveLength(1);
  });

});

describe('listCustomTagsのキャッシュ(issue #1467)', () => {
  const tag = { tagName: 'warn', description: null, tagFormat: 'BLOCK' };
  let calls: number;

  function respondSequence(payloads: unknown[]): void {
    mockedRequest.mockImplementation(async () => {
      const payload = payloads[Math.min(calls, payloads.length - 1)];
      calls += 1;
      return {
        status: 200,
        ok: true,
        statusText: 'OK',
        header: () => undefined,
        text: async () => JSON.stringify(payload),
        json: async () => payload,
        arrayBuffer: async () => new ArrayBuffer(0),
      };
    });
  }

  beforeEach(() => {
    calls = 0;
    mockedRequest.mockReset();
    resetMocks();
    apiClient.clearResponseCache();
    setConfiguration('letsBlog.serverUrl', 'https://stack.test');
    setConfiguration('letsBlog.allowInsecureTls', true);
  });

  it('空の生の結果はキャッシュせず、次回の呼び出しで再取得して新しいタグが見える', async () => {
    respondSequence([[], [tag]]);

    const first = await apiClient.listCustomTags('token', undefined, 1);
    const second = await apiClient.listCustomTags('token', undefined, 1);

    expect(first).toEqual([]);
    expect(second).toEqual([tag]);
    expect(calls).toBe(2);
  });

  it('非空の結果はTTL内なら再取得せずキャッシュを返す', async () => {
    respondSequence([[tag]]);

    await apiClient.listCustomTags('token', undefined, 1);
    const second = await apiClient.listCustomTags('token', undefined, 1);

    expect(second).toEqual([tag]);
    expect(calls).toBe(1);
  });
});
