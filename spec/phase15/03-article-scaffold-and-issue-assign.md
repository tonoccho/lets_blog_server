# Phase 15-03: フォルダ・ファイル生成と Issue 割り当て・ラベル付与

## スコープ

Webviewパネルでの承認後、VSCode拡張がローカルプロジェクトに`articles/<slug>/`フォルダと`article.md`(front matter + 本文)、`assets/.gitkeep`を自動生成。GitHub issue をユーザーに割り当て、`in-progress`ラベルを付与。生成後、エディタで`article.md`を自動開く。

## タスク一覧

### バックエンド

- [ ] `GithubClient.getAuthenticatedUser(token)` 実装
- [ ] `GithubClient.assignAndLabelIssue(...)` 実装
- [ ] 新規DTO: `AssignIssueRequest`, `AssignIssueResponse`
- [ ] `ArticlePlanService.assignIssueToActor(projectId, userId, issueNumber)` 実装
- [ ] `ArticlePlanController.POST /issues/{issueNumber}/assign` エンドポイント追加
- [ ] `GithubIssueSummary` に `assignees[]` フィールド追加
- [ ] `RepositoryIssueResponse` に `assignees[]` フィールド追加
- [ ] ユニットテスト

### VSCode拡張

- [ ] `frontMatter.ts`: `LetsBlogFrontMatter` に新規フィールド追加
- [ ] `apiClient.ts`: `assignIssue`, `getIssueDescription` 関数追加(既に02で列挙)
- [ ] `planPanel.ts`: `approveAndScaffold` メッセージハンドラ実装
- [ ] フォルダ・ファイル生成ロジック実装

## 詳細設計

### バックエンド: GithubClient 拡張

#### getAuthenticatedUser

```java
/**
 * 認証されたユーザー情報を取得する。PAT の所有者の GitHub login を得る。
 * issue のassignee指定に必要。
 */
public GithubUser getAuthenticatedUser(String token) {
    try {
        JsonNode response = client.get()
                .uri("/user")
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .retrieve()
                .body(JsonNode.class);

        String login = response.get("login").asText();
        return new GithubUser(login);
    } catch (RestClientResponseException e) {
        throw new GithubApiException(
            "GitHub認証ユーザー情報の取得に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(),
            e);
    } catch (Exception e) {
        throw new GithubApiException("GitHub API呼び出しに失敗しました: " + e.getMessage(), e);
    }
}
```

新規レコード:

```java
public record GithubUser(String login) {}
```

#### assignAndLabelIssue

```java
/**
 * issue の assignees と labels を設定する。
 * 既存issue の body を上書きせず、assignees と labels のみを更新する。
 */
public GithubIssue assignAndLabelIssue(
        String token, String owner, String repo, int issueNumber,
        List<String> assignees, List<String> labels) {
    ObjectNode requestBody = JsonNodeFactory.instance.objectNode();
    requestBody.set("assignees", objectMapper.valueToTree(assignees));
    requestBody.set("labels", objectMapper.valueToTree(labels));

    try {
        JsonNode response = client.patch()
                .uri("/repos/{owner}/{repo}/issues/{issueNumber}", owner, repo, issueNumber)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(JsonNode.class);

        return new GithubIssue(response.get("number").asInt(), response.get("html_url").asText());
    } catch (RestClientResponseException e) {
        throw new GithubApiException(
            "GitHub issue への割り当てに失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(),
            e);
    } catch (Exception e) {
        throw new GithubApiException("GitHub API呼び出しに失敗しました: " + e.getMessage(), e);
    }
}
```

#### listIssues 拡張

既存の`listIssues`で生成する`GithubIssueSummary`に assignees を含める:

```java
public List<GithubIssueSummary> listIssues(String token, String owner, String repo, String state) {
    // ... 既存コード ...
    for (JsonNode node : response) {
        if (node.has("pull_request")) continue;
        
        List<String> assignees = new ArrayList<>();
        JsonNode assigneesNode = node.get("assignees");
        if (assigneesNode != null && assigneesNode.isArray()) {
            for (JsonNode assigneeNode : assigneesNode) {
                assignees.add(assigneeNode.get("login").asText());
            }
        }
        
        issues.add(new GithubIssueSummary(
                node.get("number").asInt(),
                node.get("title").asText(),
                node.get("html_url").asText(),
                node.get("state").asText(),
                assignees  // 追加
        ));
    }
    // ...
}
```

新規シグネチャ:

```java
public record GithubIssueSummary(int number, String title, String htmlUrl, String state, List<String> assignees) {}
```

### バックエンド: DTO

#### AssignIssueRequest

```java
public record AssignIssueRequest(
    Integer issueNumber
) {}
```

#### AssignIssueResponse

```java
public record AssignIssueResponse(
    int issueNumber,
    String htmlUrl,
    String assignedLogin  // PAT所有者のGitHub login
) {}
```

### バックエンド: ArticlePlanService

#### assignIssueToActor

```java
/**
 * 指定 issue をログイン中のユーザーに割り当て、in-progress ラベルを付与する。
 */
public AssignIssueResponse assignIssueToActor(Long projectId, Long userId, Integer issueNumber) {
    GithubAccess access = resolveGithubAccess(projectId, userId);
    
    // 認証ユーザーのGitHub login を取得
    GithubUser authUser = githubClient.getAuthenticatedUser(access.token());
    
    // issue に assignee(GitHub login) と in-progress ラベルを付与
    GithubIssue issue = githubClient.assignAndLabelIssue(
            access.token(),
            access.owner(),
            access.repo(),
            issueNumber,
            List.of(authUser.login()),
            List.of("in-progress")
    );
    
    return new AssignIssueResponse(issue.number(), issue.htmlUrl(), authUser.login());
}
```

### バックエンド: ArticlePlanController

```java
@PostMapping("/issues/{issueNumber}/assign")
public AssignIssueResponse assignIssue(
        @PathVariable Long projectId,
        @PathVariable Integer issueNumber) {
    adminAuthorizationService.requireProjectMemberOrAdmin(projectId);
    Long userId = currentActorService.getCurrentActorId();
    return articlePlanService.assignIssueToActor(projectId, userId, issueNumber);
}
```

### VSCode拡張: frontMatter.ts 拡張

```typescript
export interface LetsBlogFrontMatter {
  title?: string;
  slug?: string;
  site?: string;
  status?: string;
  categories?: string[];
  tags?: string[];
  featured_image?: string;
  wp_post_id?: string | null;
  wp_post_url?: string | null;
  github_issue_number?: number;      // 新規
  github_repository?: string;         // 新規
  project_id?: number;                // 新規
  [key: string]: unknown;
}
```

### VSCode拡張: apiClient.ts

既に02で列挙済みの以下を実装:

```typescript
export async function getIssueDescription(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  issueNumber: number
): Promise<string> { ... }

export async function assignIssue(
  serverUrl: string,
  apiKey: string,
  actor: Actor,
  projectId: number,
  issueNumber: number
): Promise<AssignIssueResponse> {
  const res = await fetch(
    `${serverUrl}/api/projects/${projectId}/article-plan/issues/${issueNumber}/assign`,
    {
      method: 'POST',
      headers: buildHeaders(apiKey, actor, 'application/json'),
      body: JSON.stringify({ issueNumber })
    }
  );
  await assertOk(res);
  return (await res.json()) as AssignIssueResponse;
}

interface AssignIssueResponse {
  issueNumber: number;
  htmlUrl: string;
  assignedLogin: string;
}
```

### VSCode拡張: planPanel.ts の approveAndScaffold ハンドラ

Webview から送出される メッセージハンドリング:

```typescript
case 'approveAndScaffold': {
  const actor = await getActor(this._extensionUri);
  const projectId = getProjectId(this._extensionUri);
  if (!actor || !projectId) {
    this._sendMessage('error', { error: 'Actor or Project not selected' });
    return;
  }
  
  const apiKey = await requireApiKey(this._extensionUri);
  const issue = message.issue;
  const metadata = message.metadata;
  
  try {
    // 1. ローカルフォルダ確認
    const workspaceFolder = vscode.workspace.workspaceFolders?.[0];
    if (!workspaceFolder) {
      throw new Error('No workspace folder open');
    }
    
    const slug = metadata.slug;
    const articlesPath = path.join(workspaceFolder.uri.fsPath, 'articles', slug);
    
    if (fs.existsSync(articlesPath)) {
      const overwrite = await vscode.window.showWarningMessage(
        `Folder articles/${slug} already exists. Overwrite?`,
        'Yes',
        'No'
      );
      if (overwrite !== 'Yes') {
        this._sendMessage('error', { error: 'Cancelled' });
        return;
      }
    }
    
    // 2. article.md と assets フォルダを生成
    fs.mkdirSync(articlesPath, { recursive: true });
    fs.mkdirSync(path.join(articlesPath, 'assets'), { recursive: true });
    
    // .gitkeep ファイル作成
    fs.writeFileSync(path.join(articlesPath, 'assets', '.gitkeep'), '');
    
    // issue の description 取得
    const description = await api.getIssueDescription(
      getServerUrl(),
      apiKey,
      actor,
      projectId,
      issue.number
    );
    
    // article.md 生成
    const frontMatter: LetsBlogFrontMatter = {
      title: metadata.title,
      slug: metadata.slug,
      categories: metadata.categories,
      tags: metadata.tags,
      status: 'draft',
      github_issue_number: issue.number,
      github_repository: issue.htmlUrl.split('/').slice(0, 5).join('/'),  // https://github.com/owner/repo
      project_id: projectId
    };
    
    const content = description || '記事本文をここに記入してください。';
    const article = { data: frontMatter, content };
    const markdown = stringifyArticle(article);
    
    fs.writeFileSync(path.join(articlesPath, 'article.md'), markdown, 'utf-8');
    
    // 3. GitHub issue へ割り当て・ラベル付与
    const assignResult = await api.assignIssue(getServerUrl(), apiKey, actor, projectId, issue.number);
    
    // 4. 完了通知・エディタ起動
    this._sendMessage('scaffoldCreated', {});
    
    const articlePath = path.join(articlesPath, 'article.md');
    const doc = await vscode.workspace.openTextDocument(articlePath);
    await vscode.window.showTextDocument(doc);
    
  } catch (error) {
    this._sendMessage('error', { error: String(error) });
  }
}
```

新規import:

```typescript
import * as fs from 'fs';
import * as path from 'path';
import { stringifyArticle, LetsBlogFrontMatter } from './frontMatter';
```

## ファイル生成の詳細

### フォルダ構造

```
articles/
└── {slug}/
    ├── article.md
    └── assets/
        └── .gitkeep
```

### article.md の front matter

```yaml
---
title: "タイトル案"
slug: "slug-value"
categories: ["カテゴリ1", "カテゴリ2"]
tags: ["tag1", "tag2"]
status: "draft"
github_issue_number: 123
github_repository: "https://github.com/owner/repo"
project_id: 1
---

本文(GitHub issue description または壁打ち要約)
```

### asset/.gitkeep の役割

空のディレクトリは Git では追跡されないため、`.gitkeep`をダミーファイルとして配置し、ディレクトリの存在をリポジトリに記録。

## エラーハンドリング

- `articles/<slug>/`既存時: 上書き確認ダイアログ。ユーザーが「No」なら処理中断、エラーメッセージ表示。
- `getIssueDescription`失敗時: エラーメッセージ。
- `assignIssue`失敗時(GitHub token無効など): エラーメッセージ + 既にローカル生成済みの場合は「issue割り当てのみ失敗、ローカルファイルは生成済み」と明記。

## テスト整備

### バックエンド

- `GithubClientTest.getAuthenticatedUser_*`: `MockRestServiceServer` で `/user` エンドポイントのリクエスト・レスポンス検証
- `GithubClientTest.assignAndLabelIssue_*`: PATCH `/repos/.../issues/{issueNumber}` の requestBody に assignees/labels が含まれることを検証
- `ArticlePlanServiceTest.assignIssueToActor_*`: getAuthenticatedUser + assignAndLabelIssue のモック、正常系・エラー系
- `ArticlePlanControllerTest`: POST `/issues/{issueNumber}/assign` の権限チェック

### VSCode拡張

- 実機での手動テスト: approveAndScaffold 完全フロー
  1. 有効なworkspaceフォルダで拡張起動
  2. issue選択 → 壁打ち → メタデータ取得 → 承認
  3. `articles/<slug>/article.md`, `assets/.gitkeep` 生成確認
  4. `article.md` のfront matter 確認(github_issue_number 等が正しく設定されている)
  5. GitHub issue ページで assignee とin-progress ラベル確認

## 完了条件

- [ ] バックエンド全テスト通過
- [ ] 実機でフォルダ・ファイル生成、issue割り当て・ラベル付与の全フロー確認
- [ ] article.md の front matter が正しく生成されることを確認
- [ ] GitHub issue ページでassignee と in-progress ラベルが表示されることを確認
