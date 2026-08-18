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
  shownWarnings.length = 0;
  shownErrors.length = 0;
  lastCreatedWebviewPanel = undefined;
}

export const shownWarnings: string[] = [];
export const shownErrors: string[] = [];

export const workspace = {
  getConfiguration(section: string) {
    return {
      get<T>(key: string, defaultValue?: T): T | undefined {
        const full = `${section}.${key}`;
        return configurationValues.has(full) ? (configurationValues.get(full) as T) : defaultValue;
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
    asWebviewUri: (uri: unknown) => { toString: () => string };
    onDidReceiveMessage: (listener: (message: unknown) => void) => { dispose: () => void };
    postMessageToExtension: (message: unknown) => void;
  };
  reveal: () => void;
  onDidDispose: (listener: () => void) => { dispose: () => void };
  dispose: () => void;
}

export const window = {
  createOutputChannel: () => ({
    appendLine: () => undefined,
    show: () => undefined,
    dispose: () => undefined,
  }),
  showWarningMessage: (message: string) => {
    shownWarnings.push(message);
    return Promise.resolve(undefined);
  },
  showErrorMessage: (message: string) => {
    shownErrors.push(message);
    return Promise.resolve(undefined);
  },
  createWebviewPanel: (): MockWebviewPanel => {
    let receiveListener: ((message: unknown) => void) | undefined;
    const panel: MockWebviewPanel = {
      title: '',
      webview: {
        html: '',
        asWebviewUri: (uri: unknown) => ({ toString: () => String(uri) }),
        onDidReceiveMessage: (listener) => {
          receiveListener = listener;
          return { dispose: () => undefined };
        },
        // テストが「Webview内のスクリプトがvscode.postMessageを呼んだ」状況を再現するためのヘルパー。
        postMessageToExtension: (message: unknown) => receiveListener?.(message),
      },
      reveal: () => undefined,
      onDidDispose: () => ({ dispose: () => undefined }),
      dispose: () => undefined,
    };
    lastCreatedWebviewPanel = panel;
    return panel;
  },
};

export const Uri = {
  joinPath: (...segments: unknown[]) => segments.join('/'),
};

export const ViewColumn = { Beside: 2, One: 1 };
export const ProgressLocation = { Notification: 15 };
