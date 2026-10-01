# language: ja
@response-budget @slow
機能: プロジェクトの一括管理の比較取得の Server Action が3秒の予算内に返る

  `docs/ACCEPTANCE_CRITERIA.md` §10.5 の `app/projects/[id]/actions.ts` のうち、一括管理の環境間比較の取得
  (タグ・ポスト/ページ)(issue #1477)。書式と計り方は `server-action-project-settings.feature` の冒頭を読むこと。

  - **実 WordPress** を相手にする。AT-7(`bulk/*.feature`)が固定キーで冪等に用意している2サイト
    (`at7cmpmaster` = テスト環境、`at7cmptarget` = ローカル環境)をそのまま使い、無ければ同じ引数で構築する
    (構築は分単位。`@slow`)。プロジェクトも AT-7 の固定の比較用プロジェクト(サイトは同時に1つのプロジェクトにしか紐付けられない)で、
    共有の資源なので消さない。
  - 比較の取得はタブを開いたときの Server Action の往復で、カテゴリのタブの初回表示はサーバ側の描画
    (§10.4 の `/projects/[id]`)で、Server Action ではない。
  - 一括管理の同期・編集・削除・反映・インストール、プラグインの比較(`fetchStatusComparisonAction`)は、
    実 WordPress へ複数環境を逐次呼ぶため実測で3秒を超えた(#1552)。利用者の判断(2026-10-01)で
    「非同期ハンドオフ待ち(#1478)」に再分類されたので、ここには置かない(§10.5)。

  背景:
    前提 応答時間予算の検証のために管理者としてログインしている
    かつ 応答時間予算の検証用に、AT-7 のテストとローカルの実 WordPress を紐付けた比較用プロジェクトを使う

  @budget-action:fetchTermComparisonAction
  シナリオ: タグの環境間比較の取得(Server Action)の往復が3秒以内に返る
    もし 一括管理のタグのタブを開いて Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:fetchPostComparisonAction @budget-action:fetchPostStatusesAction
  シナリオ: ポスト/ページの環境間比較とステータス一覧の取得(Server Action)の往復が3秒以内に返る
    もし 一括管理のポスト/ページのタブを開いて Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る
