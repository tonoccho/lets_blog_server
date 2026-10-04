# language: ja
@response-budget @retries:2
機能: 操作ログの Server Action が3秒の予算内に返る

  `docs/ACCEPTANCE_CRITERIA.md` §10.5 の `app/operation-logs/actions.ts`(issue #1477)。書式と計り方は
  `page-first-display.feature` / `server-action-admin.feature` の冒頭を読むこと。

  操作ログの「コピー」は、その操作 ID に紐づくトレース全体を Server Action で取り直してから
  クリップボードへ書く。対象の操作は、管理者がログインして画面を開くまでに自然に記録された
  もの(最初に出る行)で、ログは読むだけなので何も作らず、後片付けも要らない。

  @budget-action:copyOperationTraceAction
  シナリオ: 操作トレースのコピー(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証のために管理者としてログインしている
    もし 操作ログ画面で最初の操作の「コピー」を押して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る
