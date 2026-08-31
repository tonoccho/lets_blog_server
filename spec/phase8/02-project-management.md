# Phase 8-2: プロジェクト管理基盤の実装

## 目的

複数の WordPress 環境(ローカル/テスト/本番)をひとつの「プロジェクト」として一元管理する仕組みを構築する。プロジェクトは既存の `sites` テーブルの複数行を参照する形で実装し、既存のサイト管理・プロビジョニング機構を再利用する。

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| 環境スロットの実装方式 | `projects` テーブルに `local_site_id`, `test_site_id`, `production_site_id` (すべて nullable FK to `sites.id`) を持つ。各環境はオプション・段階的に紐付可能 |
| プロジェクト削除時の扱い | プロジェクトに紐付く `sites` レコード(環境)は削除しない。プロジェクト自体のレコードのみ削除し、関連するサイトは orphan 状態になる(or プロジェクト非所属として扱う) |
| プロジェクト・ユーザーの関係 | `project_users` 中間テーブルで n:m 管理。プロジェクト削除時は関連レコードもカスケード削除 |
| サイトの重複紐付け防止 | 1つの site は最大1つの project にのみ属す(UNIQUE制約検討、実装時に判定) |
| プロジェクト作成フロー | 空のプロジェクト(環境なし)として作成開始 → 別途エンドポイントで環境を紐付け or 環境作成時にプロジェクトに紐付け (01で拡張したSiteService.register()の後に呼び出し) |

## アーキテクチャ・実装詳細

### 1. Database スキーマ(`V13__add_projects.sql`)

```sql
CREATE TABLE projects (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(255) NOT NULL,
  slug VARCHAR(255) NOT NULL UNIQUE,
  local_site_id BIGINT,
  test_site_id BIGINT,
  production_site_id BIGINT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  FOREIGN KEY (local_site_id) REFERENCES sites(id) ON DELETE SET NULL,
  FOREIGN KEY (test_site_id) REFERENCES sites(id) ON DELETE SET NULL,
  FOREIGN KEY (production_site_id) REFERENCES sites(id) ON DELETE SET NULL
);

CREATE TABLE project_users (
  project_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  wp_role VARCHAR(50) NOT NULL DEFAULT 'contributor',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (project_id, user_id),
  FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
```

- `slug` は URL-safe な文字列(プロジェクト識別用)、一意性確保
- `wp_role`: `'administrator'`, `'editor'`, `'author'`, `'contributor'`, `'subscriber'` など WordPress 標準ロール
- `local_site_id` など: NULL 許可(紐付環境はオプション)

### 2. バックエンド実装

#### エンティティ・リポジトリ
- `api/src/main/java/com/letsblog/api/domain/Project.java`
  - フィールド: `id`, `name`, `slug`, `localSiteId`, `testSiteId`, `productionSiteId`, `createdAt`, `updatedAt`
- `api/src/main/java/com/letsblog/api/domain/ProjectUser.java`
  - 複合主キー(`projectId`, `userId`)、`wpRole`, `createdAt`
- `api/src/main/java/com/letsblog/api/repository/ProjectRepository extends JpaRepository<Project, Long>`
  - `findBySlug(slug)`: slug検索
  - `findAllByOrderByCreatedAtDesc()`: 一覧取得
- `api/src/main/java/com/letsblog/api/repository/ProjectUserRepository extends JpaRepository<ProjectUser, ProjectUserId>`

#### DTO
- `api/src/main/java/com/letsblog/api/dto/ProjectResponse.java`
  - `id`, `name`, `slug`, `localSiteId`, `testSiteId`, `productionSiteId`, `createdAt`, `updatedAt`
  - 詳細版: 紐付サイト情報を embed(`SiteResponse` 部分的に)
- `api/src/main/java/com/letsblog/api/dto/ProjectCreateRequest.java`
  - `name`, `slug` のみ(環境は別エンドポイントで紐付け)
- `api/src/main/java/com/letsblog/api/dto/ProjectEnvironmentBindRequest.java`
  - `environment` (local/test/production)、`siteId`

#### Service
- `api/src/main/java/com/letsblog/api/service/ProjectService.java`
  ```java
  public Project createProject(String name, String slug)
  public Project getProject(Long projectId)
  public List<Project> listProjects()
  public Project updateProject(Long projectId, String name)
  public void deleteProject(Long projectId)  // project_users もカスケード削除
  public void bindEnvironment(Long projectId, String environment, Long siteId)
  public void unbindEnvironment(Long projectId, String environment)
  public void addUserToProject(Long projectId, Long userId, String wpRole)
  public void updateUserProjectRole(Long projectId, Long userId, String wpRole)
  public void removeUserFromProject(Long projectId, Long userId)
  public List<ProjectUser> getProjectUsers(Long projectId)
  ```

#### Controller
- `api/src/main/java/com/letsblog/api/controller/ProjectController.java`
  - `POST /api/projects` — プロジェクト作成
    - リクエスト: `ProjectCreateRequest`
    - レスポンス: `ProjectResponse`
    - 認可: 管理者のみ
  - `GET /api/projects` — プロジェクト一覧
    - レスポンス: `List<ProjectResponse>`
    - ページング対応(任意、フェーズ7までの既存ページング実装確認後に判断)
  - `GET /api/projects/{id}` — プロジェクト詳細
    - レスポンス: 紐付サイト情報付き `ProjectResponse`
  - `PUT /api/projects/{id}` — プロジェクト編集(名前など)
    - リクエスト: 変更フィールドのみ
    - 認可: 管理者のみ
  - `DELETE /api/projects/{id}` — プロジェクト削除
    - 認可: 管理者のみ
  - `POST /api/projects/{id}/environments` — 環境紐付け
    - リクエスト: `ProjectEnvironmentBindRequest`
    - 認可: 管理者のみ
  - `DELETE /api/projects/{id}/environments/{env}` — 環境切離し
    - パス: env = local/test/production
    - 認可: 管理者のみ
  - (ユーザー参加関連は 04-project-user-wp-sync で扱う)

### 3. フロントエンド実装

#### `/projects` ページ(新規作成)
- `web/src/app/projects/page.tsx`: Server Component
  - `requireAdminSession()` で管理者チェック
  - `apiClient.getProjects()` で一覧取得 → `ProjectList.tsx` へ
- `web/src/app/projects/ProjectList.tsx`: Client Component
  - プロジェクト一覧テーブル表示(名前、環境状態アイコン、作成日、操作ボタン)
  - 「新規プロジェクト」ボタン → モーダル/ページ遷移で `ProjectForm.tsx`

#### `/projects/[id]` ページ(詳細)
- `web/src/app/projects/[id]/page.tsx`: Server Component
  - `apiClient.getProject(id)` で詳細取得
  - 環境スロット表示、ユーザー管理セクション(04で追加) をレンダー

#### `ProjectForm.tsx` / `EnvironmentSlot.tsx`
- `ProjectForm.tsx` (Client Component): プロジェクト作成・編集フォーム
  - 名前、slug 入力
  - `actions.ts` の `createProjectAction` / `updateProjectAction` バインド
- `EnvironmentSlot.tsx` (Client Component): 各環境スロット表示・操作
  - 「ローカル環境を構築」ボタン → 既存の `ManagedWordPressForm` へ遷移 or 統合
  - 「外部WordPress登録」ボタン → 既存の `SiteForm` へ
  - 紐付済みなら「切離し」ボタン

#### `actions.ts`
- `web/src/app/projects/actions.ts`
  - `createProjectAction(FormState, FormData)`: POST `/api/projects`
  - `updateProjectAction(id, FormState, FormData)`: PUT `/api/projects/{id}`
  - `deleteProjectAction(id)`: DELETE `/api/projects/{id}` (確認ダイアログ付き)
  - `bindEnvironmentAction(projectId, environment, siteId)`: POST `/api/projects/{id}/environments`
  - `unbindEnvironmentAction(projectId, environment)`: DELETE `/api/projects/{id}/environments/{env}`

## スコープ・実装項目

実装対象:

- [x] `V13__add_projects.sql` マイグレーション作成
- [x] `Project`, `ProjectUser` エンティティ、リポジトリ実装
- [x] `ProjectService` / `ProjectController` 実装(ユーザー参加関連除く)
- [x] Web管理画面: `/projects` ページ + 関連コンポーネント実装
- [x] テスト整備

対象外・スコープ外:

- プロジェクト間でのサイト共有(1 site = 1 project のみ)
- プロジェクトメンバーシップの細かなアクセス制御(Phase 8では管理者のみプロジェクト操作、後続フェーズで改善検討)
- プロジェクトごとのダッシュボード・メトリクス表示(スコープ外、後続フェーズで検討)

## 実装順序

1. `V13__add_projects.sql` 作成・マイグレーション確認
2. エンティティ・リポジトリ実装
3. `ProjectService` / `ProjectController` 実装
4. Web画面実装(フォーム・ページ・アクション)
5. テスト整備
6. 実機検証(プロジェクト作成 → 環境紐付け → DB反映確認)

## テスト整備

- `ProjectServiceTest`: CRUD・環境操作・削除時のカスケード削除確認
- `ProjectControllerTest`: 各エンドポイントの成功・認可エラー・不正入力ケース

## 実機検証

- プロジェクト作成 → DB に正しく記録されることを確認
- ローカル環境を新規構築 → `sites` テーブルに記録 + `projects.local_site_id` に紐付されることを確認
- テスト/本番環境を既存 WordPress へ登録 → 同様に紐付されることを確認
- プロジェクト削除 → 環境(site)は削除されず、orphan 状態になることを確認
- プロジェクト詳細ページで紐付環境が正しく表示されることを確認

