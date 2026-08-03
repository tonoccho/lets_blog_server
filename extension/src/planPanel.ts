import * as vscode from 'vscode';
import * as fs from 'fs';
import * as path from 'path';
import * as api from './apiClient';
import { Actor, getActor, getProjectId, getServerUrl, requireApiKey } from './config';
import { LetsBlogFrontMatter, stringifyArticle } from './frontMatter';

const GITHUB_ISSUE_URL_PATTERN = /^(https:\/\/github\.com\/[^/]+\/[^/]+)\/issues\/\d+$/;

export class PlanPanel {
  public static currentPanel: PlanPanel | undefined;
  private readonly _panel: vscode.WebviewPanel;
  private readonly _context: vscode.ExtensionContext;
  private _lastArticlePath: string | undefined;

  public static createOrShow(context: vscode.ExtensionContext): void {
    if (PlanPanel.currentPanel) {
      PlanPanel.currentPanel._panel.reveal(vscode.ViewColumn.Beside);
      return;
    }
    PlanPanel.currentPanel = new PlanPanel(context);
  }

  private constructor(context: vscode.ExtensionContext) {
    this._context = context;
    this._panel = vscode.window.createWebviewPanel(
      'letsBlog.articlePlan',
      'Article Plan',
      vscode.ViewColumn.Beside,
      { enableScripts: true, retainContextWhenHidden: true }
    );
    this._panel.onDidDispose(() => this.dispose(), null);
    this._panel.webview.onDidReceiveMessage((message) => this._handleMessage(message), null);
    this._panel.webview.html = this._getHtmlContent();
  }

  private dispose(): void {
    PlanPanel.currentPanel = undefined;
    this._panel.dispose();
  }

  private _sendMessage(command: string, payload: unknown): void {
    this._panel.webview.postMessage({ command, payload });
  }

  private async _handleMessage(message: { command: string; [key: string]: unknown }): Promise<void> {
    try {
      switch (message.command) {
        case 'loadIssues':
          await this._handleLoadIssues();
          break;
        case 'sendChat':
          await this._handleSendChat(message as unknown as SendChatMessage);
          break;
        case 'suggestMetadata':
          await this._handleSuggestMetadata(message as unknown as SuggestMetadataMessage);
          break;
        case 'approveAndScaffold':
          await this._handleApproveAndScaffold(message as unknown as ApproveAndScaffoldMessage);
          break;
        case 'openArticle':
          await this._handleOpenArticle();
          break;
      }
    } catch (error) {
      this._sendMessage('error', { error: String(error instanceof Error ? error.message : error) });
    }
  }

  private async _requireContext(): Promise<{ apiKey: string; actor: Actor; projectId: number }> {
    const actor = await getActor(this._context);
    const projectId = getProjectId(this._context);
    if (!actor || !projectId) {
      throw new Error('ユーザーまたはプロジェクトが未選択です。「Let\'s Blog: Select User」「Let\'s Blog: Select Project」を先に実行してください。');
    }
    const apiKey = await requireApiKey(this._context);
    return { apiKey, actor, projectId };
  }

  private async _handleLoadIssues(): Promise<void> {
    const { apiKey, actor, projectId } = await this._requireContext();
    const issues = await api.listUnassignedIssues(getServerUrl(), apiKey, actor, projectId);
    this._sendMessage('issueList', { issues });
  }

  private async _handleSendChat(message: SendChatMessage): Promise<void> {
    const { apiKey, actor, projectId } = await this._requireContext();
    const response = await api.postPlanChat(getServerUrl(), apiKey, actor, projectId, {
      history: message.history,
      message: message.message,
      sessionId: message.sessionId,
      githubIssueNumber: message.issueNumber,
    });
    this._sendMessage('chatResponse', response);
  }

  private async _handleSuggestMetadata(message: SuggestMetadataMessage): Promise<void> {
    const { apiKey, actor, projectId } = await this._requireContext();
    const suggestion = await api.suggestMetadata(getServerUrl(), apiKey, actor, projectId, message.history);
    this._sendMessage('metadataSuggestion', suggestion);
  }

  private async _handleApproveAndScaffold(message: ApproveAndScaffoldMessage): Promise<void> {
    const { apiKey, actor, projectId } = await this._requireContext();
    const { issue, metadata } = message;

    const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
    if (!workspaceFolder) {
      throw new Error('ワークスペースフォルダが開かれていません。');
    }

    const articlesPath = path.join(workspaceFolder.uri.fsPath, 'articles', metadata.slug);
    if (fs.existsSync(articlesPath)) {
      const overwrite = await vscode.window.showWarningMessage(
        `articles/${metadata.slug} は既に存在します。上書きしますか?`,
        'Yes',
        'No'
      );
      if (overwrite !== 'Yes') {
        this._sendMessage('error', { error: 'キャンセルしました。' });
        return;
      }
    }

    fs.mkdirSync(articlesPath, { recursive: true });
    fs.mkdirSync(path.join(articlesPath, 'assets'), { recursive: true });
    fs.writeFileSync(path.join(articlesPath, 'assets', '.gitkeep'), '');

    const description = await api.getIssueDescription(getServerUrl(), apiKey, actor, projectId, issue.number);

    const githubRepositoryMatch = issue.htmlUrl.match(GITHUB_ISSUE_URL_PATTERN);
    const frontMatter: LetsBlogFrontMatter = {
      title: metadata.title,
      slug: metadata.slug,
      categories: metadata.categories,
      tags: metadata.tags,
      status: 'draft',
      github_issue_number: issue.number,
      github_repository: githubRepositoryMatch?.[1],
      project_id: projectId,
    };
    const content = description.trim().length > 0 ? description : '記事本文をここに記入してください。';
    const articlePath = path.join(articlesPath, 'article.md');
    fs.writeFileSync(articlePath, stringifyArticle({ data: frontMatter, content }), 'utf-8');
    this._lastArticlePath = articlePath;

    try {
      await api.assignIssue(getServerUrl(), apiKey, actor, projectId, issue.number);
    } catch (error) {
      this._sendMessage('error', {
        error: `記事ファイルは生成されましたが、issueの割り当てに失敗しました: ${String(error instanceof Error ? error.message : error)}`,
      });
      await this._handleOpenArticle();
      return;
    }

    this._sendMessage('scaffoldCreated', {});
  }

  private async _handleOpenArticle(): Promise<void> {
    if (!this._lastArticlePath) return;
    const doc = await vscode.workspace.openTextDocument(this._lastArticlePath);
    await vscode.window.showTextDocument(doc);
  }

  private _getHtmlContent(): string {
    return `<!DOCTYPE html>
<html lang="ja">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Article Plan</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body { font-family: var(--vscode-font-family, sans-serif); padding: 16px; color: var(--vscode-foreground); }
  .container { max-width: 800px; }
  .section { margin-bottom: 20px; }
  .section-title { font-weight: bold; margin-bottom: 8px; }
  .issue-list { border: 1px solid var(--vscode-panel-border, #ccc); max-height: 160px; overflow-y: auto; }
  .issue-item { padding: 8px; border-bottom: 1px solid var(--vscode-panel-border, #eee); cursor: pointer; }
  .issue-item:hover { background: var(--vscode-list-hoverBackground, #f5f5f5); }
  .issue-item.selected { background: var(--vscode-list-activeSelectionBackground, #e3f2fd); }
  .empty { padding: 8px; opacity: 0.7; }
  .chat-container { border: 1px solid var(--vscode-panel-border, #ccc); height: 280px; display: flex; flex-direction: column; }
  .messages { flex: 1; overflow-y: auto; padding: 10px; }
  .message { margin-bottom: 8px; padding: 8px; border-radius: 4px; white-space: pre-wrap; }
  .message.user { background: var(--vscode-list-activeSelectionBackground, #e3f2fd); text-align: right; }
  .message.assistant { background: var(--vscode-editorWidget-background, #f5f5f5); }
  .chat-input { display: flex; gap: 5px; padding: 8px; border-top: 1px solid var(--vscode-panel-border, #ccc); }
  .chat-input input { flex: 1; padding: 6px; }
  button { padding: 6px 14px; cursor: pointer; }
  button.primary { background: var(--vscode-button-background, #007acc); color: var(--vscode-button-foreground, #fff); border: none; }
  .metadata-form { border: 1px solid var(--vscode-panel-border, #ccc); padding: 10px; }
  .form-group { margin-bottom: 10px; }
  .form-group label { display: block; margin-bottom: 4px; font-weight: bold; }
  .form-group input { width: 100%; padding: 6px; }
  .button-group { display: flex; gap: 10px; }
  #message.error { color: var(--vscode-errorForeground, #d32f2f); }
  #message.success { color: #388e3c; }
</style>
</head>
<body>
<div class="container">
  <div class="section">
    <div class="section-title">未割り当てのIssue</div>
    <div class="issue-list" id="issueList"><div class="empty">読み込み中...</div></div>
  </div>

  <div class="section" id="chatSection" style="display: none;">
    <div class="section-title">壁打ちチャット</div>
    <div class="chat-container">
      <div class="messages" id="messages"></div>
      <div class="chat-input">
        <input type="text" id="chatInput" placeholder="メッセージを入力...">
        <button id="sendButton">送信</button>
      </div>
    </div>
    <button id="suggestButton" class="primary" style="margin-top: 10px;">メタデータを提案</button>
  </div>

  <div class="section" id="metadataSection" style="display: none;">
    <div class="section-title">提案メタデータ</div>
    <div class="metadata-form">
      <div class="form-group"><label>タイトル</label><input type="text" id="titleInput"></div>
      <div class="form-group"><label>スラッグ</label><input type="text" id="slugInput"></div>
      <div class="form-group"><label>カテゴリ (カンマ区切り)</label><input type="text" id="categoriesInput"></div>
      <div class="form-group"><label>タグ (カンマ区切り)</label><input type="text" id="tagsInput"></div>
      <div class="button-group">
        <button id="approveButton" class="primary">承認してスキャフォールド生成</button>
      </div>
    </div>
  </div>

  <div id="message" style="margin-top: 16px; display: none;"></div>
</div>

<script>
  const vscode = acquireVsCodeApi();
  let selectedIssue = null;
  let sessionId = undefined;
  let chatHistory = [];

  function post(command, payload) {
    vscode.postMessage(Object.assign({ command }, payload || {}));
  }

  function renderIssueList(issues) {
    const el = document.getElementById('issueList');
    el.innerHTML = '';
    if (issues.length === 0) {
      el.innerHTML = '<div class="empty">未割り当てのissueはありません。</div>';
      return;
    }
    issues.forEach((issue) => {
      const item = document.createElement('div');
      item.className = 'issue-item';
      item.textContent = '#' + issue.number + ': ' + issue.title;
      item.addEventListener('click', () => selectIssue(issue, item));
      el.appendChild(item);
    });
  }

  function selectIssue(issue, el) {
    selectedIssue = issue;
    sessionId = undefined;
    chatHistory = [];
    document.getElementById('messages').innerHTML = '';
    document.querySelectorAll('.issue-item').forEach((n) => n.classList.remove('selected'));
    el.classList.add('selected');
    document.getElementById('chatSection').style.display = 'block';
    document.getElementById('metadataSection').style.display = 'none';
  }

  function addMessage(role, content) {
    chatHistory.push({ role, content });
    const messagesDiv = document.getElementById('messages');
    const msgEl = document.createElement('div');
    msgEl.className = 'message ' + role;
    msgEl.textContent = content;
    messagesDiv.appendChild(msgEl);
    messagesDiv.scrollTop = messagesDiv.scrollHeight;
  }

  function sendMessage() {
    const input = document.getElementById('chatInput');
    const text = input.value.trim();
    if (!text || !selectedIssue) return;
    addMessage('user', text);
    input.value = '';
    post('sendChat', { history: chatHistory.slice(0, -1), message: text, sessionId, issueNumber: selectedIssue.number });
  }

  function requestMetadataSuggestion() {
    post('suggestMetadata', { history: chatHistory });
  }

  function showMetadataForm(suggestion) {
    document.getElementById('titleInput').value = suggestion.title || '';
    document.getElementById('slugInput').value = suggestion.slug || '';
    document.getElementById('categoriesInput').value = (suggestion.categories || []).join(', ');
    document.getElementById('tagsInput').value = (suggestion.tags || []).join(', ');
    document.getElementById('metadataSection').style.display = 'block';
  }

  function approveMetadata() {
    const title = document.getElementById('titleInput').value.trim();
    const slug = document.getElementById('slugInput').value.trim();
    const categories = document.getElementById('categoriesInput').value.split(',').map((s) => s.trim()).filter(Boolean);
    const tags = document.getElementById('tagsInput').value.split(',').map((s) => s.trim()).filter(Boolean);
    if (!title || !slug) {
      showMessage('タイトルとスラッグは必須です。', 'error');
      return;
    }
    post('approveAndScaffold', { issue: selectedIssue, metadata: { title, slug, categories, tags } });
  }

  function showMessage(text, type) {
    const el = document.getElementById('message');
    el.textContent = text;
    el.className = type || '';
    el.style.display = 'block';
  }

  document.getElementById('sendButton').addEventListener('click', sendMessage);
  document.getElementById('chatInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') sendMessage();
  });
  document.getElementById('suggestButton').addEventListener('click', requestMetadataSuggestion);
  document.getElementById('approveButton').addEventListener('click', approveMetadata);

  window.addEventListener('message', (event) => {
    const { command, payload } = event.data;
    switch (command) {
      case 'issueList':
        renderIssueList(payload.issues);
        break;
      case 'chatResponse':
        addMessage('assistant', payload.reply);
        sessionId = payload.sessionId;
        break;
      case 'metadataSuggestion':
        showMetadataForm(payload);
        break;
      case 'scaffoldCreated':
        showMessage('記事のスキャフォールドを生成しました。', 'success');
        setTimeout(() => post('openArticle'), 500);
        break;
      case 'error':
        showMessage(payload.error, 'error');
        break;
    }
  });

  post('loadIssues');
</script>
</body>
</html>`;
  }
}

interface SendChatMessage {
  command: 'sendChat';
  history: api.PlanChatMessage[];
  message: string;
  sessionId?: number;
  issueNumber: number;
}

interface SuggestMetadataMessage {
  command: 'suggestMetadata';
  history: api.PlanChatMessage[];
}

interface ApproveAndScaffoldMessage {
  command: 'approveAndScaffold';
  issue: api.RepositoryIssue;
  metadata: { title: string; slug: string; categories: string[]; tags: string[] };
}
