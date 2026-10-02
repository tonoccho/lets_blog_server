# language: ja
@logging @api @timeout:300000
機能: 監査ログ

  「権限に関わる操作が後から追える」「その記録を利用者が消せない」を固定する
  (issue #941 / AT-15)。

  監査ログの書き手は log-writer だが、**発行元は各ドメインサービス**である
  (identity/project/content/media/platform/publishing の `AuditLogService`)。
  記録は RabbitMQ を経由するので、操作ログと同じくポーリングで待つ。

  ## 「認可に関わる操作」としてここで扱うもの

  監査ログに残る認可操作は、プロジェクトメンバーのロール付与・変更・剥奪
  (`PROJECT_USER_ADDED` / `PROJECT_USER_ROLE_UPDATED` / `PROJECT_USER_REMOVED`、
  services/identity/.../ProjectUserSyncService.java)と、issue #1242で加わった
  メンバー個別のユーザー情報再同期(`PROJECT_USER_SYNCED`、同
  `syncUserProfileToProjectSites`)に加え、issue #1137で
  ユーザーの無効化・再有効化・削除・roleの変更(`services/identity/.../UserService.java`)
  も対象になった。

  `PROJECT_USER_SYNCED`が記録されることは、このfeatureではなく
  `features/identity/project-members.feature`(#1242のシナリオ、ステップは
  `projectMember.steps.ts`)が検証しているので、ここにシナリオは重ねない。
  media の `MEDIA_GARBAGE_COLLECTED` は認可に関わる操作ではないため、対象外とする。

  一括削除(`/api/projects/{id}/bulk-management/*/delete-all`)も#1137で
  `@AuditLog`(`services/publishing/.../TermComparisonService`等)を持つようになった。

  カテゴリ・タグの一括削除は、`articlePlan.steps.ts`の`createTaxonomySite`
  (issue #935/AT-9)がまさに「Projectの`test`環境にmanaged WordPressサイトを紐付け、
  そこへwp-cliで実カテゴリ・実タグを作る」足場を持っていたため、これと同じ手順を
  このfeature自身の足場として再現し(`createBulkDeleteTaxonomySite`)、下のシナリオで
  検証する(#1137レビュー、2026-09-12。前回時点の「足場が無い」という記述は誤りだった)。

  一方、プラグイン・テーマ・投稿の一括削除は、ここではシナリオにしない。記録の実装は
  #1137に含まれ、`@AuditLog`アノテーション付与のリフレクションテスト
  (services/publishing/src/test/java/.../aop/BulkDeleteAuditLogAnnotationTest.java)で
  検証している。受け入れシナリオでの確認は#1323が引き受ける(#1137を5件の受け入れ基準に
  収めるため、2026-09-16に分割した)。この3本のフィクスチャ(実プラグイン・実テーマ・実投稿)の
  有無は、ここでは主張しない。調べるのは#1323である。

  シナリオ: プロジェクトメンバーのロール付与・変更・剥奪が監査ログに記録される
    前提 監査ログ検証用のプロジェクトがある
    かつ 現時点の監査ログを控えておく
    もし そのプロジェクトに一般ユーザーをメンバーとして追加し、ロールを変更し、外す
    ならば ロールの付与・変更・剥奪の3件が監査ログに現れるまで待つ
    かつ 監査ログの各件には操作者・日時・対象・操作種別が揃っている

  シナリオ: 監査ログは利用者から改変・削除できない
    前提 監査ログ検証用のプロジェクトがある
    かつ そのプロジェクトに一般ユーザーをメンバーとして追加する
    かつ ロール付与の監査ログが1件記録されるまで待つ
    もし 管理者が監査ログの更新と削除を試みる
    ならば いずれの試みも成功しない
    かつ その監査ログは元のまま残っている

  シナリオ: ユーザーの無効化・再有効化・role変更・削除が監査ログに記録される
    前提 監査ログ検証用の使い捨てユーザーがいる
    かつ 現時点の監査ログを控えておく
    もし そのユーザーを無効化し、再有効化し、roleをadminへ変更し、削除する
    ならば 無効化・再有効化・role変更・削除の4件が監査ログに現れるまで待つ
    かつ 監査ログの各件には操作者・日時・対象・操作種別が揃っている
    かつ role変更の監査ログのchangesから変更前後のroleが読み取れる

  シナリオ: カテゴリの一括削除が監査ログに記録される
    前提 監査ログ検証用の、公開先に実カテゴリと実タグを持つプロジェクトがある
    かつ 現時点の監査ログを控えておく
    もし そのカテゴリを一括削除する
    ならば カテゴリの一括削除が監査ログに現れるまで待つ
    かつ 監査ログの各件には操作者・日時・対象・操作種別が揃っている

  シナリオ: タグの一括削除が監査ログに記録される
    前提 監査ログ検証用の、公開先に実カテゴリと実タグを持つプロジェクトがある
    かつ 現時点の監査ログを控えておく
    もし そのタグを一括削除する
    ならば タグの一括削除が監査ログに現れるまで待つ
    かつ 監査ログの各件には操作者・日時・対象・操作種別が揃っている
