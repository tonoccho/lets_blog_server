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
}

/** showWarningMessage が返す選択肢(未設定なら「閉じた」= undefined)。 */
let warningResponse: string | undefined;
export function setWarningResponse(value: string | undefined): void {
  warningResponse = value;
}

/** openTextDocument / showTextDocument に渡されたパス。 */
export const openedDocuments: string[] = [];

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
};

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
