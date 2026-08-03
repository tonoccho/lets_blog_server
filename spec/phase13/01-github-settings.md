# 01. GitHub リポジトリ連携・個人アクセストークン設定

## 目的

プロジェクト単位で GitHub リポジトリを紐付け、ユーザーごとに個人アクセストークン(PAT)を安全に保存する仕組みを構築する。これにより、02/03 の計画機能が issue 作成時にリポジトリ・PAT を参照できるようになる。

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| PAT保存先 | `users`テーブル、カラム名`github_token_encrypted`(VARBINARY 1024) |
| PAT暗号化方式 | 既存`CredentialCipher`(AES-256-GCM)を再利用。暗号化鍵は`APP_ENCRYPTION_KEY`環境変数 |
| API でのトークン取得 | API は`githubTokenConfigured: boolean`フラグのみを返す。生トークンは絶対に返さない |
| リポジトリ設定先 | `projects`テーブル、カラム名`github_repository`(VARCHAR 255) |
| リポジトリ形式 | "owner/repo"(例: `anthropics/prompt-library`)。`^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$`形式でバリデーション。空文字/null は紐付け解除として許可 |
| 認可 | PAT設定: self-or-admin(ユーザー自身か管理者のみ)。リポジトリ設定: admin のみ |
| API レスポンス | `PUT /api/users/{id}/github-token`は`UserProfileResponse`(既存型に`githubTokenConfigured`を追加)、`PUT /api/projects/{id}/github-repository`は`ProjectResponse`(既存型に`githubRepository`を追加) |

## アーキテクチャ・実装詳細

### 1. データベース

#### `V21__add_user_github_token.sql` (新規マイグレーション)

```sql
ALTER TABLE users
    ADD COLUMN github_token_encrypted VARBINARY(1024) NULL;
```

- `NULL`許可: トークン未設定ユーザーの区別のため
- 型・サイズ: `Site.credentialsEncrypted`(VARBINARY 2048)と同じ方針。GitHub PAT は実質数十バイト程度だが、余裕を持たせ 1024 に設定

#### `V22__add_project_github_repository.sql` (新規マイグレーション)

```sql
ALTER TABLE projects
    ADD COLUMN github_repository VARCHAR(255) NULL;
```

- `NULL`許可: リポジトリ未紐付けプロジェクトの区別のため
- 型・サイズ: "owner/repo"形式、最大長は実際にはGitHub側の255字制限に合わせ設定

### 2. バックエンド実装

#### エンティティ

**`User.java` に追加**:

```java
@Column(name = "github_token_encrypted", columnDefinition = "VARBINARY(1024)")
private byte[] githubTokenEncrypted;

public boolean hasGithubToken() {
    return githubTokenEncrypted != null && githubTokenEncrypted.length > 0;
}
```

**`Project.java` に追加**:

```java
@Column(name = "github_repository", length = 255)
private String githubRepository;

public boolean isGithubRepositoryConfigured() {
    return githubRepository != null && !githubRepository.isBlank();
}
```

#### DTO

**`UpdateGithubTokenRequest.java` (新規)**:

```java
package com.letsblog.api.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateGithubTokenRequest(
    @NotBlank(message = "Personal Access Token を入力してください") 
    String githubToken
) {
}
```

**`UpdateProjectGithubRepositoryRequest.java` (新規)**:

```java
package com.letsblog.api.dto;

import jakarta.validation.constraints.Pattern;

public record UpdateProjectGithubRepositoryRequest(
    @Pattern(
        regexp = "^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$|^$",
        message = "リポジトリは 'owner/repo' 形式で指定してください(空で紐付け解除)"
    )
    String githubRepository
) {
}
```

**`UserProfileResponse.java` に追加**:

```java
private boolean githubTokenConfigured;

// 既存の from() メソッドに追加
public static UserProfileResponse from(User user) {
    return new UserProfileResponse(
        // ... 既存フィールド ...
        user.hasGithubToken()  // boolean githubTokenConfigured に変換
    );
}
```

**`ProjectResponse.java` に追加**:

```java
private String githubRepository;

public static ProjectResponse from(Project project) {
    return new ProjectResponse(
        // ... 既存フィールド ...
        project.getGithubRepository()
    );
}
```

#### Service

**`UserService.java` に追加**:

```java
@Autowired
private CredentialCipher credentialCipher;

@Transactional
public UserProfileResponse updateGithubToken(Long userId, UpdateGithubTokenRequest request) {
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new EntityNotFoundException("ユーザーが見つかりません: " + userId));
    
    byte[] encrypted = credentialCipher.encrypt(request.githubToken());
    user.setGithubTokenEncrypted(encrypted);
    userRepository.save(user);
    
    return UserProfileResponse.from(user);
}

public String getDecryptedGithubToken(Long userId) {
    // 内部利用のみ。ArticlePlanService から呼び出されることを想定
    User user = userRepository.findById(userId)
        .orElseThrow(() -> new EntityNotFoundException("ユーザーが見つかりません: " + userId));
    if (!user.hasGithubToken()) {
        throw new IllegalStateException("ユーザーの GitHub トークンが設定されていません");
    }
    return credentialCipher.decrypt(user.getGithubTokenEncrypted());
}
```

**`ProjectService.java` に追加**:

```java
@Transactional
public ProjectResponse updateGithubRepository(Long projectId, UpdateProjectGithubRepositoryRequest request) {
    Project project = projectRepository.findById(projectId)
        .orElseThrow(() -> new EntityNotFoundException("プロジェクトが見つかりません: " + projectId));
    
    // 空文字列は null に変換(紐付け解除として扱う)
    String repo = request.githubRepository() != null && request.githubRepository().isBlank() 
        ? null 
        : request.githubRepository();
    
    project.setGithubRepository(repo);
    projectRepository.save(project);
    
    return ProjectResponse.from(project);
}
```

#### Controller

**`UserController.java` に追加**:

```java
@PutMapping("/{id}/github-token")
public UserProfileResponse updateGithubToken(
        @PathVariable Long id, 
        @Valid @RequestBody UpdateGithubTokenRequest request) {
    adminAuthorizationService.requireSelfOrAdmin(id);  // 既存メソッド再利用
    return userService.updateGithubToken(id, request);
}
```

**`ProjectController.java` に追加**:

```java
@PutMapping("/{id}/github-repository")
public ProjectResponse updateGithubRepository(
        @PathVariable Long id,
        @Valid @RequestBody UpdateProjectGithubRepositoryRequest request) {
    adminAuthorizationService.requireAdmin();  // 既存メソッド再利用
    return projectService.updateGithubRepository(id, request);
}
```

### 3. フロントエンド

#### `web/src/app/system/GithubTokenForm.tsx` (新規)

```typescript
"use client";

import { useActionState } from "react";
import { updateGithubTokenAction, UpdateGithubTokenState } from "./actions";

const initialState: UpdateGithubTokenState = {};

export function GithubTokenForm({
  githubTokenConfigured,
}: {
  githubTokenConfigured: boolean;
}) {
  const [state, formAction, pending] = useActionState(updateGithubTokenAction, initialState);

  return (
    <form action={formAction} className="rounded-lg border border-neutral-200 bg-white p-5">
      <h2 className="mb-3 font-medium">GitHub 連携</h2>
      
      <div className="mb-4">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">
            Personal Access Token (PAT)
          </span>
          <input
            type="password"
            name="githubToken"
            placeholder="ghp_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
            className="rounded border border-neutral-300 px-3 py-2 text-sm"
            autoComplete="off"
          />
          <span className="text-xs text-neutral-500">
            `repo` スコープを持つ PAT を入力してください。入力値は保存時のみサーバーに送信され、確認用に表示はされません。
          </span>
        </label>
      </div>

      <div className="mb-4">
        <div className="text-sm">
          {githubTokenConfigured ? (
            <span className="inline-block rounded bg-green-100 px-2 py-1 text-green-700">
              ✓ 設定済み
            </span>
          ) : (
            <span className="inline-block rounded bg-neutral-100 px-2 py-1 text-neutral-600">
              未設定
            </span>
          )}
        </div>
      </div>

      {state.error && (
        <p className="mb-3 text-sm text-red-600">{state.error}</p>
      )}
      {state.success && (
        <p className="mb-3 text-sm text-green-600">GitHub トークンを更新しました。</p>
      )}

      <button
        type="submit"
        disabled={pending}
        className="rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {pending ? "保存中…" : "保存"}
      </button>
    </form>
  );
}
```

#### `web/src/app/system/actions.ts` に追加

```typescript
export interface UpdateGithubTokenState {
  error?: string;
  success?: boolean;
}

export async function updateGithubTokenAction(
  _prevState: UpdateGithubTokenState,
  formData: FormData
): Promise<UpdateGithubTokenState> {
  const session = await requireSession();
  const userId = Number(session.user.id);
  const actor = { id: userId, role: session.user.role };

  const githubToken = String(formData.get("githubToken") ?? "").trim();

  if (!githubToken) {
    return { error: "Personal Access Token を入力してください。" };
  }

  try {
    await updateGithubToken(userId, { githubToken }, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath("/system");
  return { success: true };
}
```

#### `web/src/app/system/page.tsx` に追加

```typescript
import { GithubTokenForm } from "./GithubTokenForm";

export default async function SystemPage() {
  // ... 既存コード ...
  const profile = await getUserProfile(Number(session.user.id), {
    id: Number(session.user.id),
    role: session.user.role,
  });

  return (
    <div className="space-y-8">
      <h1 className="text-xl font-semibold">システム</h1>

      {/* 既存のシステム環境設定 */}
      <SystemPreferencesForm
        locale={profile.locale ?? "ja_JP"}
        timezone={profile.timezone ?? "Asia/Tokyo"}
        timezoneOptions={timezoneOptions}
      />

      {/* 新規: GitHub 連携 */}
      <GithubTokenForm
        githubTokenConfigured={profile.githubTokenConfigured ?? false}
      />

      {/* 以下既存コード... */}
    </div>
  );
}
```

#### `web/src/app/projects/[id]/ProjectGithubRepositoryForm.tsx` (新規)

```typescript
"use client";

import { useActionState } from "react";
import { updateProjectGithubRepositoryAction, UpdateProjectGithubRepositoryState } from "./actions";

const initialState: UpdateProjectGithubRepositoryState = {};

export function ProjectGithubRepositoryForm({
  projectId,
  githubRepository,
}: {
  projectId: number;
  githubRepository: string | null;
}) {
  const [state, formAction, pending] = useActionState(
    (prevState, formData) =>
      updateProjectGithubRepositoryAction(projectId, prevState, formData),
    initialState
  );

  return (
    <form action={formAction} className="rounded-lg border border-neutral-200 bg-white p-5">
      <h2 className="mb-3 font-medium">GitHub リポジトリ設定</h2>
      
      <div className="mb-4">
        <label className="flex flex-col gap-1 text-sm">
          <span className="text-neutral-600">リポジトリ (owner/repo)</span>
          <input
            type="text"
            name="githubRepository"
            placeholder="anthropics/prompt-library"
            defaultValue={githubRepository ?? ""}
            className="rounded border border-neutral-300 px-3 py-2 text-sm font-mono"
          />
          <span className="text-xs text-neutral-500">
            空にすると紐付けを解除します。
          </span>
        </label>
      </div>

      {state.error && (
        <p className="text-sm text-red-600">{state.error}</p>
      )}
      {state.success && (
        <p className="text-sm text-green-600">リポジトリ設定を更新しました。</p>
      )}

      <button
        type="submit"
        disabled={pending}
        className="mt-3 rounded bg-neutral-900 px-4 py-2 text-sm text-white disabled:bg-neutral-200 disabled:text-neutral-600"
      >
        {pending ? "保存中…" : "保存"}
      </button>
    </form>
  );
}
```

#### `web/src/app/projects/[id]/actions.ts` に追加

```typescript
export interface UpdateProjectGithubRepositoryState {
  error?: string;
  success?: boolean;
}

export async function updateProjectGithubRepositoryAction(
  projectId: number,
  _prevState: UpdateProjectGithubRepositoryState,
  formData: FormData
): Promise<UpdateProjectGithubRepositoryState> {
  const session = await requireAdminSession();
  const actor = { id: Number(session.user.id), role: session.user.role };

  const githubRepository = String(formData.get("githubRepository") ?? "").trim();

  try {
    await updateProjectGithubRepository(projectId, githubRepository, actor);
  } catch (err) {
    return { error: err instanceof Error ? err.message : String(err) };
  }

  revalidatePath(`/projects/${projectId}`);
  return { success: true };
}
```

#### `web/src/app/projects/[id]/page.tsx` に追加

```typescript
import { ProjectGithubRepositoryForm } from "./ProjectGithubRepositoryForm";

export default async function ProjectDetailPage({ params }: { params: { id: string } }) {
  // ... 既存コード ...

  return (
    <div className="space-y-8">
      {/* 既存: プロジェクト名フォーム */}
      <ProjectNameForm projectId={project.id} name={project.name} />

      {/* 既存: マスター環境設定 */}
      <MasterEnvironmentSelector projectId={project.id} project={project} />

      {/* 新規: GitHub リポジトリ設定 */}
      <ProjectGithubRepositoryForm
        projectId={project.id}
        githubRepository={project.githubRepository}
      />

      {/* 既存コード続行... */}
    </div>
  );
}
```

#### `web/src/lib/apiClient.ts` に追加

**`Project` 型に追加**:

```typescript
export interface Project {
  // ... 既存フィールド ...
  githubRepository: string | null;
}
```

**`UserProfileResponse` 型に追加**:

```typescript
export interface UserProfileResponse {
  // ... 既存フィールド ...
  githubTokenConfigured: boolean;
}
```

**関数を追加**:

```typescript
export function updateGithubToken(
  userId: number,
  data: { githubToken: string },
  actor?: ActorInfo
): Promise<UserProfileResponse> {
  return apiFetch<UserProfileResponse>(`/api/users/${userId}/github-token`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(data),
    actor,
  });
}

export function updateProjectGithubRepository(
  projectId: number,
  githubRepository: string,
  actor?: ActorInfo
): Promise<Project> {
  return apiFetch<Project>(`/api/projects/${projectId}/github-repository`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ githubRepository }),
    actor,
  });
}
```

## スコープ・実装項目

実装対象:

- [ ] マイグレーション: `V21__add_user_github_token.sql`, `V22__add_project_github_repository.sql`
- [ ] バックエンド: `User.java`, `Project.java` にフィールド・メソッド追加
- [ ] DTO: `UpdateGithubTokenRequest`, `UpdateProjectGithubRepositoryRequest` 新規作成、既存DTO(`UserProfileResponse`, `ProjectResponse`)に フィールド追加
- [ ] Service: `UserService.updateGithubToken()`, `getDecryptedGithubToken()`、`ProjectService.updateGithubRepository()`
- [ ] Controller: `UserController`, `ProjectController` に新規エンドポイント追加
- [ ] フロント: `GithubTokenForm`, `ProjectGithubRepositoryForm` 新規、`page.tsx`, `actions.ts` に追加
- [ ] API Client: `apiClient.ts` に型・関数追加
- [ ] テスト: Unit テスト整備

対象外・スコープ外:

- PAT 有効期限の監視・更新リマインダー
- PAT スコープの自動検証・設定ガイダンス
- GitHub Webhook 自動登録
- 複数リポジトリの紐付け(1プロジェクト1リポジトリのみ)

## 実装順序

1. マイグレーション作成・DB 反映
2. エンティティ・DTO 実装
3. Service / Controller 実装・ユニットテスト
4. フロントエンド実装
5. 実機検証

## テスト整備

### Unit Tests

**`UserServiceTest` に追加**:

- `updateGithubToken()` で入力トークンが暗号化されて保存されることを検証
- `getDecryptedGithubToken()` で復号されたトークンが元の値と一致することを検証
- `getDecryptedGithubToken()` 呼び出し時、トークン未設定なら `IllegalStateException` をスロー

**`ProjectServiceTest` に追加**:

- `updateGithubRepository()` で正規表現に違反する値(例: "invalid")は `ConstraintViolationException` をスロー
- 空文字列は null に変換されることを検証
- "owner/repo" 形式の値が正常に保存されることを検証

### 実機検証

- GitHub PAT(repo スコープ)を実際に発行してシステム画面に登録
- 設定済み/未設定の表示切り替えが正しく動作することを確認
- テスト用リポジトリを各プロジェクトに紐付け、保存・ページ再読み込み後も反映されていることを確認
- 無効な形式(例: "invalid-format")を入力するとバリデーションエラーが表示されることを確認
- 空にして保存し、紐付けが解除されることを確認

## 未決事項

- GitHub PAT スコープ要件をシステム画面に `repo`権限が必須である旨ヘルプテキストとして明記するか、あるいは API 側で検証するか
