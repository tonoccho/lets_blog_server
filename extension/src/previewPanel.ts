import * as vscode from 'vscode';
import { buildStaticCsp } from './webviewSecurity';

/**
 * 記事プレビュー用のシングルトンWebviewパネル。マスター環境サイトのCSSを<style>として埋め込み、
 * 変換済みHTMLをそのまま表示する(view-onlyで、Webviewからのメッセージは扱わない)。
 */
export class PreviewPanel {
  /** 開いているプレビューパネル。プレビューは常に1枚に保つ。 */
  public static currentPanel: PreviewPanel | undefined;
  private readonly _panel: vscode.WebviewPanel;

  /**
   * プレビューを表示する。既に開いている場合は内容を差し替える。
   * @param html サーバーで変換済みの記事HTML
   * @param css 適用するテーマCSS(取得できなかった場合は空文字)
   * @param warning CSSを取得できなかった場合などの警告文
   * @param siteLabel 適用中のCSSの取得元(例: "本番 / example.com")
   */
  public static createOrShow(
    html: string,
    css: string,
    warning: string | undefined,
    siteLabel?: string
  ): void {
    if (PreviewPanel.currentPanel) {
      PreviewPanel.currentPanel._panel.reveal(vscode.ViewColumn.Beside);
      PreviewPanel.currentPanel._update(html, css, warning, siteLabel);
      return;
    }
    PreviewPanel.currentPanel = new PreviewPanel(html, css, warning, siteLabel);
  }

  private constructor(html: string, css: string, warning: string | undefined, siteLabel?: string) {
    this._panel = vscode.window.createWebviewPanel(
      'letsBlog.articlePreview',
      'Article Preview',
      vscode.ViewColumn.Beside,
      { enableScripts: false }
    );
    this._panel.onDidDispose(() => this.dispose(), null);
    this._update(html, css, warning, siteLabel);
  }

  private dispose(): void {
    PreviewPanel.currentPanel = undefined;
    this._panel.dispose();
  }

  private _update(html: string, css: string, warning: string | undefined, siteLabel?: string): void {
    // どのサイトのCSSで表示しているかがタブから分かるようにする。
    this._panel.title = siteLabel ? `Article Preview (${siteLabel})` : 'Article Preview';
    this._panel.webview.html = this._getHtmlContent(html, css, warning, siteLabel);
  }

  private _getHtmlContent(
    html: string,
    css: string,
    warning: string | undefined,
    siteLabel?: string
  ): string {
    const warningBlock = warning
      ? `<div role="alert" style="background:#fff3cd;color:#664d03;padding:8px 12px;margin-bottom:16px;border-radius:4px;font-family:sans-serif;font-size:13px;">${escapeHtml(warning)}</div>`
      : '';
    // 適用中のCSSの出所を明示する。どのサイトの見た目を見ているのか分からないと
    // 環境間の差分確認という目的を果たせないため。
    const siteBanner = siteLabel
      ? `<div style="background:var(--vscode-editorWidget-background,#eee);color:var(--vscode-foreground,#333);padding:6px 12px;margin-bottom:12px;border-radius:4px;font-family:sans-serif;font-size:12px;">適用中のCSS: ${escapeHtml(siteLabel)}</div>`
      : '';
    return `<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="${buildStaticCsp()}">
<title>Article Preview</title>
<style>
${css}
</style>
</head>
<body>
${siteBanner}
${warningBlock}
<main>
${html}
</main>
</body>
</html>`;
  }
}

function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}
