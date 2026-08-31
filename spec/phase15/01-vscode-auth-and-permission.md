# Phase 15-01: VSCode 認証・権限・プロジェクト選択

## スコープ

VSCode拡張でのユーザー選択・プロジェクト選択、およびバックエンド側の権限モデル緩和を実装。ここまでで、VSCodeからAPI呼び出し時に正しいユーザーコンテキスト(X-Actor-Id/X-Actor-Role)が付与される状態を実現。

## タスク一覧

### バックエンド

- [ ] AdminAuthorizationService に`requireProjectMemberOrAdmin(projectId)`メソッド追加
- [ ] ArticlePlanController の全エンドポイントで認可を admin → projectMemberOrAdmin に置き換え
- [ ] 権限緩和のユニットテスト追加

### VSCode拡張

- [ ] config.ts: actor選択情報の保存・読み込み(`getActor`, `setActor`)
- [ ] config.ts: projectId選択情報の保存・読み込み(`getProjectId`, `setProjectId`)
- [ ] apiClient.ts: リクエストヘッダーに X-Actor-Id/X-Actor-Role を付与する共通化
- [ ] apiClient.ts: `listUsers`, `listProjects` 関数追加
- [ ] extension.ts: `letsBlog.selectActor` コマンド実装
- [ ] extension.ts: `letsBlog.selectProject` コマンド実装
- [ ] package.json: 上記2コマンドを contributes に追加

## 詳細設計

### バックエンド: AdminAuthorizationService

#### 新規メソッド

```java
/**
 * プロジェクトメンバーまたはadmin権限を持つユーザーのみを許可。
 * プロジェクト単位の機能(壁打ち・issue一覧)で使う。
 */
public void requireProjectMemberOrAdmin(Long projectId) {
    if (currentActorService.isAdmin()) {
        return;  // admin なら無条件許可
    }
    Long actorId = currentActorService.getCurrentActorId();
    if (actorId == null) {
        throw new ForbiddenException("この操作にはログインが必要です");
    }
    
    // projectUserRepository.findByProjectIdAndUserId(projectId, actorId) でチェック
    // 存在しなければ ForbiddenException
}
```

依存注入: `ProjectUserRepository` を`AdminAuthorizationService`に追加。

#### テスト

新規ファイル`AdminAuthorizationServiceTest.java`(または既存あればそこへ追加):
- `testRequireProjectMemberOrAdmin_WithAdmin`: admin ユーザーなら許可
- `testRequireProjectMemberOrAdmin_WithMember`: プロジェクトメンバーなら許可
- `testRequireProjectMemberOrAdmin_WithNonMember`: メンバーでないなら拒否
- `testRequireProjectMemberOrAdmin_WithNoActorId`: X-Actor-Idなしなら拒否

### バックエンド: ArticlePlanController

#### 変更方針

全メソッド内の`adminAuthorizationService.requireAdmin()`を`adminAuthorizationService.requireProjectMemberOrAdmin(projectId)`に置き換え。例:

```java
@PostMapping("/chat")
public PlanChatResponse chat(@PathVariable Long projectId, @Valid @RequestBody PlanChatRequest request) {
    adminAuthorizationService.requireProjectMemberOrAdmin(projectId);  // 変更
    return articlePlanService.chat(...);
}
```

対象メソッド: `chat`, `listSessions`, `getSession`, `getSessionByIssue`, `listIssues`, `getIssueDescription`, `suggestTitles`, `acceptPlan`, `suggestStructure`, `acceptStructure` (全10メソッド)。

#### テスト

`ArticlePlanControllerTest`(新規)にて、各エンドポイントについて:
- admin ユーザーでのリクエスト → 200OK
- プロジェクトメンバーのリクエスト → 200OK
- 非メンバーのリクエスト → 403 Forbidden

### VSCode拡張: config.ts 拡張

#### Actor (ユーザー) 永続化

```typescript
interface Actor {
  id: number;
  email: string;
  role: string;  // "admin" or "user"
}

const ACTOR_SECRET = 'letsBlog.actor';

export async function getActor(context: vscode.ExtensionContext): Promise<Actor | undefined> {
  const json = await context.secrets.get(ACTOR_SECRET);
  if (!json) return undefined;
  try {
    return JSON.parse(json);
  } catch {
    return undefined;
  }
}

export async function setActor(context: vscode.ExtensionContext, actor: Actor): Promise<void> {
  await context.secrets.store(ACTOR_SECRET, JSON.stringify(actor));
}

export async function clearActor(context: vscode.ExtensionContext): Promise<void> {
  await context.secrets.delete(ACTOR_SECRET);
}
```

#### ProjectId 永続化

```typescript
const PROJECT_ID_STATE = 'letsBlog.projectId';

export function getProjectId(context: vscode.ExtensionContext): number | undefined {
  return context.workspaceState.get(PROJECT_ID_STATE);
}

export async function setProjectId(context: vscode.ExtensionContext, projectId: number): Promise<void> {
  await context.workspaceState.update(PROJECT_ID_STATE, projectId);
}
```

### VSCode拡張: apiClient.ts 拡張

#### ヘッダー共通化

```typescript
interface RequestHeaders {
  'X-API-Key': string;
  'X-Actor-Id'?: string;
  'X-Actor-Role'?: string;
  'Content-Type'?: string;
}

function buildHeaders(apiKey: string, actor?: Actor, contentType?: string): RequestHeaders {
  const headers: RequestHeaders = { 'X-API-Key': apiKey };
  if (actor) {
    headers['X-Actor-Id'] = String(actor.id);
    headers['X-Actor-Role'] = actor.role;
  }
  if (contentType) {
    headers['Content-Type'] = contentType;
  }
  return headers;
}
```

既存の`publishPost`, `listSites`, `askAi`, `suggestTags`, `generateImage`で使用されているヘッダー組み立てを、この`buildHeaders`を使うよう置き換え。

#### 新規関数

```typescript
// ユーザー一覧取得(認証: APIキーのみ、actorなし)
export async function listUsers(serverUrl: string, apiKey: string): Promise<{id: number; email: string; role: string}[]> {
  const res = await fetch(`${serverUrl}/api/users`, {
    headers: buildHeaders(apiKey)
  });
  await assertOk(res);
  return (await res.json()) as {id: number; email: string; role: string}[];
}

// プロジェクト一覧取得
export async function listProjects(
  serverUrl: string, 
  apiKey: string, 
  actor?: Actor
): Promise<{id: number; name: string; slug: string; githubRepository?: string}[]> {
  const res = await fetch(`${serverUrl}/api/projects`, {
    headers: buildHeaders(apiKey, actor)
  });
  await assertOk(res);
  return (await res.json()) as {id: number; name: string; slug: string; githubRepository?: string}[];
}

// マルチターンチャット(新名称, 既存の内部関数を拡張)
// ... PostPlanChat, GetUnassignedIssues等は02で実装
```

### VSCode拡張: extension.ts 拡張

#### コマンド登録

```typescript
export function activate(context: vscode.ExtensionContext): void {
  context.subscriptions.push(
    // 既存
    vscode.commands.registerCommand('letsBlog.setApiKey', () => commandSetApiKey(context)),
    vscode.commands.registerCommand('letsBlog.selectSite', () => commandSelectSite(context)),
    vscode.commands.registerCommand('letsBlog.publish', () => commandPublish(context)),
    vscode.commands.registerCommand('letsBlog.askAi', () => commandAskAi(context)),
    vscode.commands.registerCommand('letsBlog.suggestTags', () => commandSuggestTags(context)),
    vscode.commands.registerCommand('letsBlog.generateImage', () => commandGenerateImage(context)),
    // 新規
    vscode.commands.registerCommand('letsBlog.selectActor', () => commandSelectActor(context)),
    vscode.commands.registerCommand('letsBlog.selectProject', () => commandSelectProject(context))
  );
}
```

#### commandSelectActor

```typescript
async function commandSelectActor(context: vscode.ExtensionContext): Promise<void> {
  try {
    const apiKey = await requireApiKey(context);
    const users = await api.listUsers(getServerUrl(), apiKey);
    
    if (users.length === 0) {
      vscode.window.showWarningMessage('利用可能なユーザーがありません。先に管理画面でユーザーを作成してください。');
      return;
    }
    
    const picked = await vscode.window.showQuickPick(
      users.map(u => ({ label: u.email, description: u.role, actor: u })),
      { placeHolder: 'ユーザーを選択' }
    );
    if (!picked) return;
    
    await setActor(context, picked.actor);
    vscode.window.showInformationMessage(`ユーザーを '${picked.label}' に設定しました。`);
  } catch (err) {
    vscode.window.showErrorMessage(`ユーザー選択に失敗しました: ${String(err instanceof Error ? err.message : err)}`);
  }
}
```

#### commandSelectProject

```typescript
async function commandSelectProject(context: vscode.ExtensionContext): Promise<void> {
  try {
    const apiKey = await requireApiKey(context);
    const actor = await getActor(context);
    const projects = await api.listProjects(getServerUrl(), apiKey, actor);
    
    // githubRepository が設定済み(null/undefined でない)のもののみ表示
    const validProjects = projects.filter(p => p.githubRepository);
    if (validProjects.length === 0) {
      vscode.window.showWarningMessage(
        'GitHub連携済みのプロジェクトがありません。先に管理画面でプロジェクトのGitHubリポジトリを設定してください。'
      );
      return;
    }
    
    const picked = await vscode.window.showQuickPick(
      validProjects.map(p => ({ label: p.name, description: p.githubRepository, projectId: p.id })),
      { placeHolder: 'プロジェクトを選択' }
    );
    if (!picked) return;
    
    await setProjectId(context, picked.projectId);
    vscode.window.showInformationMessage(`プロジェクトを '${picked.label}' に設定しました。`);
  } catch (err) {
    vscode.window.showErrorMessage(`プロジェクト選択に失敗しました: ${String(err instanceof Error ? err.message : err)}`);
  }
}
```

### VSCode拡張: package.json

`contributes.commands`に追加:

```json
{
  "command": "letsBlog.selectActor",
  "title": "Let's Blog: Select User",
  "description": "Select the user account to operate with"
},
{
  "command": "letsBlog.selectProject",
  "title": "Let's Blog: Select Project",
  "description": "Select the GitHub-linked project"
}
```

## 実装上の注意点

- actor選択時、既存actor が保存されていればそれをQuickPickのデフォルト表示にすると UX が良い
- projectId選択時も同様に、既存projectIdをデフォルト表示
- APIキー未設定の場合は既存の`requireApiKey`エラーが出ので、その後でactor選択へ導く導線を考慮
- buildHeaders の`contentType`パラメータは、FormData使用時(publishPost)では`Content-Type`ヘッダーを明示的に送ってはいけない(ブラウザが自動設定するため)ことに注意。既存コードの`getHeaders()`をそのまま使うべき部分と、buildHeaders(ヘッダー辞書)に分ける設計

## テスト整備

### バックエンド

- `AdminAuthorizationServiceTest`: 権限緩和メソッドのユニットテスト
- `ArticlePlanControllerTest`: 各エンドポイントの認可テスト (admin / member / non-member の3ケース)

### VSCode拡張

- 手動テスト: Extension Development Hostで`letsBlog.selectActor`/`selectProject`を実行、UI・永続化動作確認
- ユニット化は拡張側では通常行わない(VSCode API依存が大きいため)

## 完了条件

- [ ] バックエンド全テスト通過
- [ ] 実機でVSCodeからactorとprojectIdが正しく選択・保存できることを確認
- [ ] 以降のphase（02/03）でAPI呼び出し時、ヘッダーに actorが正しく付与されることを確認
