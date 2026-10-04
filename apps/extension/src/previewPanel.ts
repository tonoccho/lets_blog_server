import * as vscode from 'vscode';
import { buildRealSitePreviewCsp, createNonce } from './webviewSecurity';

/** Webviewからのメッセージ型。 */
export type PreviewMessage = { type: 'switchSite'; siteId: number | null };

/** 実サイトのプレビュー(issue #1562)で、Webviewが外部ブラウザで開くよう求めるメッセージ。パネル自身が処理する。 */
interface OpenExternalMessage {
  type: 'openExternal';
}

/** プレビューパネル内の環境切り替えセレクトに表示する選択肢。 */
export interface SiteOption {
  /** サイト未紐付け環境の場合はnull。 */
  siteId: number | null;
  /** 例: "ローカル" / "テスト" / "本番"。 */
  label: string;
  siteName: string;
}

/**
 * 記事プレビュー用のシングルトンWebviewパネル。実サイトの署名付きプレビューURL(issue #1562)をiframeで
 * 表示するか、表示できないとき(プラグインが使えない・サイトが紐づいていない)に案内を表示する。
 * テーマCSSの埋め込みやPrism.jsによる見た目の再現(旧方式)は、プラグイン必須化に伴い issue #1564 で削除した。
 * パネル内から環境を切り替えて、その環境のプレビューを再取得できる。
 */
export class PreviewPanel {
  /** 開いているプレビューパネル。プレビューは常に1枚に保つ。 */
  public static currentPanel: PreviewPanel | undefined;
  private readonly _panel: vscode.WebviewPanel;
  /** 実サイトのプレビューで表示中のURL(外部ブラウザで開く操作の宛先)。案内表示ではundefined。 */
  private _previewUrl: string | undefined;

  /** Webviewからのメッセージハンドラー（環境切り替え等）。 */
  private _messageHandler: ((message: PreviewMessage) => void) | undefined;

  /**
   * 実サイトのプレビューを表示する(issue #1562)。サーバーが発行した署名付きURLをパネル内のiframeで表示する。
   * 既に開いている場合は同じパネルを新しいURLへ差し替える(再プレビューは毎回新しいURLを取得する)。
   * 埋め込みがWordPressやWAFに拒否されても外部ブラウザで開けるよう、その操作を常に表示する。
   */
  public static showRealSite(context: vscode.ExtensionContext, options: RealSitePreviewOptions): void {
    const panel = PreviewPanel.openPanel(context, options.onMessage);
    panel._previewUrl = options.url;
    panel._setPage(
      options.siteLabel,
      renderRealSitePage(options, createNonce())
    );
  }

  /**
   * letsblogプラグインが使えない(未導入・要更新の)サイトで、プレビューの代わりに導入(更新)の案内を表示する。
   * プラグインは必須のため、旧方式での代替表示はしない。
   */
  public static showPluginGuidance(context: vscode.ExtensionContext, options: PluginGuidanceOptions): void {
    const panel = PreviewPanel.openPanel(context, options.onMessage);
    panel._previewUrl = undefined;
    panel._setPage(options.siteLabel, renderGuidancePage(options, createNonce()));
  }

  /**
   * プロジェクトにサイトが1つも紐づいていないとき、プレビューの代わりにサイトを紐づける案内を表示する。
   * プレビューは実サイトで表示するため、サイトが無ければ表示できない(旧方式のCSSなし表示はしない)。
   */
  public static showNoSite(context: vscode.ExtensionContext): void {
    const panel = PreviewPanel.openPanel(context, undefined);
    panel._previewUrl = undefined;
    panel._setPage(undefined, renderNoSitePage(createNonce()));
  }

  /** 開いているパネルを前面に出して返す。無ければ作る。 */
  private static openPanel(
    context: vscode.ExtensionContext,
    onMessage: ((message: PreviewMessage) => void) | undefined
  ): PreviewPanel {
    if (PreviewPanel.currentPanel) {
      PreviewPanel.currentPanel._panel.reveal(vscode.ViewColumn.Beside);
      PreviewPanel.currentPanel._messageHandler = onMessage;
      return PreviewPanel.currentPanel;
    }
    PreviewPanel.currentPanel = new PreviewPanel(context, onMessage);
    return PreviewPanel.currentPanel;
  }

  private _setPage(siteLabel: string | undefined, html: string): void {
    this._panel.title = siteLabel ? `Article Preview (${siteLabel})` : 'Article Preview';
    this._panel.webview.html = html;
  }

  private constructor(
    context: vscode.ExtensionContext,
    onMessage?: (message: PreviewMessage) => void
  ) {
    this._messageHandler = onMessage;
    this._panel = vscode.window.createWebviewPanel(
      'letsBlog.articlePreview',
      'Article Preview',
      vscode.ViewColumn.Beside,
      {
        enableScripts: true,
        // ローカル資材は読み込ませない(実サイトのiframeと、拡張自身のインラインスクリプトだけを使う)。
        localResourceRoots: [],
      }
    );
    this._panel.onDidDispose(() => this.dispose(), null);
    this._panel.webview.onDidReceiveMessage((message) => this._handleMessage(message), null);
  }

  private _handleMessage(message: PreviewMessage | OpenExternalMessage): void {
    if (message.type === 'openExternal') {
      this._openExternal();
      return;
    }
    if (this._messageHandler) {
      this._messageHandler(message);
    }
  }

  /** 表示中の実サイトのプレビューURLを外部ブラウザで開く。URLが無い・http(s)でない場合は何もしない。 */
  private _openExternal(): void {
    const url = this._previewUrl;
    if (url && /^https?:\/\//i.test(url)) {
      void vscode.env.openExternal(vscode.Uri.parse(url));
    }
  }

  private dispose(): void {
    PreviewPanel.currentPanel = undefined;
    this._panel.dispose();
  }

  /**
   * プレビューをフォーカスした上でWebview Developer Toolsを開く。
   * プレビュー未表示の場合はエラーメッセージを表示するのみで、例外は投げない。
   */
  public static openDevTools(): void {
    if (!PreviewPanel.currentPanel) {
      vscode.window.showErrorMessage('プレビューを開いてから実行してください。');
      return;
    }
    PreviewPanel.currentPanel._panel.reveal(vscode.ViewColumn.Beside);
    void vscode.commands.executeCommand('workbench.action.webview.openDeveloperTools');
  }
}

/**
 * パネル内の環境切り替えセレクトを描画する。選択肢が2つ未満の場合は切り替える意味がないため
 * 描画しない(呼び出し側で静的なサイトラベル表示にフォールバックする)。
 */
function renderSiteSwitcher(
  availableSites: SiteOption[] | undefined,
  currentSiteId: number | null | undefined
): string {
  if (!availableSites || availableSites.length < 2) {
    return '';
  }
  const options = availableSites
    .map((site) => {
      const value = site.siteId == null ? '' : String(site.siteId);
      const selected = site.siteId === currentSiteId ? ' selected' : '';
      const text = `${site.label} / ${site.siteName}`;
      return `<option value="${escapeHtml(value)}"${selected}>${escapeHtml(text)}</option>`;
    })
    .join('');
  return `<div style="display:flex;align-items:center;gap:8px;background:var(--vscode-editorWidget-background,#eee);color:var(--vscode-foreground,#333);padding:6px 12px;margin-bottom:12px;border-radius:4px;font-family:sans-serif;font-size:12px;">
<label for="letsblog-site-switcher">プレビューするサイト:</label>
<select id="letsblog-site-switcher">${options}</select>
</div>`;
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

/** {@link PreviewPanel.showRealSite}の引数。 */
export interface RealSitePreviewOptions {
  /** サーバーが発行した署名付きプレビューURL。 */
  url: string;
  siteLabel?: string;
  onMessage?: (message: PreviewMessage) => void;
  availableSites?: SiteOption[];
  currentSiteId?: number | null;
}

/** {@link PreviewPanel.showPluginGuidance}の引数。 */
export interface PluginGuidanceOptions {
  /** 要更新(プロトコル非互換)ならtrue、未導入ならfalse。 */
  needsUpdate: boolean;
  /** サーバーが返した理由の文言(あれば案内に添える)。 */
  message?: string;
  siteLabel?: string;
  onMessage?: (message: PreviewMessage) => void;
  availableSites?: SiteOption[];
  currentSiteId?: number | null;
}

const BANNER_STYLE =
  'background:var(--vscode-editorWidget-background,#eee);color:var(--vscode-foreground,#333);padding:6px 12px;margin-bottom:12px;border-radius:4px;font-family:sans-serif;font-size:12px;';

/** 環境切り替えセレクトが無い場合の、静的なサイトラベル表示。 */
function renderSiteLabel(siteLabel: string | undefined): string {
  return siteLabel ? `<div style="${BANNER_STYLE}">プレビュー: ${escapeHtml(siteLabel)}</div>` : '';
}

/**
 * 1つのスクリプトでacquireVsCodeApiを一度だけ呼び、環境切り替えと外部ブラウザで開く操作を配線する
 * (acquireVsCodeApiは1ページで1回しか呼べないため、個別のスクリプトに分けない)。
 */
const REAL_SITE_SCRIPT = `
(function () {
  const vscode = acquireVsCodeApi();
  const select = document.getElementById('letsblog-site-switcher');
  if (select) {
    select.addEventListener('change', () => {
      const value = select.value;
      vscode.postMessage({ type: 'switchSite', siteId: value === '' ? null : Number(value) });
    });
  }
  const open = document.getElementById('letsblog-open-external');
  if (open) {
    open.addEventListener('click', () => vscode.postMessage({ type: 'openExternal' }));
  }
})();
`;

function renderRealSitePage(options: RealSitePreviewOptions, nonce: string): string {
  const switcher = renderSiteSwitcher(options.availableSites, options.currentSiteId);
  const banner = switcher || renderSiteLabel(options.siteLabel);
  return `<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="${buildRealSitePreviewCsp(nonce, options.url)}">
<title>Article Preview</title>
<style>
body{margin:0;padding:8px;font-family:sans-serif;}
iframe{width:100%;height:calc(100vh - 120px);border:1px solid var(--vscode-panel-border,#ccc);background:#fff;}
</style>
</head>
<body>
${banner}
<div style="${BANNER_STYLE}display:flex;align-items:center;gap:8px;">
<span>表示されない場合(サイトが埋め込み表示を許可していない場合)は、外部ブラウザで開いてください。</span>
<button id="letsblog-open-external" type="button">外部ブラウザで開く</button>
</div>
<iframe src="${escapeHtml(options.url)}" title="実サイトのプレビュー"></iframe>
<script nonce="${nonce}">${REAL_SITE_SCRIPT}</script>
</body>
</html>`;
}

function renderGuidancePage(options: PluginGuidanceOptions, nonce: string): string {
  const switcher = renderSiteSwitcher(options.availableSites, options.currentSiteId);
  const banner = switcher || renderSiteLabel(options.siteLabel);
  const headline = options.needsUpdate
    ? "このサイトの Let's Blog プラグインの更新が必要です。"
    : "このサイトには Let's Blog プラグインが導入されていません。";
  const reason = options.message
    ? `<p style="color:var(--vscode-descriptionForeground,#666);">${escapeHtml(options.message)}</p>`
    : '';
  return `<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}'">
<title>Article Preview</title>
</head>
<body style="font-family:sans-serif;padding:8px;">
${banner}
<main role="alert">
<h2>${headline}</h2>
<p>プレビューは実サイトのプラグインを使って表示するため、プラグインが必要です。</p>
<p>Let's Blog のサイト画面で「プラグインを再導入」を実行したあと、もう一度プレビューを開いてください。</p>
${reason}
</main>
<script nonce="${nonce}">${REAL_SITE_SCRIPT}</script>
</body>
</html>`;
}

function renderNoSitePage(nonce: string): string {
  return `<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'nonce-${nonce}'">
<title>Article Preview</title>
</head>
<body style="font-family:sans-serif;padding:8px;">
<main role="alert">
<h2>このプロジェクトにはサイトが紐づいていません。</h2>
<p>プレビューは実サイトで表示するため、プロジェクトにサイトを紐づける必要があります。</p>
<p>Let's Blog のプロジェクト設定でサイトを紐づけたあと、もう一度プレビューを開いてください。</p>
</main>
<script nonce="${nonce}">${REAL_SITE_SCRIPT}</script>
</body>
</html>`;
}
