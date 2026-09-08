# language: ja
@publishing @api @slow @mode:serial
機能: 記事の著者マッピングと著者の自動プロビジョニング

  記事を公開した利用者が、公開先WordPressの投稿者(post_author)として正しく現れることを固定する
  (issue #1176 / AT-6-6。親issue #932(AT-6)のシナリオ14・15を引き取る子issue)。

  ## 著者マッピングの実際の経路について

  `POST /api/internal/publishing/sites/{siteKey}/authors`(AC-INT-018)はlegacy-api時代の
  `ProjectUserSyncService`から呼ばれる内部ブリッジで、専用の画面もVSCode拡張からの直接の
  導線も持たない(`AuthorProvisioningInternalController`のJavadoc参照)。実際にこの経路が
  動くのは、プロジェクトメンバーを追加/変更したとき(`ProjectUserSyncService#provisionUserOnSite`)
  であり、これはWeb管理画面から`POST /api/projects/{id}/users`として到達できる公開APIである。
  そのため本featureでは、内部ブリッジを直接叩くのではなく「プロジェクトへ利用者を追加する」
  という利用者から見える操作を起点にする。

  `PostPublishService#resolveAuthorId`は、公開者(JWTの持ち主)に対応するWordPressユーザーIDを
  まず`user_site_authors`のキャッシュ(プロジェクトメンバー追加時に書き込まれる)から探し、
  無ければメールアドレスでの動的検索にフォールバックする。見つからなければ著者未設定のまま
  投稿自体は続行する(投稿全体を失敗させない設計)ため、著者マッピングが機能していることは
  「公開した利用者と一致するWordPressユーザーがpost_authorに設定されている」ことでしか
  確認できない。

  ## 専用サイトを冪等に用意する理由

  著者マッピングの正しさは実際のWordPress側のユーザー・投稿の状態を見ないと確かめられない。
  #1167のプロビジョニング済みサイト共有フィクスチャ(`site-provisioning.steps.ts`)はサイトの
  識別子だけを残し、WordPress管理者の認証情報を残さない設計のため、後始末(作成した投稿・
  著者アカウントの削除)にwp-cliで直接アクセスする必要があるここでは使えない。そこで
  `publishTaxonomy.steps.ts`(#1174)と同じ「固定siteKeyで冪等に用意し、実行をまたいで
  再利用する」パターンを踏襲する。

  ## `@mode:serial`な理由

  両シナリオが同じ検証用サイト・プロジェクトを冪等に用意する(`前提`)。並列に走らせると、
  片方の「サイト一覧に無い→構築する」判定ともう片方の構築が競合し、既に構築済みのWordPress
  環境へ二重に構築しようとして409になる(`taxonomy.feature`と同じ理由)。

  背景:
    前提 著者マッピング検証用のWordPressサイトがあり、プロジェクトに紐づいている

  シナリオ: 記事の著者マッピングに従い、WordPress側の投稿者が期待通りになる
    もし 新しい利用者をそのプロジェクトへauthorとして参加させる
    かつ その利用者として記事を公開する
    ならば WordPress側のその投稿の投稿者は、参加させた利用者に対応するWordPressユーザーと一致する

  シナリオ: WordPress側に対応する著者が存在しない場合、自動プロビジョニングされる
    もし 新しい利用者をそのプロジェクトへauthorとして参加させる
    ならば WordPress側にその利用者のメールアドレスに対応する新規ユーザーが作成されている
