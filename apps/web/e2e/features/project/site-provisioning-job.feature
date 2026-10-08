# language: ja
@project @api @site-provisioning-job
機能: WordPressサイト自動構築のジョブとしての非同期受理

  サイト自動構築は完了まで最大240秒かかる同期API(`POST /api/sites/managed-wordpress`)のほかに、
  要求をジョブとして受理して即座にジョブIDを返す口(`POST /api/sites/managed-wordpress/jobs`)を
  持つ(issue #1479、親 #1478 の第1段。画像生成の #1405 と同じ形)。構築は背景で進み、
  `GET /api/generation-jobs/{id}` で進行段階(`provisioning` → `registering`)と、完了後の
  結果(作成されたサイトのID)を取得する。同期APIは変えない(`site-provisioning.feature` の
  既存シナリオが無改変で通ることが、その保証である)。UIは本Issueの対象外で、
  画面側の切り替えは #1696(`site-provisioning-web-job.feature`)で行った。

  ## 受け入れテストで確かめられない観点

  「構築に失敗したら provision-agent の実体(ディレクトリ・DB)が残らない」は、
  `ProvisioningException` を実コンテナで起こす手段(失敗注入)が e2e に無いため、
  このフィーチャでは確かめない。`WordPressSiteProvisioningServiceJobTest`(サービスレベル)が
  `deprovision` の呼び出しと登録済みサイトレコードの削除を固定している。
  重複 siteKey は構築を始める前に断られるため、ここで確かめるのは「failed になり理由が読める」までである。

  ## 実際に構築するため @slow

  `site-provisioning.feature` と同じ理由(実測で最大240秒)で `@slow` を付ける。
  構築したサイトは終了時に削除する(WordPress側の実体も `DELETE /api/sites/{id}` が消す)。

  @stage:provision @slow @timeout:300000
  シナリオ: サイト自動構築をジョブとして要求すると、3秒以内にジョブIDが返り、完了後は作成されたサイトのIDが引ける
    もし サイト自動構築をジョブとして要求する
    ならば サイト自動構築のジョブIDが「running」の状態で3秒以内に返る
    かつ サイト自動構築のジョブが終わるまで待つ
    ならば そのジョブは「done」で終わり、結果に作成されたサイトのIDが示される
    かつ 結果が示すサイトはサイト一覧に現れ、疎通確認が成功する
    かつ その構築の監査ログ「WORDPRESS_PROVISIONED」に操作者が残っている
    もし 同じsiteKeyでサイト自動構築をもう一度ジョブとして要求する
    かつ サイト自動構築のジョブが終わるまで待つ
    ならば そのジョブは「failed」で終わり、理由に重複したsiteKeyが示される

  シナリオ: 必須項目が欠けたサイト自動構築の要求は、ジョブを作らず400で断られる
    もし 管理者パスワードの無いサイト自動構築をジョブとして要求する
    ならば サイト自動構築の要求は「400」で断られる
