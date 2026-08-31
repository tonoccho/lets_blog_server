# 02. データベーススキーマ(MySQL)

## 目的

サイト登録情報・認証情報・投稿ID対応表・AI生成ジョブ履歴などをMySQLで永続化するためのスキーマを設計する。

## 前提

- DB: MySQL 8.0(Docker Compose上、[01-docker-compose](01-docker-compose.md)参照)
- 認証情報(WordPressアプリケーションパスワード等)はアプリ層で暗号化してから保存する
- マイグレーション管理ツールは未選定(Flyway or Liquibase、Spring Bootとの親和性からFlywayが有力候補)

## 実装状況(更新: Phase1着手時点)

- マイグレーションツールは **Flyway** を採用(`api/src/main/resources/db/migration/V1__init_schema.sql`)。
- `api_keys` テーブルは実装を見送り。クライアント↔サーバー認証は当初決定どおり環境変数 `SERVER_API_KEY` 1本の固定キー方式とし、DBでの発行・失効管理は行わない(必要になった時点でテーブルを追加する)。
- `sites` テーブルには、front matterやVSCode拡張から参照する識別子として `site_key`(UNIQUE)を追加している(ドラフト時点では明記していなかったカラム)。

## テーブル案(ドラフト)

### `sites`
WordPressサイトの登録情報。

| カラム | 型 | 備考 |
|---|---|---|
| id | BIGINT PK | |
| name | VARCHAR | 表示名 |
| base_url | VARCHAR | 例: `https://example.com` |
| wp_username | VARCHAR | |
| wp_app_password_encrypted | VARBINARY | 暗号化済みアプリケーションパスワード |
| created_at / updated_at | DATETIME | |

### `api_keys`
クライアント(VSCode拡張/Webフロント)用の固定APIキー。

| カラム | 型 | 備考 |
|---|---|---|
| id | BIGINT PK | |
| key_hash | VARCHAR | キー自体はハッシュ化して保存、発行時のみ平文表示 |
| label | VARCHAR | 用途識別(例: "自宅PC VSCode") |
| created_at | DATETIME | |
| revoked_at | DATETIME NULL | 失効管理 |

### `posts`
Markdownファイルと WordPress 投稿の対応表。

| カラム | 型 | 備考 |
|---|---|---|
| id | BIGINT PK | |
| site_id | BIGINT FK → sites.id | |
| wp_post_id | BIGINT | WordPress側の投稿ID |
| local_file_hash | VARCHAR | 差分検知用(任意) |
| status | VARCHAR | draft / publish 等 |
| last_published_at | DATETIME | |
| created_at / updated_at | DATETIME | |

### `generation_jobs`
Ollama/ComfyUI呼び出しの履歴・ステータス管理。

| カラム | 型 | 備考 |
|---|---|---|
| id | BIGINT PK | |
| type | VARCHAR | `ollama_draft` / `ollama_tagging` / `comfyui_image` 等 |
| status | VARCHAR | pending / running / done / failed |
| request_payload | JSON | |
| result_payload | JSON NULL | |
| created_at / updated_at | DATETIME | |

## タスクチェックリスト

- [ ] ER図を確定(上記ドラフトのレビュー)
- [ ] マイグレーションツール選定(Flyway想定)
- [ ] 初期マイグレーションSQL作成
- [ ] 認証情報の暗号化方式決定(例: AES-GCM、鍵管理は環境変数 or 別KMS)
- [ ] `posts` テーブルと Front matter(`wp_post_id` 等)の整合ルールを [04-vscode-extension](04-vscode-extension.md) と合わせる

## 未決事項

- `generation_jobs` を非同期キュー(将来的にRabbitMQ等)にするか、当面は同期API + DBステータスのみで済ませるか
- 複数ユーザー利用を見据えた `users` テーブルの要否(Phase 1では不要と判断しているが要再確認)
