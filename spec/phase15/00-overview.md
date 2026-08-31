# Phase 15: VSCode拡張からの記事作成開始ワークフロー

## 目的

VSCode拡張から「GitHub issueを選択 → AI壁打ちでメタデータ(カテゴリ・タグ・スラッグ・タイトル)を提案・承認 → ローカルプロジェクトに`articles/<slug>/`フォルダとfront matter付き`article.md`・`assets/`を自動生成 → issueをユーザーに割り当てて`in-progress`ラベルを付与」という一連のワークフローを実現する。

これまでのphase13以降で、Web管理画面(Next.js)側には既に壁打ちチャット・issue一覧・計画受入(GitHub issue作成)の機能が実装済み(`ArticlePlanController`/`ArticlePlanService`/`GithubClient`)。phase15では、この基盤をVSCode拡張側へ拡張し、エディタ内での一気通貫なワークフローを実現する。

## 前提・決定事項

| 項目 | 決定内容 | 根拠 |
|---|---|---|
| VSCode認証モデル | APIキー+ユーザー選択方式。`GET /api/users`(APIキーのみで呼び出し可)でユーザー一覧取得 → QuickPickで選択 → `SecretStorage`に`{id, email, role}`を保存 | 既存のapiKeyストレージパターンを踏襲。admin以外もVSCode拡張を使える状況を想定。フロントはWeb BFF同様に`X-Actor-Id`/`X-Actor-Role`ヘッダー付与 |
| プロジェクト選択 | ワークスペース単位で保持(`context.workspaceState`)。`GET /api/projects`のうち`githubRepository`設定済みのもののみ表示 | VSCodeを複数プロジェクトで使い分ける可能性を考慮。workspaceState/globalState の使い分けは既存拡張の標準パターン |
| 壁打ちUI形式 | **Webviewパネル**。issue一覧・マルチターンチャット・提案確認・承認をひとつのパネルで実現 |既存QuickPick/InputBoxでは複数ターンのチャット履歴表示とメタデータ編集フォームの実現が困難 |
| メタデータ提案 | 壁打ちチャット履歴全体を文脈とした**新規エンドポイント** `POST /api/projects/{projectId}/article-plan/suggest-metadata` | 既存`/api/ai/tags`はWordPress本文の汎用タグ推奨で壁打ち文脈に特化していないため、article-plan専用のロジック追加が必要 |
| メタデータの内容 | **title**(記事タイトル), **slug**(URL用スラッグ), **categories**(カテゴリ配列), **tags**(タグ配列) の4項目 | 要件より。拡張側での編集可能にしておき、最終承認時に確定 |
| フォルダ・ファイル構成 | `articles/<slug>/article.md` (front matter + 本文) + `articles/<slug>/assets/.gitkeep` | 要件8より。assets.mdは「フォルダ」という説明と矛盾するためディレクトリ採用 |
| 記事本文の初期値 | GitHub issue の description (存在すればそのまま、なければ壁打ち要約 or 構成案) | 既存の`getIssueDescription` API利用 |
| front matterの新規フィールド | `github_issue_number`, `github_repository`, `project_id` | 後続フェーズで記事⇔issue同期が必要な場合を想定した追跡情報 |
| issue割り当て・ステータス | assignee にログイン中のGitHubユーザー(actorの認証トークン経由で取得)を指定。状態は`open`のまま、`in-progress`ラベル付与で「執筆中」を表現 | GitHub issueはWordPressのような任意ステータスを持たないため、ラベルで実装 |
| issue一覧の絞り込み | 未割り当て(assignees配列が空)のみ表示。バックエンド既存`GET /issues`は変更せず、クライアント側でフィルタ | Web管理画面の既存動作(全openissue表示)への影響回避 |
| API認可 | `ArticlePlanController`の認可を`admin`限定から**プロジェクトメンバー or admin**に緩和 | VSCode拡張のエンドユーザーはadminでない可能性が高い |
| スコープ外 | ・チャット履歴の自動保存・再開(サーバー側永続化なし) ・記事本文のGit管理・GitHub pull request 自動化 ・issue テンプレート・自動ラベル付与ルール ・複数issue一括作成 | 本phase では out of scope |

## アーキテクチャ概要

```
VSCode拡張 (TypeScript)
├─ 初期化フロー
│  ├─ letsBlog.selectActor → /api/users でユーザー一覧 → QuickPick → SecretStorage保存
│  ├─ letsBlog.selectProject → /api/projects → QuickPick(githubRepository設定済みのみ) → workspaceState保存
│  └─ letsBlog.planArticle (メインコマンド)
│     └─ Webviewパネル起動 (planPanel.ts)
│
└─ Webviewパネル (HTML + vanilla JS in TypeScript)
   ├─ Issue一覧表示
   │  └─ GET /api/projects/{projectId}/article-plan/issues → assignees フィルタ(クライアント側)
   │
   ├─ マルチターンチャット
   │  └─ POST /api/projects/{projectId}/article-plan/chat (sessionId/githubIssueNumber付き)
   │
   ├─ メタデータ提案
   │  ├─ POST /api/projects/{projectId}/article-plan/suggest-metadata
   │  └─ フォームで編集可能に
   │
   └─ 承認・スキャフォール生成
      ├─ GET /api/projects/{projectId}/article-plan/issues/{issueNumber}/description (本文取得)
      ├─ POST /api/projects/{projectId}/article-plan/issues/{issueNumber}/assign (割り当て・ラベル付与)
      └─ ローカル `articles/<slug>/article.md` 生成 & editor.openTextDocument()

API (Spring Boot)
├─ 認可の緩和
│  └─ AdminAuthorizationService.requireProjectMemberOrAdmin(projectId)
│
├─ GithubClient 拡張
│  ├─ getAuthenticatedUser(token) → login取得
│  └─ assignAndLabelIssue(token, owner, repo, issueNumber, assignees, labels)
│
├─ 新規エンドポイント
│  ├─ POST /api/projects/{projectId}/article-plan/suggest-metadata
│  │  └─ Ollama呼び出し、title/slug/categories/tags をJSON提案
│  │
│  └─ POST /api/projects/{projectId}/article-plan/issues/{issueNumber}/assign
│     └─ issueをactorに割り当て + in-progressラベル付与
│
└─ DTO更新
   ├─ RepositoryIssueResponse に assignees[] 追加
   ├─ SuggestMetadataResponse 新規
   └─ AssignIssueRequest/Response 新規
```

## Phase 15 のスコープ

### 01. VSCode 認証・権限・プロジェクト選択
- [01-vscode-auth-and-permission.md](01-vscode-auth-and-permission.md)

### 02. VSCode Webviewパネル・壁打ちチャット・メタデータ提案
- [02-vscode-article-plan-panel.md](02-vscode-article-plan-panel.md)

### 03. フォルダ・ファイル生成・issue割り当て・ラベル付与
- [03-article-scaffold-and-issue-assign.md](03-article-scaffold-and-issue-assign.md)

対象外:

- チャット履歴のサーバー側永続化(DB保存)・セッション管理(既存の壁打ちセッション は Web側のもので、VSCode側は本フロー内では永続化しない)
- 生成されたarticle.mdのGit操作(自動commit/push)
- issue テンプレート化・複数issue一括作成・webhook連携
- VSCode拡張の他のコマンド(既存のpublish等)の修正(独立動作を維持)

## 実装順序

**推奨順序: 01 → 02 → 03**

理由:

1. **01 (認証・権限)**: バックエンド側の認可緩和とVSCode側のユーザー/プロジェクト選択を完成させることで、以降のAPI呼び出しが認可される。実装難度は低い。
2. **02 (壁打ちパネル・メタデータ提案)**: Webviewパネルの実装。APIの大部分が既存(`/chat`, `/issues` 等)で、新規は`/suggest-metadata`のみ。パネルUI側の実装が主体。
3. **03 (スキャフォール・割り当て)**: フォルダ生成・assignIssue呼び出し。02で取得したメタデータを活用。

## テスト整備(全体方針)

- **Unit**: 新規メソッド(`suggestMetadata`, `assignIssueToActor`, 認可関数)にユニットテストを追加。GithubClientの`getAuthenticatedUser`/`assignAndLabelIssue`は`MockRestServiceServer`で検証。
- **E2E/実機**: docker composeスタック上で、ローカルプロジェクトをセットアップし、VSCodeでExtension Development Hostを起動、実際にissue選択→壁打ち→提案取得→承認まで動作確認。

## 未決事項・検討項目

- メタデータ提案が空結果だった場合のUI/UX表現(タイトルなし でも承認可能か、再提案導線を用意するか) → 02着手時に確定
- Webviewパネルの閉じ方・多重起動防止・セッション復帰(既存Webviewが開いているときに再度コマンド実行したら?) → 02着手時に確定
- article.md生成時、既に`articles/<slug>/`が存在する場合の上書き確認のUI → 03着手時に確定

## 参照した既存実装

- `api/src/main/java/com/letsblog/api/service/AdminAuthorizationService.java`, `requireSelfOrAdmin`パターン
- `api/src/main/java/com/letsblog/api/controller/ArticlePlanController.java`, `ArticlePlanService.java`(既存の壁打ち・提案・session管理)
- `api/src/main/java/com/letsblog/api/github/GithubClient.java`(既存のissue作成・updateIssueBody)
- `extension/src/config.ts`(SecretStorage、既存のAPIキー管理)
- `extension/src/extension.ts`(QuickPick、既存のselectSiteコマンド)
- `extension/src/frontMatter.ts`(parseArticle/stringifyArticle)
- `web/src/app/projects/[id]/ArticlePlanPanel.tsx`(既存のWebビューパネル参考, 壁打ちUI実装パターン)
