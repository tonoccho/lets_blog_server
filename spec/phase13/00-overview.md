# Phase 13: プロジェクト⇔GitHub連携とAI壁打ちによる記事計画(仕様策定)

## 目的

プロジェクト単位でGitHubリポジトリを紐付け、Ollamaとのマルチターン壁打ちで記事タイトル案を取得し、ユーザーが選択したタイトルをGitHubの issue として自動登録する仕組みを構築する。これにより、ブログ記事の企画から実装への流れが、リポジトリのissueによるタスク管理へと自然につながる。

## 前提・決定事項

| 項目 | 決定内容 | 根拠 |
|---|---|---|
| 現状の欠落機能 | GitHub API呼び出し・認証情報保存・マルチターンAI対話・issue作成の仕組みが皆無 | 要望1/2/3を満たすため必要 |
| GitHubリポジトリ紐付けの粒度 | **プロジェクト単位**。`projects`テーブルに`github_repository`列(varchar, "owner/repo"形式)を追加 | 複数人が同じプロジェクトで作業するシナリオに対応 |
| GitHub PATの保存 | **ユーザーごと**。`users`テーブルに`github_token_encrypted`列(VARBINARY)を追加し、既存の`CredentialCipher`(AES-256-GCM)で暗号化 | 各ユーザーが自分のGitHubアカウントで issue を作成。既存の`locale`/`timezone`と同じユーザー単位設定パターンを踏襲 |
| AI壁打ちUI形式 | **マルチターンのチャット形式**。会話履歴はフロント(React state)で保持し、リクエストごとに全履歴をサーバーへ送る(ステートレスAPI)。サーバー側でのセッション永続化・DB保存は行わない | シンプル・堅牢。既存の単発Ollama呼び出しとの方針統一 |
| 提案タイトルの採用範囲 | **個別選択**(チェックボックスで選択後、「計画を受け入れる」ボタンで issue 化)。複数個選択可、すべてを統一してexecuteする運用ではない | ユーザー指定 |
| 暗号化・暗号キー管理 | 既存`CredentialCipher`クラスを再利用(AES/GCM/NoPadding、256bit、鍵は環境変数`APP_ENCRYPTION_KEY`)。`Site.credentialsEncrypted`と同じ命名・型・DI パターンで`User.githubTokenEncrypted`を実装 | サイト認証情報と同じ仕組み。既存実装の流用で堅牢 |
| GitHub API呼び出しライブラリ | Octokit等のライブラリは導入しない。`OllamaClient`と同じく`RestClient`の薄いラッパー(`GithubClient`)を自前実装し、GitHub REST API(`https://api.github.com/repos/{owner}/{repo}/issues`)を直接叩く | コードベースの一貫性(既存の自前HTTP クライアント方針)。依存追加を避ける |
| API認可 | 計画画面・関連 API は管理者(`admin`)のみが実行可能。既存の`requireAdminSession()`・`adminAuthorizationService.requireAdmin()`パターンを踏襲 | `/projects`・`/projects/[id]`同様、計画機能も管理者権限で統一 |
| GenerationJob記録 | AI チャット応答・タイトル提案は既存`generation_jobs`テーブルに`type="plan_chat"`/`"plan_suggest_titles"`で記録する。GitHub issue 作成そのもの(API呼び出し)は生成ジョブではないため記録対象外 | 既存`AiAssistService`の`startJob()`/`completeJob()`/`failJob()`パターンと統一。ただし`AiAssistService`本体は変更せず、新規`ArticlePlanService`に同等の記録ロジックを実装 |
| スコープ外 | ・Webhook/プッシュイベント処理(将来フェーズで検討)・記事本文のGit管理・チャット履歴の永続化/再開・GitHub App認証・Organizationレベル権限・issue テンプレート/ラベル自動付与・タイトル提案の再生成・チャット内容の編集機能 | これら全て、本 phase では不要 |

## アーキテクチャ概要

```
Web 管理画面 (Next.js)
  ├─ /system
  │  └─ 「GitHub 連携」セクション
  │     └─ 「個人アクセストークン」入力フォーム(パスワード入力、事前表示なし、保存時のみ送信)
  │
  └─ /projects
     ├─ 一覧テーブルの行アクション欄に「計画」リンク追加
     │
     └─ [id]
        ├─ 「GitHub リポジトリ設定」フォーム新設(owner/repo 入力、即時保存、admin のみ)
        │
        └─ /plan(新規ページ)
           ├─ ArticlePlanChat(Client Component)
           │  └─ チャット入力 UI + 送信ボタン(履歴は React state で保持)
           │
           └─ ArticlePlanProposals(Client Component)
              ├─ 「タイトル提案を取得」ボタン → suggest-titles API 呼び出し
              ├─ チェックボックス付きタイトル一覧表示
              └─ 「選択した記事の計画を受け入れる」ボタン(選択数がゼロなら disabled) → accept API 呼び出し、結果を「✓ issue #123(リンク)」/「✗ エラー」で表示

API (Spring Boot)
  ├─ PUT /api/users/{id}/github-token
  │  └─ 個人アクセストークンの設定・更新(self-or-admin)
  │
  ├─ PUT /api/projects/{id}/github-repository
  │  └─ GitHub リポジトリの紐付け設定・更新(admin)
  │
  ├─ POST /api/projects/{id}/article-plan/chat
  │  └─ マルチターンチャットの 1 ターン(会話履歴 + 新規メッセージ) → AI 応答
  │
  ├─ POST /api/projects/{id}/article-plan/suggest-titles
  │  └─ 会話履歴から記事タイトル案を提案(JSON 配列、最大 5 件)
  │
  └─ POST /api/projects/{id}/article-plan/accept
     └─ タイトル一覧を受け取り、1 タイトル 1 issue として GitHub に登録
```

## Phase 13 のスコープ

### 01. GitHub リポジトリ連携・個人アクセストークン設定
- [01-github-settings.md](01-github-settings.md)

### 02. AI 壁打ちチャットとタイトル提案
- [02-article-plan-chat.md](02-article-plan-chat.md)

### 03. 計画の受け入れ(GitHub issue 作成)
- [03-article-plan-accept.md](03-article-plan-accept.md)

対象外(スコープ外)のタスク:

- 記事本文(Markdown ファイル)そのものを GitHub リポジトリで管理・同期する仕組み
- チャット履歴のサーバー側永続化(DB 保存)・複数セッションの保存/再開
- GitHub Webhook・クライアント(VSCode 拡張等)からのプッシュイベント処理
- GitHub App による認証、Webhook 自動登録、Organization レベル権限管理
- issue テンプレート化・ラベル自動付与
- タイトル提案の再生成・チャット内容の編集
- 記事作成→投稿の自動パイプライン(issue→WordPress 投稿変換等)

## タスク実装順序

**推奨順序: 01 → 02 → 03**

理由:

1. **01 (GitHub 設定)**: リポジトリ・PATの設定を先に用意することで、02/03 が必要とする前提条件が整う。サーバー側は単純なCRUD、フロント側は既存の`SystemPreferencesForm`・`MasterEnvironmentSelector`パターンの応用のため実装難度が低く、最初に着手しやすい
2. **02 (壁打ちチャット・タイトル提案)**: `ArticlePlanService`・`ArticlePlanController`・フロント計画画面を新規作成。03で`AcceptPlanRequest`・`AcceptPlanResponse` DTOを追加するため、先に基盤を整備する必要がある
3. **03 (計画受け入れ・issue作成)**: `GithubClient`新設・`ArticlePlanService.acceptPlan()`実装。02で定義された`PlanChatMessage`型・`ArticlePlanController`をそのまま拡張・流用するため、02 完了後に着手

## 相互依存性

```
01 (GitHub 設定)
  ├─→ 02 (壁打ちチャット: リポジトリ/PATが無いと計画画面は入力できても提案処理は失敗する)
  └─→ 03 (計画受け入れ: 同上、issue 作成に必須)

02 (壁打ちチャット・タイトル提案)
  └─→ 03 (計画受け入れ: PlanChatMessage 型・ArticlePlanController を流用)
```

## テスト整備(全体方針)

- **Unit**: 各タスクのサービス層・DTO・Controller にユニットテストを追加
  - `ArticlePlanServiceTest`: Ollama/GithubClient をモック、プロンプト構築・JSON 抽出・部分失敗時の集約を検証
  - `GithubClientTest`: `MockRestServiceServer` でHTTPリクエスト内容・エラーハンドリングを検証
  - `UserServiceTest` / `ProjectServiceTest` への追加: トークン暗号化・リポジトリ形式バリデーション
- **E2E/実機**: docker compose スタック上で、実際の GitHub PAT・テスト用リポジトリで動作確認
  - GitHub PAT(`repo`スコープ)をシステム画面に登録
  - テスト用リポジトリをプロジェクトに紐付け
  - 計画画面でOllamaと数往復チャット
  - タイトル提案取得・一部選択・受け入れ
  - GitHub上で issue が 1タイトル1件として作成されることを確認
  - 未設定状態(トークン無し/リポジトリ無し)のエラーメッセージ表示確認

## 未決事項・検討項目

- issue本文(body)に何を含めるか
  - タイトルのみのシンプル issue か、チャット要約を含めるか
  - 判定時期: 03着手時に確定
- タイトル提案が0件だった場合のUI表現
  - 既存`suggestTags`のフォールバック同様、空リスト表示+再試行導線
- GitHub PATスコープ要件の明記
  - システム画面に「`repo`権限が必要」とのヘルプテキスト付与の要否
  - 判定時期: 01着手時に確定

## 参照した既存実装

実装着手時に参考にすべきコード:

- `api/src/main/java/com/letsblog/api/crypto/CredentialCipher.java` — 認証情報暗号化パターン
- `api/src/main/java/com/letsblog/api/ai/OllamaClient.java`、`api/src/main/java/com/letsblog/api/service/AiAssistService.java` — Ollama呼び出し・GenerationJob記録・JSON抽出フォールバック
- `api/src/main/java/com/letsblog/api/controller/UserController.java`(特に`/preferences`) — ユーザー単位設定のCRUD・認可パターン
- `web/src/app/system/{page.tsx,SystemPreferencesForm.tsx,actions.ts}` — ユーザー設定フォーム(useActionState)のパターン
- `spec/phase12/02-master-environment-setting.md`・`web/src/app/projects/[id]/MasterEnvironmentSelector.tsx` — プロジェクト単位設定(管理者のみ即時保存)のパターン
- `api/src/test/java/com/letsblog/api/cms/WordPressAdapterTest.java` — `MockRestServiceServer` によるHTTPクライアントのテスト手法
