# language: ja
@response-budget
機能: プロジェクトのメンバー管理の Server Action が3秒の予算内に返る

  `docs/ACCEPTANCE_CRITERIA.md` §10.5 の `app/projects/[id]/actions.ts` のうち、メンバーの追加・ロール変更・
  削除・ユーザー情報の同期(issue #1477)。書式と計り方は `server-action-project-settings.feature` の冒頭を読むこと。

  - 対象の利用者は、シナリオごとに API で作る使い捨てで、共有の E2E アカウントを書き換えない。
  - プロジェクトにはサイトを紐付けない。したがって WordPress 側のユーザーの作成・同期は起きず、
    測っているのは Next.js から gateway・identity-service までの往復(同期は「紐づく環境が無い」旨が返るまで)。
    実 WordPress への同期の所要時間は、ここでは測らない。

  背景:
    前提 応答時間予算の検証のために管理者としてログインしている
    かつ 応答時間予算の検証用のプロジェクトがある

  @budget-action:addProjectUserAction
  シナリオ: メンバーの追加(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用に、そのプロジェクトのメンバーではない使い捨ての利用者がいる
    もし メンバータブで使い捨ての利用者を追加して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:updateProjectUserRoleAction
  シナリオ: メンバーのロール変更(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用に、そのプロジェクトのメンバーである使い捨ての利用者がいる
    もし メンバータブで使い捨ての利用者のロールを変更して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:removeProjectUserAction
  シナリオ: メンバーの削除(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用に、そのプロジェクトのメンバーである使い捨ての利用者がいる
    もし メンバータブで使い捨ての利用者を削除して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:syncProjectUserAction
  シナリオ: メンバーのユーザー情報の同期(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用に、そのプロジェクトのメンバーである使い捨ての利用者がいる
    もし メンバータブで使い捨ての利用者のユーザー情報を同期して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る
