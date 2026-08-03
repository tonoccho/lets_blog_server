import * as vscode from 'vscode';

/**
 * 記事プレビュー用のシングルトンWebviewパネル。マスター環境サイトのCSSを<style>として埋め込み、
 * 変換済みHTMLをそのまま表示する(view-onlyで、Webviewからのメッセージは扱わない)。
 */
export class PreviewPanel {
  public static currentPanel: PreviewPanel | undefined;
  private readonly _panel: vscode.WebviewPanel;

  public static createOrShow(html: string, css: string, warning: string | undefined): void {
    if (PreviewPanel.currentPanel) {
      PreviewPanel.currentPanel._panel.reveal(vscode.ViewColumn.Beside);
      PreviewPanel.currentPanel._update(html, css, warning);
      return;
    }
    PreviewPanel.currentPanel = new PreviewPanel(html, css, warning);
  }

  private constructor(html: string, css: string, warning: string | undefined) {
    this._panel = vscode.window.createWebviewPanel(
      'letsBlog.articlePreview',
      'Article Preview',
      vscode.ViewColumn.Beside,
      { enableScripts: false }
    );
    this._panel.onDidDispose(() => this.dispose(), null);
    this._update(html, css, warning);
  }

  private dispose(): void {
    PreviewPanel.currentPanel = undefined;
    this._panel.dispose();
  }

  private _update(html: string, css: string, warning: string | undefined): void {
    this._panel.webview.html = this._getHtmlContent(html, css, warning);
  }

  private _getHtmlContent(html: string, css: string, warning: string | undefined): string {
    const warningBlock = warning
      ? `<div style="background:#fff3cd;color:#664d03;padding:8px 12px;margin-bottom:16px;border-radius:4px;font-family:sans-serif;font-size:13px;">${escapeHtml(warning)}</div>`
      : '';
    return `<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8">
<title>Article Preview</title>
<style>
${css}
</style>
</head>
<body>
${warningBlock}
${html}
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
