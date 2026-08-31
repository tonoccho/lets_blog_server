# Phase 8-1: ユーザー情報拡充(WordPress互換フィールド)

## 目的

現在のサーバー側 `users` テーブルは `id, email, password_hash, role, created_at, updated_at` のみで、名前・プロフィール・言語設定などのユーザー情報を持たない。Phase 8でプロジェクト参加ユーザーをWordPressへ自動登録する際に、これらの拡張情報をWordPress側に反映する必要がある。本タスクでは、ユーザー情報を WordPress と互換性のあるスキーマに拡張する。

## 前提・決定事項

| 項目 | 決定内容 |
|---|---|
| 拡張フィールド | WordPress互換: `first_name`, `last_name`, `display_name`, `nickname`, `website_url`, `bio`, `locale`, `avatar_url`。サーバー専用: `department`, `position` |
| avatar_url の扱い | ローカルファイルアップロード機構は Phase 8 では実装しない。Gravatar (gravatar.com) への参照を基本とし、`avatar_url` はGravatar URL または別URLを格納するカラムとして用意(実装は URL参照のみ) |
| WordPress カスタムメタ | サーバーの `department`/`position` はWordPress側にネイティブ対応フィールドがないため、WordPress側には送信しない |
| 言語設定の初期値 | `locale` のデフォルトは `'ja_JP'` |
| 既存フィールドの互換性 | `email`, `password_hash`, `role` は引き続き使用。RBAC用 `user_roles` 中間テーブルもそのまま継続 |

## アーキテクチャ・実装詳細

### 1. Database スキーマ拡張(`V12__expand_users.sql`)

```sql
ALTER TABLE users ADD COLUMN (
  first_name VARCHAR(100),
  last_name VARCHAR(100),
  display_name VARCHAR(100),
  nickname VARCHAR(100),
  website_url VARCHAR(500),
  bio TEXT,
  locale VARCHAR(10) DEFAULT 'ja_JP',
  avatar_url VARCHAR(500),
  department VARCHAR(100),
  position VARCHAR(100)
);
```

- すべてのカラムは `NULL` 許可(既存レコードは `NULL` で初期化)
- `locale` のみデフォルト値 `'ja_JP'` 設定
- インデックス: 特に追加しない(ユーザー検索は `email` 既存インデックスで十分)

### 2. バックエンド実装

#### `User` エンティティ拡張
- `api/src/main/java/com/letsblog/api/domain/User.java`
  - 新フィールド追加: `firstName`, `lastName`, `displayName`, `nickname`, `websiteUrl`, `bio`, `locale`, `avatarUrl`, `department`, `position`
  - ゲッターのみ(setterは必要に応じて個別対応)

#### `UserDto` / `UserProfileUpdateRequest` 新設
- `api/src/main/java/com/letsblog/api/dto/UserProfileResponse.java`: 全フィールド返却用(GET時)
- `api/src/main/java/com/letsblog/api/dto/UserProfileUpdateRequest.java`: プロフィール編集時のリクエスト(メール・パスワード変更は別エンドポイント)

#### `UserService` 拡張
- 既存の `UserService` に以下メソッド追加:
  - `updateUserProfile(userId, UserProfileUpdateRequest)`: プロフィール情報を更新。戻り値はupdated `User`
  - `findUserWithProfile(userId)`: 拡張フィールド含めた `User` 取得

#### `UserController` 拡張
- `GET /api/users/{id}` — ユーザー詳細(拡張フィールド含める)
  - 戻り値: `UserProfileResponse`
  - 認可: 自身 or 管理者のみ参照可能(既存のチェック継承)
- `PUT /api/users/{id}` — ユーザープロフィール更新(メール・パスワードは 除外)
  - リクエスト: `UserProfileUpdateRequest`
  - 戻り値: 更新後の `UserProfileResponse`
  - 認可: 自身 or 管理者のみ更新可能

### 3. フロントエンド実装

#### 既存 `/users` ページ の拡張
- `web/src/app/users/page.tsx` / `UsersPage.tsx` (Server Component) は既存のユーザー一覧フォーム
- 新しい `UserProfileForm.tsx` (Client Component) 追加:
  - 既存の基本情報(`email`)に加え、拡張フィールドを含むフォーム
  - `first_name`, `last_name` の入力フィールド(初期値は `display_name` から推測可能)
  - `bio` テキストエリア
  - `locale` セレクト(`ja_JP`, `en_US` など)
  - `website_url` URL入力フィールド
  - `department`, `position` テキスト入力
  - 試験的: `avatar_url` 表示(変更は Gravatar連携フロー明記予定)

#### ユーザー編集ページ(新規または拡張)
- `/users/[id]/edit` ページ新設 (or `/users` ページ内でモーダル/タブで対応)
- 上記 `UserProfileForm` をそのまま利用
- `actions.ts` に `updateUserProfileAction` 追加

## スコープ・実装項目

実装対象:

- [x] `V12__expand_users.sql` Flyway マイグレーション作成
- [x] `User` エンティティにフィールド追加
- [x] `UserProfileResponse`, `UserProfileUpdateRequest` DTO新設
- [x] `UserService` にメソッド追加
- [x] `UserController` にエンドポイント追加(GET/PUT `/api/users/{id}`)
- [x] Web画面: `/users` ページをプロフィール編集対応に拡張
- [x] テスト整備

対象外・スコープ外:

- アバター画像のファイルアップロード機構(Gravatar参照のみ、ドキュメント化)
- WordPress側への `department`/`position` メタデータ同期(Phase 8-4では対象外、将来の拡張)
- メールアドレス・パスワード変更機能の改修(既存の別エンドポイント継承)
- OAuth/SSO のプロフィール自動同期(スコープ外)

## 実装順序

1. `V12__expand_users.sql` 作成・マイグレーション確認
2. `User` エンティティ拡張
3. DTO新設、`UserService` / `UserController` 実装
4. Web画面フォーム実装
5. テスト整備(`UserServiceTest` 拡張、`UserControllerTest` 新設)
6. 実機検証(ユーザープロフィール編集 → DB反映確認 → WordPress同期準備確認)

## テスト整備

- `UserServiceTest` に `updateUserProfile_プロフィール更新完了` テストケース追加
  - モック: `userRepository`, 手動コンストラクタ注入
  - テスト: `updateUserProfile()` の戻り値フィールド確認、DB呼び出し検証
- `UserControllerTest` 新設
  - `PUT /api/users/{id}` の成功・非認可エラー・不正入力ケース

## 実機検証

- ユーザープロフィール画面で各フィールド入力 → DB に正しく保存されることを確認
- GET `/api/users/{id}` で拡張フィールドが返却されることを確認
- 非管理者ユーザーが他人のプロフィール編集を試みた場合、401/403エラーが返されることを確認

