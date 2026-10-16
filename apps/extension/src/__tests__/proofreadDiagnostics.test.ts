import {
  decorationTypes,
  diagnosticCollections,
  progressRuns,
  shownInformations,
  shownWarnings,
  resetMocks,
  setConfiguration,
  setVisibleTextEditors,
  Range,
} from '../__mocks__/vscode';
import { ProofreadController } from '../proofreadDiagnostics';
import * as api from '../apiClient';
import { REVIEW_STEPS } from '../proofreadLogic';
import { ReviewChecklistStore } from '../reviewChecklistStore';
import { LogLevel, logger } from '../logger';
import { LocalHttpServer, RecordedRequest, Reply, jsonReply } from './support/localHttpServer';
import { TestContext, createContext, loginAs, selectProject } from './support/extensionContext';
import { waitFor } from './support/panelKit';

// 外部通信(gateway の /api)だけを実サーバーで受ける。config.ts・apiClient.ts・reviewChecklistStore.ts は実物。
// stepHandler / statusesHandler / categoriesHandler は「サーバー側の振る舞い」を差し込む口で、
// 呼び出し記録は実際にサーバーへ届いた要求を表す。
let server: LocalHttpServer;
let context: TestContext;
type StepHandler = (step: string, request: RecordedRequest) => Reply | Promise<Reply>;
const stepHandler = jest.fn<Reply | Promise<Reply>, Parameters<StepHandler>>();
const statusesHandler = jest.fn<Reply | Promise<Reply>, []>();
const categoriesHandler = jest.fn<Reply | Promise<Reply>, []>();

const NO_FINDINGS = { suggestions: [], skipped: false };

/** ステップごとの応答を関数で決める。投げた例外は HTTP 403(再試行されない失敗)になる。 */
function byStep(fn: (step: string, request: RecordedRequest) => unknown | Promise<unknown>): void {
  stepHandler.mockImplementation(async (step, request) => {
    try {
      return jsonReply(await fn(step, request));
    } catch (error) {
      return { status: 403, body: (error as Error).message };
    }
  });
}

function onceFor(payload: unknown): void {
  stepHandler.mockResolvedValueOnce(jsonReply(payload));
}

function allFor(payload: unknown): void {
  stepHandler.mockResolvedValue(jsonReply(payload));
}

/** サーバーへ届いた、レビューステップの要求のステップキー(届いた順)。 */
function requestedSteps(): string[] {
  return server.recorded
    .map((r) => /review-steps\/([A-Z_]+)\/suggestions/.exec(r.path)?.[1])
    .filter((step): step is string => step !== undefined);
}

/** サーバーへ届いたレビューステップ要求のうち、n 番目の要求の宛先プロジェクト。 */
function requestedProjectIds(): number[] {
  return server.recorded
    .map((r) => /projects\/(\d+)\/ai\/review-steps/.exec(r.path)?.[1])
    .filter((id): id is string => id !== undefined)
    .map(Number);
}

interface FakeEditor {
  document: FakeDocument;
  setDecorations: jest.Mock;
}

interface FakeDocument {
  uri: { toString: () => string };
  languageId: string;
  getText: () => string;
  positionAt: (offset: number) => { line: number; character: number };
}

function makeDocument(text: string, uri = 'file:///a.md', languageId = 'markdown'): FakeDocument {
  return {
    uri: { toString: () => uri },
    languageId,
    getText: () => text,
    positionAt: (offset) => ({ line: 0, character: offset }),
  };
}

function makeEditor(document: FakeDocument): FakeEditor {
  return { document, setDecorations: jest.fn() };
}

const ARTICLE = '---\ntitle: t\nstatus: draft\n---\nAはBです。CはDです。';

/** サーバーへ届いた、投稿ステータス一覧への要求。 */
function statusRequests(): string[] {
  return server.recorded.filter((r) => r.path === '/api/metadata/post-statuses').map((r) => r.path);
}

function controllerWith(): ProofreadController {
  return new ProofreadController(context);
}

/** decorationTypes[i] に最後に置かれた装飾を editor から取り出す。 */
function lastDecorations(editor: FakeEditor, type: unknown): { range: Range; hoverMessage: { value: string } }[] {
  const calls = editor.setDecorations.mock.calls.filter((c) => c[0] === type);
  return calls[calls.length - 1][1];
}

beforeEach(async () => {
  resetMocks();
  jest.resetAllMocks();
  api.clearResponseCache();
  server = await LocalHttpServer.start();
  setConfiguration('letsBlog.serverUrl', server.url);
  context = createContext();
  await loginAs(context, null);
  await selectProject(context, 7);
  stepHandler.mockResolvedValue(jsonReply(NO_FINDINGS));
  statusesHandler.mockResolvedValue(jsonReply([{ value: 'draft', label: '下書き' }]));
  categoriesHandler.mockResolvedValue(jsonReply([]));
  server.respondWith((request) => {
    const step = /\/ai\/review-steps\/([A-Z_]+)\/suggestions$/.exec(request.path)?.[1];
    if (request.method === 'POST' && step) return stepHandler(step, request);
    if (request.method === 'GET' && request.path === '/api/metadata/post-statuses') return statusesHandler();
    if (request.method === 'GET' && request.path.endsWith('/article-plan/categories')) return categoriesHandler();
    return { status: 404, body: `no route: ${request.method} ${request.path}` };
  });
});

afterEach(async () => {
  await server.stop();
});

describe('ProofreadController.runManual(5ステップのレビュー)', () => {
  it('5ステップを日本語チェック→…→文体チェックの順に、projectIdとステップキーを指定して呼ぶ', async () => {
    const controller = controllerWith();
    await controller.runManual(makeDocument(ARTICLE) as never);

    expect(requestedSteps()).toEqual([
      'JAPANESE',
      'PROOFREADING',
      'FACT_CHECK',
      'READER_PERSPECTIVE',
      'STYLE',
    ]);
    expect(requestedProjectIds()).toEqual([7, 7, 7, 7, 7]);
    for (const request of server.recorded.filter((r) => r.method === 'POST')) {
      expect(JSON.parse(request.body)).toEqual({ text: 'AはBです。CはDです。' });
    }
  });

  it('front matterのproject_idがあれば選択中のプロジェクトより優先する', async () => {
    const text = '---\ntitle: t\nproject_id: 99\n---\n本文';
    // 選択中のプロジェクトが無くても、front matter の project_id だけで実行できる。
    context.stateValues.clear();
    await controllerWith().runManual(makeDocument(text) as never);
    expect(requestedProjectIds()).toEqual([99, 99, 99, 99, 99]);
  });

  it('実行中は現在のステップ名を含む進捗を表示する', async () => {
    await controllerWith().runManual(makeDocument(ARTICLE) as never);

    expect(progressRuns).toHaveLength(1);
    const messages = progressRuns[0].reports.map((r) => r.message);
    expect(messages).toEqual([
      '日本語チェック (1/5)',
      '校正チェック (2/5)',
      '校閲 (3/5)',
      '読者視点でのチェック (4/5)',
      '文体チェック (5/5)',
    ]);
  });

  it('各ステップの指摘を、そのステップ専用の色の装飾として表示し、ホバーに内容を載せる', async () => {
    const document = makeDocument(ARTICLE);
    const editor = makeEditor(document);
    const otherEditor = makeEditor(makeDocument('x', 'file:///other.md'));
    setVisibleTextEditors([editor, otherEditor]);
    byStep((step) => {
      if (step === 'PROOFREADING') {
        return { suggestions: [{ stepKey: step, originalText: 'AはB', message: '誤り', suggestion: 'XはY', sources: [] }], skipped: false };
      }
      if (step === 'STYLE') {
        return { suggestions: [{ stepKey: step, originalText: 'CはD', message: '文体', suggestion: null, sources: [] }], skipped: false };
      }
      return { suggestions: [], skipped: false };
    });

    const controller = controllerWith();
    await controller.runManual(document as never);

    expect(decorationTypes).toHaveLength(5);
    const colors = decorationTypes.map((t) => JSON.stringify(t.options.borderColor));
    expect(new Set(colors).size).toBe(5);
    expect(decorationTypes[1].options.borderColor).toMatchObject({ id: REVIEW_STEPS[1].colorId });
    expect(String(decorationTypes[1].options.borderStyle)).toContain('solid');

    const proofreading = lastDecorations(editor, decorationTypes[1]);
    expect(proofreading).toHaveLength(1);
    expect(proofreading[0].range).toEqual(
      new Range({ character: 31, line: 0 }, { character: 34, line: 0 })
    );
    expect(proofreading[0].hoverMessage.value).toContain('校正チェック');
    expect(proofreading[0].hoverMessage.value).toContain('誤り');
    expect(proofreading[0].hoverMessage.value).toContain('XはY');

    const style = lastDecorations(editor, decorationTypes[4]);
    expect(style).toHaveLength(1);
    expect(style[0].hoverMessage.value).toContain('文体チェック');
    expect(lastDecorations(editor, decorationTypes[0])).toEqual([]);
    // 別ドキュメントのエディタには装飾を置かない。
    expect(otherEditor.setDecorations).not.toHaveBeenCalled();
  });

  it('categoriesがすべてサイトに存在すれば、カテゴリの診断は出さない', async () => {
    jest.useFakeTimers({ doNotFake: ['Date', 'setImmediate', 'clearImmediate', 'nextTick', 'queueMicrotask', 'performance'] });
    try {
      categoriesHandler.mockResolvedValue(jsonReply(['tech']));
      await loginAs(context);
      const text = '---\ntitle: t\nstatus: draft\ncategories: [tech]\n---\n本文';
      controllerWith().scheduleCheck(makeDocument(text) as never);
      await jest.advanceTimersByTimeAsync(500);
      await waitFor(() => diagnosticCollections[0]?.has('file:///a.md') === true);

      expect(categoriesHandler).toHaveBeenCalled();
      expect(diagnosticCollections[0].get('file:///a.md')).toEqual([]);
    } finally {
      jest.useRealTimers();
    }
  });

  it('再実行すると前回の装飾は新しい結果で置き換わり、装飾タイプは作り直さない', async () => {
    const document = makeDocument(ARTICLE);
    const editor = makeEditor(document);
    setVisibleTextEditors([editor]);
    const controller = controllerWith();
    onceFor({
      suggestions: [{ stepKey: 'JAPANESE', originalText: 'AはB', message: 'm', suggestion: null, sources: [] }],
      skipped: false,
    });
    await controller.runManual(document as never);
    expect(lastDecorations(editor, decorationTypes[0])).toHaveLength(1);

    allFor({ suggestions: [], skipped: false });
    await controller.runManual(document as never);
    expect(lastDecorations(editor, decorationTypes[0])).toEqual([]);
    expect(decorationTypes).toHaveLength(5);
  });

  it('本文が空ならAPIを呼ばず装飾を消す', async () => {
    const document = makeDocument('---\ntitle: t\n---\n   \n');
    const editor = makeEditor(document);
    setVisibleTextEditors([editor]);
    await controllerWith().runManual(document as never);

    expect(requestedSteps()).toEqual([]);
    expect(lastDecorations(editor, decorationTypes[0])).toEqual([]);
  });

  it('未ログインなら案内つきで失敗する', async () => {
    context.secretValues.clear();
    await expect(controllerWith().runManual(makeDocument(ARTICLE) as never)).rejects.toThrow('ログインしていません');
    expect(requestedSteps()).toEqual([]);
  });

  it('APIエラーは呼び出し元へ伝える', async () => {
    stepHandler.mockResolvedValue({ status: 403, body: 'boom' });
    await expect(controllerWith().runManual(makeDocument(ARTICLE) as never)).rejects.toMatchObject({ status: 403, responseBody: 'boom' });
  });

  it('後から始まった実行があれば、古い実行は結果を反映せず黙って終わる', async () => {
    const document = makeDocument(ARTICLE);
    const editor = makeEditor(document);
    setVisibleTextEditors([editor]);
    const controller = controllerWith();
    let release: (reply: Reply) => void = () => undefined;
    stepHandler.mockImplementationOnce(() => new Promise<Reply>((resolve) => (release = resolve)));
    const first = controller.runManual(document as never);
    await waitFor(() => requestedSteps().length === 1);
    allFor({ suggestions: [], skipped: false });
    const second = controller.runManual(document as never);
    release(
      jsonReply({
        suggestions: [{ stepKey: 'JAPANESE', originalText: 'AはB', message: 'm', suggestion: null, sources: [] }],
        skipped: false,
      })
    );
    await expect(first).resolves.toBeUndefined();
    await second;
    expect(lastDecorations(editor, decorationTypes[0])).toEqual([]);
  });

  it('最終ステップの応答待ちの間に別のレビューが始まっても、古い結果は反映しない', async () => {
    const document = makeDocument(ARTICLE);
    const editor = makeEditor(document);
    setVisibleTextEditors([editor]);
    const controller = controllerWith();
    let release: (v: unknown) => void = () => undefined;
    byStep((step) =>
      step === 'STYLE'
        ? new Promise((resolve) => (release = resolve))
        : { suggestions: [], skipped: false });
    const first = controller.runManual(document as never);
    await waitFor(() => requestedSteps().includes('STYLE'));
    allFor({ suggestions: [], skipped: false });
    await controller.runManual(document as never);
    release({
      suggestions: [{ stepKey: 'STYLE', originalText: 'AはB', message: 'm', suggestion: null, sources: [] }],
      skipped: false,
    });
    await first;
    expect(lastDecorations(editor, decorationTypes[4])).toEqual([]);
  });

  it('最終ステップの応答ヘッダが届いた後、本文の受信中に別のレビューが始まっても、古い結果は反映しない', async () => {
    const document = makeDocument(ARTICLE);
    const editor = makeEditor(document);
    setVisibleTextEditors([editor]);
    const controller = controllerWith();
    let openBody: () => void = () => undefined;
    const bodyGate = new Promise<void>((resolve) => (openBody = resolve));
    stepHandler.mockImplementation(async (step) =>
      step === 'STYLE'
        ? {
            ...jsonReply({
              suggestions: [{ stepKey: 'STYLE', originalText: 'AはB', message: 'm', suggestion: null, sources: [] }],
              skipped: false,
            }),
            bodyGate,
          }
        : jsonReply(NO_FINDINGS)
    );
    // 拡張が最終ステップの応答ヘッダを受け取った時点を、ログ(通信の完了記録)で確かめる。
    const logLines: string[] = [];
    logger.setSink({ appendLine: (line) => logLines.push(line) });
    logger.setMinLevel(LogLevel.Debug);
    try {
      const first = controller.runManual(document as never);
      await waitFor(() => logLines.some((line) => line.includes('STYLE/suggestions -> 200')));
      // 最終ステップは応答ヘッダを受け取った後で本文を止めている。その間に新しいレビューが始まる。
      allFor({ suggestions: [], skipped: false });
      await controller.runManual(document as never);
      openBody();
      await first;
      expect(lastDecorations(editor, decorationTypes[4])).toEqual([]);
    } finally {
      logger.setSink(undefined);
      logger.setMinLevel(LogLevel.Info);
    }
  });

  it('refreshEditorは保持している装飾を再表示する', async () => {
    const document = makeDocument(ARTICLE);
    setVisibleTextEditors([]);
    const controller = controllerWith();
    onceFor({
      suggestions: [{ stepKey: 'JAPANESE', originalText: 'AはB', message: 'm', suggestion: null, sources: [] }],
      skipped: false,
    });
    await controller.runManual(document as never);

    const editor = makeEditor(document);
    controller.refreshEditor(editor as never);
    expect(lastDecorations(editor, decorationTypes[0])).toHaveLength(1);

    const unknown = makeEditor(makeDocument('z', 'file:///unknown.md'));
    controller.refreshEditor(unknown as never);
    expect(unknown.setDecorations).not.toHaveBeenCalled();
  });

  it('ドキュメントを閉じると保持した装飾を破棄し、disposeで装飾タイプを解放する', async () => {
    const document = makeDocument(ARTICLE);
    const controller = controllerWith();
    onceFor({
      suggestions: [{ stepKey: 'JAPANESE', originalText: 'AはB', message: 'm', suggestion: null, sources: [] }],
      skipped: false,
    });
    await controller.runManual(document as never);
    controller.clearDocument(document as never);
    const editor = makeEditor(document);
    controller.refreshEditor(editor as never);
    expect(editor.setDecorations).not.toHaveBeenCalled();

    controller.dispose();
    expect(decorationTypes.every((t) => t.disposed)).toBe(true);
  });
});

/** 要求を受けても応答を返さないサーバー(クライアントが中断するまで応答待ちになる)。 */
const HANG: Reply = { status: 200, hang: true };

const JAPANESE_FINDING = { stepKey: 'JAPANESE', originalText: 'AはB', message: 'm', suggestion: null, sources: [] };

describe('ProofreadController の中断・失敗・スキップ(issue #1224)', () => {
  it('進捗通知はキャンセルできる', async () => {
    await controllerWith().runManual(makeDocument(ARTICLE) as never);
    expect(progressRuns[0].options.cancellable).toBe(true);
  });

  it('実行中にキャンセルすると、それ以降のステップは実行されず、応答待ちのリクエストも中断される', async () => {
    stepHandler.mockResolvedValue(HANG);
    const running = controllerWith().runManual(makeDocument(ARTICLE) as never);
    await waitFor(() => requestedSteps().length === 1);
    progressRuns[0].cancel();

    await expect(running).resolves.toBeUndefined();
    // 応答待ちのリクエストは、サーバー側から見て接続が閉じられている(中断された)。
    await waitFor(() => server.abortedRequests === 1);
    expect(server.abortedRequests).toBe(1);
    expect(requestedSteps()).toHaveLength(1);
    expect(shownInformations.some((m) => m.includes('中断'))).toBe(true);
  });

  it('ステップの合間にキャンセルされても、次のステップは実行されない', async () => {
    byStep((step) => {
      if (step === 'PROOFREADING') progressRuns[0].cancel();
      return { suggestions: [], skipped: false };
    });
    await expect(controllerWith().runManual(makeDocument(ARTICLE) as never)).resolves.toBeUndefined();
    expect(requestedSteps()).toEqual(['JAPANESE', 'PROOFREADING']);
  });

  it('キャンセルしても、直前のレビュー結果の表示を途中結果で上書きしない(チェックリストにも記録しない)', async () => {
    const document = makeDocument(ARTICLE);
    const editor = makeEditor(document);
    setVisibleTextEditors([editor]);
    const store = new ReviewChecklistStore(context);
    const controller = new ProofreadController(context, store);
    onceFor({ suggestions: [JAPANESE_FINDING], skipped: false });
    await controller.runManual(document as never);
    expect(lastDecorations(editor, decorationTypes[0])).toHaveLength(1);
    const recordedBefore = store.get(document.uri.toString());
    expect(recordedBefore?.items).toHaveLength(1);
    editor.setDecorations.mockClear();

    // 2回目: 1ステップ目は指摘0件で完了、2ステップ目の途中でキャンセルされる。
    stepHandler.mockImplementation(async (step) => {
      if (step === 'PROOFREADING') {
        progressRuns[1].cancel();
        return HANG;
      }
      return jsonReply(NO_FINDINGS);
    });
    await expect(controller.runManual(document as never)).resolves.toBeUndefined();

    expect(editor.setDecorations).not.toHaveBeenCalled();
    expect(store.get(document.uri.toString())).toEqual(recordedBefore);
    const fresh = makeEditor(document);
    controller.refreshEditor(fresh as never);
    expect(lastDecorations(fresh, decorationTypes[0])).toHaveLength(1);
  });

  it('キャンセル後に再実行すれば、通常どおり結果が表示される', async () => {
    const document = makeDocument(ARTICLE);
    const editor = makeEditor(document);
    setVisibleTextEditors([editor]);
    const controller = controllerWith();
    stepHandler.mockResolvedValueOnce(HANG);
    const first = controller.runManual(document as never);
    await waitFor(() => requestedSteps().length === 1);
    progressRuns[0].cancel();
    await first;

    allFor({ suggestions: [JAPANESE_FINDING], skipped: false });
    await controller.runManual(document as never);
    expect(lastDecorations(editor, decorationTypes[0])).toHaveLength(1);
  });

  it('1ステップがサーバエラーで失敗しても、他ステップの指摘は表示し、失敗したステップ名を報告する', async () => {
    const document = makeDocument(ARTICLE);
    const editor = makeEditor(document);
    setVisibleTextEditors([editor]);
    byStep((step) => {
      if (step === 'PROOFREADING') throw new Error('Internal Server Error');
      if (step === 'STYLE') {
        return { suggestions: [{ stepKey: step, originalText: 'CはD', message: '文体', suggestion: null, sources: [] }], skipped: false };
      }
      return { suggestions: [], skipped: false };
    });

    await expect(controllerWith().runManual(document as never)).resolves.toBeUndefined();

    expect(requestedSteps()).toHaveLength(5);
    expect(lastDecorations(editor, decorationTypes[4])).toHaveLength(1);
    expect(shownWarnings).toHaveLength(1);
    expect(shownWarnings[0]).toContain('校正チェック');
    expect(shownWarnings[0]).toContain('403');
  });

  it('ステップが失敗したレビューは、完了したものとしてチェックリストへ記録しない', async () => {
    byStep((step) => {
      if (step === 'STYLE') throw new Error('boom');
      return { suggestions: [], skipped: false };
    });
    const store = new ReviewChecklistStore(context);
    const document = makeDocument(ARTICLE);
    await new ProofreadController(context, store).runManual(document as never);
    expect(store.get(document.uri.toString())).toBeUndefined();
  });

  it('全ステップが失敗した場合は、既存の表示を残したまま例外として伝える', async () => {
    const document = makeDocument(ARTICLE);
    const editor = makeEditor(document);
    setVisibleTextEditors([editor]);
    const controller = controllerWith();
    onceFor({ suggestions: [JAPANESE_FINDING], skipped: false });
    await controller.runManual(document as never);
    editor.setDecorations.mockClear();

    stepHandler.mockResolvedValue({ status: 403, body: 'down' });
    await expect(controller.runManual(document as never)).rejects.toMatchObject({ status: 403, responseBody: 'down' });
    expect(editor.setDecorations).not.toHaveBeenCalled();
  });

  it('校閲がスキップされたら、失敗ではなくスキップとして理由つきで知らせる(指摘0件とは別)', async () => {
    byStep((step) =>
      step === 'FACT_CHECK'
        ? { suggestions: [], skipped: true, skipReason: 'Brave Search APIキーが未設定です' }
        : { suggestions: [], skipped: false });
    await controllerWith().runManual(makeDocument(ARTICLE) as never);

    expect(shownWarnings).toEqual([]);
    expect(shownInformations).toHaveLength(1);
    expect(shownInformations[0]).toContain('校閲');
    expect(shownInformations[0]).toContain('スキップ');
    expect(shownInformations[0]).toContain('Brave Search APIキーが未設定です');
  });

  it('スキップも失敗も無く指摘が0件なら、スキップの通知は出ない', async () => {
    await controllerWith().runManual(makeDocument(ARTICLE) as never);
    expect(shownInformations).toEqual([]);
    expect(shownWarnings).toEqual([]);
  });

  it('未ログインで手動実行すると、ログインを促すメッセージで失敗し、APIは呼ばれない', async () => {
    context.secretValues.clear();
    await expect(controllerWith().runManual(makeDocument(ARTICLE) as never)).rejects.toThrow(
      /ログインしていません.*Login/
    );
    expect(requestedSteps()).toEqual([]);
  });
});

describe('ProofreadController.scheduleCheck(自動実行)', () => {
  // デバウンスの setTimeout だけを偽物にする。実サーバーとの通信を待てるよう、Date と setImmediate は実物のまま。
  beforeEach(() => jest.useFakeTimers({ doNotFake: ['Date', 'setImmediate', 'clearImmediate', 'nextTick', 'queueMicrotask', 'performance'] }));
  afterEach(() => jest.useRealTimers());

  it('本文を編集してもレビューAPIは呼ばれない(proofreadEnabledを有効にしていても)', async () => {
    setConfiguration('letsBlog.proofreadEnabled', true);
    setConfiguration('letsBlog.proofreadDebounceMs', 500);
    const controller = controllerWith();
    controller.scheduleCheck(makeDocument(ARTICLE) as never);
    await jest.advanceTimersByTimeAsync(60_000);

    expect(requestedSteps()).toEqual([]);
    expect(progressRuns).toHaveLength(0);
  });

  it('markdown以外は何もしない', async () => {
    controllerWith().scheduleCheck(makeDocument(ARTICLE, 'file:///a.txt', 'plaintext') as never);
    await jest.advanceTimersByTimeAsync(5_000);
    expect(statusRequests()).toEqual([]);
  });

  it('front matterの検証は編集の都度デバウンスして実行され、不正なstatusを診断とQuickFixで示す', async () => {
    statusesHandler.mockResolvedValue(jsonReply([{ value: 'publish', label: '公開' }]));
    const controller = controllerWith();
    const document = makeDocument(ARTICLE);
    controller.scheduleCheck(document as never);
    controller.scheduleCheck(document as never); // 連続編集は1回にまとまる
    expect(statusRequests()).toEqual([]);
    await jest.advanceTimersByTimeAsync(500);
    await waitFor(() => (diagnosticCollections[0]?.get('file:///a.md') ?? []).length > 0);

    expect(statusRequests()).toHaveLength(1);
    const diagnostics = diagnosticCollections[0].get('file:///a.md') ?? [];
    expect(diagnostics.map((d) => d.code)).toContain('invalid-status');

    const actions = controller.provideCodeActions(document as never, undefined as never, {
      diagnostics: diagnostics.filter((d) => d.code === 'invalid-status'),
    } as never);
    expect(actions.map((a) => a.command?.command)).toEqual(['letsBlog.fixInvalidStatus']);
  });

  it('過去日時のpublish_scheduled_atと存在しないカテゴリも診断とQuickFixで示す', async () => {
    categoriesHandler.mockResolvedValue(jsonReply(['tech']));
    await loginAs(context);
    const text = '---\ntitle: t\nstatus: draft\npublish_scheduled_at: 2000-01-01T00:00:00Z\ncategories: [news]\n---\n本文';
    const controller = controllerWith();
    const document = makeDocument(text);
    controller.scheduleCheck(document as never);
    await jest.advanceTimersByTimeAsync(500);
    await waitFor(() => (diagnosticCollections[0]?.get('file:///a.md') ?? []).length >= 2);

    const diagnostics = diagnosticCollections[0].get('file:///a.md') ?? [];
    expect(diagnostics.map((d) => d.code).sort()).toEqual(['invalid-category', 'scheduled-past']);
    const actions = controller.provideCodeActions(document as never, undefined as never, { diagnostics } as never);
    expect(actions.map((a) => a.command?.command).sort()).toEqual([
      'letsBlog.removeInvalidCategory',
      'letsBlog.schedulePublication',
    ]);
    expect(actions.find((a) => a.title.includes('news'))).toBeDefined();
  });

  it('未ログインならAPIを要するfront matter検証はスキップし、APIエラーでも診断処理は落ちない', async () => {
    context.secretValues.clear();
    controllerWith().scheduleCheck(makeDocument(ARTICLE) as never);
    await jest.advanceTimersByTimeAsync(500);
    expect(statusRequests()).toEqual([]);

    await loginAs(context, null);
    await loginAs(context);
    statusesHandler.mockResolvedValue({ status: 403, body: 'x' });
    categoriesHandler.mockResolvedValue({ status: 403, body: 'y' });
    const text = '---\ntitle: t\ncategories: [a]\n---\n本文';
    controllerWith().scheduleCheck(makeDocument(text, 'file:///b.md') as never);
    await jest.advanceTimersByTimeAsync(500);
    await waitFor(() => categoriesHandler.mock.calls.length > 0);
    expect(categoriesHandler).toHaveBeenCalled();
  });

  it('キーが引用符つきで書かれていて行を特定できない場合は、診断を出さずに検証を続ける', async () => {
    categoriesHandler.mockResolvedValue(jsonReply(['tech']));
    await loginAs(context);
    statusesHandler.mockResolvedValue(jsonReply([{ value: 'publish', label: '公開' }]));
    const text =
      '---\n"title": t\n"status": draft\n"publish_scheduled_at": 2000-01-01T00:00:00Z\n"categories": [news]\n---\n本文';
    const document = makeDocument(text);
    controllerWith().scheduleCheck(document as never);
    await jest.advanceTimersByTimeAsync(500);
    await waitFor(() => categoriesHandler.mock.calls.length > 0);
    await waitFor(() => diagnosticCollections[0]?.has('file:///a.md') === true);

    expect(statusRequests()).toHaveLength(1);
    expect(categoriesHandler).toHaveBeenCalled();
    expect(diagnosticCollections[0].get('file:///a.md')).toEqual([]);
  });

  it('提案の無い診断ソースや別ソースの診断にはCodeActionを返さない', () => {
    const controller = controllerWith();
    const actions = controller.provideCodeActions(makeDocument(ARTICLE) as never, undefined as never, {
      diagnostics: [{ source: 'other', range: new Range(1, 2) }],
    } as never);
    expect(actions).toEqual([]);
  });

  it('runManualはfront matter検証の保留タイマーを取り消して即時実行する', async () => {
    const controller = controllerWith();
    const document = makeDocument(ARTICLE);
    controller.scheduleCheck(document as never);
    await controller.runManual(document as never);
    await jest.advanceTimersByTimeAsync(1_000);
    expect(statusRequests()).toHaveLength(1);
  });

  it('clearDocumentはfront matterの保留タイマーを取り消す', async () => {
    const controller = controllerWith();
    const document = makeDocument(ARTICLE);
    controller.scheduleCheck(document as never);
    controller.clearDocument(document as never);
    await jest.advanceTimersByTimeAsync(1_000);
    expect(statusRequests()).toEqual([]);
    controller.dispose();
  });
});

describe('ProofreadController と指摘チェックリスト(issue #1216)', () => {
  it('checklistStoreを渡した場合、レビュー結果を記事ファイルごとに記録する', async () => {
    byStep((step) => {
      if (step === 'PROOFREADING') {
        return { suggestions: [{ stepKey: step, originalText: 'AはB', message: '誤り', suggestion: null, sources: [] }], skipped: false };
      }
      return { suggestions: [], skipped: false };
    });
    const store = new ReviewChecklistStore(context);
    const document = makeDocument(ARTICLE);

    await new ProofreadController(context, store).runManual(document as never);

    const recorded = store.get(document.uri.toString());
    expect(recorded?.bodyHash).toEqual(expect.any(String));
    expect(recorded?.skippedSteps ?? []).toEqual([]);
    const findings = recorded?.items ?? [];
    expect(findings).toHaveLength(1);
    expect(findings[0].originalText).toBe('AはB');
  });

  it('スキップされたステップを、理由つきでrecordReviewへ渡す(issue #1545 AC1)', async () => {
    byStep((step) =>
      step === 'PROOFREADING'
        ? { suggestions: [], skipped: true, skipReason: '本文が短いため' }
        : { suggestions: [], skipped: false });
    const store = new ReviewChecklistStore(context);

    const document = makeDocument(ARTICLE);
    await new ProofreadController(context, store).runManual(document as never);

    const skipped = store.get(document.uri.toString())?.skippedSteps ?? [];
    expect(skipped).toHaveLength(1);
    expect(skipped[0].stepKey).toBe('PROOFREADING');
    expect(skipped[0].reason).toBe('本文が短いため');
  });

  it('checklistStoreを渡さない場合も、従来どおり動作する(後方互換)', async () => {
    await expect(controllerWith().runManual(makeDocument(ARTICLE) as never)).resolves.toBeUndefined();
  });
});

describe('ProofreadController.reviewForPublish(issue #1217: Publish直前のレビュー)', () => {
  function suggestionFor(step: string, message: string): unknown {
    return { suggestions: [{ stepKey: step, originalText: 'AはB', message, suggestion: null, sources: [] }], skipped: false };
  }

  /** 実物の ReviewChecklistStore を、メモリ上の workspaceState(実行基盤の代替)で動かす。 */
  function fakeStore(): ReviewChecklistStore {
    return new ReviewChecklistStore(context);
  }

  it('未レビューならレビューを実行し、未対応の指摘があればブロックして件数を返す', async () => {
    byStep((step) =>
      step === 'PROOFREADING' ? suggestionFor(step, '誤り') : { suggestions: [], skipped: false });
    const controller = new ProofreadController(context, fakeStore());

    const outcome = await controller.reviewForPublish(makeDocument(ARTICLE) as never);

    expect(requestedSteps()).toHaveLength(REVIEW_STEPS.length);
    expect(outcome).toEqual({ blocked: true, unresolvedCount: 1, reviewed: true });
  });

  it('本文が変わっていなければ2回目はレビューAPIを呼ばず、保持した結果を使う', async () => {
    byStep((step) =>
      step === 'PROOFREADING' ? suggestionFor(step, '誤り') : { suggestions: [], skipped: false });
    const controller = new ProofreadController(context, fakeStore());
    const document = makeDocument(ARTICLE);
    await controller.reviewForPublish(document as never);
    server.recorded.length = 0;

    const outcome = await controller.reviewForPublish(document as never);

    expect(requestedSteps()).toEqual([]);
    expect(outcome).toEqual({ blocked: true, unresolvedCount: 1, reviewed: false });
  });

  it('front matterだけが変わっても再実行しない(判定は本文だけで行う)', async () => {
    const controller = new ProofreadController(context, fakeStore());
    await controller.reviewForPublish(makeDocument(ARTICLE) as never);
    server.recorded.length = 0;

    await controller.reviewForPublish(makeDocument(ARTICLE.replace('status: draft', 'status: publish')) as never);

    expect(requestedSteps()).toEqual([]);
  });

  it('本文が変わっていれば再実行する', async () => {
    const controller = new ProofreadController(context, fakeStore());
    await controller.reviewForPublish(makeDocument(ARTICLE) as never);
    server.recorded.length = 0;

    await controller.reviewForPublish(makeDocument(`${ARTICLE}追記です。`) as never);

    expect(requestedSteps()).toHaveLength(REVIEW_STEPS.length);
  });

  it('実行中は進捗通知を出す', async () => {
    const controller = new ProofreadController(context, fakeStore());
    await controller.reviewForPublish(makeDocument(ARTICLE) as never);
    expect(progressRuns.length).toBeGreaterThan(0);
  });

  it('本文が空ならAPIを呼ばずブロックしない', async () => {
    const controller = new ProofreadController(context, fakeStore());
    const outcome = await controller.reviewForPublish(makeDocument('---\ntitle: t\n---\n') as never);
    expect(requestedSteps()).toEqual([]);
    expect(outcome.blocked).toBe(false);
  });

  it('チェックリストの保存先が無いとレビュー結果を判定に使えないため失敗する(投稿を通さない)', async () => {
    await expect(controllerWith().reviewForPublish(makeDocument(ARTICLE) as never)).rejects.toThrow(
      'レビュー結果'
    );
  });

  it('レビュー中に別のレビューが始まって結果が反映されなかった場合も失敗する(投稿を通さない)', async () => {
    const store = fakeStore();
    const controller = new ProofreadController(context, store);
    const document = makeDocument(ARTICLE);
    let release: () => void = () => undefined;
    stepHandler.mockImplementationOnce(
      () => new Promise<Reply>((resolve) => { release = () => resolve(jsonReply(NO_FINDINGS)); })
    );
    // 後続の実行が先行の要求を中断するので、先行の失敗は release() より前に起きる。早めに受け止める。
    const first = controller.reviewForPublish(document as never).then(
      () => undefined,
      (error: Error) => error
    );
    await waitFor(() => requestedSteps().length === 1);
    await controller.runManual(document as never);
    release();
    expect((await first)?.message).toContain('レビュー結果');
  });
});

describe('ProofreadController.reviewForPreview(issue #1226: プレビュー直前のレビュー)', () => {
  function suggestionFor(step: string, message: string): unknown {
    return { suggestions: [{ stepKey: step, originalText: 'AはB', message, suggestion: null, sources: [] }], skipped: false };
  }

  /** 実物の ReviewChecklistStore を、メモリ上の workspaceState(実行基盤の代替)で動かす。 */
  function fakeStore(): ReviewChecklistStore {
    return new ReviewChecklistStore(context);
  }

  it('未レビューならレビューを実行し、未対応の件数を返す', async () => {
    byStep((step) =>
      step === 'PROOFREADING' ? suggestionFor(step, '誤り') : { suggestions: [], skipped: false });
    const controller = new ProofreadController(context, fakeStore());

    const outcome = await controller.reviewForPreview(makeDocument(ARTICLE) as never);

    expect(requestedSteps()).toHaveLength(REVIEW_STEPS.length);
    expect(outcome).toEqual({ unresolvedCount: 1, reviewed: true, failed: false });
    expect(progressRuns.length).toBeGreaterThan(0);
  });

  it('Publishでレビュー済みで本文が同じなら、プレビューではAPIを呼ばない(判定はPublishと同じ)', async () => {
    const controller = new ProofreadController(context, fakeStore());
    const document = makeDocument(ARTICLE);
    await controller.reviewForPublish(document as never);
    server.recorded.length = 0;

    const outcome = await controller.reviewForPreview(document as never);

    expect(requestedSteps()).toEqual([]);
    expect(outcome.reviewed).toBe(false);
  });

  it('レビュー結果を保持できなくても例外にせず失敗として返す(プレビューを止めない)', async () => {
    const outcome = await controllerWith().reviewForPreview(makeDocument(ARTICLE) as never);
    expect(outcome.failed).toBe(true);
  });
});
