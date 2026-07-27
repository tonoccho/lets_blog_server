# 03. Spring Boot API サーバー

## 目的

VSCode拡張・Webフロントエンドからのリクエストを受け、WordPress REST APIおよびOllama/ComfyUI/PlantUMLへの仲介を行うAPIサーバーを実装する。将来的に他CMSアダプタを追加できる構造にする。

## 前提・決定事項

- 実装言語: Java + Spring Boot
- クライアント認証: 固定APIキー(リクエストヘッダで送付、[02-database-schema](02-database-schema.md) の `api_keys` テーブルで検証)
- WordPress認証: サイトごとに REST API + アプリケーションパスワード
- データストア: MySQL

## 実装状況(更新: 03-api-server 完了時点)

- ビルドツールは **Gradle**(Groovy DSL)。Java toolchainは21指定(`gradle wrapper` はGradle 9.6.1)。
- 認証は「固定APIキー」のみ(`ApiKeyAuthFilter`、`/api/health`以外の`/api/**`に適用)。
- `CmsAdapter`(cmsパッケージ)インターフェースと `WordPressAdapter` 実装が完了。WordPress REST API(`wp-json/wp/v2`)に対し Basic認証(ユーザー名 + アプリケーションパスワード)でアクセスする。
- Markdown→HTML変換は **flexmark-java**(`flexmark-all`, テーブル拡張込み)で実装(`MarkdownRenderer`)。
- カテゴリ/タグは名前で検索し、無ければ作成するロジックを `WordPressAdapter.resolveCategories/resolveTags` に実装済み。
- 画像アップロードは `PostPublishService` 内で、Markdown本文中の元ファイル名をWordPressアップロード後のURLに文字列置換する方式で実装(VSCode拡張からは、本文中で使うファイル名と同名のマルチパートファイルを同梱する想定)。単体の `/api/media/upload` エンドポイントも用意。
- **実機検証**: 一時的にDockerでWordPressコンテナ(`wordpress:latest` + `mysql:8.0`)を `lbs-net` に構築し、以下をエンドツーエンドで確認して破棄した。
  - サイト登録 → カテゴリ/タグ自動作成 → 画像アップロード → Markdown投稿(新規作成、`status=publish`)→ WordPress側でHTML・画像URL・カテゴリ/タグが正しく反映
  - 同じ `wpPostId` を指定した再投稿で正しく更新され、`posts` テーブルも1レコードのままupsertされる(重複作成されない)
  - 注意点: WordPressの Application Passwords 機能はデフォルトでHTTPS必須。HTTP環境で検証する場合は `wp_is_application_passwords_available` フィルタ(またはmust-useプラグイン)で有効化する必要がある。実際の利用者のWordPressサイトは通常HTTPSなので、本番運用では問題にならない想定。

## コンポーネント構成(案)

- `controller` 層: VSCode拡張/Webフロント向けAPIエンドポイント
- `cms` パッケージ: CMSアダプタインターフェース + WordPress実装(`CmsAdapter` interface, `WordPressAdapter` 実装)
- `ai` パッケージ: Ollama/ComfyUI/PlantUMLクライアント
- `crypto`: 認証情報の暗号化/復号
- `repository`: JPA/MyBatis等によるDBアクセス

## 主要APIエンドポイント

| メソッド | パス | 用途 | 状態 |
|---|---|---|---|
| POST | `/api/sites` | サイト登録 | 実装済み |
| GET | `/api/sites` | サイト一覧取得 | 実装済み |
| GET | `/api/posts` | 投稿履歴一覧(サイト名を結合、Web管理画面向け) | 実装済み |
| GET | `/api/generation-jobs` | AIジョブ履歴一覧(Web管理画面向け) | 実装済み(ジョブ作成は06/07で着手) |
| POST | `/api/posts/publish` | Markdown(multipart、画像同梱)をWordPressへ新規投稿/更新(`wpPostId`指定時は更新) | 実装済み・実機検証済み |
| POST | `/api/media/upload` | 画像単体アップロード(ローカル画像 or 将来のComfyUI生成画像) | 実装済み・実機検証済み |
| POST | `/api/taxonomy/resolve` | カテゴリ/タグ名 → ID解決(なければ作成) | 実装済み・実機検証済み |
| POST | `/api/render/plantuml` | PlantUMLブロックのレンダリング仲介 | 未実装([08-plantuml-integration](08-plantuml-integration.md)) |
| POST | `/api/ai/draft` | Ollamaによる下書き/校正/要約 | 未実装([06-ollama-integration](06-ollama-integration.md)) |
| POST | `/api/ai/tags` | Ollamaによるタグ/カテゴリ提案 | 未実装([06-ollama-integration](06-ollama-integration.md)) |
| POST | `/api/ai/image` | ComfyUIによる画像生成 | 未実装([07-comfyui-integration](07-comfyui-integration.md)) |

`/api/posts/publish` のリクエスト形式(`multipart/form-data`):

| フィールド | 必須 | 説明 |
|---|---|---|
| `site` | ✓ | サイトの `siteKey` |
| `title` | ✓ | 投稿タイトル |
| `slug` | - | 投稿スラッグ |
| `status` | - | `draft`(既定) / `publish` 等 |
| `categories` | - | カテゴリ名(複数指定可、存在しなければ自動作成) |
| `tags` | - | タグ名(複数指定可、存在しなければ自動作成) |
| `wpPostId` | - | 指定時は当該投稿を更新、未指定時は新規作成 |
| `markdown` | ✓ | Markdown本文 |
| `images` | - | 本文中で参照する画像ファイル(複数可)。Markdown本文中のファイル名表記(例: `eyecatch.png`)がそのままアップロード後のWordPress URLに文字列置換される |

レスポンス: `{ "wpPostId": number, "wpPostUrl": string, "status": string }`

## タスクチェックリスト

- [x] プロジェクト雛形作成 → Gradle
- [x] APIキー認証フィルタ(`ApiKeyAuthFilter`)実装
- [x] `sites` CRUD実装 + 認証情報暗号化保存
- [x] `CmsAdapter` インターフェース設計 + `WordPressAdapter` 実装
- [x] Markdown→HTML変換(flexmark-java)
- [x] カテゴリ/タグ自動作成ロジック実装
- [x] 画像アップロード仲介実装(WordPress Media APIへのプロキシ)
- [ ] Ollama/ComfyUI/PlantUMLへのHTTPクライアント実装(各連携specで別途着手)
- [x] エラーハンドリング方針 → `GlobalExceptionHandler` で `SiteNotFoundException`→404, `CmsApiException`→502, `IllegalArgumentException`→409, バリデーションエラー→400 に統一

## 未決事項

- 認証情報暗号化の鍵管理方法(現状は環境変数 `APP_ENCRYPTION_KEY` 直渡し。将来的にKMS等への移行を検討)
- 画像差し替えを「ファイル名の文字列置換」で行っている点(VSCode拡張側の実際のMarkdown記法・相対パス表記が決まった段階で、より頑健な置換方式(正規表現でのMarkdown画像記法パースなど)に見直す余地あり)
- WordPress以外のCMSアダプタ追加時の `CmsAdapter` インターフェースの過不足(現状はWordPress実装のみで検証)
