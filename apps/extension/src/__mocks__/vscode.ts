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
  decorationTypes.length = 0;
  progressRuns.length = 0;
  diagnosticCollections.length = 0;
  visibleTextEditors = [];
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
  openTextDocument(fileName: string) {
    openedDocuments.push(fileName);
    return Promise.resolve({ fileName });
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
  onDidChangeConfiguration: () => ({ dispose: () => undefined }),
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
export const progressRuns: { options: Record<string, unknown>; reports: { message?: string }[] }[] = [];

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
  createTextEditorDecorationType: (options: Record<string, unknown>): MockDecorationType => {
    const type: MockDecorationType = { options, disposed: false, dispose: () => { type.disposed = true; } };
    decorationTypes.push(type);
    return type;
  },
  withProgress: async <T>(
    options: Record<string, unknown>,
    task: (progress: { report: (value: { message?: string }) => void }) => Promise<T>
  ): Promise<T> => {
    const run = { options, reports: [] as { message?: string }[] };
    progressRuns.push(run);
    return task({ report: (value) => run.reports.push(value) });
  },
  createOutputChannel: () => ({
    appendLine: () => undefined,
    show: () => undefined,
    dispose: () => undefined,
  }),
  showWarningMessage: (message: string, ..._items: string[]) => {
    shownWarnings.push(message);
    return Promise.resolve(warningResponse);
  },
  showTextDocument: (document: { fileName: string }) => {
    openedDocuments.push(document.fileName);
    return Promise.resolve(undefined);
  },
  showErrorMessage: (message: string) => {
    shownErrors.push(message);
    return Promise.resolve(undefined);
  },
  showInformationMessage: (message: string) => {
    shownInformations.push(message);
    return Promise.resolve(undefined);
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
    return panel;
  },
};

/**
 * vscode.Uri の最小再現。webviewPanelBase は joinPath(...).fsPath でHTML資材を読むため、
 * 文字列ではなく fsPath / toString を持つオブジェクトを返す。
 */
export const Uri = {
  joinPath: (...segments: unknown[]) => {
    const joined = segments.join('/');
    return { fsPath: joined.replace(/^file:\/\//, ''), toString: () => joined };
  },
};

export const ViewColumn = { Beside: 2, One: 1 };
export const ProgressLocation = { Notification: 15 };
