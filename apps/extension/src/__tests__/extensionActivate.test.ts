import {
  diagnosticCollections,
  executedCommands,
  fireEvent,
  outputChannels,
  registeredCommands,
  registeredProviders,
  shownErrors,
  CodeActionKind,
} from '../__mocks__/vscode';
import { deactivate } from '../extension';
import { logger, LogLevel } from '../logger';
import { setConfiguration } from '../__mocks__/vscode';
import { Harness, startExtension } from './support/extensionHarness';
import { FakeEditor } from './support/fakeEditor';
import { jsonReply } from './support/localHttpServer';
import { waitFor } from './support/panelKit';

/**
 * 拡張の有効化(activate)が、全コマンド・補完・イベント購読・差し戻し確認を登録し、
 * 購読したイベントが実際の処理へつながること。外部通信は実サーバー、VS Code は代替。
 */
let harness: Harness | undefined;

afterEach(async () => {
  jest.useRealTimers();
  logger.setSink(undefined);
  logger.setMinLevel(LogLevel.Info);
  await harness?.stop();
  harness = undefined;
});

const EXPECTED_COMMANDS = [
  'letsBlog.createArticle',
  'letsBlog.createArticleWithoutAi',
  'letsBlog.schedulePublication',
  'letsBlog.login',
  'letsBlog.logout',
  'letsBlog.selectSite',
  'letsBlog.publish',
  'letsBlog.submitArticle',
  'letsBlog.resubmitArticle',
  'letsBlog.showRejectionFindings',
  'letsBlog.checkRejections',
  'letsBlog.deletePost',
  'letsBlog.askAi',
  'letsBlog.suggestTags',
  'letsBlog.switchAiProvider',
  'letsBlog.generateImage',
  'letsBlog.imageGallery',
  'letsBlog.addNewDiagram',
  'letsBlog.editDiagram',
  'letsBlog.diagramGallery',
  'letsBlog.generateSection',
  'letsBlog.askAiSearch',
  'letsBlog.selectProject',
  'letsBlog.planArticle',
  'letsBlog.previewArticle',
  'letsBlog.previewDevTools',
  'letsBlog.pasteSmartCard',
  'letsBlog.pasteAsLink',
  'letsBlog.proofreadNow',
  'letsBlog.reviewChecklist',
  'letsBlog.fixInvalidStatus',
  'letsBlog.removeInvalidCategory',
];

function useFakeTimers(): void {
  jest.useFakeTimers({
    doNotFake: ['Date', 'setImmediate', 'clearImmediate', 'nextTick', 'queueMicrotask', 'performance'],
  });
}

describe('activate: 登録', () => {
  it('package.json が宣言するコマンドをすべて登録する', async () => {
    harness = await startExtension();

    expect([...registeredCommands.keys()].sort()).toEqual([...EXPECTED_COMMANDS].sort());
  });

  it('markdown にクイックフィックスの提供元と、frontmatter・本文タグの補完を登録する', async () => {
    harness = await startExtension();

    const codeActions = registeredProviders.filter((p) => p.kind === 'codeActions');
    expect(codeActions).toHaveLength(1);
    expect(codeActions[0].selector).toEqual({ language: 'markdown' });
    expect(codeActions[0].extra).toEqual([{ providedCodeActionKinds: [CodeActionKind.QuickFix] }]);

    const completions = registeredProviders.filter((p) => p.kind === 'completion');
    expect(completions.map((p) => p.extra)).toEqual([[' ', '-', ':'], ['[']]);
    expect(completions.every((p) => (p.selector as { language: string }).language === 'markdown')).toBe(true);
  });

  it('有効化をログに残し、debugMode の設定を反映する', async () => {
    const lines: string[] = [];
    logger.setSink({ appendLine: (line) => lines.push(line) });
    setConfiguration('letsBlog.debugMode', true);

    harness = await startExtension();
    logger.debug('詳細ログ');

    expect(lines.some((l) => l.includes("Let's Blog 拡張を有効化しました。"))).toBe(true);
    expect(lines.some((l) => l.includes('詳細ログ'))).toBe(false);
  });

  it('previewDevTools は、プレビューが開いていなければ案内する', async () => {
    harness = await startExtension();

    await harness.run('letsBlog.previewDevTools');

    expect(shownErrors).toEqual(['プレビューを開いてから実行してください。']);
  });

  it('deactivate は何もしない', () => {
    expect(deactivate()).toBeUndefined();
  });
});

describe('activate: 設定変更の購読', () => {
  it('debugMode が変わったときだけログレベルを読み直す', async () => {
    const lines: string[] = [];
    harness = await startExtension();
    logger.setSink({ appendLine: (line) => lines.push(line) });
    logger.setMinLevel(LogLevel.Info);

    setConfiguration('letsBlog.debugMode', true);
    fireEvent('workspace.onDidChangeConfiguration', { affectsConfiguration: (key: string) => key === 'letsBlog.serverUrl' });
    logger.debug('まだ出ない');
    fireEvent('workspace.onDidChangeConfiguration', { affectsConfiguration: (key: string) => key === 'letsBlog.debugMode' });
    logger.debug('出る');

    expect(lines.map((l) => l.replace(/^\[[^\]]*\] /, ''))).toEqual(['[DEBUG] 出る']);
  });

  it('差し戻し確認の間隔が変わったら、新しい間隔で定期確認をやり直す', async () => {
    useFakeTimers();
    harness = await startExtension({ loggedInAtActivation: true });
    const myReviews = (): number => harness!.requests('GET /api/projects/7/article-review/my-reviews').length;
    harness.api({ 'GET /api/projects/7/article-review/my-reviews': jsonReply([]) });
    await waitFor(() => myReviews() >= 1);
    const afterStart = myReviews();

    setConfiguration('letsBlog.rejectionPollIntervalMs', 10_000);
    fireEvent('workspace.onDidChangeConfiguration', {
      affectsConfiguration: (key: string) => key === 'letsBlog.rejectionPollIntervalMs',
    });
    await jest.advanceTimersByTimeAsync(10_000);
    await waitFor(() => myReviews() > afterStart);

    expect(myReviews()).toBe(afterStart + 1);
  });
});

describe('activate: エディタ・文書のイベント', () => {
  const ARTICLE = '---\ntitle: t\nstatus: draft\n---\n本文';

  it('文書を開く・編集すると front matter の検証を予約し、閉じると取り消す', async () => {
    useFakeTimers();
    harness = await startExtension();
    harness.api({ 'GET /api/metadata/post-statuses': jsonReply([{ value: 'draft', label: '下書き' }]) });
    const editor = new FakeEditor(ARTICLE);
    const invalidStatusCodes = (): unknown[] =>
      (diagnosticCollections[0]?.get(editor.document.uri.toString()) ?? []).map((d) => d.code);

    // 開いた文書は、デバウンスの後に検証される(status は有効なので診断なし)。
    fireEvent('workspace.onDidOpenTextDocument', editor.document);
    await jest.advanceTimersByTimeAsync(500);
    await waitFor(() => diagnosticCollections[0]?.has(editor.document.uri.toString()) === true);
    expect(invalidStatusCodes()).toEqual([]);

    // 編集後の内容が検証される。
    editor.text = ARTICLE.replace('status: draft', 'status: bogus');
    fireEvent('workspace.onDidChangeTextDocument', { document: editor.document });
    await jest.advanceTimersByTimeAsync(500);
    await waitFor(() => invalidStatusCodes().length > 0);
    expect(invalidStatusCodes()).toEqual(['invalid-status']);

    // 編集の直後に閉じると、診断は消え、予約した検証も行われない(再び診断が付かない)。
    fireEvent('workspace.onDidChangeTextDocument', { document: editor.document });
    fireEvent('workspace.onDidCloseTextDocument', editor.document);
    await jest.advanceTimersByTimeAsync(1_000);
    expect(invalidStatusCodes()).toEqual([]);
  });

  it('表示中のエディタが変わると、保持している校正の表示を各エディタへ置き直す', async () => {
    harness = await startExtension();
    const editor = new FakeEditor(ARTICLE);

    expect(() => fireEvent('window.onDidChangeVisibleTextEditors', [editor])).not.toThrow();
    expect(editor.setDecorations).not.toHaveBeenCalled();
  });
});

describe('activate: カーソルがダイアグラム参照の行にあるかのコンテキスト', () => {
  const DIAGRAM_LINE = '![図](assets/diagram-3-1700000000000.svg)';

  function setContextCalls(): boolean[] {
    return executedCommands
      .filter((c) => c.command === 'setContext' && c.args[0] === 'letsBlog.cursorOnDiagram')
      .map((c) => c.args[1] as boolean);
  }

  it('起動時にエディタが無ければ false を設定し、値が変わったときだけ設定し直す', async () => {
    harness = await startExtension();
    const editor = new FakeEditor(`本文\n${DIAGRAM_LINE}\n`);
    expect(setContextCalls()).toEqual([false]);

    editor.setCursorLine(0);
    fireEvent('window.onDidChangeTextEditorSelection', { textEditor: editor });
    expect(setContextCalls()).toEqual([false]);

    editor.setCursorLine(1);
    fireEvent('window.onDidChangeTextEditorSelection', { textEditor: editor });
    fireEvent('window.onDidChangeTextEditorSelection', { textEditor: editor });
    expect(setContextCalls()).toEqual([false, true]);

    fireEvent('window.onDidChangeActiveTextEditor', undefined);
    expect(setContextCalls()).toEqual([false, true, false]);
  });

  it('markdown 以外のエディタでは、ダイアグラム参照の行でも false のまま', async () => {
    harness = await startExtension();
    const editor = new FakeEditor(DIAGRAM_LINE, '/workspace/notes.txt', 'plaintext');

    fireEvent('window.onDidChangeActiveTextEditor', editor);

    expect(setContextCalls()).toEqual([false]);
  });

  it('起動時に markdown のエディタがダイアグラム参照の行にあれば、すぐ true を設定する', async () => {
    const editor = new FakeEditor(DIAGRAM_LINE);
    harness = await startExtension({ activeEditor: editor });

    expect(setContextCalls()).toEqual([true]);
  });
});

describe('activate: 無効化', () => {
  it('購読を破棄するとログの出力先も破棄される', async () => {
    harness = await startExtension();
    logger.setSink(undefined);
    logger.info('出力チャンネルを作らせる');
    const channel = outputChannels[outputChannels.length - 1];
    expect(channel.disposed).toBe(false);

    for (const subscription of harness.context.subscriptions) {
      (subscription as { dispose: () => void }).dispose();
    }

    expect(channel.disposed).toBe(true);
  });
});
