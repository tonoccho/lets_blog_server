# 05. Web管理フロントエンド

## 目的

VSCode拡張が扱わない「管理系」機能(サイト登録、APIキー発行、投稿履歴、AIジョブ状況確認)を提供するWeb UIを実装する。

## 前提・決定事項

- 実装: TypeScript / Node.js + Tailwind CSS
- 役割: 管理画面(執筆・投稿はVSCode拡張側)
- 認証: Phase 1では個人利用前提のためログイン機能は必須としない(要再確認、[未決事項](#未決事項)参照)

## 実装状況(更新: 05-web-frontend 完了時点)

`web/` 配下に Next.js(App Router, TypeScript, Tailwind CSS v4)で実装済み。

- フレームワーク: **Next.js**(`create-next-app`で雛形作成)。APIサーバーとの通信は **Server Components + Server Actions によるSSR経由**に統一(ブラウザから直接APIサーバーを叩かないため、CORS設定が不要になり、APIキーもブラウザに露出しない)。
- APIキーは `web/.env.local` の `LETS_BLOG_API_KEY`(サーバー環境変数、`server-only`パッケージでクライアントバンドルへの混入を防止)。VSCode拡張とは別に、Web管理画面自身も同じ固定APIキーを使うクライアントの1つという位置付け。
- 画面: ダッシュボード(`/`)、サイト一覧・登録(`/sites`)、投稿履歴(`/posts`)、AIジョブ一覧(`/ai-jobs`)、システム(`/system`、phpMyAdmin/ComfyUI/PlantUMLへのリンク)
- サイト登録は React `useActionState` + Server Action(`app/sites/actions.ts`)によるフォーム送信
- バックエンド側に本UIのため `GET /api/posts`(サイト名を結合した投稿履歴)、`GET /api/generation-jobs`(AIジョブ履歴)を追加実装([03-api-server](03-api-server.md)参照)

**実機検証**: `npm run build` / `npm run dev` で起動確認。Playwright(システムのGoogle Chromeを使用)でブラウザ経由のサイト登録フォーム送信を実行し、Server Action→APIサーバー→MySQLへの登録、および一覧への反映(revalidatePath)を確認。ダッシュボードの件数表示・各一覧ページのAPIデータ反映も確認済み。

## 画面構成

| 画面 | 内容 | 状態 |
|---|---|---|
| ダッシュボード(`/`) | サイト数・投稿数・AIジョブ数のサマリ | 実装済み |
| サイト一覧・登録(`/sites`) | WordPressサイトのURL/ユーザー名/アプリケーションパスワード登録・一覧 | 実装済み |
| 投稿履歴(`/posts`) | `posts` テーブルの一覧(サイト名・ステータス・最終投稿日時) | 実装済み |
| AIジョブ状況(`/ai-jobs`) | `generation_jobs` の一覧(06/07実装後に実データが入る) | 実装済み(現状は空) |
| システム(`/system`) | phpMyAdmin/ComfyUI/PlantUMLへのリンク、接続先APIサーバーURL表示 | 実装済み |

「APIキー発行・失効UI」は当初案にあったが、[03-api-server](03-api-server.md)側が単一の固定APIキー方式(`api_keys`テーブルなし)で確定したため、Phase 1のスコープからは外した。

## タスクチェックリスト

- [x] プロジェクト雛形作成(Next.js + Tailwind)
- [x] APIサーバーの管理系エンドポイントとの疎通実装(Server Components経由のfetch)
- [x] サイト登録フォーム実装(Server Action)
- [x] 投稿履歴一覧UI実装
- [x] AIジョブ状況一覧UI実装
- [x] システム(関連サービスへのリンク)画面実装

## 未決事項

- ログイン機能の要否(現状は未実装。ローカル限定運用なら省略可、将来のリモート化を見据えるなら最低限のBasic認証は入れておきたい)
- 投稿履歴からのWordPress記事への直接リンク(`wp_post_url`をpostsテーブルに保存していないため、現状は`wpPostId`のみ表示。必要なら`posts`テーブルにURLも保存する)
