# language: ja
@project
機能: プロジェクト設定画面での LinkedIn の接続と、本番サイトのプラグインからの告知

  プロジェクト設定画面の「LinkedIn」欄で、接続した LinkedIn メンバー本人のプロフィールを SNS 告知先として
  OAuth 2.0 で接続・切断できること。得られたアクセストークン・メンバー ID・有効期限がその場で本番サイトの
  letsblog プラグインへ `wp letsblog sns config set` で送られ、アプリにも、プラグインの DB にも、画面・API の
  応答にも平文で残らないこと(Client Secret はプラグインへ送らない)。テスト投稿と記事の公開時の告知が
  LinkedIn の API スタブへ、接続したメンバー(`urn:li:person:<sub>`)を投稿者として、記事のリンク付きで1回だけ届き、
  告知履歴に残ること。LinkedIn のアクセストークンは60日で切れ、更新できない(プラグインは更新しない)ので、
  期限切れ、または API が 401 を返すときは投稿せず、その理由が告知履歴に残り、状態が「要再接続」になること
  (issue #1581。Epic #1572。プラグイン側は #1573・#1575、アプリ側の接続は X の #1574 と同じ方式)。

  LinkedIn の認可画面と API は `linkedin-stub` で代替する(docs/ACCEPTANCE_TESTING.md の外部依存スタブ)。
  アプリ(project-service)は LINKEDIN_API_BASE_URL / LINKEDIN_TOKEN_URL / LINKEDIN_AUTHORIZE_URL で、プラグインは
  wp-config.php の定数 LETSBLOG_LINKEDIN_API_BASE_URL でスタブへ向ける。フィクスチャは自動構築(マネージド)の
  WordPress サイトで、サイトの状態の確認と細工は `docker exec lbs-wordpress wp ...` で行う。

  背景:
    前提 管理者としてログインする

  @stage:provision @stub-isolation:linkedin @slow @timeout:900000 @mode:serial
  シナリオ: LinkedIn を接続すると本番サイトの wp letsblog sns status に反映され、トークンはどこにも平文で残らない
    前提 LinkedIn 接続検証用に本番サイトを持つプロジェクトを構築しておく
    もし LinkedIn 接続検証のプロジェクトの SNS 告知設定画面で OAuth のアプリ情報を入力して LinkedIn と接続する
    ならば SNS 告知設定画面の LinkedIn にアカウント"Let's Blog E2E"の接続済みが表示される
    かつ 本番サイトの wp letsblog sns status が LinkedIn の接続済みを返す
    かつ アプリの API の応答に LinkedIn のトークンが含まれない
    かつ アプリの DB に LinkedIn のトークンが保存されていない
    かつ 本番サイトの DB に LinkedIn のトークンが平文で保存されていない

  @stage:provision @stub-isolation:linkedin @slow @timeout:900000 @mode:serial
  シナリオ: LinkedIn を切断すると、本番サイトの wp letsblog sns status が未設定に戻る
    前提 LinkedIn 接続検証用に本番サイトを持つプロジェクトを構築しておく
    かつ LinkedIn 接続検証のプロジェクトで LinkedIn を接続しておく
    もし SNS 告知設定画面で LinkedIn を切断する
    ならば SNS 告知設定画面の LinkedIn の接続状態は"未接続"と表示される
    かつ 本番サイトの wp letsblog sns status が LinkedIn の未設定を返す

  @stage:provision @stub-isolation:linkedin @slow @timeout:900000 @mode:serial
  シナリオ: テスト投稿が接続したメンバーを投稿者として LinkedIn のスタブに届き、告知履歴に成功として表示される
    前提 LinkedIn 接続検証用に本番サイトを持つプロジェクトを構築しておく
    かつ LinkedIn 接続検証のプロジェクトで LinkedIn を接続しておく
    もし SNS 告知設定画面で LinkedIn の投稿テストを行う
    ならば LinkedIn のスタブにテスト投稿が1回だけ届いている
    かつ LinkedIn の告知履歴にテスト投稿が成功として表示される

  @stage:provision @stub-isolation:linkedin @slow @timeout:900000 @mode:serial
  シナリオ: 記事を公開すると、LinkedIn のスタブにもタイトルと記事の URL が1回だけ投稿される
    前提 LinkedIn 接続検証用に本番サイトを持つプロジェクトを構築しておく
    かつ LinkedIn 接続検証のプロジェクトで LinkedIn を接続しておく
    もし LinkedIn 接続検証の本番サイトへ Let's Blog から記事を即時公開する
    ならば LinkedIn のスタブにタイトルと URL が1回だけ投稿されている
    かつ LinkedIn の告知履歴に記事の公開が成功として記録されている

  @stage:provision @stub-isolation:linkedin @slow @timeout:900000 @mode:serial
  シナリオ: 有効期限を過ぎたトークンでは投稿せず、理由が告知履歴に残り、要再接続になる
    前提 LinkedIn 接続検証用に本番サイトを持つプロジェクトを構築しておく
    かつ LinkedIn 接続検証のプロジェクトで LinkedIn を接続しておく
    かつ LinkedIn 接続検証の本番サイトのトークンが期限切れになっている
    もし SNS 告知設定画面で LinkedIn の投稿テストを行う
    ならば LinkedIn のスタブには何も投稿されていない
    かつ LinkedIn の告知履歴に失敗の理由として"期限切れ"が表示される
    かつ SNS 告知設定画面の LinkedIn の接続状態は"要再接続"と表示される

  @stage:provision @stub-isolation:linkedin @slow @timeout:900000 @mode:serial
  シナリオ: LinkedIn の API が 401 を返すときは投稿せず、理由が告知履歴に残り、要再接続になる
    前提 LinkedIn 接続検証用に本番サイトを持つプロジェクトを構築しておく
    かつ LinkedIn 接続検証のプロジェクトで LinkedIn を接続しておく
    かつ LinkedIn 接続検証の本番サイトのトークンは期限内だが LinkedIn に拒否される
    もし SNS 告知設定画面で LinkedIn の投稿テストを行う
    ならば LinkedIn のスタブには何も投稿されていない
    かつ LinkedIn の告知履歴に失敗の理由として"401"が表示される
    かつ SNS 告知設定画面の LinkedIn の接続状態は"要再接続"と表示される
