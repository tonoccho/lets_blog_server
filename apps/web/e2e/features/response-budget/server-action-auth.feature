# language: ja
@response-budget
機能: ログイン開始の Server Action が3秒の予算内に返る

  `docs/ACCEPTANCE_CRITERIA.md` §10.5 の `app/login/actions.ts`(issue #1477)。書式と計り方は
  `server-action-admin.feature` / `page-first-display.feature` の冒頭を読むこと。

  `startNoJsLoginAction` は、`/login` の自動リダイレクトが成立しなかったとき(JS が動かない環境、
  リダイレクトの失敗)に出る「ログインを開始する」ボタンのフォームから呼ばれる(issue #1052)。
  通常は JS が自動でKeycloakへ遷移するので、このシナリオは**自動遷移の要求だけを止めて**
  (`/api/auth/signin/keycloak` へのブラウザからの POST を遮断)、3秒後に現れる救済のボタンを押す。
  JS を無効にしたコンテキストでは、フォームはブラウザの通常の POST になり `next-action` ヘッダを
  持たないため Server Action の往復として数えられない(§10.3 の注意)。有効なままボタンを押せば、
  React が Server Action として送る。計るのはその POST(Keycloak への実 POST のサーバ側代行を含む)で、
  遷移先のKeycloakの画面の表示までは含まない。

  @budget-action:startNoJsLoginAction
  シナリオ: ログイン開始(Server Action)の往復が3秒以内に返る
    もし 自動遷移を止めた「/login」で救済の「ログインを開始する」を押して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る
