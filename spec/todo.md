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
- [ ] [03-api-server](phase1/03-api-server.md) — Spring Boot API サーバー
  - [x] プロジェクト雛形作成 → Gradle(Kotlin DSLではなくGroovy DSL)+ Java 21 toolchainに決定
  - [x] APIキー認証フィルタ実装(固定キー、`X-API-Key`ヘッダ、401/CORS等は未対応)
  - [x] サイト登録・認証情報 暗号化保存 実装(AES-256-GCM、`/api/sites` で動作確認済み)
  - [ ] WordPress REST API アダプタ実装
  - [ ] Markdown→HTML 変換パイプライン実装
  - [ ] カテゴリ/タグ 自動作成ロジック実装
  - [ ] 画像アップロード仲介実装
- [ ] [04-vscode-extension](phase1/04-vscode-extension.md) — VSCode 拡張
  - [ ] プロジェクト雛形作成
  - [ ] Front matter スキーマ実装・バリデーション
  - [ ] 「投稿/更新」コマンド実装
  - [ ] サイト切り替えUI実装
  - [ ] APIキー設定(SecretStorage)実装
- [ ] [05-web-frontend](phase1/05-web-frontend.md) — Web管理フロントエンド
  - [ ] プロジェクト雛形作成(Next.js等 + Tailwind)
  - [ ] サイト登録UI実装
  - [ ] APIキー発行UI実装
  - [ ] 投稿履歴閲覧UI実装
- [ ] [06-ollama-integration](phase1/06-ollama-integration.md) — Ollama 連携
  - [ ] モデル選定・pull
  - [ ] 下書き/校正/要約 API実装
  - [ ] タグ/カテゴリ自動提案 API実装
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
