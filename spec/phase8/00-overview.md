# Phase 8: マルチ環境プロジェクト管理・ユーザー情報拡充・UI改善

## 目的

Phase 7までで基本的なプロビジョニング・カスタムタグ機能が完成した。Phase 8では、以下3つの大型機能を追加する:

1. **マルチ環境プロジェクト管理**: 単一のWordPressサイト管理ではなく、「プロジェクト」という上位概念を導入。1つのプロジェクトの配下に、ローカル/テスト/本番の3つのWordPress環境を段階的に紐付けられる。これらはカスタムタグやユーザーを共有する。
2. **ユーザー情報の拡充・WordPress同期**: サーバー側のユーザー情報をWordPress並みの情報量に拡張。プロジェクト参加ユーザーは自動的にWordPress上に指定権限で登録される。
3. **UI改善**: 管理画面のメニューをアイコンベースに統一し、ナビゲーション体験を向上させる。

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| プロジェクト・環境の関係 | `projects` テーブルは `local_site_id`, `test_site_id`, `production_site_id`(すべてnullable FK to sites)を持つ。各環境はオプション・段階的に紐付可能。内部的には既存の `sites` テーブルを再利用し、既存の `SiteService`・`WordPressAdapter`・プロビジョニング機構をそのまま活用 |
| カスタムタグの共有範囲 | `custom_tags` テーブルに `project_id`(nullable)列を追加。プロジェクト配下サイトへのレンダリング時は「そのプロジェクトのタグ + グローバル(`project_id IS NULL`)タグ」を対象とする。既存グローバルタグはマイグレーション完了後も `project_id = NULL` で扱う |
| ユーザーの同期先 | 各プロジェクトに参加したサーバー側ユーザーは、そのプロジェクトに紐付く環境(ローカル/テスト/本番、設定されているもののみ)のWordPress へ01で拡張したユーザー情報を自動登録。権限(editor/author/contributor など)はプロジェクト参加時に設定可能 |
| ユーザー情報項目 | WordPress互換フィールド追加: `first_name`, `last_name`, `display_name`, `nickname`, `website_url`, `bio`, `locale`, `avatar_url`。加えてサーバー専用: `department`, `position`。パスワード・メールはそのまま既存継承 |
| メニューアイコン | `lucide-react`を導入。全メニュー項目にアイコン付与(ダッシュボード/ユーザー/プロジェクト/カスタムタグ/監査ログなど) |
| 既存「サイト」機能との関係 | プロジェクト環境(local/test/production)は内部的には既存の `sites` テーブル行として実装。既存の `/sites` 機能は廃止し、プロジェクト管理へ統合 |

## アーキテクチャ概要

```
管理画面 (Next.js)
├─ メニュー(lucide-react アイコン統一)
│  ├─ ダッシュボード
│  ├─ プロジェクト (新規)
│  ├─ ユーザー (拡張)
│  ├─ 投稿履歴
│  ├─ AIジョブ
│  ├─ カスタムタグ (プロジェクトスコープ対応)
│  ├─ 監査ログ
│  ├─ ロール管理
│  └─ システム

API (Spring Boot)
├─ POST /api/projects — プロジェクト作成(環境なし)
├─ GET /api/projects — プロジェクト一覧
├─ GET /api/projects/{id} — プロジェクト詳細(紐付サイト情報)
├─ PUT /api/projects/{id} — プロジェクト名・説明編集
├─ DELETE /api/projects/{id} — プロジェクト削除
├─ POST /api/projects/{id}/environments — 環境(サイト)の紐付け
├─ DELETE /api/projects/{id}/environments/{env} — 環境の切離し
├─ POST /api/projects/{id}/users — ユーザーをプロジェクトに参加させる
│  └─ → 紐付く各環境WordPress側へ自動登録(provisionAuthor拡張版)
├─ PUT /api/projects/{id}/users/{userId} — ユーザーのWP権限変更(即時同期)
├─ DELETE /api/projects/{id}/users/{userId} — ユーザーをプロジェクトから削除
├─ GET /api/users/{id} — 拡張ユーザー情報取得
├─ PUT /api/users/{id} — ユーザー情報更新(プロフィール等)
├─ GET /api/custom-tags?projectId={id} — プロジェクトスコープのカスタムタグ取得
└─ (既存の /api/sites, /api/posts, /api/ai など互換性維持)

Database (Flyway)
├─ V12: users テーブル拡張(first_name, last_name, display_name, etc.)
├─ V13: projects テーブル追加(id, name, slug, local_site_id, test_site_id, production_site_id, created_at, updated_at)
├─    + project_users テーブル(project_id, user_id, wp_role, created_at)
├─ V14: custom_tags に project_id(nullable FK) 列追加
└─ (既存の sites, user_roles, custom_tags テーブルは継続)
```

## Phase 8 のスコープ

実装項目:

### 1. ユーザー情報拡充
- [01-user-profile-expansion.md](01-user-profile-expansion.md)
  - `V12__expand_users.sql`: WordPress互換フィールド追加(`first_name`, `last_name`, `display_name`, `nickname`, `website_url`, `bio`, `locale`, `avatar_url`) + サーバー専用(`department`, `position`)
  - `User` エンティティ・DTO 拡張
  - `UserService` / `UserController` 拡張(`/api/users/{id}` の詳細取得・更新)
  - Web管理画面: 既存 `/users` ページのフォーム拡張
  - テスト整備

### 2. プロジェクト管理基盤
- [02-project-management.md](02-project-management.md)
  - `V13__add_projects.sql`: `projects` テーブル + `project_users` 中間テーブル
  - `Project` / `ProjectUser` エンティティ・リポジトリ・DTO
  - `ProjectService` (CRUD、環境スロット管理、削除時のサイトリンク解除)
  - `ProjectController` (`/api/projects` CRUD + 環境操作)
  - Web管理画面: `/projects` ページ(一覧・作成・詳細・環境スロット紐付けUI)
  - テスト整備

### 3. カスタムタグのプロジェクトスコープ化
- [03-project-scoped-custom-tags.md](03-project-scoped-custom-tags.md)
  - `V14__add_custom_tags_project_scope.sql`: `custom_tags` に `project_id`(nullable FK) 追加
  - `CustomTagService` / `CustomTagRenderService` の改修
  - Web管理画面: `/custom-tags` をプロジェクトコンテキスト対応に拡張
  - テスト整備

### 4. プロジェクト参加ユーザーのWordPress同期
- [04-project-user-wp-sync.md](04-project-user-wp-sync.md)
  - `WordPressAdapter.provisionAuthor()` をロール引数化(固定`"author"`→可変)、ユーザー情報拡張対応
  - `ProjectUserSyncService` 新設: プロジェクト参加/ロール変更をトリガにWordPress側に自動登録
  - `ProjectController` にユーザー参加・ロール変更・削除エンドポイント追加
  - Web管理画面: プロジェクト詳細内にユーザー管理UI追加
  - テスト整備

### 5. UI メニューアイコン化
- [05-ui-menu-icons.md](05-ui-menu-icons.md)
  - `lucide-react` 導入
  - `web/src/app/layout.tsx` のメニュー改修(アイコン+ラベル)
  - 新メニュー項目 `/projects` 追加
  - バックエンド変更なし

対象外・スコープ外:

- microCMS のプロジェクト対応(WordPress専用)
- テスト用・本番用 WordPress の実装・セットアップ(リモートサーバーの準備は別途)
- アバター画像ファイルアップロード機構(Gravatar参照を基本とする)
- WordPress側への `department`/`position` カスタムメタ同期(将来検討)

## タスク実装順序

1. [05-ui-menu-icons.md](05-ui-menu-icons.md) — UIメニューアイコン化(独立、依存なし)
2. [01-user-profile-expansion.md](01-user-profile-expansion.md) — ユーザー情報拡充(01で拡張したユーザー情報を04で使用するため先行)
3. [02-project-management.md](02-project-management.md) — プロジェクト管理基盤
4. [03-project-scoped-custom-tags.md](03-project-scoped-custom-tags.md) — カスタムタグのプロジェクトスコープ化
5. [04-project-user-wp-sync.md](04-project-user-wp-sync.md) — プロジェクト参加ユーザーのWordPress同期
6. エンドツーエンド実機検証(ローカルプロジェクト作成 → ユーザー参加 → WordPress自動登録確認)

