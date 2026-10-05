# language: ja
@project
機能: プロジェクト設定画面での Threads アカウントの接続と、本番サイトのプラグインからの告知

  プロジェクト設定画面の「Threads」欄で、プロジェクトの公式 Threads アカウントを OAuth で接続・切断できること。
  得られた長期トークンがその場で本番サイトの letsblog プラグインへ `wp letsblog sns config set` で送られ、
  アプリにも、プラグインの DB にも、画面・API の応答にも平文で残らないこと。テスト投稿と記事の公開時の告知が
  Threads の API スタブへ1回だけ届き、告知履歴に残ること。
  Threads の長期トークンは、発行から24時間以上たち、かつ期限が切れる前でなければ更新できない。更新が必要なのに
  この条件を満たさないときは投稿せず、その理由が告知履歴に残ること(issue #1579。Epic #1572。
  プラグイン側は #1573・#1575、アプリ側の接続は X の #1574 と同じ方式)。

  Threads の認可画面と API は `threads-stub` で代替する(docs/ACCEPTANCE_TESTING.md の外部依存スタブ)。
  アプリ(project-service)は THREADS_API_BASE_URL / THREADS_AUTHORIZE_URL で、プラグインは wp-config.php の定数
  LETSBLOG_THREADS_API_BASE_URL でスタブへ向ける。フィクスチャは自動構築(マネージド)の WordPress サイトで、
  サイトの状態の確認と細工は `docker exec lbs-wordpress wp ...` で行う。

  背景:
    前提 管理者としてログインする

  @stage:provision @stub-isolation:threads @slow @timeout:900000 @mode:serial
  シナリオ: Threads を接続すると本番サイトの wp letsblog sns status に反映され、トークンはどこにも平文で残らない
    前提 Threads 接続検証用に本番サイトを持つプロジェクトを構築しておく
    もし Threads 接続検証のプロジェクトの SNS 告知設定画面で OAuth のアプリ情報を入力して Threads と接続する
    ならば SNS 告知設定画面の Threads にアカウント"lets_blog_e2e"の接続済みが表示される
    かつ 本番サイトの wp letsblog sns status が Threads の接続済みを返す
    かつ アプリの API の応答に Threads のトークンが含まれない
    かつ アプリの DB に Threads のトークンが保存されていない
    かつ 本番サイトの DB に Threads のトークンが平文で保存されていない

  @stage:provision @stub-isolation:threads @slow @timeout:900000 @mode:serial
  シナリオ: Threads を切断すると、本番サイトの wp letsblog sns status が未設定に戻る
    前提 Threads 接続検証用に本番サイトを持つプロジェクトを構築しておく
    かつ Threads 接続検証のプロジェクトで Threads を接続しておく
    もし SNS 告知設定画面で Threads を切断する
    ならば SNS 告知設定画面の Threads の接続状態は"未接続"と表示される
    かつ 本番サイトの wp letsblog sns status が Threads の未設定を返す

  @stage:provision @stub-isolation:threads @slow @timeout:900000 @mode:serial
  シナリオ: テスト投稿が Threads のスタブに届き、告知履歴に成功として表示される
    前提 Threads 接続検証用に本番サイトを持つプロジェクトを構築しておく
    かつ Threads 接続検証のプロジェクトで Threads を接続しておく
    もし SNS 告知設定画面で Threads の投稿テストを行う
    ならば Threads のスタブにテスト投稿が1回だけ届いている
    かつ Threads の告知履歴にテスト投稿が成功として表示される

  @stage:provision @stub-isolation:threads @slow @timeout:900000 @mode:serial
  シナリオ: 記事を公開すると、Threads のスタブにもタイトルと URL が1回だけ投稿される
    前提 Threads 接続検証用に本番サイトを持つプロジェクトを構築しておく
    かつ Threads 接続検証のプロジェクトで Threads を接続しておく
    もし Threads 接続検証の本番サイトへ Let's Blog から記事を即時公開する
    ならば Threads のスタブにタイトルと URL が1回だけ投稿されている
    かつ Threads の告知履歴に記事の公開が成功として記録されている

  @stage:provision @stub-isolation:threads @slow @timeout:900000 @mode:serial
  シナリオ: 期限が近く発行から24時間以上たっている長期トークンは、更新してから投稿する
    前提 Threads 接続検証用に本番サイトを持つプロジェクトを構築しておく
    かつ Threads 接続検証のプロジェクトで Threads を接続しておく
    かつ Threads 接続検証の本番サイトのトークンが発行から55日で期限まで5日になっている
    もし SNS 告知設定画面で Threads の投稿テストを行う
    ならば Threads のスタブでトークンが1回更新されている
    かつ Threads のスタブにテスト投稿が1回だけ届いている

  @stage:provision @stub-isolation:threads @slow @timeout:900000 @mode:serial
  シナリオ: 発行から24時間たっていないトークンの更新が必要なときは、投稿せず理由が告知履歴に残る
    前提 Threads 接続検証用に本番サイトを持つプロジェクトを構築しておく
    かつ Threads 接続検証のプロジェクトで Threads を接続しておく
    かつ Threads 接続検証の本番サイトのトークンが発行から1時間で期限まで3日になっている
    もし SNS 告知設定画面で Threads の投稿テストを行う
    ならば Threads のスタブには何も投稿されず、更新もされていない
    かつ Threads の告知履歴に失敗の理由として"24時間"が表示される

  @stage:provision @stub-isolation:threads @slow @timeout:900000 @mode:serial
  シナリオ: 期限が切れた長期トークンは更新できないので、投稿せず理由が告知履歴に残り、要再接続になる
    前提 Threads 接続検証用に本番サイトを持つプロジェクトを構築しておく
    かつ Threads 接続検証のプロジェクトで Threads を接続しておく
    かつ Threads 接続検証の本番サイトのトークンが期限切れになっている
    もし SNS 告知設定画面で Threads の投稿テストを行う
    ならば Threads のスタブには何も投稿されず、更新もされていない
    かつ Threads の告知履歴に失敗の理由として"期限が切れ"が表示される
    かつ SNS 告知設定画面の Threads の接続状態は"要再接続"と表示される
