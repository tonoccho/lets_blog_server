import * as vscode from 'vscode';
import * as api from './apiClient';
import { getActor, getServerUrl, requireApiKey } from './config';

/**
 * VSCode拡張の「Let's Blog: Generate Section」用WebviewPanel。見出し単位で本文/リード文を
 * AI生成し、選択範囲があれば置換、なければカーソル位置へ挿入する。WebviewPanelの骨格は
 * imageGenPanel.ts(acquireVsCodeApi + postMessageディスパッチ)に準拠する。
 */
export class SectionGenPanel {
  private static currentPanel: SectionGenPanel | undefined;
  private readonly _panel: vscode.WebviewPanel;

  static createOrShow(
    context: vscode.ExtensionContext,
    editor: vscode.TextEditor,
    articleTitle: string | undefined
  ): void {
    if (SectionGenPanel.currentPanel) {
      SectionGenPanel.currentPanel._panel.reveal(vscode.ViewColumn.Beside);
      return;
    }
    SectionGenPanel.currentPanel = new SectionGenPanel(context, editor, articleTitle);
  }

  private constructor(
    private readonly _context: vscode.ExtensionContext,
    private readonly _editor: vscode.TextEditor,
    private readonly _articleTitle: string | undefined
  ) {
    this._panel = vscode.window.createWebviewPanel(
      'letsBlog.sectionGen',
      'Generate Section',
      vscode.ViewColumn.Beside,
      { enableScripts: true, retainContextWhenHidden: true }
    );
    this._panel.onDidDispose(() => this.dispose(), null);
    this._panel.webview.onDidReceiveMessage((message) => this._handleMessage(message), null);
    this._panel.webview.html = this._getHtmlContent();
  }

  private dispose(): void {
    SectionGenPanel.currentPanel = undefined;
    this._panel.dispose();
  }

  private _sendMessage(command: string, payload: unknown): void {
    this._panel.webview.postMessage({ command, payload });
  }

  private async _handleMessage(message: { command: string; [key: string]: unknown }): Promise<void> {
    try {
      switch (message.command) {
        case 'init':
          this._sendMessage('init', {
            articleTitle: this._articleTitle ?? '',
            selectedText: this._editor.document.getText(this._editor.selection),
          });
          break;
        case 'generate':
          await this._handleGenerate(message as unknown as GenerateMessage);
          break;
        case 'insert':
          await this._handleInsert(message as unknown as InsertMessage);
          break;
      }
    } catch (error) {
      this._sendMessage('error', { error: String(error instanceof Error ? error.message : error) });
    }
  }

  private async _handleGenerate(message: GenerateMessage): Promise<void> {
    const apiKey = await requireApiKey(this._context);
    const actor = await getActor(this._context);
    const result = await api.generateSection(getServerUrl(), apiKey, actor, message.params);
    this._sendMessage('generated', result);
  }

  private async _handleInsert(message: InsertMessage): Promise<void> {
    const edit = new vscode.WorkspaceEdit();
    if (!this._editor.selection.isEmpty) {
      edit.replace(this._editor.document.uri, this._editor.selection, message.text);
    } else {
      edit.insert(this._editor.document.uri, this._editor.selection.active, message.text);
    }
    await vscode.workspace.applyEdit(edit);
    await this._editor.document.save();
    this._sendMessage('inserted', {});
    vscode.window.showInformationMessage('記事に挿入しました。');
  }

  private _getHtmlContent(): string {
    return `<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Generate Section</title>
<style>
  * { box-sizing: border-box; }
  body { font-family: var(--vscode-font-family, sans-serif); padding: 16px; color: var(--vscode-foreground); }
  .container { max-width: 640px; }
  .form-group { margin-bottom: 10px; }
  .form-group label { display: block; margin-bottom: 4px; }
  .form-group input, .form-group select, .form-group textarea {
    width: 100%; padding: 6px; background: var(--vscode-input-background, #fff);
    color: var(--vscode-input-foreground, inherit); border: 1px solid var(--vscode-panel-border, #ccc);
  }
  textarea { min-height: 80px; font-family: inherit; }
  button { padding: 6px 14px; cursor: pointer; }
  button.primary { background: var(--vscode-button-background, #007acc); color: var(--vscode-button-foreground, #fff); border: none; }
  button:disabled { opacity: 0.6; cursor: default; }
  .result-section { display: none; border: 1px solid var(--vscode-panel-border, #ccc); padding: 10px; margin-top: 16px; }
  .result-text { white-space: pre-wrap; margin-bottom: 8px; }
  .sources { font-size: 0.85em; opacity: 0.85; margin-bottom: 8px; }
  .sources a { color: var(--vscode-textLink-foreground, #3794ff); }
  .search-note { font-size: 0.85em; opacity: 0.7; font-style: italic; margin-bottom: 8px; }
  #message { margin-top: 16px; display: none; }
  #message.error { color: var(--vscode-errorForeground, #d32f2f); }
  #message.success { color: #388e3c; }
</style>
</head>
<body>
<div class="container">
  <div class="form-group">
    <label>モード</label>
    <select id="mode">
      <option value="body">本文</option>
      <option value="lead">リード文</option>
    </select>
  </div>
  <div class="form-group">
    <label>記事タイトル</label>
    <input type="text" id="articleTitle">
  </div>
  <div class="form-group">
    <label>見出し</label>
    <input type="text" id="heading" placeholder="このセクションの見出し">
  </div>
  <div class="form-group" id="precedingContextGroup">
    <label>直前までの文脈(任意。選択範囲があれば自動入力されます)</label>
    <textarea id="precedingContext"></textarea>
  </div>
  <button id="generateButton" class="primary">生成</button>

  <div class="result-section" id="resultSection">
    <div class="result-text" id="resultText"></div>
    <div class="sources" id="sourcesArea"></div>
    <div class="search-note" id="searchNoteArea"></div>
    <button id="insertButton" class="primary">記事に挿入</button>
  </div>

  <div id="message"></div>
</div>

<script>
  const vscode = acquireVsCodeApi();
  let currentResult = null;

  function post(command, payload) {
    vscode.postMessage(Object.assign({ command }, payload || {}));
  }

  function showMessage(text, type) {
    const el = document.getElementById('message');
    el.textContent = text;
    el.className = type || '';
    el.style.display = 'block';
  }

  document.getElementById('mode').addEventListener('change', (e) => {
    document.getElementById('precedingContextGroup').style.display = e.target.value === 'body' ? 'block' : 'none';
  });

  function generate() {
    const heading = document.getElementById('heading').value.trim();
    if (!heading) {
      showMessage('見出しを入力してください。', 'error');
      return;
    }
    const params = {
      mode: document.getElementById('mode').value,
      heading,
      articleTitle: document.getElementById('articleTitle').value || undefined,
      precedingContext: document.getElementById('precedingContext').value || undefined,
    };
    document.getElementById('generateButton').disabled = true;
    showMessage('生成しています…', '');
    post('generate', { params });
  }

  function renderResult(result) {
    currentResult = result;
    document.getElementById('generateButton').disabled = false;
    document.getElementById('resultSection').style.display = 'block';
    document.getElementById('resultText').textContent = result.result;

    const sourcesArea = document.getElementById('sourcesArea');
    if (result.sources && result.sources.length > 0) {
      sourcesArea.innerHTML = '出典: ' + result.sources
        .map((s) => '<a href="' + s.url + '">' + s.title + '</a>')
        .join(', ');
    } else {
      sourcesArea.innerHTML = '';
    }

    const noteArea = document.getElementById('searchNoteArea');
    noteArea.textContent = result.searchNote || '';

    showMessage('生成しました。', 'success');
  }

  function insertText() {
    if (!currentResult) return;
    post('insert', { text: currentResult.result });
  }

  document.getElementById('generateButton').addEventListener('click', generate);
  document.getElementById('insertButton').addEventListener('click', insertText);

  window.addEventListener('message', (event) => {
    const { command, payload } = event.data;
    switch (command) {
      case 'init':
        document.getElementById('articleTitle').value = payload.articleTitle || '';
        if (payload.selectedText) {
          document.getElementById('precedingContext').value = payload.selectedText;
        }
        break;
      case 'generated':
        renderResult(payload);
        break;
      case 'inserted':
        showMessage('記事に挿入しました。', 'success');
        break;
      case 'error':
        document.getElementById('generateButton').disabled = false;
        showMessage(payload.error, 'error');
        break;
    }
  });

  post('init');
</script>
</body>
</html>`;
  }
}

interface GenerateMessage {
  command: 'generate';
  params: api.AiSectionParams;
}

interface InsertMessage {
  command: 'insert';
  text: string;
}
