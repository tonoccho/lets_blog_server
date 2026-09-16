# language: ja
@project
機能: /db-importのusermeta meta_key改名は完全一致のみを対象にする

  provision-agentの`/db-import`(issue #511)はDBインポート後、退避しておいた同期先自身の
  usermetaのmeta_keyを旧テーブルプレフィックスから新プレフィックスへ付け替える
  (`{oldPrefix}capabilities`/`{oldPrefix}user_level`をWordPressが権限判定に使うため)。
  この付け替えを前方一致(LEFT match)で行っていたため、プレフィックス由来ではない
  (たまたま同じ文字列で始まるだけの)meta_keyまで巻き込んで改名してしまっていた
  (issue #1074)。`{oldPrefix}capabilities`/`{oldPrefix}user_level`の2つの完全一致キー
  だけを対象にするよう固定する。

  受け入れテスト環境にはSSH管理WordPressコンテナが無く、`/db-import`をUIから起動する経路が
  無いため(issue #1074 Open Questions)、provision-agentのエンドポイント(ポート9000、
  `lbs-wordpress`コンテナ内部)へ直接HTTPでPOSTすることで検証する
  (同issueで明示的に許容されている手段)。

  @stage:provision @destructive @slow @timeout:180000
  シナリオ: プレフィックス由来ではないusermetaキーは改名されず、capabilities/user_levelだけが新プレフィックスへ付け替わる
    前提 usermetaにプレフィックス由来ではないキーを持つmanagedサイトがある
    もし 異なるテーブルプレフィックスのDBダンプをそのサイトへdb-importする
    ならば 新プレフィックスのusermetaにcapabilitiesとuser_levelが存在し旧プレフィックスのものは残っていない
    かつ プレフィックス由来ではないusermetaキーは改名されずそのまま残っている
    かつ そのサイトの管理者アカウントでダッシュボードが表示される
