# Let's Blog Server — To-Do

Phase 1(初期構築)の進捗管理リスト。各項目の詳細は `phase1/` 配下の実行計画ファイルを参照。

## Phase 1: 初期スタック構築

- [x] [00-overview](phase1/00-overview.md) — 全体スコープ・アーキテクチャの確定(ユーザー承認済み)
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
- [x] [07-comfyui-integration](phase1/07-comfyui-integration.md) — ComfyUI 連携
  - [x] チェックポイント配置(`v1-5-pruned-emaonly.safetensors`)
  - [x] ワークフローJSON作成(txt2img: Checkpoint→CLIPTextEncode→EmptyLatentImage→KSampler→VAEDecode→SaveImage)
  - [x] 画像生成API仲介実装(`POST /api/ai/image`、`/prompt`投入→`/history`ポーリング→`/view`取得)
  - [x] 生成画像のWordPressメディア自動アップロード → 既存の`/api/posts/publish`画像アップロード経路に統合
  - [x] 実機検証(プロンプト→画像生成→VSCode拡張経由でのWordPress投稿まで一気通貫で確認)
- [x] [08-plantuml-integration](phase1/08-plantuml-integration.md) — PlantUML 連携
  - [x] Markdown内図ブロック記法確定(標準的な```plantumlフェンスコードブロック)
  - [x] レンダリングAPI仲介実装(`POST /api/render/plantuml`、PlantUML独自エンコードを自前実装)
  - [x] 生成画像の埋め込み処理実装(`PlantUmlEmbedService`、投稿パイプラインに自動組み込み)
  - [x] 実機検証(日本語シーケンス図のレンダリング→WordPress投稿への埋め込みまで確認)

## Phase 2: Web管理フロントエンドのユーザー認証(複数人利用)

- [x] [00-overview](phase2/00-overview.md) — 全体スコープ・ユーザー認証アーキテクチャの確定
- [x] [01-database-schema](phase2/01-database-schema.md) — MySQL `users` テーブル設計・マイグレーション
- [x] [02-api-server](phase2/02-api-server.md) — Spring Boot API サーバー拡張(ユーザー認証・管理エンドポイント)
  - [x] BCryptパッケージ追加・`UserService` 実装
  - [x] `/api/auth/login` エンドポイント実装
  - [x] `/api/users` CRUD エンドポイント実装
  - [x] 初回管理者ブートストラップ処理実装
- [x] [03-web-frontend](phase2/03-web-frontend.md) — Web管理フロントエンド(Next.js)拡張
  - [x] next-auth導入・ログイン画面実装(v5想定からv4安定版に変更、`middleware.ts`は`proxy.ts`に変更)
  - [x] ユーザー管理画面(`/users`)実装
  - [x] 既存ページのログイン必須化・admin権限チェック実装
  - [x] 実機検証(ログイン・ユーザー管理・アクセス制御)

## Phase 3: CMSアダプタ拡張(microCMS対応)

- [x] [00-overview](phase3/00-overview.md) — 全体スコープ・アーキテクチャの確定
- [x] [01-domain-model](phase3/01-domain-model.md) — CmsType、sealed CmsCredentials、ID型String化
- [x] [02-wordpress-adapter-tests](phase3/02-wordpress-adapter-tests.md) — WordPressAdapter修正・回帰テスト整備
- [x] [03-cms-adapter-factory](phase3/03-cms-adapter-factory.md) — CmsAdapterFactory新設、既存4コンポーネントの注入変更
- [x] [04-database-schema](phase3/04-database-schema.md) — Flyway V3、エンティティ/DTO/Service更新
- [x] [05-microcms-adapter](phase3/05-microcms-adapter.md) — MicroCmsAdapter実装・テスト・実機検証
- [x] [06-web-frontend](phase3/06-web-frontend.md) — サイト登録UI動的化(CMS選択)
- [x] [07-vscode-extension](phase3/07-vscode-extension.md) — wpPostId型のString化

## Phase 3 以降(未着手・スコープ外候補)

- [ ] リモート常時稼働化(自宅サーバー/VPS)対応・外部公開時のセキュリティ強化
- [ ] その他のCMS対応(WordPress/microCMS以外)
- [ ] microCMS実アカウントでのエンドツーエンド実機検証(サイト登録・投稿作成/更新・画像アップロード・カテゴリ/タグ自動作成)、実際のAPIレスポンス仕様に応じた `MicroCmsAdapter` の調整([05-microcms-adapter](phase3/05-microcms-adapter.md)未決事項)
- [ ] VSCode拡張のGUI経由での実機検証(拡張パネルのロード確認、投稿/更新コマンドの手動操作)([07-vscode-extension](phase3/07-vscode-extension.md)未決事項)
- [ ] microCMS利用時の事前準備手順(カテゴリ/タグ用list型APIの作成方法)のドキュメント化
- [ ] microCMSサイト登録時のエンドポイント名・認証情報のバリデーション強化(現状はAPI呼び出し時のエラー任せ、[06-web-frontend](phase3/06-web-frontend.md)未決事項)
- [ ] `CmsAdapterFactory` の未対応CMS種別エラーのHTTPステータス見直し(現状 `CmsApiException` 経由で502だが、本来は400/501が適切ではないか、[03-cms-adapter-factory](phase3/03-cms-adapter-factory.md)未決事項)
- [ ] 認証情報(`credentials` Map)の型安全性強化の検討(現状は `Map<String,String>` の自由形式、[04-database-schema](phase3/04-database-schema.md)未決事項)
- [ ] レガシーWordPress専用カラム(`sites.wp_username`/`wp_app_password_encrypted`)の整理・削除の検討(全サイトが新スキーマに移行し安定運用が確認できた後)
- [ ] front matterの `wp_post_id`/`wpPostId` をCMS非依存な名称(`cms_post_id` 等)へリネームする移行計画([07-vscode-extension](phase3/07-vscode-extension.md)未決事項、後方互換性の設計込み)
- [ ] `WordPressAdapterTest`/`MicroCmsAdapterTest` のリクエストボディ検証強化(現状はHTTPメソッド/URIのみ検証、[02-wordpress-adapter-tests](phase3/02-wordpress-adapter-tests.md)未決事項)

## Phase 4: ユーザー管理拡張機能

- [ ] [00-overview](phase4/00-overview.md) — 全体スコープ・決定事項・タスク一覧
- [ ] [01-password-reset](phase4/01-password-reset.md) — パスワード再設定機能(トークン・メール送信・Web画面)
- [ ] [02-audit-log](phase4/02-audit-log.md) — 監査ログ機能(AOP・管理画面・検索・削除ポリシー)
- [ ] [03-ui-contrast-fix](phase4/03-ui-contrast-fix.md) — 管理画面の低コントラスト箇所の修正
- [ ] [04-user-self-registration](phase4/04-user-self-registration.md) — セルフサインアップ実装・初期管理者払い出し方式の変更
- [ ] [05-site-provisioning-visibility](phase4/05-site-provisioning-visibility.md) — サイト登録の疎通確認・プロビジョニング有無の明示

## Phase 5: ユーザー認証・運用機能の強化

- [ ] [00-overview](phase5/00-overview.md) — 全体スコープ・決定事項・タスク一覧
- [x] [01-smtp-mail-setup](phase5/01-smtp-mail-setup.md) — SMTP メール送信の本番環境対応・テンプレート実装
- [x] [02-audit-log-archival](phase5/02-audit-log-archival.md) — 監査ログアーカイブ・削除ポリシーの自動化(削除処理自体はPhase4で実装済み、cron時刻をUTC 02:00に調整)
- [x] [03-two-factor-auth](phase5/03-two-factor-auth.md) — 2FA(TOTP)認証の実装(設定画面・ログイン時2段階入力まで実機確認済み)
- [ ] [04-rbac-enhancements](phase5/04-rbac-enhancements.md) — ロールベースアクセス制御の細粒度化(バックエンド実装済み、管理画面UIは未着手)
- [ ] [05-true-provisioning](phase5/05-true-provisioning.md) — 真のプロビジョニング機能の実装
