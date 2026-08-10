import * as vscode from 'vscode';
import * as fs from 'fs';
import { CancelledError, describeError } from './errorHandler';
import { logger } from './logger';
import { createNonce } from './webviewSecurity';
import { WebviewMessageBase } from './webviewMessages';

/** Webviewの資材(HTML/CSS/JS)を置くディレクトリ名。拡張ルートからの相対。 */
const WEBVIEW_ASSET_DIR = 'webviews';

/** 現在開いているパネルをviewTypeごとに1枚だけ保持する。 */
const openPanels = new Map<string, { reveal(): void }>();

/**
 * viewTypeごとに1枚だけパネルを開く。既に開いていれば新規生成せず前面に出す。
 *
 * 各パネルが個別にstatic currentPanelを持つと、破棄時のクリアを書き漏らした際に
 * 「閉じたのに二度と開けない」状態になる。破棄時の後始末を基底クラス側へ寄せるため、
 * 生存管理をここへ集約している。
 */
export function showSingletonPanel(viewType: string, create: () => { reveal(): void }): void {
  const existing = openPanels.get(viewType);
  if (existing) {
    existing.reveal();
    return;
  }
  openPanels.set(viewType, create());
}

export interface WebviewPanelOptions {
  /** vscode.window.createWebviewPanelに渡すviewType(パネルの識別子)。 */
  viewType: string;
  /** タブに表示するタイトル。 */
  title: string;
  /** webviews/配下の資材のベース名(例: 'plan' → plan.html / plan.css / plan.js)。 */
  assetName: string;
}

/**
 * 全パネルが読み込む共通資材。ローディング表示は3パネルで同じ挙動が要るため、
 * パネル固有の資材とは別に共通ファイルとして配信する。
 */
const SHARED_ASSETS = ['loadingIndicator.css', 'loadingIndicator.js'];

/**
 * Webviewパネルの共通処理をまとめた基底クラス。
 *
 * 各パネルで重複していた「パネル生成 → メッセージ購読 → ディスパッチ → エラー整形 →
 * 破棄」の骨格と、HTML資材の読み込み・CSP付与をここへ集約する。
 * 派生クラスはコマンドごとの処理(handleMessage)だけを実装すればよい。
 */
export abstract class WebviewPanelBase<TInbound extends WebviewMessageBase<string>, TOutbound extends string> {
  protected readonly panel: vscode.WebviewPanel;

  protected constructor(
    protected readonly context: vscode.ExtensionContext,
    private readonly options: WebviewPanelOptions
  ) {
    this.panel = vscode.window.createWebviewPanel(
      options.viewType,
      options.title,
      vscode.ViewColumn.Beside,
      {
        enableScripts: true,
        retainContextWhenHidden: true,
        // Webviewが読み込めるローカル資材をwebviews/配下に限定する。
        localResourceRoots: [vscode.Uri.joinPath(context.extensionUri, WEBVIEW_ASSET_DIR)],
      }
    );

    this.panel.onDidDispose(() => this.dispose(), null);
    this.panel.webview.onDidReceiveMessage((message: TInbound) => this.dispatch(message), null);
    this.panel.webview.html = this.renderHtml();
  }

  /** 既存のパネルを前面に出す。 */
  public reveal(): void {
    this.panel.reveal(vscode.ViewColumn.Beside);
  }

  /** パネルを破棄し、シングルトン登録からも取り除く。 */
  protected dispose(): void {
    openPanels.delete(this.options.viewType);
    this.panel.dispose();
  }

  /**
   * 進行中の長時間処理を中断するためのコントローラ。
   * パネルは同時に1つの長時間処理しか走らせないため、1本だけ保持すれば足りる。
   */
  private _currentOperation: AbortController | undefined;

  /**
   * 中断可能な処理を実行する。実行中に cancel を受け取ると、この処理へ渡した
   * signal 経由でHTTPリクエストごと打ち切られる。
   */
  protected async runCancellable<T>(operation: (signal: AbortSignal) => Promise<T>): Promise<T> {
    this._currentOperation?.abort();
    const controller = new AbortController();
    this._currentOperation = controller;
    try {
      return await operation(controller.signal);
    } finally {
      if (this._currentOperation === controller) {
        this._currentOperation = undefined;
      }
    }
  }

  /** 進行中の処理を中断する。Webviewの「キャンセル」ボタンから呼ばれる。 */
  protected cancelCurrentOperation(): void {
    this._currentOperation?.abort();
    this._currentOperation = undefined;
  }

  /** Webviewへメッセージを送る。 */
  protected postMessage(command: TOutbound, payload: unknown): void {
    void this.panel.webview.postMessage({ command, payload });
  }

  /**
   * Webviewからのメッセージを処理する。派生クラスで実装する。
   * ここで投げた例外は dispatch() が捕捉し、ログ記録とWebviewへの通知を行う。
   */
  protected abstract handleMessage(message: TInbound): Promise<void>;

  /**
   * 例外処理を一箇所に集約したディスパッチャ。
   * 各パネルが個別にtry/catchを書くとエラー整形やログ出力が揃わないため基底で行う。
   */
  private async dispatch(message: TInbound): Promise<void> {
    try {
      await this.handleMessage(message);
    } catch (error) {
      // 利用者による中断は失敗ではないため、エラーとしては通知しない。
      if (error instanceof CancelledError) {
        logger.info(`${this.options.viewType}: ${message.command} をキャンセルしました。`);
        this.postMessage('cancelled' as TOutbound, {});
        return;
      }
      const description = describeError(error);
      logger.error(`${this.options.viewType}: ${message.command} に失敗しました: ${description}`, {
        stack: error instanceof Error ? error.stack : undefined,
      });
      // 'error' は全パネル共通の通知コマンド。
      this.postMessage('error' as TOutbound, { error: description });
    }
  }

  /**
   * webviews/配下のHTMLを読み込み、CSP・nonce・資材URIを差し込む。
   *
   * CSS/JSは別ファイルのままWebviewへ配信する(構文ハイライトと差分の見やすさを保つため)。
   * style-srcに'unsafe-inline'を含めているのは、HTML中のstyle属性を許可するため。
   */
  private renderHtml(): string {
    const webview = this.panel.webview;
    const nonce = createNonce();
    const assetUri = (fileName: string): vscode.Uri =>
      webview.asWebviewUri(
        vscode.Uri.joinPath(this.context.extensionUri, WEBVIEW_ASSET_DIR, fileName)
      );

    const csp = [
      "default-src 'none'",
      `img-src ${webview.cspSource} data: https:`,
      `style-src ${webview.cspSource} 'unsafe-inline'`,
      `script-src 'nonce-${nonce}'`,
    ].join('; ');

    const htmlPath = vscode.Uri.joinPath(
      this.context.extensionUri,
      WEBVIEW_ASSET_DIR,
      `${this.options.assetName}.html`
    ).fsPath;

    const sharedStyles = SHARED_ASSETS.filter((name) => name.endsWith('.css'))
      .map((name) => `<link rel="stylesheet" href="${assetUri(name)}">`)
      .join('\n');
    const sharedScripts = SHARED_ASSETS.filter((name) => name.endsWith('.js'))
      .map((name) => `<script nonce="${nonce}" src="${assetUri(name)}"></script>`)
      .join('\n');

    return fs
      .readFileSync(htmlPath, 'utf-8')
      .replace(/\{\{csp\}\}/g, csp)
      .replace(/\{\{nonce\}\}/g, nonce)
      .replace(/\{\{sharedStyles\}\}/g, sharedStyles)
      .replace(/\{\{sharedScripts\}\}/g, sharedScripts)
      .replace(/\{\{styleUri\}\}/g, assetUri(`${this.options.assetName}.css`).toString())
      .replace(/\{\{scriptUri\}\}/g, assetUri(`${this.options.assetName}.js`).toString());
  }
}
