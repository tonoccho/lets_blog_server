/**
 * 'vscode' モジュールのテスト用スタブ。
 *
 * 拡張ホスト外では 'vscode' を解決できないため、テストが実際に使う範囲だけを再現する。
 * 設定値はテストから setConfiguration() で差し替えられる。
 */

const configurationValues = new Map<string, unknown>();

/** テストから設定値を差し込む(例: setConfiguration('letsBlog.serverUrl', 'https://example.test'))。 */
export function setConfiguration(key: string, value: unknown): void {
  configurationValues.set(key, value);
}

/** テスト間で状態が漏れないよう、差し込んだ設定と記録した呼び出しを消す。 */
export function resetMocks(): void {
  configurationValues.clear();
  configurationUpdates.length = 0;
  shownWarnings.length = 0;
  shownErrors.length = 0;
  lastCreatedWebviewPanel = undefined;
  warningResponse = undefined;
  openedDocuments.length = 0;
  workspaceFolders = undefined;
  appliedEdits.length = 0;
  shownInformations.length = 0;
  openedExternalUrls.length = 0;
  decorationTypes.length = 0;
  progressRuns.length = 0;
  diagnosticCollections.length = 0;
  visibleTextEditors = [];
  errorResponse = undefined;
  outputChannels.length = 0;
  // 拡張が作ったきり使い回している出力チャンネル(モジュールの変数に保持される)は、状態だけを初期化する。
  for (const channel of channelsByName.values()) {
    channel.lines.length = 0;
    channel.shown = 0;
    channel.disposed = false;
  }
  executedCommands.length = 0;
  registeredCommands.clear();
  eventListeners.clear();
  registeredProviders.length = 0;
  quickPickCalls.length = 0;
  quickPickQueue.length = 0;
  inputBoxCalls.length = 0;
  inputBoxQueue.length = 0;
  warningQueue.length = 0;
  informationQueue.length = 0;
  errorQueue.length = 0;
  activeTextEditor = undefined;
  progressStartsCancelled = false;
  clipboardText = '';
  showTextDocumentResolver = undefined;
  untitledDocuments.length = 0;
}

// --- 拡張ホストの対話・イベント基盤の代替(issue #1840) -----------------------------------------
// extension.ts は VS Code が呼び出すコマンド・イベントの登録先、利用者との対話(QuickPick・入力欄・
// 通知への返答)、アクティブエディタ、クリップボードに依存する。これらは拡張ホストの外に実体が無いので、
// 登録を記録し、利用者の返答はテストが順番に差し込む。

/** registerCommand で登録された(コマンドID → 実行関数)。VS Code が利用者の操作で呼ぶ入口。 */
export const registeredCommands = new Map<string, (...args: any[]) => unknown>();

/** 利用者の操作(コマンドパレットなど)で登録済みコマンドを実行する。 */
export function runRegisteredCommand(command: string, ...args: unknown[]): Promise<unknown> {
  const handler = registeredCommands.get(command);
  if (!handler) throw new Error(`コマンド ${command} は登録されていません`);
  return Promise.resolve(handler(...args));
}

/** onDid... で登録されたイベントの購読者(イベント名 → 購読関数)。 */
const eventListeners = new Map<string, ((event: any) => void)[]>();

function onEvent(name: string) {
  return (listener: (event: any) => void): { dispose: () => void } => {
    const list = eventListeners.get(name) ?? [];
    list.push(listener);
    eventListeners.set(name, list);
    return {
      dispose: () => {
        const current = eventListeners.get(name) ?? [];
        eventListeners.set(name, current.filter((l) => l !== listener));
      },
    };
  };
}

/** VS Code がイベントを発火した状況を再現する。 */
export function fireEvent(name: string, event?: unknown): void {
  for (const listener of [...(eventListeners.get(name) ?? [])]) listener(event);
}

/** registerCodeActionsProvider / registerCompletionItemProvider の登録内容。 */
export const registeredProviders: { kind: 'codeActions' | 'completion'; selector: unknown; provider: any; extra: unknown[] }[] = [];

/** showQuickPick の呼び出し記録と、利用者の返答(呼び出しごとに 1 つ消費。無ければ「閉じた」)。 */
export const quickPickCalls: { items: any[]; options?: any }[] = [];
const quickPickQueue: ((items: any[], options?: any) => unknown)[] = [];
export function queueQuickPick(responder: (items: any[], options?: any) => unknown): void {
  quickPickQueue.push(responder);
}

/** showInputBox の呼び出し記録と、利用者の返答。 */
export const inputBoxCalls: { options?: any }[] = [];
const inputBoxQueue: ((options?: any) => string | undefined)[] = [];
export function queueInputBox(responder: (options?: any) => string | undefined): void {
  inputBoxQueue.push(responder);
}

/** 通知(警告・情報・エラー)の選択肢への返答。呼び出しごとに 1 つ消費し、無ければ既定の返答を使う。 */
const warningQueue: (string | undefined)[] = [];
const informationQueue: (string | undefined)[] = [];
const errorQueue: (string | undefined)[] = [];
export function queueWarningResponse(value: string | undefined): void {
  warningQueue.push(value);
}
export function queueInformationResponse(value: string | undefined): void {
  informationQueue.push(value);
}
export function queueErrorResponse(value: string | undefined): void {
  errorQueue.push(value);
}

/** true なら、withProgress の task が始まる時点で利用者が既にキャンセルしている状況にする。 */
let progressStartsCancelled = false;
export function setProgressStartsCancelled(value: boolean): void {
  progressStartsCancelled = value;
}

/** window.activeTextEditor の差し替え口。 */
let activeTextEditor: unknown;
export function setActiveTextEditor(editor: unknown): void {
  activeTextEditor = editor;
}

/** env.clipboard.readText が返すクリップボードの内容。 */
let clipboardText = '';
export function setClipboardText(text: string): void {
  clipboardText = text;
}

/** showTextDocument が返すエディタを、開かれた文書から決める差し口(既定は undefined)。 */
let showTextDocumentResolver: ((document: any) => unknown) | undefined;
export function setShowTextDocumentResolver(resolver: ((document: any) => unknown) | undefined): void {
  showTextDocumentResolver = resolver;
}

/** openTextDocument({content, language}) で作られた無題の文書。 */
export const untitledDocuments: { content: string; language?: string }[] = [];

/** vscode.Disposable の最小再現。 */
export class Disposable {
  constructor(private readonly callOnDispose?: () => void) {}
  dispose(): void {
    this.callOnDispose?.();
  }
  static from(...disposables: { dispose: () => unknown }[]): Disposable {
    return new Disposable(() => disposables.forEach((d) => d.dispose()));
  }
}

/** commands.executeCommand に渡された(コマンドID, 引数)の記録。 */
export const executedCommands: { command: string; args: unknown[] }[] = [];

/** vscode.commands の最小再現。実行は記録だけで、組み込みコマンドは動かさない。 */
export const commands = {
  registerCommand(command: string, handler: (...args: any[]) => unknown): { dispose: () => void } {
    registeredCommands.set(command, handler);
    return { dispose: () => registeredCommands.delete(command) };
  },
  executeCommand(command: string, ...args: unknown[]): Promise<unknown> {
    executedCommands.push({ command, args });
    const handler = registeredCommands.get(command);
    return Promise.resolve(handler ? handler(...args) : undefined);
  },
};

/** showErrorMessage が返す選択肢(未設定なら「閉じた」= undefined)。 */
let errorResponse: string | undefined;
export function setErrorResponse(value: string | undefined): void {
  errorResponse = value;
}

/** createOutputChannel が作った出力チャンネルの記録(書かれた行・表示・破棄の確認用)。 */
export const outputChannels: { name: string; lines: string[]; shown: number; disposed: boolean }[] = [];

/** 名前で引ける出力チャンネル(最後に作られたもの)。テストをまたいで保持される。 */
const channelsByName = new Map<string, { name: string; lines: string[]; shown: number; disposed: boolean }>();
export function findOutputChannel(name: string): { name: string; lines: string[]; shown: number; disposed: boolean } | undefined {
  return channelsByName.get(name);
}

/** showWarningMessage が返す選択肢(未設定なら「閉じた」= undefined)。 */
let warningResponse: string | undefined;
export function setWarningResponse(value: string | undefined): void {
  warningResponse = value;
}

/** openTextDocument / showTextDocument に渡されたパス。 */
export const openedDocuments: string[] = [];

/**
 * workspace.applyEdit に渡された編集内容の記録(issue #1104)。
 * 実ドキュメントを持たないため、編集を適用せず「何をどこへ書こうとしたか」だけを残す。
 */
export const appliedEdits: { kind: 'replace' | 'insert'; uri: string; text: string }[] = [];

/** env.openExternal に渡されたURL(外部ブラウザで開いたもの)。 */
export const openedExternalUrls: string[] = [];

/** vscode.env の最小再現。 */
export const env = {
  clipboard: { readText: (): Promise<string> => Promise.resolve(clipboardText) },
  openExternal(uri: { toString: () => string }): Promise<boolean> {
    openedExternalUrls.push(uri.toString());
    return Promise.resolve(true);
  },
};

/** showInformationMessage で表示した文言。 */
export const shownInformations: string[] = [];

/** workspace.workspaceFolders の差し替え口。 */
let workspaceFolders: { uri: { fsPath: string } }[] | undefined;
export function setWorkspaceFolders(folders: { uri: { fsPath: string } }[] | undefined): void {
  workspaceFolders = folders;
}

export const shownWarnings: string[] = [];
export const shownErrors: string[] = [];

/**
 * `update()` の呼び出し記録(issue #775)。書き込み先(ConfigurationTarget)まで検証できるように、
 * 値だけでなくtargetも保持する。
 */
export const configurationUpdates: { key: string; value: unknown; target: number | undefined }[] = [];

/** vscode.ConfigurationTarget の数値はVS Code APIの定義に合わせる。 */
export const ConfigurationTarget = {
  Global: 1,
  Workspace: 2,
  WorkspaceFolder: 3,
} as const;

export const workspace = {
  get workspaceFolders() {
    return workspaceFolders;
  },
  openTextDocument(target: string | { fsPath?: string; content?: string; language?: string }) {
    if (typeof target === 'string') {
      openedDocuments.push(target);
      return Promise.resolve({ fileName: target });
    }
    if (target.content !== undefined) {
      untitledDocuments.push({ content: target.content, language: target.language });
      return Promise.resolve({ fileName: '', content: target.content });
    }
    // Uri を渡された場合は、そのパスを開いたものとして記録する。
    openedDocuments.push(String(target.fsPath));
    return Promise.resolve({ fileName: String(target.fsPath), uri: target });
  },
  getConfiguration(section: string) {
    return {
      get<T>(key: string, defaultValue?: T): T | undefined {
        const full = `${section}.${key}`;
        return configurationValues.has(full) ? (configurationValues.get(full) as T) : defaultValue;
      },
      update(key: string, value: unknown, target?: number): Promise<void> {
        const full = `${section}.${key}`;
        configurationValues.set(full, value);
        configurationUpdates.push({ key: full, value, target });
        return Promise.resolve();
      },
    };
  },
  onDidChangeConfiguration: onEvent('workspace.onDidChangeConfiguration'),
  onDidChangeTextDocument: onEvent('workspace.onDidChangeTextDocument'),
  onDidOpenTextDocument: onEvent('workspace.onDidOpenTextDocument'),
  onDidCloseTextDocument: onEvent('workspace.onDidCloseTextDocument'),
  applyEdit(edit: WorkspaceEdit): Promise<boolean> {
    appliedEdits.push(...edit.entries);
    return Promise.resolve(true);
  },
};

/** vscode.Position / vscode.Range の最小再現(位置は文字オフセットで表す)。 */
export class Position {
  public readonly line: number;
  constructor(public readonly offset: number, public readonly character: number = 0) {
    this.line = offset;
  }
}

/** vscode.Selection の最小再現(anchor/active と、範囲としての start/end)。 */
export class Selection {
  public readonly start: unknown;
  public readonly end: unknown;
  constructor(public readonly anchor: unknown, public readonly active: unknown) {
    this.start = anchor;
    this.end = active;
  }
}

export const TextEditorRevealType = { Default: 0, InCenter: 1, InCenterIfOutsideViewport: 2, AtTop: 3 } as const;

export class Range {
  constructor(public readonly start: unknown, public readonly end: unknown) {}

  isEqual(other: Range): boolean {
    return JSON.stringify(this.start) === JSON.stringify(other.start) && JSON.stringify(this.end) === JSON.stringify(other.end);
  }
}

/** vscode.ThemeColor の最小再現。 */
export class ThemeColor {
  constructor(public readonly id: string) {}
}

/** vscode.MarkdownString の最小再現。 */
export class MarkdownString {
  constructor(public value: string = '') {}
}

export const DiagnosticSeverity = { Error: 0, Warning: 1, Information: 2, Hint: 3 } as const;

export class Diagnostic {
  source?: string;
  code?: string | number;
  constructor(public range: Range, public message: string, public severity?: number) {}
}

export const CodeActionKind = { QuickFix: 'quickfix' } as const;

export class CodeAction {
  diagnostics?: Diagnostic[];
  edit?: WorkspaceEdit;
  command?: { command: string; title: string; arguments?: unknown[] };
  constructor(public title: string, public kind?: string) {}
}

/** createDiagnosticCollection が返す診断コレクションの記録先(uri文字列 -> 診断)。 */
export const diagnosticCollections: Map<string, Diagnostic[]>[] = [];

export const languages = {
  registerCodeActionsProvider: (selector: unknown, provider: unknown, metadata?: unknown) => {
    registeredProviders.push({ kind: 'codeActions', selector, provider, extra: [metadata] });
    return { dispose: () => undefined };
  },
  registerCompletionItemProvider: (selector: unknown, provider: unknown, ...triggers: string[]) => {
    registeredProviders.push({ kind: 'completion', selector, provider, extra: triggers });
    return { dispose: () => undefined };
  },
  createDiagnosticCollection: () => {
    const store = new Map<string, Diagnostic[]>();
    diagnosticCollections.push(store);
    return {
      set: (uri: unknown, diagnostics: Diagnostic[]) => store.set(String(uri), diagnostics),
      delete: (uri: unknown) => store.delete(String(uri)),
      dispose: () => store.clear(),
    };
  },
};

/** createTextEditorDecorationType が作った装飾タイプの記録(色分けの検証用)。 */
export interface MockDecorationType {
  options: Record<string, unknown>;
  disposed: boolean;
  dispose: () => void;
}
export const decorationTypes: MockDecorationType[] = [];

/** withProgress に渡された設定と、task内で report された内容。 */
export const progressRuns: {
  options: Record<string, unknown>;
  reports: { message?: string }[];
  /** 利用者が進捗通知の「キャンセル」を押した状況の再現(issue #1224)。 */
  cancel: () => void;
}[] = [];

/** window.visibleTextEditors の差し替え口。 */
let visibleTextEditors: unknown[] = [];
export function setVisibleTextEditors(editors: unknown[]): void {
  visibleTextEditors = editors;
}

/** vscode.WorkspaceEdit の最小再現。適用はせず、内容を記録するだけ。 */
export class WorkspaceEdit {
  public readonly entries: { kind: 'replace' | 'insert'; uri: string; text: string }[] = [];

  replace(uri: unknown, _range: unknown, text: string): void {
    this.entries.push({ kind: 'replace', uri: String(uri), text });
  }

  insert(uri: unknown, _position: unknown, text: string): void {
    this.entries.push({ kind: 'insert', uri: String(uri), text });
  }
}

/** createWebviewPanel が作ったすべてのパネル(テスト終了時にまとめて閉じるため)。 */
const createdWebviewPanels: MockWebviewPanel[] = [];

/** これまでに作られたすべての Webview パネル(閉じられていないもの)。 */
export function allWebviewPanels(): { webview: { html: string } }[] {
  return [...createdWebviewPanels];
}

/** VS Code がすべての Webview パネルを閉じた状況の再現(パネルのシングルトン登録も外れる)。 */
export function disposeAllWebviewPanels(): void {
  for (const panel of createdWebviewPanels.splice(0)) {
    panel.fireDispose();
  }
}

/** createWebviewPanelが返す最後のパネル(テストからwebview.html等を検査するために保持する)。 */
export let lastCreatedWebviewPanel: MockWebviewPanel | undefined;

interface MockWebviewPanel {
  title: string;
  webview: {
    html: string;
    /** CSPで使う配信元。実物と同じく webview 固有のスキームを模す。 */
    cspSource: string;
    asWebviewUri: (uri: unknown) => { toString: () => string };
    onDidReceiveMessage: (listener: (message: unknown) => void) => { dispose: () => void };
    postMessageToExtension: (message: unknown) => void;
    /** 拡張側から Webview へ送られたメッセージの記録。 */
    postMessage: (message: unknown) => Promise<boolean>;
    readonly posted: unknown[];
  };
  reveal: () => void;
  onDidDispose: (listener: () => void) => { dispose: () => void };
  /** onDidDispose に登録されたリスナー(VSCodeがパネルを閉じた状況の再現に使う)。 */
  fireDispose: () => void;
  dispose: () => void;
  disposed: boolean;
}

export const window = {
  get visibleTextEditors() {
    return visibleTextEditors;
  },
  get activeTextEditor() {
    return activeTextEditor;
  },
  onDidChangeVisibleTextEditors: onEvent('window.onDidChangeVisibleTextEditors'),
  onDidChangeTextEditorSelection: onEvent('window.onDidChangeTextEditorSelection'),
  onDidChangeActiveTextEditor: onEvent('window.onDidChangeActiveTextEditor'),
  showQuickPick: (items: any[], options?: any) => {
    quickPickCalls.push({ items, options });
    const responder = quickPickQueue.shift();
    return Promise.resolve(responder ? responder(items, options) : undefined);
  },
  showInputBox: (options?: any) => {
    inputBoxCalls.push({ options });
    const responder = inputBoxQueue.shift();
    return Promise.resolve(responder ? responder(options) : undefined);
  },
  createTextEditorDecorationType: (options: Record<string, unknown>): MockDecorationType => {
    const type: MockDecorationType = { options, disposed: false, dispose: () => { type.disposed = true; } };
    decorationTypes.push(type);
    return type;
  },
  withProgress: async <T>(
    options: Record<string, unknown>,
    task: (
      progress: { report: (value: { message?: string }) => void },
      token: { isCancellationRequested: boolean; onCancellationRequested: (listener: () => void) => { dispose: () => void } }
    ) => Promise<T>
  ): Promise<T> => {
    const listeners: (() => void)[] = [];
    const token = {
      isCancellationRequested: progressStartsCancelled,
      onCancellationRequested: (listener: () => void) => {
        // 既に取り消された token に購読すると、VS Code は直ちに(取り消しを)通知する。
        if (token.isCancellationRequested) listener();
        listeners.push(listener);
        return { dispose: () => undefined };
      },
    };
    const run = {
      options,
      reports: [] as { message?: string }[],
      cancel: () => {
        token.isCancellationRequested = true;
        for (const listener of listeners) listener();
      },
    };
    progressRuns.push(run);
    return task({ report: (value) => run.reports.push(value) }, token);
  },
  createOutputChannel: (name: string) => {
    const channel = { name, lines: [] as string[], shown: 0, disposed: false };
    outputChannels.push(channel);
    channelsByName.set(name, channel);
    return {
      clear: () => {
        channel.lines.length = 0;
      },
      appendLine: (line: string) => {
        channel.lines.push(line);
      },
      show: () => {
        channel.shown += 1;
      },
      dispose: () => {
        channel.disposed = true;
      },
    };
  },
  showWarningMessage: (message: string, ..._items: unknown[]) => {
    shownWarnings.push(message);
    return Promise.resolve(warningQueue.length > 0 ? warningQueue.shift() : warningResponse);
  },
  showTextDocument: (document: { fileName: string }, _options?: unknown) => {
    openedDocuments.push(document.fileName);
    return Promise.resolve(showTextDocumentResolver ? showTextDocumentResolver(document) : undefined);
  },
  showErrorMessage: (message: string, ..._items: string[]) => {
    shownErrors.push(message);
    return Promise.resolve(errorQueue.length > 0 ? errorQueue.shift() : errorResponse);
  },
  showInformationMessage: (message: string, ..._items: string[]) => {
    shownInformations.push(message);
    return Promise.resolve(informationQueue.length > 0 ? informationQueue.shift() : undefined);
  },
  createWebviewPanel: (): MockWebviewPanel => {
    let receiveListener: ((message: unknown) => void) | undefined;
    let disposeListener: (() => void) | undefined;
    const posted: unknown[] = [];
    const panel: MockWebviewPanel = {
      title: '',
      webview: {
        html: '',
        cspSource: 'vscode-webview://mock',
        asWebviewUri: (uri: unknown) => ({ toString: () => String(uri) }),
        onDidReceiveMessage: (listener) => {
          receiveListener = listener;
          return { dispose: () => undefined };
        },
        // テストが「Webview内のスクリプトがvscode.postMessageを呼んだ」状況を再現するためのヘルパー。
        postMessageToExtension: (message: unknown) => receiveListener?.(message),
        postMessage: (message: unknown) => {
          posted.push(message);
          return Promise.resolve(true);
        },
        posted,
      },
      reveal: () => undefined,
      onDidDispose: (listener: () => void) => {
        disposeListener = listener;
        return { dispose: () => undefined };
      },
      fireDispose: () => disposeListener?.(),
      dispose: () => {
        panel.disposed = true;
      },
      disposed: false,
    };
    lastCreatedWebviewPanel = panel;
    createdWebviewPanels.push(panel);
    return panel;
  },
};

/**
 * vscode.Uri の最小再現。webviewPanelBase は joinPath(...).fsPath でHTML資材を読むため、
 * 文字列ではなく fsPath / toString を持つオブジェクトを返す。
 */
export const Uri = {
  parse: (value: string) => ({ fsPath: value.replace(/^file:\/\//, ''), toString: () => value }),
  file: (fsPath: string) => ({ fsPath, toString: () => `file://${fsPath}` }),
  joinPath: (...segments: unknown[]) => {
    const joined = segments.join('/');
    return { fsPath: joined.replace(/^file:\/\//, ''), toString: () => joined };
  },
};

export const ViewColumn = { Beside: 2, One: 1 };
export const ProgressLocation = { Notification: 15 };

/** vscode.extensions の最小再現(issue #1335)。既定ではGit拡張が無い環境として振る舞う。 */
export const extensions = { getExtension: jest.fn((): unknown => undefined) };

/** vscode.CompletionItemKind の数値はVS Code APIの定義に合わせる(一部のみ)。 */
export const CompletionItemKind = { Keyword: 13, Snippet: 14, EnumMember: 19 } as const;

/** vscode.SnippetString の最小再現。 */
export class SnippetString {
  constructor(public readonly value: string = '') {}
}

/** vscode.CompletionItem の最小再現。 */
export class CompletionItem {
  detail?: string;
  range?: unknown;
  insertText?: string | SnippetString;
  filterText?: string;
  sortText?: string;
  documentation?: unknown;
  constructor(public readonly label: string, public readonly kind?: number) {}
}
