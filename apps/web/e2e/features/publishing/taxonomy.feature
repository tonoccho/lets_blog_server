# language: ja
@publishing @api @slow @mode:serial
機能: カテゴリ・タグの解決

  記事に付けたカテゴリ・タグ名が、公開先WordPressの分類へ正しく紐付くことを固定する
  (issue #1174 / AT-6-4。親issue #932(AT-6)のシナリオ10・11を引き取る子issue)。

  `POST /api/taxonomy/resolve` はVSCode拡張がfront matter上のカテゴリ/タグ名をWordPress側の
  IDへ解決するために呼ぶAPIで、専用の画面を持たない(`TaxonomyController`のJavadoc参照)。
  受け入れ基準は「解決したIDがWordPress側の実際の分類と一致しているか」であって画面の見た目
  ではないため、`@api`としてHTTPから直接確かめる。

  ## 「存在しなければ新規作成」という仕様の確定について

  親issue #932が自己申告していたブロッキング疑問(存在しないカテゴリ/タグを指定した場合の
  挙動)は、2026-09-03の実装調査で解消済み(#1174 Background)。SSH経由・Agent経由どちらの
  公開経路も「名前の完全一致(大文字小文字無視)で既存タームを探し、なければ作成する」を
  実装している(`WordPressSshOperations`・`WordPressAgentOperations`)。

  ## 専用サイトを冪等に用意する理由

  この分類の正しさは実際のWordPress側の状態を見ないと確かめられない。#1167のプロビジョニング
  済みサイト共有フィクスチャ(`site-provisioning.steps.ts`)はサイトの識別子だけを残し、
  WordPress管理者の認証情報を残さない設計のため、後始末(作成したタームの削除)にwp-cliで
  直接アクセスする必要があるここでは使えない。そこで`media-garbage-collection`
  (`mediaGarbageCollection.steps.ts`)と同じ「固定siteKeyで冪等に用意し、実行をまたいで
  再利用する」パターンを踏襲し、シナリオごとに新規サイトを構築しない。

  ## `@mode:serial`な理由

  両シナリオが同じ検証用サイトを冪等に用意する(`前提`)。並列に走らせると、片方の
  「サイト一覧に無い→構築する」判定ともう片方の構築が競合し、既に構築済みのWordPress
  環境へ二重に構築しようとして409になる。docs/ACCEPTANCE_TESTING.md §4の案内どおり
  `@mode:serial`で直列化する。

  背景:
    前提 分類解決用のWordPressサイトがある

  シナリオ: 既にあるカテゴリ・タグは大文字小文字を無視した完全一致で解決される
    もし 新しい名前でカテゴリとタグを解決する
    かつ 同じ名前を大文字小文字だけ変えて再度カテゴリとタグを解決する
    ならば 1回目と2回目で同じIDが返る
    かつ WordPress側にそのカテゴリとタグがそれぞれ1件だけ存在する

  シナリオ: 存在しないカテゴリ・タグを指定すると新規作成される
    もし 存在しないカテゴリとタグを解決する
    ならば WordPress側にそのカテゴリとタグが新規作成されている
