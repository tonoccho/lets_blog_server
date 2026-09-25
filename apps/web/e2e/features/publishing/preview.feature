# language: ja
@publishing @api @slow @mode:serial
機能: 記事プレビューと一時投稿の後始末

  VSCode拡張の記事プレビューが、公開せずに実テーマの見た目を確認でき、プレビュー終了時に
  一時投稿をWordPress側に残さないことを固定する
  (issue #1175 / AT-6-5。親issue #932(AT-6)のシナリオ12・13を引き取る子issue)。

  ## プレビュー用APIはcontent-service/publishing-serviceに分かれている

  記事本文のMarkdown→HTML変換(`POST /api/projects/{id}/preview/render`)はCMSへの依存を
  持たないためcontent-serviceが持つ(issue #576)。テーマCSS取得(`GET .../theme-css`)・
  実テーマのDOM構造を保った骨組み差し込み(`POST .../skeleton`)・プレビュー用一時投稿の削除
  (`DELETE .../preview-post`)はCMSアダプタへの深い依存を持つためpublishing-serviceが持つ
  (issue #712、Epic #551 C6-6)。同じ`/api/projects/{projectId}/preview`配下のパスを
  gatewayが`/render`かそれ以外かで振り分ける。

  ## `/skeleton`が実際にWordPressへ非公開投稿を作る経路を使う理由

  対象サイトが本番以外で、かつサーバー側でwp-cliを実行できる認証情報(managed WordPressの
  agent transport、またはSSH transport。ユーザー名あり)の場合、`ArticlePreviewService`は
  スクレイプ&スプライス経路ではなく、プレビュー対象記事そのものを非公開(private)投稿として
  実際にWordPressへ作成し、その実ページを直接閲覧する経路を使う
  (`ArticlePreviewService#renderSkeleton`のJavadoc参照)。このため「後始末で本当に
  WordPress側から消えるか」を実際に確かめられる。

  ## 専用サイトを冪等に用意する理由

  この振る舞いの正しさは、実際にWordPressへ作成された投稿の状態(公開ではなく非公開であること、
  削除後に残っていないこと)をwp-cliで見ないと確かめられない。#1167のプロビジョニング済み
  サイト共有フィクスチャ(`site-provisioning.steps.ts`)はサイトの識別子だけを残し、WordPress
  管理者の認証情報を残さない設計のため、wp-cliで直接アクセスする必要があるここでは使えない。
  そこで`publishTaxonomy.steps.ts`(issue #1174)と同じ「固定siteKeyで冪等に用意し、実行を
  またいで再利用する」パターンを踏襲する。

  ## `@mode:serial`な理由

  両シナリオが同じ検証用サイトを冪等に用意する(`前提`)。並列に走らせると、片方の
  「サイト一覧に無い→構築する」判定ともう片方の構築が競合し、既に構築済みのWordPress
  環境へ二重に構築しようとして409になる。docs/ACCEPTANCE_TESTING.md §4の案内どおり
  `@mode:serial`で直列化する。

  背景:
    前提 プレビュー検証用のWordPressサイトがあり、プロジェクトのテスト環境に紐づいている

  シナリオ: プレビューを生成すると公開せずに実テーマの見た目を確認できる
    もし 記事をプレビューする
    ならば プレビューに実テーマのCSSを当てた見た目が返る
    かつ プレビュー用テーマCSS取得APIも公開先サイトの実CSSを返す
    かつ WordPress側にその記事は非公開の一時投稿としてのみ存在し公開はされていない

  シナリオ: プレビュー用の一時投稿はプレビュー終了時にWordPress側に残らない
    もし 記事をプレビューしてからプレビューを終了する
    ならば WordPress側にそのプレビュー用の一時投稿が残っていない
