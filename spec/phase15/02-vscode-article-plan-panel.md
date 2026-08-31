# Phase 15-02: VSCode Webviewパネル・壁打ちチャット・メタデータ提案

## スコープ

VSCode拡張でのWebviewパネル実装。issue一覧表示 → マルチターン壁打ちチャット → メタデータ提案・編集 → 承認。バックエンド側はメタデータ提案エンドポイント(`/suggest-metadata`)を新規追加。

## タスク一覧

### バックエンド

- [ ] `ArticlePlanService.suggestMetadata(projectId, history)` メソッド実装
- [ ] 新規DTO: `SuggestMetadataRequest`, `SuggestMetadataResponse`
- [ ] `ArticlePlanController` に `POST /suggest-metadata` エンドポイント追加
- [ ] `SuggestTitlesResponse` と同様の JSON 抽出・フォールバック実装
- [ ] `GenerationJobRepository` に type="plan_suggest_metadata" の記録
- [ ] ユニットテスト(`ArticlePlanServiceTest`)

### VSCode拡張

- [ ] `planPanel.ts` (新規): Webviewパネルの主体
  - issue一覧取得・表示
  - マルチターンチャットUI
  - メタデータ提案呼び出し・フォーム編集
- [ ] `apiClient.ts`: 新規API関数群
  - `listUnassignedIssues`
  - `postPlanChat`
  - `getIssueDescription`
  - `suggestMetadata`
- [ ] `extension.ts`: `letsBlog.planArticle` コマンド実装
- [ ] `package.json`: コマンド定義追加

## 詳細設計

### バックエンド: ArticlePlanService.suggestMetadata

#### メソッドシグネチャ

```java
public SuggestMetadataResponse suggestMetadata(Long projectId, List<PlanChatMessage> history) {
    // Ollamaに「カテゴリ・タグ・スラッグ・タイトルをJSONオブジェクトで提案」させる
    // 既存のsuggestTitles/suggestStructure と同じパターンで実装
}
```

#### 実装詳細

1. `startJob("plan_suggest_metadata", ...)`で GenerationJob 開始
2. `ollamaModelService.getSelectedModel(projectId)`でモデル取得
3. プロンプト構築（会話履歴 + 提案指示）
4. `ollamaClient.generate(prompt, model)`呼び出し
5. JSON抽出・パース → `SuggestMetadataResponse` 生成
6. `completeJob`で記録

#### プロンプト例

```
System: あなたはブログ記事企画の壁打ち相手です。...

User: ...
Assistant: ...
[履歴がある分だけ繰り返し]

上記の会話から、記事の以下の情報をJSON形式で提案してください:
{
  "title": "記事タイトル(20-50文字程度)",
  "slug": "article-slug(URLに適した英数字)",
  "categories": ["カテゴリ1", "カテゴリ2"],
  "tags": ["タグ1", "タグ2", "タグ3"]
}

出力はJSONオブジェクトのみとし、説明文は含めないでください。
```

#### JSON抽出と解析

```java
private SuggestMetadataResponse parseMetadata(String raw) {
    String jsonPart = extractJsonObject(raw);  // { ... } 部分を抽出
    try {
        JsonNode node = objectMapper.readTree(jsonPart);
        String title = node.get("title").asText("");
        String slug = node.get("slug").asText("");
        List<String> categories = parseStringArray(node.get("categories"));
        List<String> tags = parseStringArray(node.get("tags"));
        return new SuggestMetadataResponse(title, slug, categories, tags);
    } catch (Exception e) {
        return new SuggestMetadataResponse("", "", List.of(), List.of());  // フォールバック
    }
}

private String extractJsonObject(String raw) {
    int start = raw.indexOf('{');
    int end = raw.lastIndexOf('}');
    if (start < 0 || end < 0 || end < start) {
        return "{}";
    }
    return raw.substring(start, end + 1);
}

private List<String> parseStringArray(JsonNode node) {
    if (node == null || !node.isArray()) {
        return List.of();
    }
    List<String> result = new ArrayList<>();
    for (JsonNode item : node) {
        String str = item.asText("").strip();
        if (!str.isBlank()) {
            result.add(str);
        }
    }
    return result;
}
```

### バックエンド: DTO

#### SuggestMetadataRequest

```java
public record SuggestMetadataRequest(
    List<PlanChatMessage> history
) {}
```

#### SuggestMetadataResponse

```java
public record SuggestMetadataResponse(
    String title,
    String slug,
    List<String> categories,
    List<String> tags
) {}
```

### バックエンド: ArticlePlanController エンドポイント

```java
@PostMapping("/suggest-metadata")
public SuggestMetadataResponse suggestMetadata(
        @PathVariable Long projectId,
        @Valid @RequestBody SuggestMetadataRequest request) {
    adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
    return articlePlanService.suggestMetadata(projectId, request.history());
}
```

### VSCode拡張: apiClient.ts 新規関数

```typescript
// Issue一覧(未割り当てのフィルタはクライアント側)
export async function listIssues(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  state: string = 'open'
): Promise<RepositoryIssue[]> {
  const res = await fetch(`${serverUrl}/api/projects/${projectId}/article-plan/issues?state=${state}`, {
    headers: buildHeaders(apiKey, actor)
  });
  await assertOk(res);
  const issues = (await res.json()) as RepositoryIssue[];
  // クライアント側でassignees フィルタ
  return issues.filter(i => !i.assignees || i.assignees.length === 0);
}

interface RepositoryIssue {
  number: number;
  title: string;
  htmlUrl: string;
  state: string;
  assignees?: string[];
}

// マルチターンチャット
export async function postPlanChat(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  request: PlanChatRequest
): Promise<PlanChatResponse> {
  const res = await fetch(`${serverUrl}/api/projects/${projectId}/article-plan/chat`, {
    method: 'POST',
    headers: buildHeaders(apiKey, actor, 'application/json'),
    body: JSON.stringify(request)
  });
  await assertOk(res);
  return (await res.json()) as PlanChatResponse;
}

interface PlanChatRequest {
  history: PlanChatMessage[];
  message: string;
  sessionId?: number;
  githubIssueNumber?: number;
}

interface PlanChatMessage {
  role: 'user' | 'assistant';
  content: string;
}

interface PlanChatResponse {
  reply: string;
  sessionId: number;
}

// Issue description 取得
export async function getIssueDescription(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  issueNumber: number
): Promise<string> {
  const res = await fetch(
    `${serverUrl}/api/projects/${projectId}/article-plan/issues/${issueNumber}/description`,
    { headers: buildHeaders(apiKey, actor) }
  );
  await assertOk(res);
  const data = (await res.json()) as { description: string };
  return data.description ?? '';
}

// メタデータ提案
export async function suggestMetadata(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  history: PlanChatMessage[]
): Promise<SuggestMetadataResponse> {
  const res = await fetch(`${serverUrl}/api/projects/${projectId}/article-plan/suggest-metadata`, {
    method: 'POST',
    headers: buildHeaders(apiKey, actor, 'application/json'),
    body: JSON.stringify({ history })
  });
  await assertOk(res);
  return (await res.json()) as SuggestMetadataResponse;
}

interface SuggestMetadataResponse {
  title: string;
  slug: string;
  categories: string[];
  tags: string[];
}
```

### VSCode拡張: planPanel.ts (新規ファイル)

全体構成:

```typescript
import * as vscode from 'vscode';
import * as api from './apiClient';
import { Actor, getActor, getProjectId } from './config';
import { getServerUrl, requireApiKey } from './config';

export class PlanPanel {
  public static currentPanel: PlanPanel | undefined;
  private readonly _panel: vscode.WebviewPanel;
  private readonly _extensionUri: vscode.Uri;
  private _disposed = false;

  public static createOrShow(extensionUri: vscode.Uri) {
    // 既存パネル があれば reveal のみ、なければ新規作成
    if (PlanPanel.currentPanel) {
      PlanPanel.currentPanel._panel.reveal(vscode.ViewColumn.Beside);
      return;
    }
    PlanPanel.currentPanel = new PlanPanel(extensionUri);
  }

  private constructor(extensionUri: vscode.Uri) {
    this._extensionUri = extensionUri;
    this._panel = vscode.window.createWebviewPanel(
      'letsBlog.articlePlan',
      'Article Plan',
      vscode.ViewColumn.Beside,
      { enableScripts: true }
    );
    this._panel.onDidDispose(() => this.dispose(), null);
    this._panel.webview.onDidReceiveMessage(
      (message) => this._handleMessage(message),
      null
    );
    this._update();
  }

  private dispose() {
    this._disposed = true;
    this._panel.dispose();
    PlanPanel.currentPanel = undefined;
  }

  private async _update() {
    this._panel.webview.html = await this._getHtmlContent();
  }

  private async _getHtmlContent(): Promise<string> {
    // インラインHTMLを生成
    // CSS + JavaScript をすべて含める（self-contained Webview）
    return `<!DOCTYPE html>
      <html>
      <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>Article Plan</title>
        <style>
          * { margin: 0; padding: 0; box-sizing: border-box; }
          body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; padding: 20px; }
          .container { max-width: 800px; }
          .section { margin-bottom: 20px; }
          .section-title { font-weight: bold; margin-bottom: 10px; }
          .issue-list { border: 1px solid #ccc; max-height: 150px; overflow-y: auto; }
          .issue-item { padding: 8px; border-bottom: 1px solid #eee; cursor: pointer; }
          .issue-item:hover { background: #f5f5f5; }
          .issue-item.selected { background: #e3f2fd; }
          .chat-container { border: 1px solid #ccc; height: 300px; display: flex; flex-direction: column; }
          .messages { flex: 1; overflow-y: auto; padding: 10px; }
          .message { margin-bottom: 10px; padding: 8px; border-radius: 4px; }
          .message.user { background: #e3f2fd; text-align: right; }
          .message.assistant { background: #f5f5f5; }
          .chat-input { display: flex; gap: 5px; padding: 10px; border-top: 1px solid #ccc; }
          .chat-input input { flex: 1; padding: 8px; border: 1px solid #ccc; }
          .chat-input button { padding: 8px 16px; background: #007acc; color: white; border: none; cursor: pointer; }
          .metadata-form { border: 1px solid #ccc; padding: 10px; }
          .form-group { margin-bottom: 10px; }
          .form-group label { display: block; margin-bottom: 5px; font-weight: bold; }
          .form-group input, .form-group textarea { width: 100%; padding: 8px; border: 1px solid #ccc; }
          .button-group { display: flex; gap: 10px; }
          .button-group button { padding: 8px 16px; cursor: pointer; }
          .button-group button.primary { background: #007acc; color: white; border: none; }
          .button-group button.secondary { background: #f5f5f5; border: 1px solid #ccc; }
          .error { color: #d32f2f; }
          .success { color: #388e3c; }
        </style>
      </head>
      <body>
        <div class="container">
          <div class="section">
            <div class="section-title">Issue Selection</div>
            <div class="issue-list" id="issueList"></div>
          </div>

          <div class="section" id="chatSection" style="display: none;">
            <div class="section-title">Brainstorm Chat</div>
            <div class="chat-container">
              <div class="messages" id="messages"></div>
              <div class="chat-input">
                <input type="text" id="chatInput" placeholder="Ask AI...">
                <button onclick="sendMessage()">Send</button>
              </div>
            </div>
            <button onclick="suggestMetadata()" style="margin-top: 10px; padding: 8px 16px; background: #007acc; color: white; border: none; cursor: pointer;">
              Get Metadata Suggestion
            </button>
          </div>

          <div class="section" id="metadataSection" style="display: none;">
            <div class="section-title">Suggested Metadata</div>
            <div class="metadata-form">
              <div class="form-group">
                <label>Title</label>
                <input type="text" id="titleInput" placeholder="Article title">
              </div>
              <div class="form-group">
                <label>Slug</label>
                <input type="text" id="slugInput" placeholder="article-slug">
              </div>
              <div class="form-group">
                <label>Categories (comma-separated)</label>
                <input type="text" id="categoriesInput" placeholder="Category1, Category2">
              </div>
              <div class="form-group">
                <label>Tags (comma-separated)</label>
                <input type="text" id="tagsInput" placeholder="tag1, tag2, tag3">
              </div>
              <div class="button-group">
                <button class="primary" onclick="approveMetadata()">Approve & Create Scaffold</button>
                <button class="secondary" onclick="resetForm()">Reset</button>
              </div>
            </div>
          </div>

          <div id="message" style="margin-top: 20px; padding: 10px; border-radius: 4px; display: none;"></div>
        </div>

        <script>
          const vscode = acquireVsCodeApi();
          
          let selectedIssue = null;
          let sessionId = null;
          let chatHistory = [];

          async function loadIssues() {
            vscode.postMessage({ command: 'loadIssues' });
          }

          function selectIssue(issue) {
            selectedIssue = issue;
            document.querySelectorAll('.issue-item').forEach(el => el.classList.remove('selected'));
            event.target.classList.add('selected');
            document.getElementById('chatSection').style.display = 'block';
            vscode.postMessage({ command: 'selectIssue', issue });
          }

          async function sendMessage() {
            const input = document.getElementById('chatInput');
            const message = input.value.trim();
            if (!message) return;

            addMessage('user', message);
            input.value = '';

            vscode.postMessage({
              command: 'sendChat',
              message,
              sessionId,
              issueNumber: selectedIssue.number
            });
          }

          async function suggestMetadata() {
            vscode.postMessage({ command: 'suggestMetadata', sessionId, chatHistory });
          }

          async function approveMetadata() {
            const title = document.getElementById('titleInput').value.trim();
            const slug = document.getElementById('slugInput').value.trim();
            const categories = document.getElementById('categoriesInput').value.split(',').map(s => s.trim()).filter(s => s);
            const tags = document.getElementById('tagsInput').value.split(',').map(s => s.trim()).filter(s => s);

            if (!title || !slug) {
              showMessage('Title and Slug are required.', 'error');
              return;
            }

            vscode.postMessage({
              command: 'approveAndScaffold',
              issue: selectedIssue,
              metadata: { title, slug, categories, tags }
            });
          }

          function addMessage(role, content) {
            const msg = { role, content };
            chatHistory.push(msg);
            const messagesDiv = document.getElementById('messages');
            const msgEl = document.createElement('div');
            msgEl.className = `message ${role}`;
            msgEl.textContent = content;
            messagesDiv.appendChild(msgEl);
            messagesDiv.scrollTop = messagesDiv.scrollHeight;
          }

          function showMetadataForm(suggestion) {
            document.getElementById('titleInput').value = suggestion.title;
            document.getElementById('slugInput').value = suggestion.slug;
            document.getElementById('categoriesInput').value = suggestion.categories.join(', ');
            document.getElementById('tagsInput').value = suggestion.tags.join(', ');
            document.getElementById('metadataSection').style.display = 'block';
          }

          function showMessage(text, type = 'info') {
            const msgDiv = document.getElementById('message');
            msgDiv.textContent = text;
            msgDiv.className = type;
            msgDiv.style.display = 'block';
          }

          function resetForm() {
            document.getElementById('titleInput').value = '';
            document.getElementById('slugInput').value = '';
            document.getElementById('categoriesInput').value = '';
            document.getElementById('tagsInput').value = '';
          }

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
                showMessage('Article scaffold created successfully!', 'success');
                setTimeout(() => vscode.postMessage({ command: 'openArticle' }), 1000);
                break;
              case 'error':
                showMessage(payload.error, 'error');
                break;
            }
          });

          function renderIssueList(issues) {
            const issueList = document.getElementById('issueList');
            issueList.innerHTML = '';
            issues.forEach(issue => {
              const el = document.createElement('div');
              el.className = 'issue-item';
              el.textContent = `#${issue.number}: ${issue.title}`;
              el.onclick = () => selectIssue(issue);
              issueList.appendChild(el);
            });
          }

          // 初期化
          loadIssues();
        </script>
      </body>
      </html>`;
  }

  private async _handleMessage(message: any) {
    const command = message.command;

    try {
      switch (command) {
        case 'loadIssues': {
          const actor = await getActor(this._extensionUri);
          const projectId = getProjectId(this._extensionUri);
          if (!actor || !projectId) {
            this._sendMessage('error', { error: 'Actor or Project not selected' });
            return;
          }
          const apiKey = await requireApiKey(this._extensionUri);
          const issues = await api.listIssues(getServerUrl(), apiKey, actor, projectId);
          this._sendMessage('issueList', { issues });
          break;
        }

        case 'selectIssue': {
          // 既存セッションを復元するか新規セッション初期化
          // この段階ではchatHistoryをクリア
          break;
        }

        case 'sendChat': {
          const actor = await getActor(this._extensionUri);
          const projectId = getProjectId(this._extensionUri);
          if (!actor || !projectId) {
            this._sendMessage('error', { error: 'Actor or Project not selected' });
            return;
          }
          const apiKey = await requireApiKey(this._extensionUri);
          
          const response = await api.postPlanChat(
            getServerUrl(),
            apiKey,
            actor,
            projectId,
            {
              history: message.chatHistory || [],
              message: message.message,
              sessionId: message.sessionId,
              githubIssueNumber: message.issueNumber
            }
          );
          this._sendMessage('chatResponse', response);
          break;
        }

        case 'suggestMetadata': {
          const actor = await getActor(this._extensionUri);
          const projectId = getProjectId(this._extensionUri);
          if (!actor || !projectId) {
            this._sendMessage('error', { error: 'Actor or Project not selected' });
            return;
          }
          const apiKey = await requireApiKey(this._extensionUri);
          
          const suggestion = await api.suggestMetadata(
            getServerUrl(),
            apiKey,
            actor,
            projectId,
            message.chatHistory
          );
          this._sendMessage('metadataSuggestion', suggestion);
          break;
        }

        case 'approveAndScaffold': {
          // phase 03で実装
          break;
        }

        case 'openArticle': {
          // phase 03で実装
          break;
        }
      }
    } catch (error) {
      this._sendMessage('error', { error: String(error) });
    }
  }

  private _sendMessage(command: string, payload: any) {
    this._panel.webview.postMessage({ command, payload });
  }
}
```

### VSCode拡張: extension.ts に commandPlanArticle 追加

```typescript
vscode.commands.registerCommand('letsBlog.planArticle', () => {
    const actor = getActor(context);
    const projectId = getProjectId(context);
    
    if (!actor) {
        vscode.window.showErrorMessage('Please select a user first using "Let\'s Blog: Select User".');
        return;
    }
    if (!projectId) {
        vscode.window.showErrorMessage('Please select a project first using "Let\'s Blog: Select Project".');
        return;
    }
    
    PlanPanel.createOrShow(context.extensionUri);
})
```

### VSCode拡張: package.json

```json
{
  "command": "letsBlog.planArticle",
  "title": "Let's Blog: Plan Article",
  "description": "Start article planning workflow (brainstorm + scaffold)"
}
```

## 実装上の注意点

- **Webview CSP**: VSCode Webview は strict Content Security Policy を持つため、外部CDN(Bootstrap等)は使用不可。すべてのCSS/JavaScriptはインラインに含めるか`enableScripts: true`で最小限に。
- **State管理**: Webviewパネルとコマンド間の状態共有は`postMessage`で行う(shared ObjectModel なし)。
- **ChatHistory**: Webview側で保持し、各API呼び出し時に送信。サーバー側では受け取るが保存しない(ステートレス設計)。
- **Assignees フィルタ**: バックエンド側では変更なし、クライアント側(`listIssues`の戻り値をフィルタ)で実装。既存Web画面との互換性維持。

## テスト整備

### バックエンド

- `ArticlePlanServiceTest.suggestMetadata_*`: JSON生成・抽出・フォールバック
- `GithubClientTest`: (phase 03で)

### VSCode拡張

- Extension Development Hostでパネル起動・issue一覧表示・チャット入力・メタデータ取得まで手動確認

## 完了条件

- [ ] バックエンド全テスト通過
- [ ] 実機でWebviewパネル起動・issue選択・チャット送信・メタデータ提案取得を確認
- [ ] phase 03でフォルダ生成・issue割り当てが接続されることで、end-to-endフロー確認
