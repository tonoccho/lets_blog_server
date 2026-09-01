import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import * as vscode from 'vscode';
import { lastCreatedWebviewPanel, resetMocks } from '../__mocks__/vscode';
import { showSingletonPanel, WebviewPanelBase } from '../webviewPanelBase';
import { CancelledError } from '../errorHandler';
import { WebviewMessageBase } from '../webviewMessages';

/**
 * Webviewパネルの状態遷移(生成 → 購読 → ディスパッチ → 中断 → 破棄)の検証
 * (issue #942 / AT-16 Layer 2)。パネルはUI操作を伴うため受け入れテストでは自動化しない
 * (apps/extension/MANUAL_ACCEPTANCE_CHECKLIST.md)。その代わり、UIに依らない骨格をここで固定する。
 */

type TestInbound = WebviewMessageBase<'run' | 'boom' | 'cancelMe'>;

let extensionRoot: string;

class TestPanel extends WebviewPanelBase<TestInbound, 'done' | 'error' | 'cancelled'> {
  public readonly handled: string[] = [];
  /** handleMessage が投げる例外(テストごとに差し替える)。 */
  public failure: Error | undefined;

  constructor(context: vscode.ExtensionContext) {
    super(context, { viewType: 'letsBlog.test', title: 'テスト', assetName: 'test' });
  }

  protected async handleMessage(message: TestInbound): Promise<void> {
    this.handled.push(message.command);
    if (this.failure) throw this.failure;
    this.postMessage('done', { command: message.command });
  }

  /** 中断可能な処理を外から起動するための入口(protectedメンバーの検証用)。 */
  public run<T>(operation: (signal: AbortSignal) => Promise<T>): Promise<T> {
    return this.runCancellable(operation);
  }

  public cancel(): void {
    this.cancelCurrentOperation();
  }
}

function createContext(): vscode.ExtensionContext {
  return { extensionUri: `file://${extensionRoot}` } as unknown as vscode.ExtensionContext;
}

beforeAll(() => {
  extensionRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'letsblog-webview-'));
  fs.mkdirSync(path.join(extensionRoot, 'webviews'), { recursive: true });
  fs.writeFileSync(
    path.join(extensionRoot, 'webviews', 'test.html'),
    [
      '<meta http-equiv="Content-Security-Policy" content="{{csp}}">',
      '{{sharedStyles}}',
      '<link rel="stylesheet" href="{{styleUri}}">',
      '{{sharedScripts}}',
      '<script nonce="{{nonce}}" src="{{scriptUri}}"></script>',
    ].join('\n'),
    'utf-8'
  );
});

afterAll(() => {
  fs.rmSync(extensionRoot, { recursive: true, force: true });
});

afterEach(() => {
  resetMocks();
});

describe('WebviewPanelBase', () => {
  it('HTMLのプレースホルダへCSP・nonce・資材URIを差し込む', () => {
    const panel = new TestPanel(createContext());
    const html = lastCreatedWebviewPanel?.webview.html ?? '';

    expect(html).toContain("default-src 'none'");
    expect(html).toContain("img-src vscode-webview://mock data: https:");
    expect(html).toContain('webviews/test.css');
    expect(html).toContain('webviews/test.js');
    expect(html).toContain('webviews/loadingIndicator.css');
    expect(html).toContain('webviews/loadingIndicator.js');

    const nonce = /nonce="([^"]+)"/.exec(html)?.[1];
    expect(nonce).toBeTruthy();
    expect(html).toContain(`script-src 'nonce-${nonce}'`);
    panel.close();
  });

  it('追加のCSPディレクティブを指定するとCSPへ連結される', () => {
    const context = createContext();
    const panel = new (class extends WebviewPanelBase<TestInbound, 'done'> {
      constructor() {
        super(context, {
          viewType: 'letsBlog.framed',
          title: '枠付き',
          assetName: 'test',
          extraCspDirectives: ['frame-src https://drawio.example'],
        });
      }
      protected async handleMessage(): Promise<void> {
        // このテストではメッセージ処理を検証しない。
      }
    })();

    expect(lastCreatedWebviewPanel?.webview.html).toContain('frame-src https://drawio.example');
    panel.close();
  });

  it('Webviewからのメッセージを処理し、結果をWebviewへ返す', async () => {
    const panel = new TestPanel(createContext());
    lastCreatedWebviewPanel?.webview.postMessageToExtension({ command: 'run' });
    await Promise.resolve();

    expect(panel.handled).toEqual(['run']);
    expect(lastCreatedWebviewPanel?.webview.posted).toEqual([
      { command: 'done', payload: { command: 'run' } },
    ]);
    panel.close();
  });

  it('処理が失敗したときはerrorメッセージへ整形して返す', async () => {
    const panel = new TestPanel(createContext());
    panel.failure = new Error('壊れました');

    lastCreatedWebviewPanel?.webview.postMessageToExtension({ command: 'boom' });
    await Promise.resolve();
    await Promise.resolve();

    const posted = lastCreatedWebviewPanel?.webview.posted ?? [];
    expect(posted).toHaveLength(1);
    const message = posted[0] as { command: string; payload: { error: string } };
    expect(message.command).toBe('error');
    expect(message.payload.error).toContain('壊れました');
    panel.close();
  });

  it('Error以外が投げられてもerrorメッセージとして返す(スタックは付かない)', async () => {
    const panel = new TestPanel(createContext());
    // 例外はライブラリから文字列で投げられることもある。
    panel.failure = '文字列の例外' as unknown as Error;

    lastCreatedWebviewPanel?.webview.postMessageToExtension({ command: 'boom' });
    await Promise.resolve();
    await Promise.resolve();

    const message = (lastCreatedWebviewPanel?.webview.posted ?? [])[0] as {
      command: string;
      payload: { error: string };
    };
    expect(message.command).toBe('error');
    expect(message.payload.error).toContain('文字列の例外');
    panel.close();
  });

  it('利用者による中断はエラーではなくcancelledとして返す', async () => {
    const panel = new TestPanel(createContext());
    panel.failure = new CancelledError('中断しました');

    lastCreatedWebviewPanel?.webview.postMessageToExtension({ command: 'cancelMe' });
    await Promise.resolve();
    await Promise.resolve();

    expect(lastCreatedWebviewPanel?.webview.posted).toEqual([{ command: 'cancelled', payload: {} }]);
    panel.close();
  });

  it('cancelCurrentOperationで実行中の処理のsignalが中断される', async () => {
    const panel = new TestPanel(createContext());
    let observed: AbortSignal | undefined;
    const pending = panel.run((signal) => {
      observed = signal;
      return new Promise<string>((resolve) => signal.addEventListener('abort', () => resolve('aborted')));
    });

    panel.cancel();
    await expect(pending).resolves.toBe('aborted');
    expect(observed?.aborted).toBe(true);
    panel.close();
  });

  it('新しい処理を始めると前の処理が中断される', async () => {
    const panel = new TestPanel(createContext());
    let firstSignal: AbortSignal | undefined;
    const first = panel.run((signal) => {
      firstSignal = signal;
      return new Promise<string>((resolve) => signal.addEventListener('abort', () => resolve('aborted')));
    });

    const second = await panel.run(async () => 'second');

    await expect(first).resolves.toBe('aborted');
    expect(firstSignal?.aborted).toBe(true);
    expect(second).toBe('second');
    panel.close();
  });

  it('処理が終わると保持していたコントローラを手放す(後からのキャンセルで落ちない)', async () => {
    const panel = new TestPanel(createContext());
    await panel.run(async () => 'ok');
    expect(() => panel.cancel()).not.toThrow();
    panel.close();
  });
});

describe('showSingletonPanel', () => {
  afterEach(() => resetMocks());

  it('同じviewTypeでは既存のパネルを前面に出し、新しく作らない', () => {
    const context = createContext();
    let created = 0;
    const factory = () => {
      created += 1;
      return new TestPanel(context);
    };

    const first = showSingletonPanel('letsBlog.test', factory);
    const second = showSingletonPanel('letsBlog.test', factory);

    expect(created).toBe(1);
    expect(second).toBe(first);
    first.close();
  });

  it('閉じたあとは同じviewTypeでも新しく作り直せる', () => {
    const context = createContext();
    const first = showSingletonPanel('letsBlog.test', () => new TestPanel(context));
    first.close();

    const second = showSingletonPanel('letsBlog.test', () => new TestPanel(context));
    expect(second).not.toBe(first);
    second.close();
  });

  it('VSCode側でパネルが閉じられた場合も生存管理から取り除かれる', () => {
    const context = createContext();
    const first = showSingletonPanel('letsBlog.test', () => new TestPanel(context));
    // VSCodeがタブを閉じた状況(onDidDispose)を再現する。
    lastCreatedWebviewPanel?.fireDispose();

    const second = showSingletonPanel('letsBlog.test', () => new TestPanel(context));
    expect(second).not.toBe(first);
    second.close();
  });
});
