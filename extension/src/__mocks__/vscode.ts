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
};

export const ViewColumn = { Beside: 2, One: 1 };
export const ProgressLocation = { Notification: 15 };
