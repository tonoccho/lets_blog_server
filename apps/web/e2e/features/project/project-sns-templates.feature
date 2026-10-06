# language: ja
@project
機能: プロジェクト設定画面での告知文テンプレートの編集と、本番サイトのプラグインによる告知文の組み立て

  プロジェクト設定画面の「SNS 告知」欄で、記事の公開時と PV 達成時の告知文のテンプレートを別々に編集できること。
  差し込み項目は `{title}`(記事のタイトル)・`{url}`(パーマリンク)と、PV 達成時だけの `{period}`・`{threshold}`。
  保存したテンプレートは wp-cli(`wp letsblog sns templates set`)で本番サイトの letsblog プラグインへ送られ、
  プラグインが告知のたびに差し込み項目を値に置き換えて投稿すること。テンプレートが空なら既定の告知文
  (タイトルとパーマリンク)で投稿されること。SNS の文字数の上限を超えるときは、URL を残して切り詰めて投稿されること
  (issue #1583。Epic #1572。公開時の告知は #1575、PV 達成時の告知は #1577、設定画面は #1574)。

  文字数の上限の検証には X(重み付きで 280。全角は 2、URL は 23)を使う。Threads(500)・Facebook と、
  PV 達成時の `{period}`・`{threshold}` の置き換えは、GA4 の PV の取得を要するため、
  プラグインの CLI テスト(infra/wordpress/provision-agent/__tests__/test-letsblog-sns.php /
  test-letsblog-pv-announce.php)で検証する。
  フィクスチャは自動構築(マネージド)の WordPress サイトで、X の API の向きは wp-config.php の定数
  `LETSBLOG_X_API_BASE_URL`(x スタブ)、接続は `wp letsblog sns config set` で行う。

  背景:
    前提 管理者としてログインする

  @stage:provision @stub-isolation:x @slow @timeout:600000 @mode:serial
  シナリオ: 公開時と PV 達成時のテンプレートを別々に保存すると、本番サイトのプラグインへ反映される
    前提 告知文検証用に、X を接続した本番サイトを持つプロジェクトを用意する
    もし 告知文検証の SNS 告知設定画面で公開時のテンプレートに"【新着】{title} {url}"、PV 達成時のテンプレートに"{period}で{threshold}PV達成! {title} {url}"を保存する
    ならば 告知文検証の SNS 告知設定画面のテンプレートの送信状態は"送信済み"と表示される
    かつ 告知文検証の本番サイトのプラグインの公開時のテンプレートは"【新着】{title} {url}"である
    かつ 告知文検証の本番サイトのプラグインの PV 達成時のテンプレートは"{period}で{threshold}PV達成! {title} {url}"である

  @stage:provision @stub-isolation:x @slow @timeout:600000 @mode:serial
  シナリオ: 公開時のテンプレートの差し込み項目が値に置き換わって、X に投稿される
    前提 告知文検証用に、X を接続した本番サイトを持つプロジェクトを用意する
    かつ 告知文検証の SNS 告知設定画面で公開時のテンプレートに"【新着】{title} {url}"、PV 達成時のテンプレートに""を保存してある
    もし 告知文検証の記事を Let's Blog から即時公開する
    ならば 告知文検証の X のスタブに"【新着】"に続けてタイトルと URL を空白で区切った本文が1回だけ投稿されている

  @stage:provision @stub-isolation:x @slow @timeout:600000 @mode:serial
  シナリオ: テンプレートを空にして保存すると、既定の告知文(タイトルと URL)で投稿される
    前提 告知文検証用に、X を接続した本番サイトを持つプロジェクトを用意する
    かつ 告知文検証の SNS 告知設定画面で公開時のテンプレートに"【新着】{title} {url}"、PV 達成時のテンプレートに""を保存してある
    かつ 告知文検証の SNS 告知設定画面で公開時のテンプレートに""、PV 達成時のテンプレートに""を保存してある
    もし 告知文検証の記事を Let's Blog から即時公開する
    ならば 告知文検証の X のスタブにタイトルと URL が改行で区切られて1回だけ投稿されている

  @stage:provision @stub-isolation:x @slow @timeout:600000 @mode:serial
  シナリオ: X の文字数の上限を超えるテンプレートは、URL を残して切り詰めて投稿される
    前提 告知文検証用に、X を接続した本番サイトを持つプロジェクトを用意する
    かつ 告知文検証の SNS 告知設定画面で、タイトルと全角300文字の本文と URL を並べた公開時のテンプレートを保存してある
    もし 告知文検証の記事を Let's Blog から即時公開する
    ならば 告知文検証の X のスタブの投稿は上限の280を超えず、末尾に URL が残り、切り詰めの印を含む
