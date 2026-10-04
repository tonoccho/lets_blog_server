import * as vscode from 'vscode';
import { logger } from './logger';
import { buildPreviewCsp, buildRealSitePreviewCsp, createNonce } from './webviewSecurity';

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

/** 拡張機能に同梱しているPrism.jsバンドル(コードブロックのシンタックスハイライト用)の配置パス。 */
const PRISM_ASSET_PATH = ['webviews', 'vendor', 'prism', 'prism-bundle.min.js'];
/**
 * 拡張機能に同梱しているPrism.jsのline-numbersプラグインの配置パス。公開先WordPressテーマ(JIN:R)が
 * コードブロックに行番号ガターを表示するため、プレビューでも同様に再現する(Issue #334)。
 */
const PRISM_LINE_NUMBERS_ASSET_PATH = ['webviews', 'vendor', 'prism', 'prism-line-numbers.min.js'];

/**
 * 記事プレビュー用のシングルトンWebviewパネル。マスター環境サイトのCSSを<style>として埋め込み、
 * 変換済みHTMLをそのまま表示する。パネル内から環境を切り替えてCSS/骨格を再取得できる。
 * コードブロックは公開先テーマと同様にPrism.js(拡張機能へバンドル済み、nonce付きで実行)で
 * シンタックスハイライトする。公開先テーマ(JIN:R)がline-numbersプラグインで行番号ガターを
 * 表示しているため、プレビューでも同プラグインを読み込み、コードブロックの`<pre>`へ
 * `line-numbers`クラスを付与して再現する(Issue #334)。
 */
export class PreviewPanel {
  /** 開いているプレビューパネル。プレビューは常に1枚に保つ。 */
  public static currentPanel: PreviewPanel | undefined;
  private readonly _panel: vscode.WebviewPanel;
  private readonly _extensionUri: vscode.Uri;
  /**
   * renderPreviewSkeletonがローカル/テスト環境向けに作成した非公開プレビュー投稿のID(環境=siteIdごと)。
   * 環境を切り替えても削除せず保持し、次回同じ環境を選んだ際に更新して使い回す(投稿を積み上げない
   * ため)。パネルを閉じた際にまとめて削除する({@link dispose}参照)。
   */
  private readonly _previewPostIdsBySiteId = new Map<number, string>();
  /** 実サイトのプレビューで表示中のURL(外部ブラウザで開く操作の宛先)。旧方式の表示・案内表示ではundefined。 */
  private _previewUrl: string | undefined;
  private _deletePreviewPost?: (siteId: number, postId: string) => Promise<void>;

  /** Webviewからのメッセージハンドラー（環境切り替え等）。 */
  private _messageHandler: ((message: PreviewMessage) => void) | undefined;

  /**
   * プレビューを表示する。既に開いている場合は内容を差し替える。
   * @param context 拡張機能のコンテキスト(同梱資材のURI解決に使う)
   * @param html サーバーで変換済みの記事HTML
   * @param css 適用するテーマCSS(取得できなかった場合は空文字)
   * @param warning CSSを取得できなかった場合などの警告文
   * @param siteLabel 適用中のCSSの取得元(例: "本番 / example.com")
   * @param featuredImageDataUri front matterのfeatured_imageのdata URI(未設定/未検出の場合はundefined)
   * @param onMessage パネル内での環境切り替え等のメッセージハンドラー
   * @param availableSites パネル内の環境切り替えセレクトに表示する選択肢(2つ以上の場合のみセレクトを表示)
   * @param currentSiteId 現在表示中のサイトID(サイト未紐付けの場合はnull)
   * @param deletePreviewPost ローカル/テスト環境向けの非公開プレビュー投稿を削除するコールバック。
   *                          パネルを閉じた際、記録済みの投稿すべてに対して呼ばれる。
   */
  public static createOrShow(
    context: vscode.ExtensionContext,
    html: string,
    css: string,
    warning: string | undefined,
    siteLabel?: string,
    featuredImageDataUri?: string,
    onMessage?: (message: PreviewMessage) => void,
    availableSites?: SiteOption[],
    currentSiteId?: number | null,
    deletePreviewPost?: (siteId: number, postId: string) => Promise<void>
  ): void {
    if (PreviewPanel.currentPanel) {
      PreviewPanel.currentPanel._panel.reveal(vscode.ViewColumn.Beside);
      PreviewPanel.currentPanel._messageHandler = onMessage;
      PreviewPanel.currentPanel._update(html, css, warning, siteLabel, featuredImageDataUri, availableSites, currentSiteId);
      if (deletePreviewPost) {
        PreviewPanel.currentPanel._deletePreviewPost = deletePreviewPost;
      }
      return;
    }
    const panel = new PreviewPanel(context, onMessage);
    PreviewPanel.currentPanel = panel;
    panel._update(html, css, warning, siteLabel, featuredImageDataUri, availableSites, currentSiteId);
    panel._deletePreviewPost = deletePreviewPost;
  }

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
    this._extensionUri = context.extensionUri;
    this._messageHandler = onMessage;
    this._panel = vscode.window.createWebviewPanel(
      'letsBlog.articlePreview',
      'Article Preview',
      vscode.ViewColumn.Beside,
      {
        enableScripts: true,
        // コードハイライト用に同梱したPrism.js以外のローカル資材は読み込ませない。
        localResourceRoots: [vscode.Uri.joinPath(context.extensionUri, 'webviews', 'vendor', 'prism')],
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

  /** siteId(環境)に紐づく、作成済みのプレビュー用非公開投稿IDを返す(未作成ならundefined)。 */
  public getPreviewPostId(siteId: number): string | undefined {
    return this._previewPostIdsBySiteId.get(siteId);
  }

  /** siteId(環境)に紐づくプレビュー用非公開投稿IDを記録する。 */
  public recordPreviewPostId(siteId: number, postId: string): void {
    this._previewPostIdsBySiteId.set(siteId, postId);
  }

  private dispose(): void {
    PreviewPanel.currentPanel = undefined;
    this._panel.dispose();
    // onDidDisposeはVS Code側でawaitされないため、削除はfire-and-forgetで行う
    // (失敗してもUIをブロックしない。非公開投稿のため残っても実害は小さい)。
    if (this._deletePreviewPost) {
      const deletePreviewPost = this._deletePreviewPost;
      for (const [siteId, postId] of this._previewPostIdsBySiteId) {
        deletePreviewPost(siteId, postId).catch((err: unknown) => {
          logger.debug(`プレビュー用投稿の削除に失敗しました: siteId=${siteId}, postId=${postId}`, { err });
        });
      }
      this._previewPostIdsBySiteId.clear();
    }
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

  private _update(
    html: string,
    css: string,
    warning: string | undefined,
    siteLabel?: string,
    featuredImageDataUri?: string,
    availableSites?: SiteOption[],
    currentSiteId?: number | null
  ): void {
    this._previewUrl = undefined;
    // どのサイトのCSSで表示しているかがタブから分かるようにする。
    this._panel.title = siteLabel ? `Article Preview (${siteLabel})` : 'Article Preview';
    this._panel.webview.html =
      this._getHtmlContent(html, css, warning, siteLabel, featuredImageDataUri, availableSites, currentSiteId);
  }

  private _getHtmlContent(
    html: string,
    css: string,
    warning: string | undefined,
    siteLabel?: string,
    featuredImageDataUri?: string,
    availableSites?: SiteOption[],
    currentSiteId?: number | null
  ): string {
    const warningBlock = warning
      ? `<div role="alert" style="background:#fff3cd;color:#664d03;padding:8px 12px;margin-bottom:16px;border-radius:4px;font-family:sans-serif;font-size:13px;">${escapeHtml(warning)}</div>`
      : '';
    const nonce = createNonce();
    // パネル内から環境(ローカル/テスト/本番)を切り替えられるセレクトを表示する。
    // 選択肢が2つ未満(サイトが1つしか紐づいていない等)の場合は切り替える意味がないため表示しない。
    const siteSwitcher = renderSiteSwitcher(availableSites, currentSiteId);
    // 適用中のCSSの出所を明示する。どのサイトの見た目を見ているのか分からないと
    // 環境間の差分確認という目的を果たせないため。
    const siteBanner = siteSwitcher
      ? siteSwitcher
      : siteLabel
        ? `<div style="background:var(--vscode-editorWidget-background,#eee);color:var(--vscode-foreground,#333);padding:6px 12px;margin-bottom:12px;border-radius:4px;font-family:sans-serif;font-size:12px;">適用中のCSS: ${escapeHtml(siteLabel)}</div>`
        : '';
    // 投稿先サイトのテーマは記事に紐づくアイキャッチ画像を表示するため、プレビューでも
    // 同様に本文の先頭に表示する(テーマのDOM構造までは再現せず、単に画像を出すのみ)。
    const eyecatchBlock = featuredImageDataUri
      ? `<div class="letsblog-preview-eyecatch" style="margin:0 0 16px;"><img src="${escapeHtml(featuredImageDataUri)}" alt="" style="max-width:100%;height:auto;display:block;"></div>`
      : '';
    const prismUri = this._panel.webview.asWebviewUri(
      vscode.Uri.joinPath(this._extensionUri, ...PRISM_ASSET_PATH)
    );
    const prismLineNumbersUri = this._panel.webview.asWebviewUri(
      vscode.Uri.joinPath(this._extensionUri, ...PRISM_LINE_NUMBERS_ASSET_PATH)
    );
    return `<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="${buildPreviewCsp(nonce)}">
<title>Article Preview</title>
<style>
${css}
</style>
</head>
<body>
${siteBanner}
${warningBlock}
<main>
${eyecatchBlock}
${html}
</main>
<script nonce="${nonce}" src="${prismUri}"></script>
<script nonce="${nonce}" src="${prismLineNumbersUri}"></script>
<script nonce="${nonce}">
document.querySelectorAll('pre > code').forEach((code) => code.parentElement.classList.add('line-numbers'));
Prism.highlightAll();
${siteSwitcher ? SITE_SWITCHER_SCRIPT : ''}
</script>
</body>
</html>`;
  }
}

/**
 * 環境切り替えセレクトのchangeを拾い、拡張機能側へ`switchSite`メッセージを送る。
 * インラインのonchange属性はCSPのscript-srcで許可できないため、addEventListenerで配線する。
 */
const SITE_SWITCHER_SCRIPT = `
(function () {
  const select = document.getElementById('letsblog-site-switcher');
  if (!select) return;
  const vscode = acquireVsCodeApi();
  select.addEventListener('change', () => {
    const value = select.value;
    vscode.postMessage({ type: 'switchSite', siteId: value === '' ? null : Number(value) });
  });
})();
`;

/**
 * パネル内の環境切り替えセレクトを描画する。選択肢が2つ未満の場合は切り替える意味がないため
 * 描画しない(呼び出し側で従来の静的なサイトラベル表示にフォールバックする)。
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
<label for="letsblog-site-switcher">適用中のCSS:</label>
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
