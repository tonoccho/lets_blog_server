# Let's Blog Server — To-Do

Phase 1(初期構築)の進捗管理リスト。各項目の詳細は `phase1/` 配下の実行計画ファイルを参照。

## Phase 1: 初期スタック構築

- [ ] [00-overview](phase1/00-overview.md) — 全体スコープ・アーキテクチャの確定
- [x] [01-docker-compose](phase1/01-docker-compose.md) — Docker Compose 一式の構成
  - [x] docker-compose.yml 作成
  - [x] .env.example 作成
  - [x] GPU(NVIDIA Container Toolkit)動作確認 — ollama/comfyui コンテナ内から `nvidia-smi` 認識、ComfyUI側は torch 2.13.0+cu130 で `cuda.is_available()=True` まで確認
  - [x] mysql / phpmyadmin / api の起動確認(ヘルスチェック・API呼び出し成功)
  - [x] ollama / comfyui / plantuml の起動確認
    - ollama: `ollama/ollama:latest` 起動、`GET /` → `Ollama is running`
    - plantuml: `plantuml/plantuml-server:jetty` 起動、`GET /` → 302(正常なリダイレクト)
    - comfyui: イメージを `yanwk/comfyui-boot:cu130-slim`(CUDA 13系, 約4.9GB)に確定して起動、`GET /system_stats` → 200、GPU認識確認済み
  - [x] `docker compose down` → `up -d` の再作成後もMySQLデータ(登録済みサイト)が永続化されることを確認
- [x] [02-database-schema](phase1/02-database-schema.md) — MySQL スキーマ設計
  - [x] ER図確定(sites / posts / generation_jobs。api_keysはPhase1では見送り、固定APIキーのみで運用)
  - [x] マイグレーションツール選定 → Flyway に決定
  - [x] 初期マイグレーションスクリプト作成(`V1__init_schema.sql`、起動時の自動適用を確認済み)
- [x] [03-api-server](phase1/03-api-server.md) — Spring Boot API サーバー
  - [x] プロジェクト雛形作成 → Gradle(Kotlin DSLではなくGroovy DSL)+ Java 21 toolchainに決定
  - [x] APIキー認証フィルタ実装(固定キー、`X-API-Key`ヘッダ、401/CORS等は未対応)
  - [x] サイト登録・認証情報 暗号化保存 実装(AES-256-GCM、`/api/sites` で動作確認済み)
  - [x] WordPress REST API アダプタ実装(`CmsAdapter`/`WordPressAdapter`、Basic認証)
  - [x] Markdown→HTML 変換パイプライン実装(flexmark-java)
  - [x] カテゴリ/タグ 自動作成ロジック実装(名前検索→無ければ作成)
  - [x] 画像アップロード仲介実装(`/api/posts/publish` 内、および単体 `/api/media/upload`)
  - [x] `/api/posts/publish`(新規作成・更新)を一時WordPressコンテナに対して実機検証(HTML/画像/カテゴリ/タグ反映、再投稿での更新・upsertを確認)
- [x] [04-vscode-extension](phase1/04-vscode-extension.md) — VSCode 拡張
  - [x] プロジェクト雛形作成(TypeScript, `extension/`配下)
  - [x] Front matter スキーマ実装・バリデーション(gray-matter、`site`/`title`必須チェック)
  - [x] 「投稿/更新」コマンド実装(`letsBlog.publish`、画像同梱・front matter書き戻し)
  - [x] サイト切り替えUI実装(`letsBlog.selectSite`、QuickPick)
  - [x] APIキー設定(SecretStorage)実装(`letsBlog.setApiKey`)
  - [x] AI支援コマンド実装(`letsBlog.askAi` / `letsBlog.suggestTags` / `letsBlog.generateImage`、対応サーバーAPIは06/07で実装)
  - [x] コンパイル済みクライアントコードを一時WordPressに対して実機検証(サイト一覧取得・画像同梱投稿・カテゴリ/タグ自動作成を確認)
- [x] [05-web-frontend](phase1/05-web-frontend.md) — Web管理フロントエンド
  - [x] プロジェクト雛形作成(Next.js + Tailwind CSS v4)
  - [x] サイト登録UI実装(Server Action、Playwrightでブラウザ経由の送信を実機検証)
  - [x] 投稿履歴閲覧UI実装(`/posts`、バックエンドに`GET /api/posts`を追加)
  - [x] AIジョブ状況閲覧UI実装(`/ai-jobs`、バックエンドに`GET /api/generation-jobs`を追加)
  - [x] システム画面実装(`/system`、phpMyAdmin/ComfyUI/PlantUMLへのリンク)
  - ※「APIキー発行UI」は固定APIキー方式に伴いスコープ外化(詳細は[05-web-frontend](phase1/05-web-frontend.md)参照)
- [x] [06-ollama-integration](phase1/06-ollama-integration.md) — Ollama 連携
  - [x] モデル選定・pull(`qwen2.5:7b-instruct`)
  - [x] 下書き/校正/要約 API実装(`POST /api/ai/draft`)
  - [x] タグ/カテゴリ自動提案 API実装(`POST /api/ai/tags`)
  - [x] `generation_jobs` への実行履歴記録を実装
  - [x] 実モデル・実APIでdraft/proofread/summarize/tagsを実機検証(VSCode拡張のクライアントコード経由でも確認)
- [ ] [07-comfyui-integration](phase1/07-comfyui-integration.md) — ComfyUI 連携
  - [ ] ワークフローJSON作成
  - [ ] 画像生成API仲介実装
  - [ ] 生成画像のWordPressメディア自動アップロード実装
- [ ] [08-plantuml-integration](phase1/08-plantuml-integration.md) — PlantUML 連携
  - [ ] Markdown内図ブロック記法確定
  - [ ] レンダリングAPI仲介実装
  - [ ] 生成画像の埋め込み処理実装

## Phase 2 以降(未着手・スコープ外候補)

- [ ] WordPress 以外のCMSアダプタ追加
- [ ] リモート常時稼働化(自宅サーバー/VPS)対応・外部公開時のセキュリティ強化
- [ ] Web管理フロントエンドのユーザー認証(複数人利用)
