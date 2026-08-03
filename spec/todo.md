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

## Phase 4: ユーザー管理拡張機能

- [x] [00-overview](phase4/00-overview.md) — 全体スコープ・決定事項・タスク一覧
- [x] [01-password-reset](phase4/01-password-reset.md) — パスワード再設定機能(トークン・メール送信・Web画面)
- [x] [02-audit-log](phase4/02-audit-log.md) — 監査ログ機能(AOP・管理画面・検索・削除ポリシー)
- [x] [03-ui-contrast-fix](phase4/03-ui-contrast-fix.md) — 管理画面の低コントラスト箇所の修正
- [x] [04-user-self-registration](phase4/04-user-self-registration.md) — セルフサインアップ実装・初期管理者払い出し方式の変更
- [x] [05-site-provisioning-visibility](phase4/05-site-provisioning-visibility.md) — サイト登録の疎通確認・プロビジョニング有無の明示

## Phase 5: ユーザー認証・運用機能の強化

- [x] [00-overview](phase5/00-overview.md) — 全体スコープ・決定事項・タスク一覧
- [x] [01-smtp-mail-setup](phase5/01-smtp-mail-setup.md) — SMTP メール送信の本番環境対応・テンプレート実装
- [x] [02-audit-log-archival](phase5/02-audit-log-archival.md) — 監査ログアーカイブ・削除ポリシーの自動化(削除処理自体はPhase4で実装済み、cron時刻をUTC 02:00に調整)
- [x] [03-two-factor-auth](phase5/03-two-factor-auth.md) — 2FA(TOTP)認証の実装(設定画面・ログイン時2段階入力まで実機確認済み)
- [x] [04-rbac-enhancements](phase5/04-rbac-enhancements.md) — ロールベースアクセス制御の細粒度化(/admin/rolesでのロール割り当て・解除まで実機確認済み)
- [x] [05-true-provisioning](phase5/05-true-provisioning.md) — 真のプロビジョニング機能の実装(実WordPressコンテナでカテゴリ・タグ・著者の自動作成を実機確認済み。microCMSの著者プロビジョニングは未対応のまま)

## Phase 6: リバースプロキシ導入・サブパスルーティング・セットアップマニュアル整備

- [x] [00-overview](phase6/00-overview.md) — 全体スコープ・決定事項・タスク一覧
- [x] [01-reverse-proxy](phase6/01-reverse-proxy.md) — リバースプロキシコンテナ追加・https://localhost一本化・ComfyUI/Ollamaのサブパスルーティング(実機でweb/api/phpmyadmin/ollama/comfyui/plantuml/mailhogの疎通・個別ポート遮断・ComfyUI WebSocketまで確認済み。ComfyUI JS内の絶対パスAPI呼び出しの有無とブラウザでの目視確認は未実施)
- [x] [02-setup-manual](phase6/02-setup-manual.md) — セットアップマニュアル整備(docs/setup.mdを新規作成)

## Phase 7: WordPress自動プロビジョニング・カスタムタグレンダリング機能

- [x] [00-overview](phase7/00-overview.md) — 全体スコープ・決定事項・タスク一覧
- [x] [01-wordpress-provisioning](phase7/01-wordpress-provisioning.md) — 常駐WordPressコンテナへのサブディレクトリ設置型自動プロビジョニング(実機検証済み。credentials.baseUrlとsite.baseUrlの分離、mysqlクライアントのTLS、wp-cliのメモリ上限、.htaccess手動生成、Application PasswordsのHTTPS要件、サイト削除時の`posts`外部キー制約など、実機検証で判明した問題に対応済み)
- [x] [02-custom-tags](phase7/02-custom-tags.md) — カスタムショートコードタグ機能(DB駆動、実機検証済み)

## Phase 8: マルチ環境プロジェクト管理・ユーザー情報拡充・UI改善

- [x] [00-overview](phase8/00-overview.md) — 全体スコープ・決定事項・タスク一覧
- [x] [01-user-profile-expansion](phase8/01-user-profile-expansion.md) — ユーザー情報拡充(WordPress互換フィールド追加、V12マイグレーション。実機検証済み)
- [x] [02-project-management](phase8/02-project-management.md) — プロジェクト管理基盤(projects/project_users テーブル、CRUD、V13マイグレーション。実機検証済み)
- [x] [03-project-scoped-custom-tags](phase8/03-project-scoped-custom-tags.md) — カスタムタグのプロジェクトスコープ化(V14マイグレーション。実機検証済み)
- [x] [04-project-user-wp-sync](phase8/04-project-user-wp-sync.md) — プロジェクト参加ユーザーのWordPress自動登録・同期(実機の管理対象WordPressで作成・ロール変更・削除まで確認済み。WordPress側`locale`フィールドが未インストール言語で400エラーになる問題を発見し、同期対象から除外して対応)
- [x] [05-ui-menu-icons](phase8/05-ui-menu-icons.md) — 管理画面メニューのアイコン化(lucide-react導入)

## Phase 8 未決事項・検討項目

### 全般
- [ ] ユーザー同期のタイミング: 即時(REST API呼び出し) vs 非同期ジョブ化(初期は即時、後続で非同期化検討)
- [ ] 同期失敗時のリトライ戦略・キューイング・ユーザーへのエラー通知方針
- [ ] アクセス制御: プロジェクトメンバー(参加ユーザー)による編集権限の段階的導入(現状は管理者のみ)

### ユーザー情報拡充
- [ ] アバター画像ファイルアップロード機構の対応時期・メカニズム(Gravatar参照が基本)
- [ ] WordPress側への `department`/`position` カスタムメタ同期の実装検討
- [ ] ユーザー削除時の `avatar_url` ファイル管理ポリシー(現状URL参照のみのため不要だが要確認)

### プロジェクト管理
- [ ] 環境スロット作成時、既存の `sites` 登録フロー(`SiteCreationPanel`)との統合方法(新規/既存登録の切替 + プロジェクト自動紐付)
- [ ] プロジェクト slug の形式・衝突検出・自動生成ロジック(name から自動生成するか、手入力か)
- [ ] ページング・ソート機能の実装時期

### カスタムタグスコープ化
- [ ] プロジェクト削除時のカスタムタグ削除ポリシー(FK CASCADE で自動削除 vs 手動削除)
- [ ] グローバルタグを特定プロジェクト専用に変更する機能の要否
- [ ] タグテンプレートの継承・複製機能

### WordPress同期
- [ ] WordPress側ユーザーの削除・無効化処理ポリシー(現状は削除しない方針)
- [ ] `department`/`position` 情報の WordPress側マッピング方法(カスタムメタ or 別途管理)

### UI・その他
- [ ] モバイル対応: ナビゲーションメニューのアイコンのみ表示 or ドロワー化の時期
- [ ] 現在ページへのハイライト表示(active link styling)実装
- [ ] メニュー/各ページのアニメーション・トランジション効果(任意)
- [ ] テスト用・本番用 WordPress サーバーの接続情報管理・暗号化方式(環境変数 vs DB)

## Phase 9: Phase8フィードバック対応・UI改善・運用堅牢化

- [x] [00-overview](phase9/00-overview.md) — 全体スコープ・決定事項・タスク一覧
- [x] [01-header-fixed](phase9/01-header-fixed.md) — ヘッダーメニューのスクロール固定表示(`sticky top-0 z-40`、1行変更)
- [x] [02-wordpress-provisioning-hardening](phase9/02-wordpress-provisioning-hardening.md) — WordPress新規構築の堅牢化(クリーンアップ・管理者ピッカー・言語選択。実際に`locale=en_US`で新規構築→英語UI確認→削除によるディレクトリ・DB完全クリーンアップまで実機確認済み。不正ロケール指定時の400応答も確認済み)
- [x] [03-project-site-user-visibility](phase9/03-project-site-user-visibility.md) — プロジェクト⇔サイト⇔ユーザー可視化(新規`GET /api/project-users`のadmin認可・データ取得を実機確認済み)
- [x] [04-user-profile-expansion](phase9/04-user-profile-expansion.md) — ユーザープロフィール拡充(SNSリンク・カスタムリンク・WP互換項目。V15マイグレーション適用・JSON型フィールドの実DB往復を実機確認済み)

実装はすべて完了・バックエンド全テスト(`./gradlew test`)通過・フロントエンド型チェック/lint/`next build`通過・API経由での実機検証(WordPress実構築/削除、プロフィールJSON往復、project-users認可)まで確認済み。ヘッダー固定表示やフォームのUI操作(管理者ピッカー・言語選択・DisplayNameプルダウン・カスタムリンク追加削除)についてはブラウザでの目視確認が未実施(本セッションではブラウザ操作ツールが利用できなかったため)。

## Phase 10: サイト管理強化・WordPress著者作成エラー修正・環境間同期

- [x] [00-overview](phase10/00-overview.md) — 全体スコープ・決定事項・タスク一覧
- [x] [01-site-management-visibility-edit](phase10/01-site-management-visibility-edit.md) — サイト管理画面に疎通確認(再チェック)・編集機能を追加(`PUT /api/sites/{id}`、`POST /api/sites/{id}/test-connection`。実機確認済み)
- [x] [02-wordpress-author-permission-fix](phase10/02-wordpress-author-permission-fix.md) — WordPress著者作成403エラーの原因究明・修正(登録認証情報の管理者権限不足を検知・警告。`CmsAdapter.hasAuthorProvisioningCapability()`追加、事前チェックで403を未然防止)
- [x] [03-environment-sync](phase10/03-environment-sync.md) — プロジェクト環境(ローカル/テスト/本番)間のテーマ・プラグイン・DB同期(実際にmanaged環境を2つ構築し、テーマ/プラグイン/DB同期→記事反映・URL維持・同期先固有アカウント保護・バックアップ生成・nginx経由到達まで実機確認済み)
- [x] [04-wordpress-ssh-transport](phase10/04-wordpress-ssh-transport.md) — Cloudflare等でREST APIが遮断される外部サイト向けにSSH+wp-cli経由の代替操作経路を追加(実装・バックエンド全テスト(`./gradlew test`)通過・フロントエンド型チェック/lint(既存の無関係な1件を除く)/`next build`通過・docker実機でのE2E確認(ログイン→SSHトランスポート選択→鍵ペア生成まで)済み。実際のリモートSSHサーバーでの投稿作成・メディアアップロード等の実機検証は未実施)

実装・バックエンド全テスト(`./gradlew test`)・フロントエンド型チェック/lint/`next build`・Docker実機検証まですべて完了。ブラウザでのUI目視確認(疎通確認ボタン・編集フォーム・環境同期パネルの操作感)は本セッションではブラウザ操作ツールが利用できず未実施。

## Phase 10 未決事項・検討項目

### サイト管理(01)
- [ ] `siteKey`/`cmsType`の変更を編集機能で許可するかどうか
- [ ] managedサイトの`baseUrl`/認証情報を編集可能にする要否(現状は名前のみ編集可)
- [ ] 疎通確認結果の履歴保存・一覧表示の要否

### WordPress著者作成403修正(02)
- [ ] WordPress管理者権限チェック(`create_users`)を登録時にブロッキングにするか、警告のみに留めるかの継続検討
- [ ] プロジェクト詳細画面に各環境サイトの管理者権限状態を表示するかどうか
- [ ] 既に登録済みで権限不足になっているサイトを一括検出する棚卸し用バッチ・画面の要否

### 環境間同期(03)
- [ ] 大規模DBで同期処理がnginxの延長タイムアウト(300秒)を超える場合の非同期ジョブ化・進捗表示
- [ ] バックアップの保持世代数・自動削除ポリシー(現状は直近1世代を上書き保存するのみ)
- [ ] 外部登録(非managed)サイトへの同期対応の要否・実現方式(SSH鍵配布、リモートエージェント設置等)
- [ ] テーマ/プラグインの個別選択同期(現状は`wp-content/themes`・`wp-content/plugins`ディレクトリ全体の一括同期)
- [ ] DB同期時に`wp_users`/`wp_usermeta`以外にも除外すべきテーブルがないかの継続精査(プラグイン依存データ等)
- [ ] 同期の確認UXを`window.confirm`より強固にする要否(環境名の入力必須化等)

## 既存未決事項・スコープ外候補

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

## Phase 13: プロジェクト⇔GitHub連携とAI壁打ちによる記事計画(仕様策定)

- [x] [00-overview](phase13/00-overview.md) — 全体スコープ・決定事項・アーキテクチャ・タスク一覧・未決事項
- [x] [01-github-settings](phase13/01-github-settings.md) — GitHub リポジトリ連携・個人アクセストークン設定(ユーザー・プロジェクト単位。マイグレーション`V21`/`V22`、`UserService`/`ProjectService`拡張、`/system`・プロジェクト詳細ページへのフォーム追加まで実装済み。バックエンド全テスト・フロントエンド型チェック/lint/`next build`通過。実機でのGitHub PAT登録・リポジトリ紐付け確認は未実施)
- [ ] [02-article-plan-chat](phase13/02-article-plan-chat.md) — AI 壁打ちチャートとタイトル提案(マルチターン対話、Ollama 再利用)
- [ ] [03-article-plan-accept](phase13/03-article-plan-accept.md) — 計画受け入れ・GitHub issue 作成(GithubClient 新設、部分失敗の集約)

本 phase の仕様ファイル作成完了。タスク01実装完了、02/03は次セッション以降。

## Phase 15: VSCode拡張からの記事作成開始ワークフロー

- [x] [00-overview](phase15/00-overview.md) — 全体スコープ・決定事項・アーキテクチャ・タスク一覧
- [x] [01-vscode-auth-and-permission](phase15/01-vscode-auth-and-permission.md) — VSCode 認証・権限モデル・ユーザー/プロジェクト選択(`AdminAuthorizationService.requireProjectMemberOrAdmin`追加、ArticlePlanController全10メソッドの認可緩和、VSCode拡張に`selectActor`/`selectProject`コマンド追加。実装済み)
- [x] [02-vscode-article-plan-panel](phase15/02-vscode-article-plan-panel.md) — Webviewパネル・壁打ちチャット・メタデータ提案(`POST /suggest-metadata`エンドポイント新設、VSCode拡張に`planPanel.ts`(Webviewパネル)・`planArticle`コマンド追加。実装済み)
- [x] [03-article-scaffold-and-issue-assign](phase15/03-article-scaffold-and-issue-assign.md) — フォルダ・ファイル生成・issue割り当て・ラベル付与(`GithubClient.getAuthenticatedUser`/`assignAndLabelIssue`、`ArticlePlanService.assignIssueToActor`、`POST /issues/{issueNumber}/assign`エンドポイント追加。VSCode拡張側で`articles/<slug>/article.md`・`assets/.gitkeep`生成とissue割り当てまでのフロー実装済み)

実装・バックエンド全テスト(`./gradlew test`)通過・VSCode拡張コンパイル(`npm run compile`)通過まで確認済み。Extension Development Hostでの実機操作(パネル起動・issue選択・壁打ち・スキャフォールド生成・実際のGitHub issueへの割り当て確認)は本セッションではブラウザ/VSCode操作ツールが利用できず未実施。
