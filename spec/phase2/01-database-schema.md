# 01. データベーススキーマ拡張(users テーブル)

## 目的

Web管理フロントエンドのログインユーザーを管理するための `users` テーブルを追加する。

## 前提・決定事項

- マイグレーションツールは引き続き Flyway([phase1/02-database-schema](../phase1/02-database-schema.md)参照)。新規マイグレーション `V2__add_users.sql` を追加する。
- パスワードは平文保存せず、APIサーバー側でBCryptハッシュ化した値のみを保存する。
- 複数人利用が前提のため `email` はUNIQUE制約を付与する。

## 実装状況(更新: 01-database-schema 完了時点)

- `V2__add_users.sql` を追加し、Dockerコンテナ再起動でFlywayが自動適用することを確認済み(`schema_version=2`)。
- 初回管理者アカウントは `InitialAdminBootstrap`(`ApplicationRunner`)で実装([02-api-server](02-api-server.md)参照)。

## テーブル案

### `users`

| カラム | 型 | 備考 |
|---|---|---|
| id | BIGINT PK | |
| email | VARCHAR UNIQUE | ログインID兼連絡先 |
| password_hash | VARCHAR | BCryptハッシュ値 |
| role | VARCHAR(20) | `admin` / `user` |
| created_at / updated_at | DATETIME | |

## 初回管理者アカウントのブートストラップ(案)

- 初回起動時、`users` テーブルが空の場合に限り、環境変数 `INITIAL_ADMIN_EMAIL` / `INITIAL_ADMIN_PASSWORD`(`.env.example` に追記)からadminロールのユーザーを1件自動作成する。
- 実装場所はマイグレーションSQLではなく、APIサーバー起動時の初期化処理(`ApplicationRunner`等)とする(パスワードのBCryptハッシュ化にアプリ側のライブラリが必要なため)。
- 既にユーザーが1件以上存在する場合は何もしない(冪等)。
- 本方式は [00-overview](00-overview.md) の未決事項として、実装前にレビューする。

## タスクチェックリスト

- [x] `users` テーブルのカラム定義レビュー(上記ドラフトの確定)
- [x] `V2__add_users.sql` マイグレーション作成
- [x] 初回管理者アカウントのブートストラップ処理実装
- [x] `.env.example` に `INITIAL_ADMIN_EMAIL` / `INITIAL_ADMIN_PASSWORD` を追記

## 未決事項

- ブートストラップ用パスワードを初回ログイン後に変更必須にするか(Phase 2では未対応、将来検討)
- `users` テーブルの論理削除(`deleted_at`)を持つか、物理削除のみとするか(現状は物理削除を想定)
