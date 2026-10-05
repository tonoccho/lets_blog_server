# language: ja
@publishing @api @slow @mode:serial @site-isolation:preview
機能: 廃止した旧プレビュー経路(テーマCSS取得・骨組み差し込み・一時投稿)が存在しない

  letsblog プラグインを必須にしたため、プラグインのないサイト向けだった旧プレビュー経路を削除した
  (issue #1564。Epic #1555)。実サイトのプレビューは署名付きプレビュー URL(`preview-signed-url.feature`、
  issue #1561 / #1562)が担う。ここでは旧経路が戻っていないことを固定する。

  ## 固定すること

  - `GET /api/projects/{id}/preview/theme-css`、`POST .../skeleton`、`DELETE .../preview-post` は、
    どれも 404 を返す(以前はそれぞれテーマ CSS 取得・非公開投稿を作る骨組み差し込み・その投稿の削除)。
  - 呼んでも対象サイトに非公開投稿が作られない(`wp_posts` の行数が変わらない)。

  ## Web UI から到達できない理由

  旧経路の呼び出し元は VSCode 拡張だけで、Lets Blog の Web 画面に入口が無い。そのため `@api` の形で、
  gateway 経由で直接呼ぶ。サイト内の状態(`wp_posts` の行数)は wp-cli で確かめる。

  ## `@mode:serial` な理由

  背景が同じ検証用サイトを冪等に用意する。並列に走らせると「サイト一覧に無い→構築する」判定が
  競合して二重に構築しようとして409になる(docs/ACCEPTANCE_TESTING.md §4)。

  背景:
    前提 プレビュー検証用のWordPressサイトがあり、プロジェクトのテスト環境に紐づいている

  シナリオアウトライン: 旧プレビュー経路の「<メソッド> <パス>」は存在せず、サイトに投稿も作られない
    前提 対象サイトの wp_posts の行数を控えておく
    もし 旧プレビュー経路の「<メソッド>」「<パス>」を呼ぶ
    ならば 応答は 404 になる
    かつ 対象サイトの wp_posts の行数が変わっていない

    例:
      | メソッド | パス          |
      | GET      | theme-css     |
      | POST     | skeleton      |
      | DELETE   | preview-post  |
