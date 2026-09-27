import {
  decorationTypes,
  diagnosticCollections,
  progressRuns,
  resetMocks,
  setConfiguration,
  setVisibleTextEditors,
  Range,
} from '../__mocks__/vscode';
import { ProofreadController } from '../proofreadDiagnostics';
import * as api from '../apiClient';
import { REVIEW_STEPS } from '../proofreadLogic';

jest.mock('../apiClient', () => ({
  reviewStepSuggestions: jest.fn(),
  getPostStatuses: jest.fn(),
  listExistingCategories: jest.fn(),
}));
jest.mock('../config', () => ({
  getAccessToken: jest.fn(async () => 'token'),
  getActor: jest.fn(async () => undefined),
  getProjectId: jest.fn(() => 7),
  requireProjectId: jest.fn(() => 7),
}));

import * as config from '../config';

const mocked = api as jest.Mocked<typeof api>;

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

const context = {} as never;
const ARTICLE = '---\ntitle: t\nstatus: draft\n---\nAはBです。CはDです。';

function controllerWith(): ProofreadController {
  return new ProofreadController(context);
}

/** decorationTypes[i] に最後に置かれた装飾を editor から取り出す。 */
function lastDecorations(editor: FakeEditor, type: unknown): { range: Range; hoverMessage: { value: string } }[] {
  const calls = editor.setDecorations.mock.calls.filter((c) => c[0] === type);
  return calls[calls.length - 1][1];
}

beforeEach(() => {
  resetMocks();
  jest.resetAllMocks();
  (config.getAccessToken as jest.Mock).mockResolvedValue('token');
  (config.getActor as jest.Mock).mockResolvedValue(undefined);
  (config.getProjectId as jest.Mock).mockReturnValue(7);
  (config.requireProjectId as jest.Mock).mockReturnValue(7);
  mocked.getPostStatuses.mockResolvedValue([{ value: 'draft' }] as never);
  mocked.listExistingCategories.mockResolvedValue([]);
  mocked.reviewStepSuggestions.mockResolvedValue({ suggestions: [], skipped: false } as never);
});

describe('ProofreadController.runManual(5ステップのレビュー)', () => {
  it('5ステップを日本語チェック→…→文体チェックの順に、projectIdとステップキーを指定して呼ぶ', async () => {
    const controller = controllerWith();
    await controller.runManual(makeDocument(ARTICLE) as never);

    expect(mocked.reviewStepSuggestions.mock.calls.map((c) => c[3])).toEqual([
      'JAPANESE',
      'PROOFREADING',
      'FACT_CHECK',
      'READER_PERSPECTIVE',
      'STYLE',
    ]);
    for (const call of mocked.reviewStepSuggestions.mock.calls) {
      expect(call[2]).toBe(7);
      expect(call[4]).toBe('AはBです。CはDです。');
    }
  });

  it('front matterのproject_idがあれば選択中のプロジェクトより優先する', async () => {
    const text = '---\ntitle: t\nproject_id: 99\n---\n本文';
    await controllerWith().runManual(makeDocument(text) as never);
    expect(mocked.reviewStepSuggestions.mock.calls[0][2]).toBe(99);
    expect(config.requireProjectId).not.toHaveBeenCalled();
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
    mocked.reviewStepSuggestions.mockImplementation((async (_k: string, _a: unknown, _p: number, step: string) => {
      if (step === 'PROOFREADING') {
        return { suggestions: [{ stepKey: step, originalText: 'AはB', message: '誤り', suggestion: 'XはY', sources: [] }], skipped: false };
      }
      if (step === 'STYLE') {
        return { suggestions: [{ stepKey: step, originalText: 'CはD', message: '文体', suggestion: null, sources: [] }], skipped: false };
      }
      return { suggestions: [], skipped: false };
    }) as never);

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

  it('再実行すると前回の装飾は新しい結果で置き換わり、装飾タイプは作り直さない', async () => {
    const document = makeDocument(ARTICLE);
    const editor = makeEditor(document);
    setVisibleTextEditors([editor]);
    const controller = controllerWith();
    mocked.reviewStepSuggestions.mockResolvedValueOnce({
      suggestions: [{ stepKey: 'JAPANESE', originalText: 'AはB', message: 'm', suggestion: null, sources: [] }],
      skipped: false,
    } as never);
    await controller.runManual(document as never);
    expect(lastDecorations(editor, decorationTypes[0])).toHaveLength(1);

    mocked.reviewStepSuggestions.mockResolvedValue({ suggestions: [], skipped: false } as never);
    await controller.runManual(document as never);
    expect(lastDecorations(editor, decorationTypes[0])).toEqual([]);
    expect(decorationTypes).toHaveLength(5);
  });

  it('本文が空ならAPIを呼ばず装飾を消す', async () => {
    const document = makeDocument('---\ntitle: t\n---\n   \n');
    const editor = makeEditor(document);
    setVisibleTextEditors([editor]);
    await controllerWith().runManual(document as never);

    expect(mocked.reviewStepSuggestions).not.toHaveBeenCalled();
    expect(lastDecorations(editor, decorationTypes[0])).toEqual([]);
  });

  it('未ログインなら案内つきで失敗する', async () => {
    (config.getAccessToken as jest.Mock).mockResolvedValue(undefined);
    await expect(controllerWith().runManual(makeDocument(ARTICLE) as never)).rejects.toThrow('ログインしていません');
    expect(mocked.reviewStepSuggestions).not.toHaveBeenCalled();
  });

  it('APIエラーは呼び出し元へ伝える', async () => {
    mocked.reviewStepSuggestions.mockRejectedValue(new Error('boom'));
    await expect(controllerWith().runManual(makeDocument(ARTICLE) as never)).rejects.toThrow('boom');
  });

  it('後から始まった実行があれば、古い実行は結果を反映せず黙って終わる', async () => {
    const document = makeDocument(ARTICLE);
    const editor = makeEditor(document);
    setVisibleTextEditors([editor]);
    const controller = controllerWith();
    let release: (v: unknown) => void = () => undefined;
    mocked.reviewStepSuggestions.mockImplementationOnce(
      () => new Promise((resolve) => (release = resolve)) as never
    );
    const first = controller.runManual(document as never);
    await new Promise((r) => setImmediate(r));
    mocked.reviewStepSuggestions.mockResolvedValue({ suggestions: [], skipped: false } as never);
    const second = controller.runManual(document as never);
    release({ suggestions: [{ stepKey: 'JAPANESE', originalText: 'AはB', message: 'm', suggestion: null, sources: [] }], skipped: false });
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
    mocked.reviewStepSuggestions.mockImplementation((async (_k: string, _a: unknown, _p: number, step: string) =>
      step === 'STYLE'
        ? new Promise((resolve) => (release = resolve))
        : { suggestions: [], skipped: false }) as never);
    const first = controller.runManual(document as never);
    for (let i = 0; i < 20; i++) await new Promise((r) => setImmediate(r));
    mocked.reviewStepSuggestions.mockResolvedValue({ suggestions: [], skipped: false } as never);
    await controller.runManual(document as never);
    release({
      suggestions: [{ stepKey: 'STYLE', originalText: 'AはB', message: 'm', suggestion: null, sources: [] }],
      skipped: false,
    });
    await first;
    expect(lastDecorations(editor, decorationTypes[4])).toEqual([]);
  });

  it('refreshEditorは保持している装飾を再表示する', async () => {
    const document = makeDocument(ARTICLE);
    setVisibleTextEditors([]);
    const controller = controllerWith();
    mocked.reviewStepSuggestions.mockResolvedValueOnce({
      suggestions: [{ stepKey: 'JAPANESE', originalText: 'AはB', message: 'm', suggestion: null, sources: [] }],
      skipped: false,
    } as never);
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
    mocked.reviewStepSuggestions.mockResolvedValueOnce({
      suggestions: [{ stepKey: 'JAPANESE', originalText: 'AはB', message: 'm', suggestion: null, sources: [] }],
      skipped: false,
    } as never);
    await controller.runManual(document as never);
    controller.clearDocument(document as never);
    const editor = makeEditor(document);
    controller.refreshEditor(editor as never);
    expect(editor.setDecorations).not.toHaveBeenCalled();

    controller.dispose();
    expect(decorationTypes.every((t) => t.disposed)).toBe(true);
  });
});

describe('ProofreadController.scheduleCheck(自動実行)', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it('本文を編集してもレビューAPIは呼ばれない(proofreadEnabledを有効にしていても)', async () => {
    setConfiguration('letsBlog.proofreadEnabled', true);
    setConfiguration('letsBlog.proofreadDebounceMs', 500);
    const controller = controllerWith();
    controller.scheduleCheck(makeDocument(ARTICLE) as never);
    await jest.advanceTimersByTimeAsync(60_000);

    expect(mocked.reviewStepSuggestions).not.toHaveBeenCalled();
    expect(progressRuns).toHaveLength(0);
  });

  it('markdown以外は何もしない', async () => {
    controllerWith().scheduleCheck(makeDocument(ARTICLE, 'file:///a.txt', 'plaintext') as never);
    await jest.advanceTimersByTimeAsync(5_000);
    expect(mocked.getPostStatuses).not.toHaveBeenCalled();
  });

  it('front matterの検証は編集の都度デバウンスして実行され、不正なstatusを診断とQuickFixで示す', async () => {
    mocked.getPostStatuses.mockResolvedValue([{ value: 'publish' }] as never);
    const controller = controllerWith();
    const document = makeDocument(ARTICLE);
    controller.scheduleCheck(document as never);
    controller.scheduleCheck(document as never); // 連続編集は1回にまとまる
    expect(mocked.getPostStatuses).not.toHaveBeenCalled();
    await jest.advanceTimersByTimeAsync(500);

    expect(mocked.getPostStatuses).toHaveBeenCalledTimes(1);
    const diagnostics = diagnosticCollections[0].get('file:///a.md') ?? [];
    expect(diagnostics.map((d) => d.code)).toContain('invalid-status');

    const actions = controller.provideCodeActions(document as never, undefined as never, {
      diagnostics: diagnostics.filter((d) => d.code === 'invalid-status'),
    } as never);
    expect(actions.map((a) => a.command?.command)).toEqual(['letsBlog.fixInvalidStatus']);
  });

  it('過去日時のpublish_scheduled_atと存在しないカテゴリも診断とQuickFixで示す', async () => {
    mocked.listExistingCategories.mockResolvedValue(['tech']);
    (config.getActor as jest.Mock).mockResolvedValue({ id: 1 });
    const text = '---\ntitle: t\nstatus: draft\npublish_scheduled_at: 2000-01-01T00:00:00Z\ncategories: [news]\n---\n本文';
    const controller = controllerWith();
    const document = makeDocument(text);
    controller.scheduleCheck(document as never);
    await jest.advanceTimersByTimeAsync(500);

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
    (config.getAccessToken as jest.Mock).mockResolvedValue(undefined);
    controllerWith().scheduleCheck(makeDocument(ARTICLE) as never);
    await jest.advanceTimersByTimeAsync(500);
    expect(mocked.getPostStatuses).not.toHaveBeenCalled();

    (config.getAccessToken as jest.Mock).mockResolvedValue('token');
    (config.getActor as jest.Mock).mockResolvedValue({ id: 1 });
    mocked.getPostStatuses.mockRejectedValue(new Error('x'));
    mocked.listExistingCategories.mockRejectedValue(new Error('y'));
    const text = '---\ntitle: t\ncategories: [a]\n---\n本文';
    controllerWith().scheduleCheck(makeDocument(text, 'file:///b.md') as never);
    await jest.advanceTimersByTimeAsync(500);
    expect(mocked.listExistingCategories).toHaveBeenCalled();
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
    expect(mocked.getPostStatuses).toHaveBeenCalledTimes(1);
  });

  it('clearDocumentはfront matterの保留タイマーを取り消す', async () => {
    const controller = controllerWith();
    const document = makeDocument(ARTICLE);
    controller.scheduleCheck(document as never);
    controller.clearDocument(document as never);
    await jest.advanceTimersByTimeAsync(1_000);
    expect(mocked.getPostStatuses).not.toHaveBeenCalled();
    controller.dispose();
  });
});
