# language: ja
@response-budget
機能: サイトの Server Action が3秒の予算内に返る

  `docs/ACCEPTANCE_CRITERIA.md` §10.5 の `app/sites/[id]/edit/actions.ts` と `app/sites/actions.ts`(issue #1477)。書式と計り方は
  `page-first-display.feature` / `server-action-admin.feature` の冒頭を読むこと。

  対象のサイトは、シナリオごとに API で登録する使い捨て(SSH 接続の資格情報は到達できない値)で、
  後片付けは `responseBudgetCleanup.steps.ts`。編集画面の保存は資格情報の欄を含めて送るので、
  サーバ側の疎通確認が走る場合はその分も往復に含まれる。

  登録・疎通確認・wp-cli のインストール・静的コンテンツの生成は、到達できない SSH 先(`wrong.invalid`)へ向かう。
  サーバ側が SSH で接続を試みて失敗する経路も、利用者が実際に踏む往復なので、成否ではなく**往復が返るまで**を測る。
  サイトの登録は疎通確認を内部で行う(失敗しても登録は完了する)ので、その時間も往復に含まれる。
  SSH 鍵ペアの生成は保存せずに鍵を作って返すだけの操作。

  @budget-action:updateSiteAction
  シナリオ: サイトの更新(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証のために管理者としてログインしている
    かつ 応答時間予算の検証用のサイトがある
    もし サイト編集画面で表示名を変更して保存し Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:registerSiteAction
  シナリオ: サイトの登録(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証のために管理者としてログインしている
    もし サイト一覧画面でサイトを登録して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:generateSshKeyPairAction
  シナリオ: サイトの SSH 鍵ペアの生成(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証のために管理者としてログインしている
    もし サイト一覧画面でサイト用の SSH 鍵ペアを生成して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:deleteSiteAction
  シナリオ: サイトの削除(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証のために管理者としてログインしている
    かつ 応答時間予算の検証用のサイトがある
    もし サイト一覧画面で検証用のサイトを削除して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:checkSiteConnectionAction
  シナリオ: サイトの疎通確認(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証のために管理者としてログインしている
    かつ 応答時間予算の検証用のサイトがある
    もし サイト一覧画面で検証用のサイトの疎通確認を実行して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:installWpCliAction
  シナリオ: wp-cli のインストール(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証のために管理者としてログインしている
    かつ 応答時間予算の検証用のサイトがある
    もし サイト編集画面で wp-cli をインストールして Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:generateStaticContentAction
  シナリオ: 静的コンテンツの生成(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証のために管理者としてログインしている
    かつ 応答時間予算の検証用のサイトがある
    もし サイト編集画面で静的コンテンツを生成して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る
