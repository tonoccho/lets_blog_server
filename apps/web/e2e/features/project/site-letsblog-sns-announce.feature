# language: ja
@project
機能: letsblog プラグインが、記事が公開状態になったときに接続済みの SNS へ告知し、予約公開と再公開では告知しない

  Let's Blog が停止していても告知できるよう、公開の検知は WordPress 側(プラグインの `transition_post_status`)で行う。
  接続済みの SNS(X の API スタブ)へ、記事のタイトルとパーマリンクが1回だけ投稿され、告知履歴に残ること。
  予約公開(Let's Blog の `publish_scheduled_at`)の記事は投稿時にも予約時刻の公開時にも告知されず、
  公開済みの記事の更新や、非公開にしてからの再公開でも再告知されないこと。
  SNS がエラーを返しても記事は公開され、失敗とその理由が告知履歴に残ること(issue #1575。Epic #1572。基盤は #1573)。
  フィクスチャは自動構築(マネージド)の WordPress サイトで、X の API の向き先は wp-config.php の定数
  `LETSBLOG_X_API_BASE_URL`(x スタブ)、接続は `wp letsblog sns config set` で行う。
  wp-admin からの公開は WP REST API(ログイン済みセッション)で行い、WP-Cron の実行は `wp cron event run --due-now` で確かめる
  (実行環境のループバックに依存しない)。

  背景:
    前提 サイト一覧ページを開いている

  @stage:provision @stub-isolation:x @slow @timeout:600000 @mode:serial
  シナリオ: wp-admin から下書きを公開すると、X のスタブにタイトルと URL が1回だけ投稿され、告知履歴に記録される
    前提 告知検証用に、X を接続したサイトに下書きの記事を作成しておく
    もし 告知検証の下書きを wp-admin から公開する
    かつ 告知検証のサイトで WP-Cron を実行する
    ならば 告知検証の X のスタブにタイトルと URL が1回だけ投稿されている
    かつ 告知検証の告知履歴に成功として記録されている

  @stage:provision @stub-isolation:x @slow @timeout:600000 @mode:serial
  シナリオ: Let's Blog から即時公開すると、wp-cli 経由でも1回だけ投稿される
    前提 告知検証用に、X を接続したサイトを用意する
    もし 告知検証の記事を Let's Blog から即時公開する
    ならば 告知検証の X のスタブにタイトルと URL が1回だけ投稿されている
    かつ 告知検証の告知履歴に成功として記録されている

  @stage:provision @stub-isolation:x @slow @timeout:600000 @mode:serial
  シナリオ: 予約投稿は、投稿時にも予約時刻に公開されたときにも告知されない
    前提 告知検証用に、X を接続したサイトを用意する
    もし 告知検証の記事を Let's Blog から予約投稿する
    ならば 告知検証の X のスタブには何も投稿されていない
    もし 告知検証の予約投稿が予約時刻に公開される
    かつ 告知検証のサイトで WP-Cron を実行する
    ならば 告知検証の X のスタブには何も投稿されていない

  @stage:provision @stub-isolation:x @slow @timeout:600000 @mode:serial
  シナリオ: 公開済みの記事の更新や、非公開にしてからの再公開では告知されない
    前提 告知検証用に、X を接続したサイトを用意する
    かつ 告知検証の記事を Let's Blog から即時公開する
    かつ 告知検証の X のスタブにタイトルと URL が1回だけ投稿されている
    もし 告知検証の公開済みの記事の本文を更新する
    かつ 告知検証の記事を非公開にしてから再公開する
    かつ 告知検証のサイトで WP-Cron を実行する
    ならば 告知検証の X のスタブにはまだ1回だけ投稿されている

  @stage:provision @stub-isolation:x @slow @timeout:600000 @mode:serial
  シナリオ: X がエラーを返しても記事は公開され、失敗とその理由が告知履歴に記録される
    前提 告知検証用に、X を接続したサイトを用意する
    かつ 告知検証の X のスタブが次の投稿に 429 を返すようにしておく
    もし 告知検証の記事を Let's Blog から即時公開する
    ならば 告知検証の記事は公開状態になっている
    かつ 告知検証の告知履歴に失敗とその理由が記録されている
