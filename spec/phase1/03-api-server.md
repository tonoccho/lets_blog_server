# 03. Spring Boot API サーバー

## 目的

VSCode拡張・Webフロントエンドからのリクエストを受け、WordPress REST APIおよびOllama/ComfyUI/PlantUMLへの仲介を行うAPIサーバーを実装する。将来的に他CMSアダプタを追加できる構造にする。

## 前提・決定事項

- 実装言語: Java + Spring Boot
- クライアント認証: 固定APIキー(リクエストヘッダで送付、[02-database-schema](02-database-schema.md) の `api_keys` テーブルで検証)
- WordPress認証: サイトごとに REST API + アプリケーションパスワード
- データストア: MySQL

## 実装状況(更新: Phase1着手時点)

- ビルドツールは **Gradle**(Groovy DSL)に決定。Java toolchainは21を指定(ローカルJDKは25だが、Gradle 8系がJDK25非対応だったため、`gradle wrapper` はGradle 9.6.1を使用)。
- `api/` 配下にプロジェクト一式を作成済み。`./gradlew bootJar` でのビルド、`docker compose up --build api` でのコンテナ起動、`/api/health` `/api/sites`(登録・一覧・重複エラー)の動作を確認済み。
- 認証は当面「固定APIキー」のみ(`ApiKeyAuthFilter`、`/api/health`以外の`/api/**`に適用)。
- WordPressアダプタ・Markdown変換・カテゴリ/タグ解決・画像アップロードは未実装(次イテレーションで着手)。

## コンポーネント構成(案)

- `controller` 層: VSCode拡張/Webフロント向けAPIエンドポイント
- `cms` パッケージ: CMSアダプタインターフェース + WordPress実装(`CmsAdapter` interface, `WordPressAdapter` 実装)
- `ai` パッケージ: Ollama/ComfyUI/PlantUMLクライアント
- `crypto`: 認証情報の暗号化/復号
- `repository`: JPA/MyBatis等によるDBアクセス

## 主要APIエンドポイント(ドラフト)

| メソッド | パス | 用途 |
|---|---|---|
| POST | `/api/sites` | サイト登録 |
| GET | `/api/sites` | サイト一覧取得 |
| POST | `/api/posts/publish` | Markdown(HTML変換済み)をWordPressへ新規投稿 |
| PUT | `/api/posts/{id}` | 既存投稿の更新 |
| POST | `/api/media/upload` | 画像アップロード(ローカル画像 or ComfyUI生成画像) |
| POST | `/api/taxonomy/resolve` | カテゴリ/タグ名 → ID解決(なければ作成) |
| POST | `/api/render/plantuml` | PlantUMLブロックのレンダリング仲介 |
| POST | `/api/ai/draft` | Ollamaによる下書き/校正/要約 |
| POST | `/api/ai/tags` | Ollamaによるタグ/カテゴリ提案 |
| POST | `/api/ai/image` | ComfyUIによる画像生成 |

具体的なリクエスト/レスポンス形式は [04-vscode-extension](04-vscode-extension.md) 側の投稿パイプライン設計と合わせて確定する。

## タスクチェックリスト

- [ ] プロジェクト雛形作成(Maven/Gradle選定、Spring Initializr等)
- [ ] APIキー認証フィルタ(`Filter`/`Interceptor`)実装
- [ ] `sites` CRUD実装 + 認証情報暗号化保存
- [ ] `CmsAdapter` インターフェース設計 + `WordPressAdapter` 実装
- [ ] Markdown→HTML変換(例: flexmark-java 等のライブラリ選定)
- [ ] カテゴリ/タグ自動作成ロジック実装
- [ ] 画像アップロード仲介実装(WordPress Media APIへのプロキシ)
- [ ] Ollama/ComfyUI/PlantUMLへのHTTPクライアント実装
- [ ] エラーハンドリング方針(WordPress側エラーの伝播形式)策定

## 未決事項

- Markdown→HTML変換をAPIサーバー側で行うか、VSCode拡張側で行うか(現状案: サーバー側に寄せて、CMS差し替え時の一貫性を保つ)
- 投稿の下書き保存/公開のステート遷移をどうAPIで表現するか(`status` パラメータのみで十分か)
- 認証情報暗号化の鍵管理方法
