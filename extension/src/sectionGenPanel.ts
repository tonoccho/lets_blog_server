import * as vscode from 'vscode';
import * as api from './apiClient';
import { getActor, getServerUrl, requireApiKey } from './config';
import { SectionContext } from './headingContext';
import { describeError } from './errorHandler';
import { buildScriptedCsp, createNonce } from './webviewSecurity';
import { logger } from './logger';

/**
 * VSCode拡張の「Let's Blog: Generate Section」用WebviewPanel。カーソル位置の見出し階層から
 * 自動判定したモード(本文/リード文/サブセクション考慮リード文)で生成し、追加の指示による
 * 壁打ち(再生成)を経て、選択範囲があれば置換・なければカーソル位置へ挿入する。
 * WebviewPanelの骨格はimageGenPanel.ts(acquireVsCodeApi + postMessageディスパッチ)に準拠する。
 */
export class SectionGenPanel {
  private static currentPanel: SectionGenPanel | undefined;
  private readonly _panel: vscode.WebviewPanel;

  static createOrShow(
    context: vscode.ExtensionContext,
    editor: vscode.TextEditor,
    articleTitle: string | undefined,
    sectionContext: SectionContext
  ): void {
    if (SectionGenPanel.currentPanel) {
      SectionGenPanel.currentPanel._panel.reveal(vscode.ViewColumn.Beside);
      return;
    }
    SectionGenPanel.currentPanel = new SectionGenPanel(context, editor, articleTitle, sectionContext);
  }

  private constructor(
    private readonly _context: vscode.ExtensionContext,
    private readonly _editor: vscode.TextEditor,
    private readonly _articleTitle: string | undefined,
    private readonly _sectionContext: SectionContext
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
            sectionContext: this._sectionContext,
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
      const description = describeError(error);
      logger.error(`${this.constructor.name}: ${String(message.command)} に失敗しました: ${description}`, {
        stack: error instanceof Error ? error.stack : undefined,
      });
      this._sendMessage('error', { error: description });
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
    const nonce = createNonce();
    return `<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="${buildScriptedCsp(nonce)}">
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
  .hint { font-size: 0.85em; opacity: 0.75; margin: -4px 0 10px; }
  .chat { display: none; border: 1px solid var(--vscode-panel-border, #ccc); padding: 10px; margin-top: 16px; }
  .bubble { white-space: pre-wrap; margin-bottom: 10px; padding: 8px; border-radius: 4px; }
  .bubble.assistant { background: var(--vscode-editor-inactiveSelectionBackground, #2a2d2e); }
  .bubble.user { background: var(--vscode-list-hoverBackground, #37373d); text-align: right; }
  .bubble .role { font-size: 0.75em; opacity: 0.7; display: block; margin-bottom: 4px; }
  .sources { font-size: 0.85em; opacity: 0.85; margin-bottom: 8px; }
  .sources a { color: var(--vscode-textLink-foreground, #3794ff); }
  .search-note { font-size: 0.85em; opacity: 0.7; font-style: italic; margin-bottom: 8px; }
  .refine-row { display: flex; gap: 8px; margin-top: 10px; }
  .refine-row input { flex: 1; }
  .button-group { display: flex; gap: 10px; margin-top: 10px; }
  #message { margin-top: 16px; display: none; }
  #message.error { color: var(--vscode-errorForeground, #d32f2f); }
  #message.success { color: #388e3c; }
</style>
</head>
<body>
<div class="container">
  <div class="form-group">
    <label>モード(カーソル位置から自動判定。必要に応じて変更可)</label>
    <select id="mode">
      <option value="body">本文(セクションタイトルを考慮)</option>
      <option value="lead">リード文(記事全体を考慮)</option>
      <option value="lead-subsections">リード文(サブセクションを考慮)</option>
    </select>
  </div>
  <div class="form-group">
    <label>記事タイトル</label>
    <input type="text" id="articleTitle">
  </div>
  <div class="form-group" id="headingGroup">
    <label>見出し</label>
    <input type="text" id="heading" placeholder="このセクションの見出し">
  </div>
  <div class="hint" id="outlineHint"></div>
  <div class="form-group" id="precedingContextGroup">
    <label>直前までの文脈(任意。選択範囲やカーソル位置までの本文から自動入力されます)</label>
    <textarea id="precedingContext"></textarea>
  </div>
  <button id="generateButton" class="primary">生成</button>

  <div class="chat" id="chat">
    <div id="messages"></div>
    <div class="sources" id="sourcesArea"></div>
    <div class="search-note" id="searchNoteArea"></div>
    <div class="refine-row">
      <input type="text" id="refineInput" placeholder="追加の指示(例: もっと短く、丁寧語で)">
      <button id="refineButton">壁打ちで再生成</button>
    </div>
    <div class="button-group">
      <button id="insertButton" class="primary">この案を記事に挿入</button>
    </div>
  </div>

  <div id="message"></div>
</div>

<script nonce="${nonce}">
  const vscode = acquireVsCodeApi();
  let currentResult = null;
  let chatHistory = [];
  let pendingUserMessage = null;
  let subsectionHeadings = [];

  function post(command, payload) {
    vscode.postMessage(Object.assign({ command }, payload || {}));
  }

  function showMessage(text, type) {
    const el = document.getElementById('message');
    el.textContent = text;
    el.className = type || '';
    el.style.display = 'block';
  }

  function applyModeVisibility() {
    const mode = document.getElementById('mode').value;
    document.getElementById('precedingContextGroup').style.display = mode === 'body' ? 'block' : 'none';
    document.getElementById('headingGroup').style.display = mode === 'lead' ? 'none' : 'block';
    const hint = document.getElementById('outlineHint');
    if ((mode === 'lead' || mode === 'lead-subsections') && subsectionHeadings.length > 0) {
      const label = mode === 'lead' ? '記事の構成: ' : 'サブセクション: ';
      hint.textContent = label + subsectionHeadings.join(' / ');
    } else {
      hint.textContent = '';
    }
  }

  document.getElementById('mode').addEventListener('change', applyModeVisibility);

  function baseParams() {
    return {
      mode: document.getElementById('mode').value,
      heading: document.getElementById('heading').value.trim() || undefined,
      articleTitle: document.getElementById('articleTitle').value || undefined,
      precedingContext: document.getElementById('precedingContext').value || undefined,
      subsectionHeadings: subsectionHeadings.length > 0 ? subsectionHeadings : undefined,
    };
  }

  function generate() {
    document.getElementById('generateButton').disabled = true;
    showMessage('生成しています…', '');
    chatHistory = [];
    pendingUserMessage = null;
    document.getElementById('messages').innerHTML = '';
    post('generate', { params: baseParams() });
  }

  function refine() {
    const instruction = document.getElementById('refineInput').value.trim();
    if (!instruction || !currentResult) return;
    document.getElementById('refineButton').disabled = true;
    showMessage('再生成しています…', '');
    pendingUserMessage = instruction;
    post('generate', { params: Object.assign(baseParams(), { history: chatHistory, message: instruction }) });
  }

  function appendBubble(role, text) {
    const div = document.createElement('div');
    div.className = 'bubble ' + role;
    const roleLabel = document.createElement('span');
    roleLabel.className = 'role';
    roleLabel.textContent = role === 'assistant' ? 'AI提案' : '追加の指示';
    div.appendChild(roleLabel);
    const body = document.createElement('div');
    body.textContent = text;
    div.appendChild(body);
    document.getElementById('messages').appendChild(div);
  }

  function renderResult(result) {
    currentResult = result;
    document.getElementById('generateButton').disabled = false;
    document.getElementById('refineButton').disabled = false;
    document.getElementById('refineInput').value = '';
    document.getElementById('chat').style.display = 'block';

    if (pendingUserMessage) {
      chatHistory.push({ role: 'user', content: pendingUserMessage });
      appendBubble('user', pendingUserMessage);
      pendingUserMessage = null;
    }
    chatHistory.push({ role: 'assistant', content: result.result });
    appendBubble('assistant', result.result);

    // 出典はサーバー/AI由来の文字列のため、HTMLとして組み立てずDOM APIで構築する
    // (タイトルやURLにマークアップが混入しても要素として解釈されないようにする)。
    const sourcesArea = document.getElementById('sourcesArea');
    sourcesArea.textContent = '';
    if (result.sources && result.sources.length > 0) {
      sourcesArea.appendChild(document.createTextNode('出典: '));
      result.sources.forEach((s, index) => {
        if (index > 0) sourcesArea.appendChild(document.createTextNode(', '));
        const link = document.createElement('a');
        link.href = s.url;
        link.textContent = s.title;
        sourcesArea.appendChild(link);
      });
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
  document.getElementById('refineButton').addEventListener('click', refine);
  document.getElementById('insertButton').addEventListener('click', insertText);

  window.addEventListener('message', (event) => {
    const { command, payload } = event.data;
    switch (command) {
      case 'init': {
        document.getElementById('articleTitle').value = payload.articleTitle || '';
        const ctx = payload.sectionContext || {};
        document.getElementById('mode').value = ctx.mode || 'body';
        if (ctx.heading) {
          document.getElementById('heading').value = ctx.heading;
        }
        subsectionHeadings = ctx.subsectionHeadings || [];
        if (ctx.mode === 'body' && ctx.precedingContext) {
          document.getElementById('precedingContext').value = ctx.precedingContext;
        } else if (payload.selectedText) {
          document.getElementById('precedingContext').value = payload.selectedText;
        }
        applyModeVisibility();
        break;
      }
      case 'generated':
        renderResult(payload);
        break;
      case 'inserted':
        showMessage('記事に挿入しました。', 'success');
        break;
      case 'error':
        document.getElementById('generateButton').disabled = false;
        document.getElementById('refineButton').disabled = false;
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
