# language: ja
@response-budget
機能: 利用者管理の Server Action が3秒の予算内に返る

  `docs/ACCEPTANCE_CRITERIA.md` §10.5 の `app/users/actions.ts` と `app/users/[id]/edit/actions.ts`
  (issue #1477)。書式と計り方は `page-first-display.feature` / `server-action-admin.feature` の冒頭を読むこと。

  - 対象の利用者は、シナリオごとに API で作る**使い捨て**で、共有の E2E アカウントを書き換えない。
    後片付けは `responseBudgetCleanup.steps.ts`。
  - 個人設定の保存(`updatePreferencesAction`)は**本人の設定だけ**を変える操作なので、
    Keycloak の資格情報を整えた使い捨ての利用者としてログインして行う(共有の管理者の設定を書き換えない)。
  - プロフィールの保存とアバターの保存は、管理者が使い捨ての利用者の編集画面で行う(管理者は他人の分も保存できる)。

  @budget-action:createUserAction
  シナリオ: 利用者の追加(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証のために管理者としてログインしている
    もし 利用者管理画面で新しい利用者を追加して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:deleteUserAction
  シナリオ: 利用者の削除(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証のために管理者としてログインしている
    かつ 応答時間予算の検証用に使い捨ての利用者がいる
    もし 利用者管理画面で使い捨ての利用者を削除して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:updateUserProfileAction
  シナリオ: プロフィールの保存(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証のために管理者としてログインしている
    かつ 応答時間予算の検証用に使い捨ての利用者がいる
    もし 使い捨ての利用者の編集画面で名を変更して保存し Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:uploadAvatarAction
  シナリオ: アバターの保存(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証のために管理者としてログインしている
    かつ 応答時間予算の検証用に使い捨ての利用者がいる
    もし 使い捨ての利用者の編集画面でアバター画像を選び切り抜きを確定して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:updatePreferencesAction
  シナリオ: 個人設定の保存(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用にログインできる使い捨ての利用者がいる
    かつ その使い捨ての利用者としてログインしている
    もし 自分の編集画面の個人設定でタイムゾーンを変更して保存し Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る
