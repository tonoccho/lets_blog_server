# language: ja
@project
機能: プロジェクト設定画面での Facebook ページの接続と、本番サイトのプラグインからの告知

  プロジェクト設定画面の「Facebook」欄で、プロジェクトの公式 Facebook ページを OAuth で接続・切断できること。
  認可のあと、管理しているページから投稿先のページを選び、そのページのトークンだけがその場で本番サイトの
  letsblog プラグインへ `wp letsblog sns config set` で送られる(個人アカウントには投稿しない)。トークンは
  アプリにも、プラグインの DB にも、画面・API の応答にも平文で残らない。テスト投稿と記事の公開時の告知が
  Facebook の Graph API スタブ(ページのフィード)へリンク付きで1回だけ届き、告知履歴に残ること。
  投稿先のページのトークンが失効している、または pages_manage_posts の権限が無いときは投稿せず、その理由が
  告知履歴に残ること(issue #1580。Epic #1572。プラグイン側は #1573・#1575、アプリ側の接続は X の #1574 と同じ方式)。

  Facebook の認可画面と API は `facebook-stub` で代替する(docs/ACCEPTANCE_TESTING.md の外部依存スタブ)。
  アプリ(project-service)は FACEBOOK_API_BASE_URL / FACEBOOK_AUTHORIZE_URL で、プラグインは wp-config.php の定数
  LETSBLOG_FACEBOOK_API_BASE_URL でスタブへ向ける。フィクスチャは自動構築(マネージド)の WordPress サイトで、
  サイトの状態の確認と細工は `docker exec lbs-wordpress wp ...` で行う。

  背景:
    前提 管理者としてログインする

  @stage:provision @stub-isolation:facebook @slow @timeout:900000 @mode:serial
  シナリオ: Facebook ページを接続すると本番サイトの wp letsblog sns status に反映され、トークンはどこにも平文で残らない
    前提 Facebook 接続検証用に本番サイトを持つプロジェクトを構築しておく
    もし Facebook 接続検証のプロジェクトの SNS 告知設定画面で OAuth のアプリ情報を入力し、ページ"Let's Blog E2E ページ"を選んで Facebook と接続する
    ならば SNS 告知設定画面の Facebook にページ"Let's Blog E2E ページ"の接続済みが表示される
    かつ 本番サイトの wp letsblog sns status が Facebook の接続済みを返す
    かつ アプリの API の応答に Facebook のトークンが含まれない
    かつ アプリの DB に Facebook のトークンが保存されていない
    かつ 本番サイトの DB に Facebook のトークンが平文で保存されていない

  @stage:provision @stub-isolation:facebook @slow @timeout:900000 @mode:serial
  シナリオ: Facebook を切断すると、本番サイトの wp letsblog sns status が未設定に戻る
    前提 Facebook 接続検証用に本番サイトを持つプロジェクトを構築しておく
    かつ Facebook 接続検証のプロジェクトで Facebook ページ"Let's Blog E2E ページ"を接続しておく
    もし SNS 告知設定画面で Facebook を切断する
    ならば SNS 告知設定画面の Facebook の接続状態は"未接続"と表示される
    かつ 本番サイトの wp letsblog sns status が Facebook の未設定を返す

  @stage:provision @stub-isolation:facebook @slow @timeout:900000 @mode:serial
  シナリオ: テスト投稿が選んだページのフィードに届き、告知履歴に成功として表示される
    前提 Facebook 接続検証用に本番サイトを持つプロジェクトを構築しておく
    かつ Facebook 接続検証のプロジェクトで Facebook ページ"Let's Blog E2E ページ"を接続しておく
    もし SNS 告知設定画面で Facebook の投稿テストを行う
    ならば Facebook のスタブの選んだページのフィードにテスト投稿が1回だけ届いている
    かつ Facebook の告知履歴にテスト投稿が成功として表示される

  @stage:provision @stub-isolation:facebook @slow @timeout:900000 @mode:serial
  シナリオ: 記事を公開すると、Facebook ページのフィードにもタイトルとリンクが1回だけ投稿される
    前提 Facebook 接続検証用に本番サイトを持つプロジェクトを構築しておく
    かつ Facebook 接続検証のプロジェクトで Facebook ページ"Let's Blog E2E ページ"を接続しておく
    もし Facebook 接続検証の本番サイトへ Let's Blog から記事を即時公開する
    ならば Facebook のスタブの選んだページのフィードにタイトルとリンクが1回だけ投稿されている
    かつ Facebook の告知履歴に記事の公開が成功として記録されている

  @stage:provision @stub-isolation:facebook @slow @timeout:900000 @mode:serial
  シナリオ: 投稿の権限が無いページを選んだときは、投稿せず理由が告知履歴に残る
    前提 Facebook 接続検証用に本番サイトを持つプロジェクトを構築しておく
    かつ Facebook 接続検証のプロジェクトで Facebook ページ"権限のないページ"を接続しておく
    もし SNS 告知設定画面で Facebook の投稿テストを行う
    ならば Facebook のスタブには何も投稿されていない
    かつ Facebook の告知履歴に失敗の理由として"pages_manage_posts"が表示される

  @stage:provision @stub-isolation:facebook @slow @timeout:900000 @mode:serial
  シナリオ: ページのトークンが失効しているときは、投稿せず理由が告知履歴に残り、要再接続になる
    前提 Facebook 接続検証用に本番サイトを持つプロジェクトを構築しておく
    かつ Facebook 接続検証のプロジェクトで Facebook ページ"Let's Blog E2E ページ"を接続しておく
    かつ Facebook 接続検証の本番サイトのページのトークンが失効している
    もし SNS 告知設定画面で Facebook の投稿テストを行う
    ならば Facebook のスタブには何も投稿されていない
    かつ Facebook の告知履歴に失敗の理由として"Session has expired"が表示される
    かつ SNS 告知設定画面の Facebook の接続状態は"要再接続"と表示される
