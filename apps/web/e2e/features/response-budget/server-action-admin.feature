# language: ja
@response-budget
機能: 管理画面の Server Action が3秒の予算内に返る

  利用者の要望「すべてのクリックに対する応答を3秒以内にする」(issue #1476)の展開(issue #1477)。
  `docs/ACCEPTANCE_CRITERIA.md` §10.5 の `app/admin/**` の Server Action を、1操作1シナリオで検証する。
  書式(`@budget-action:`)と計り方は `page-first-display.feature` の冒頭を読むこと。

  - 計るのは**サーバへ実際に送られた Server Action の POST の往復**だけ(§10.3)。
    クリックの空振りの撃ち直しは含まれない。共通ステップ「Server Action の往復は「…」ミリ秒以内に返る」で判定する。
  - 前提(対象の利用者・鍵ペア)は API で作る。操作は実際の画面で行い、シナリオごとに作った使い捨てで、
    共有の E2E アカウントや共有の設定を変えない(後片付けは `responseBudgetCleanup.steps.ts`)。
  - `restoreBackupAction` は**バックアップとして読めないファイル**を渡す。サーバ側は中身を検証した時点で拒否し、
    データベースには何も書かない(`BackupService#restoreBackup`)ので、実データを壊さずに Next.js から
    API までの往復を測れる。測っているのは「拒否が返るまで」であり、実際のリストア(ダンプの流し込み)の
    所要時間ではない。後者は §10.5 の注記のとおり3秒に収まる性質のものではなく、環境を上書きするため
    受け入れシナリオでは実行しない(`platform/backup.feature` が同じ理由でリストアを扱わない)。
  - `updateAppSettingsAction` は表示された値をそのまま保存し直す(値は変わらない)。

  背景:
    前提 応答時間予算の検証のために管理者としてログインしている

  @budget-action:assignRoleAction
  シナリオ: ロールの付与(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用に使い捨ての利用者がいる
    もし ロール管理画面で使い捨ての利用者に「ROLE_EDITOR」を付与して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:removeRoleAction
  シナリオ: ロールの解除(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用に「ROLE_EDITOR」を持つ使い捨ての利用者がいる
    もし ロール管理画面で使い捨ての利用者から「ROLE_EDITOR」を解除して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:createSshKeyPairAction
  シナリオ: SSH鍵ペアの生成(Server Action)の往復が3秒以内に返る
    もし SSH鍵管理画面で新しいSSH鍵ペアを生成して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:deleteSshKeyPairAction
  シナリオ: SSH鍵ペアの削除(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のSSH鍵ペアがある
    もし SSH鍵管理画面でそのSSH鍵ペアを削除して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:updateAppSettingsAction
  シナリオ: システム設定の保存(Server Action)の往復が3秒以内に返る
    もし システム設定画面で表示されたままの値を保存して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:restoreBackupAction
  シナリオ: バックアップのリストア要求(Server Action)の往復が3秒以内に返る
    もし バックアップ画面でバックアップとして読めないファイルのリストアを実行して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る
