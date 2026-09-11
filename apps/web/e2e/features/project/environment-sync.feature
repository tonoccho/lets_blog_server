# language: ja
@project
機能: 環境同期(managed→managed)でテーブルプレフィックスが異なる場合のDB同期

  「環境同期」パネル(`EnvironmentSyncPanel.tsx`)からmanagedサイト間でDBを同期したとき、
  同期元と同期先でテーブルプレフィックスが異なっていても、同期先のWordPressロール定義
  (`{prefix}user_roles`)が保たれ、`administrator`を割り当てたメンバーが同期先の
  wp-adminを開けることを固定する(issue #1075)。

  プレフィックスの食い違いは実運用では`/db-import`(#511)経由で発生するが、受け入れテスト
  環境にはSSH管理WordPressのコンテナが無いため、`/db-import`の最終状態(「ダンプ由来の
  テーブル名+それに合わせたwp-configのtable_prefix」)と等価な状態を、同期先のテーブルを
  一括改名し`wp config set table_prefix`を変更することで人為的に作る(issue #1075 Open
  Questionsで明示的に許容されている)。改名時にoption_nameの値も付け替えて自己無矛盾な
  状態(実際の`/db-import`が作る状態と同じ)にしてから検証対象の同期を実行する。

  `@stage:provision`のfeatureは本シナリオ追加時点で0件だったため、managedサイトを2つ
  用意する最小限の足場をこのシナリオ自身が持つ(#931 [AT-5]が再利用できる形)。

  @stage:provision @destructive @slow @timeout:300000
  シナリオ: プレフィックスが異なるmanagedサイト間でDBを同期してもロール定義とメンバーの権限が保たれる
    前提 環境同期検証用のプロジェクトと2つのmanagedサイトがある
    かつ プロジェクトへwpRole=editorのメンバーを追加している
    もし プレフィックスが同一のまま同期元から同期先へDBを同期する
    ならば 同期先のoptionsの構成は同期元と一致している
    もし 同期先のテーブルプレフィックスを同期元と異なる値へ変更する
    かつ 環境同期パネルから同期元のDBを同期先へ同期する
    ならば 同期先のoptionsに同期先プレフィックスのuser_rolesが1件存在し同期元プレフィックスのuser_rolesは存在しない
    かつ 同期先のロール一覧は同期元と同じロール集合である
    かつ 同期先のテーブル名に同期元プレフィックスのものが残っていない
    かつ 同期先のプライバシーポリシー等のコアオプション名は改名されず残っている
    もし そのメンバーの役割をadministratorへ変更する
    ならば そのメンバーの同期先での権限はadministratorただ1つである
    かつ 同期先サイトの管理者アカウントでダッシュボードが表示される
