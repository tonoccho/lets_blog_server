# Phase 8-4: プロジェクト参加ユーザーのWordPress自動登録

## 目的

プロジェクトにユーザーを参加させる際に、そのプロジェクトに紐付く WordPress 環境(ローカル/テスト/本番)へ、サーバー側のユーザー情報(01で拡張したプロフィール)を自動的に登録する。WordPress側でユーザーを作成・更新し、指定されたロール(editor/author/contributor など)を付与する。

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| ユーザー登録対象 | プロジェクトに紐付く環境(local_site_id, test_site_id, production_site_id が null でないもの)のみ。紐付なし環境への登録は不要 |
| ロール設定 | プロジェクト参加時に `wp_role`(`'administrator'`, `'editor'`, `'author'`, `'contributor'`, `'subscriber'` など)を指定。同期時にこのロールを WordPress へ反映 |
| 同期のタイミング | 即時同期(REST API 呼び出し)。非同期ジョブ化は Phase 8 のスコープ外(未決事項として記載) |
| WordPress への情報マッピング | サーバー側: `first_name`, `last_name`, `display_name`, `email`, `website_url`, `bio`, `locale`。WordPress側: `/wp-json/wp/v2/users` の対応フィールドへマッピング |
| 既存 provisionAuthor の拡張 | Phase 7 の `WordPressAdapter.provisionAuthor(credentials, email)` はロール固定(`'author'`)だが、ロールを引数化・ユーザー更新機能を追加 |
| ユーザー更新処理 | ユーザーが既に WordPress 側に存在する場合、プロフィール情報を PUT で更新。ロール変更も即座に反映 |
| 同期失敗時の扱い | 即時例外発火(ロールバック)。ユーザーへのエラー返却。リトライロジックは Phase 8 のスコープ外 |

## アーキテクチャ・実装詳細

### 1. Database 変更

`V13__add_projects.sql` で既に `project_users` テーブル作成済み:
- `project_id`, `user_id`, `wp_role`, `created_at`
- 変更なし

### 2. バックエンド実装

#### `WordPressAdapter.provisionAuthor()` の改修(重要)
- 既存メソッド: `provisionAuthor(CmsCredentials credentials, String email) -> String authorId`
  - ロール固定 (`'author'`)
- 改修版: オーバーロード またはパラメータ追加
  ```java
  public String provisionAuthor(
    CmsCredentials credentials,
    String email,
    String wpRole,           // 追加
    String firstName,        // 追加(01で拡張)
    String lastName,         // 追加
    String displayName,      // 追加
    String websiteUrl,       // 追加
    String bio,              // 追加
    String locale            // 追加
  ) -> String userId
  ```
  - 既存ユーザー検索ロジック継承
  - なければ POST `/wp-json/wp/v2/users` (拡張フィールド含める)
  - あれば PUT `/wp-json/wp/v2/users/{userId}` で更新
  - ロールは常に指定値で上書き

#### `ProjectUserSyncService` 新設
- `api/src/main/java/com/letsblog/api/service/ProjectUserSyncService.java`
  ```java
  public void addUserToProject(
    Long projectId,
    Long userId,
    String wpRole
  )
  // 処理:
  // 1. project 取得、紐付サイト(local/test/production)のリストアップ
  // 2. user 取得(01で拡張したフィールド)
  // 3. 各サイト の credentials から CmsAdapter を取得
  // 4. adapter.provisionAuthor() 拡張版を呼び出し → WordPress登録
  // 5. project_users テーブルに (projectId, userId, wpRole) 記録
  
  public void updateUserProjectRole(
    Long projectId,
    Long userId,
    String newWpRole
  )
  // 処理:
  // 1. project_users から既存 wp_role 取得
  // 2. newWpRole と異なればロール変更必要
  // 3. project 取得、紐付サイトごとに adapter.updateUserRole() 呼び出し(または provisionAuthor 拡張版で PUT)
  // 4. project_users テーブルの wp_role 更新
  
  public void removeUserFromProject(
    Long projectId,
    Long userId
  )
  // 処理:
  // project_users から削除(WordPress側のユーザー削除はスコープ外—ユーザーは残す)
  
  private List<Site> getProjectSites(Long projectId)
  // Project から紐付サイト(null でないもの)を取得
  ```

#### `WordPressAdapter` にユーザーロール更新メソッド追加(オプション)
- `updateUserRole(CmsCredentials credentials, String email, String newRole) -> void`
  - 既存ユーザーのロール変更のみ
  - ユーザーが見つからない場合は例外

#### `ProjectController` 拡張(ユーザー参加関連)
- `POST /api/projects/{id}/users` — ユーザーをプロジェクトに追加
  - リクエスト: `AddProjectUserRequest { userId: Long, wpRole: String }`
  - 処理: `projectUserSyncService.addUserToProject(projectId, userId, wpRole)`
  - レスポンス: 成功レスポンス or エラー詳細
  - 認可: 管理者のみ
- `PUT /api/projects/{id}/users/{userId}` — ユーザーロール変更
  - リクエスト: `UpdateProjectUserRequest { wpRole: String }`
  - 処理: `projectUserSyncService.updateUserProjectRole(projectId, userId, newWpRole)`
  - 認可: 管理者のみ
- `DELETE /api/projects/{id}/users/{userId}` — ユーザーをプロジェクトから削除
  - 処理: `projectUserSyncService.removeUserFromProject(projectId, userId)`
  - 認可: 管理者のみ
- `GET /api/projects/{id}/users` — プロジェクトのユーザー一覧
  - レスポンス: `List<ProjectUserResponse { userId, email, displayName, wpRole }>`
  - 認可: 管理者 or そのプロジェクトのメンバー(後続フェーズで細分化)

#### エラーハンドリング・ロギング
- WordPress REST API エラー時: CmsApiException をキャッチ、ユーザーへ詳細エラー返却
- 監査ログ: ユーザー参加・ロール変更・削除時に監査ログ記録(既存の AuditLogService 利用)

### 3. フロントエンド実装

#### プロジェクト詳細ページにユーザー管理パネル追加
- `web/src/app/projects/[id]/page.tsx` または `ProjectDetail.tsx`
  - 既存の環境スロット表示に加え、「プロジェクトメンバー」セクション追加

#### `ProjectUserManager.tsx` (新規 Client Component)
- メンバー一覧テーブル表示(メールアドレス、表示名、ロール、操作)
- 「ユーザーを追加」ボタン → モーダル `AddProjectUserModal.tsx`
- 各メンバー行の「ロール変更」「削除」ボタン

#### `AddProjectUserModal.tsx` (Client Component)
- ユーザー選択ドロップダウン(サーバー全ユーザーから選択、既に属するユーザーは除外)
- ロール選択ドロップダウン(administrator/editor/author/contributor/subscriber)
- 「追加」ボタン → `addUserToProjectAction()` 実行

#### `web/src/app/projects/[id]/actions.ts`
- `addUserToProjectAction(projectId, userId, wpRole)`: POST `/api/projects/{id}/users`
- `updateProjectUserRoleAction(projectId, userId, newWpRole)`: PUT `/api/projects/{id}/users/{userId}`
- `removeUserFromProjectAction(projectId, userId)`: DELETE `/api/projects/{id}/users/{userId}` (確認ダイアログ)

## スコープ・実装項目

実装対象:

- [x] `WordPressAdapter.provisionAuthor()` をロール・プロフィール情報引数化
- [x] `ProjectUserSyncService` 新設(複数環境への並列登録ロジック)
- [x] `ProjectController` にユーザー参加・ロール変更・削除エンドポイント追加
- [x] Web管理画面: プロジェクト詳細内にユーザー管理UI実装
- [x] テスト整備
- [x] 監査ログ統合

対象外・スコープ外:

- 非同期ジョブ化(バックグラウンド処理への変更は後続フェーズで検討)
- リトライ・失敗時の自動復旧ロジック
- WordPress側ユーザーの削除・無効化処理(ユーザーは残す運用)
- プロジェクトメンバーのアクセス制御細分化(管理者のみ制御に限定、後続フェーズで改善)
- `department`/`position` の WordPress カスタムメタ同期(将来検討)

## 実装順序

1. `WordPressAdapter.provisionAuthor()` の改修・テスト(既存テスト回帰確認)
2. `ProjectUserSyncService` 実装
3. `ProjectController` ユーザー参加エンドポイント追加
4. Web画面: ユーザー管理パネル実装
5. テスト整備(SyncService, Controller, 既存 WordPressAdapter テストの回帰確認)
6. 実機検証(ユーザー参加 → WordPress登録確認)

## テスト整備

- `WordPressAdapterTest` 拡張
  - 既存の `provisionAuthor_新規ユーザー作成` 継承(互換性確認)
  - `provisionAuthor_ロール引数指定` : ロール指定での作成確認
  - `provisionAuthor_既存ユーザー更新` : プロフィール・ロール更新確認
- `ProjectUserSyncServiceTest` 新設
  - `addUserToProject_複数環境への登録` : 紐付サイト数分の provisionAuthor 呼び出し確認
  - `addUserToProject_環境スロット未設定` : 紐付サイトなしの場合、登録処理なし確認
  - `addUserToProject_wp_role指定` : 指定ロール が WordPress へ反映確認
  - `updateUserProjectRole_ロール変更` : updateUserRole() or provisionAuthor() で ロール変更確認
  - `removeUserFromProject_削除` : project_users から削除確認
- `ProjectControllerTest` 拡張
  - ユーザー参加・削除・ロール変更エンドポイントの成功・認可エラー・バリデーションエラーケース

## 実機検証

1. プロジェクト作成 → ローカル・テスト環境を構築 (02の実機検証済み)
2. ユーザーをプロジェクトに参加(editor ロール指定)
   - サーバー側: project_users に記録確認
   - ローカル WordPress: 対象ユーザーが editor ロールで登録確認 (`wp-json/wp/v2/users`)
   - テスト WordPress: 同様に登録確認
3. プロジェクト詳細画面でメンバー一覧に表示されることを確認
4. ロール変更(editor → author) → WordPress側で即座にロール変更が反映されることを確認
5. ユーザー削除 → project_users から削除、WordPress側ユーザーは残る確認

