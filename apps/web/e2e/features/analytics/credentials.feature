# language: ja
@analytics @stub @stub-isolation:analytics
機能: Google Analytics / AdSense 資格情報の管理

  プロジェクト単位に Google の資格情報を預けて、ダッシュボードに自分の計測データと
  収益を出せるようにする(issue #939 / AT-13、AC-ANA-001 / AC-ANA-003 / AC-ANA-004 /
  AC-ANA-005)。

  ## 実 Google を叩かない

  向き先は `docker-compose.e2e-stubs.yml` が `ga-stub` / `adsense-stub` へ差し替える
  (docs/ACCEPTANCE_TESTING.md §9)。したがって全シナリオが `@stub` である。
  スタブが起動していなければスキップではなく失敗する(#843 の再発防止)。

  Google Analytics は #1231 でサービスアカウントJSONからユーザーOAuth(AdSense と同じ
  3-legged)へ移行した。トークン交換は `GOOGLE_ANALYTICS_OAUTH_TOKEN_URI`、プロパティ一覧は
  `GOOGLE_ANALYTICS_ADMIN_API_BASE_URL` が `ga-stub` を指す(docs/ACCEPTANCE_TESTING.md §9)。

  ## 秘密情報を後から読み出せないこと

  #939 の受け入れ基準が名指ししている。「マスク表示になっている」ではなく
  **保存した値そのものが、画面のHTMLにもAPI応答にも現れない**ことを確かめる。
  そのために、登録するクライアントシークレットには**そのシナリオ限りの目印**を
  埋め込み、その目印と、スタブが返すリフレッシュトークンを探す。目印が見つからないことが「読み出せない」ことの証拠になる。

  ## OAuth のコールバック2本を `@api` にする理由

  AdSense / Google Analytics のOAuthコールバックが通る経路には、ブラウザから到達できない区間がある。連携の起点
  (`/connect/adsense/start`)は **accounts.google.com へリダイレクトする**ので、
  ブラウザで踏むと実 Google の同意画面へ出ていく。同意画面はスタブ化の対象外
  (#928 が置き換えたのはトークン交換とレポートAPIだけ)なので、認可コードを
  ブラウザ経路で手に入れる方法がそもそも無い。したがって認可コードの交換は
  `POST /api/projects/{id}/api-keys/adsense/oauth-callback` を直接呼んで確かめる。
  これは Next.js の Route Handler がサーバー間で呼ぶのと同じ呼び方である。

  `state` の照合は Next.js 側(`/connect/adsense/callback` の Route Handler)にあり、
  ログイン済みのブラウザセッションが要る。ここでは**未認証のコールバックが
  認可コードを処理しない**ことまでを見て、cookie と `state` の不一致そのものは
  `apps/web/src/app/connect/adsense/__tests__/callback-route.test.ts` が担当する
  (docs/ACCEPTANCE_TESTING.md §4 の `@api` 例外を明示的に使っている)。

  シナリオ: Google Analytics のOAuthクライアントを保存すると、Googleアカウントとの連携を始めるリンクが現れる
    前提 管理者としてログインする
    かつ Analytics を確かめるためのプロジェクトがある
    もし そのプロジェクトのGoogle Analytics設定でOAuthクライアントを保存する
    ならば 保存しましたと表示される
    かつ Google Analytics設定の状態に「未設定」と表示される
    かつ Google Analytics設定にクライアントシークレットが設定済みとして表示される
    かつ Google Analytics設定にサービスアカウントの入力欄は無い
    かつ Google Analyticsの連携リンクの遷移先はanalytics.readonlyだけを要求するGoogleの認可URLである

  @api
  シナリオ: Google Analytics の認可コードをコールバックで受け取ると、リフレッシュトークンが保存され連携済みになる
    前提 Analytics を確かめるためのプロジェクトがある
    かつ そのプロジェクトにGoogle AnalyticsのOAuthクライアントが登録されている
    もし 認可コード「at1231-authorization-code」でGoogle AnalyticsのOAuth連携を完了する
    ならば Google Analyticsは連携済みでプロパティは未選択になる

  シナリオ: 連携済みのプロジェクトでは、アクセスできるGA4プロパティが一覧に出て、選んで保存できる
    前提 管理者としてログインする
    かつ Analytics を確かめるためのプロジェクトがある
    かつ そのプロジェクトにGoogle Analyticsが連携済みでプロパティは未選択である
    もし Google Analytics設定の画面を開き直す
    ならば Google Analytics設定の状態に「連携済み(プロパティ未選択)」と表示される
    かつ プロパティの一覧に表示名「E2E Stub Site」とプロパティID「987654321」がある
    かつ プロパティの一覧に表示名「E2E Stub Second Site」とプロパティID「555000111」がある
    もし プロパティ「987654321」を選んで保存する
    ならば Google Analytics設定の状態に「連携済み(プロパティID: 987654321)」と表示される
    かつ Google Analytics設定のAPI応答は設定済みでプロパティIDが「987654321」である

  シナリオ: 登録済みの資格情報は、画面にもAPI応答にも平文で再表示されない
    前提 管理者としてログインする
    かつ Analytics を確かめるためのプロジェクトがある
    かつ そのプロジェクトにGoogle AnalyticsとAdSenseの資格情報が登録されている
    もし Google Analytics設定の画面を開き直す
    ならば 画面にGoogle Analyticsのクライアントシークレットとリフレッシュトークンは現れない
    かつ Google Analytics設定のAPI応答にクライアントシークレットもリフレッシュトークンも含まれない
    もし Google AdSense設定の画面を開き直す
    ならば 画面にクライアントシークレットは現れない
    かつ Google AdSense設定のAPI応答にクライアントシークレットもリフレッシュトークンも含まれない

  シナリオ: Google Analytics の連携を解除すると、未設定状態に戻る
    前提 管理者としてログインする
    かつ Analytics を確かめるためのプロジェクトがある
    かつ そのプロジェクトにGoogle Analyticsの資格情報が登録されている
    もし そのプロジェクトのGoogle Analytics設定で連携を解除する
    ならば Google Analytics設定の状態に「未設定」と表示される
    かつ Google Analytics設定のAPI応答は未設定を示す
    もし そのプロジェクトのダッシュボードを開く
    ならば Google Analyticsのパネルに設定画面への導線が表示される

  @api
  シナリオ: Google Analytics の不正な認可コードは拒否され、連携済みにならない
    前提 Analytics を確かめるためのプロジェクトがある
    かつ そのプロジェクトにGoogle AnalyticsのOAuthクライアントが登録されている
    もし 認可コード「e2e-stub-invalid-code」でGoogle AnalyticsのOAuth連携を完了しようとする
    ならば OAuth連携は拒否される
    かつ Google Analyticsは連携済みにならない
    もし 認証なしでGoogle AnalyticsのOAuthコールバックURLを開く
    ならば 認可コードは処理されず、ログイン画面へ戻される

  シナリオ: AdSense のパブリッシャーIDとOAuthクライアントを登録できる
    前提 管理者としてログインする
    かつ Analytics を確かめるためのプロジェクトがある
    もし そのプロジェクトのGoogle AdSense設定でパブリッシャーIDとOAuthクライアントを保存する
    ならば 保存しましたと表示される
    かつ Google AdSense設定にクライアントシークレットが設定済みとして表示される
    かつ Googleアカウントとの連携を始めるリンクが現れる

  @api
  シナリオ: AdSense の認可コードをコールバックで受け取ると、リフレッシュトークンが保存される
    前提 Analytics を確かめるためのプロジェクトがある
    かつ そのプロジェクトにAdSenseのパブリッシャーIDとOAuthクライアントが登録されている
    もし 認可コード「at13-authorization-code」でAdSenseのOAuth連携を完了する
    ならば AdSenseは連携済みになる
    かつ 保存されたリフレッシュトークンでAdSenseのレポートを取得できる

  @api
  シナリオ: 不正な認可コードや不正なstateのコールバックは拒否される
    前提 Analytics を確かめるためのプロジェクトがある
    かつ そのプロジェクトにAdSenseのパブリッシャーIDとOAuthクライアントが登録されている
    もし 認可コード「e2e-stub-invalid-code」でAdSenseのOAuth連携を完了しようとする
    ならば OAuth連携は拒否される
    かつ AdSenseは連携済みにならない
    もし 認証なしでAdSenseのOAuthコールバックURLを開く
    ならば 認可コードは処理されず、ログイン画面へ戻される

  # ## パブリッシャーIDの自動取得(#1232)
  #
  # 手入力を不要にする。OAuthクライアントだけを保存して連携すると、連携したGoogleアカウントが
  # 利用できるAdSenseアカウント(accounts.list)からパブリッシャーIDが決まる。
  # adsense-stub は accounts.list を認可コードで切り替える(docs/ACCEPTANCE_TESTING.md §9):
  # 既定=1件 / `e2e-stub-adsense-multi-accounts-code`=2件 / `e2e-stub-adsense-accounts-error-code`=取得失敗(403)。

  シナリオ: AdSense のパブリッシャーIDを空のままOAuthクライアントだけを保存でき、連携リンクが現れる
    前提 管理者としてログインする
    かつ Analytics を確かめるためのプロジェクトがある
    もし そのプロジェクトのGoogle AdSense設定でパブリッシャーIDを空のままOAuthクライアントを保存する
    ならば 保存しましたと表示される
    かつ Google AdSense設定のパブリッシャーID欄は空である
    かつ Google AdSense設定にクライアントシークレットが設定済みとして表示される
    かつ Googleアカウントとの連携を始めるリンクが現れる

  @api
  シナリオ: AdSense のアカウントが1件なら、認可コードのコールバックでパブリッシャーIDが自動で保存される
    前提 Analytics を確かめるためのプロジェクトがある
    かつ そのプロジェクトにパブリッシャーIDなしでAdSenseのOAuthクライアントが登録されている
    もし 認可コード「at1232-single-account-code」でAdSenseのOAuth連携を完了する
    ならば AdSenseは連携済みで保存されたパブリッシャーIDは「pub-1234567890123456」である
    かつ 保存されたリフレッシュトークンでAdSenseのレポートを取得できる

  シナリオ: 自動で取得したパブリッシャーIDが設定画面に表示され、ダッシュボードのウィジェットが収益レポートを表示する
    前提 管理者としてログインする
    かつ Analytics を確かめるためのプロジェクトがある
    かつ そのプロジェクトにパブリッシャーIDなしでAdSenseのOAuthクライアントが登録されている
    かつ 認可コード「at1232-single-account-code」でAdSenseのOAuth連携を完了する
    もし Google AdSense設定の画面を開き直す
    ならば Google AdSense設定の状態に「連携済み(パブリッシャーID: pub-1234567890123456)」と表示される
    もし そのプロジェクトのダッシュボードを開く
    ならば Google AdSenseのパネルに「推定収益」として「12.34」が表示される
    かつ Google AdSenseのパネルに日次推移とプラットフォーム別内訳が表示される

  シナリオ: AdSense のアカウントが複数あるときは、一覧から選んで保存できる
    前提 管理者としてログインする
    かつ Analytics を確かめるためのプロジェクトがある
    かつ そのプロジェクトにパブリッシャーIDなしでAdSenseのOAuthクライアントが登録されている
    かつ 認可コード「e2e-stub-adsense-multi-accounts-code」でAdSenseのOAuth連携を完了する
    もし Google AdSense設定の画面を開き直す
    ならば Google AdSense設定の状態に「連携済み(パブリッシャーID未設定)」と表示される
    かつ AdSenseのアカウント一覧に表示名「E2E Stub Publisher」とパブリッシャーID「pub-1234567890123456」がある
    かつ AdSenseのアカウント一覧に表示名「E2E Stub Second Publisher」とパブリッシャーID「pub-2222222222222222」がある
    もし パブリッシャーID「pub-2222222222222222」を一覧から選んで保存する
    ならば Google AdSense設定の状態に「連携済み(パブリッシャーID: pub-2222222222222222)」と表示される
    かつ AdSenseの保存されたパブリッシャーIDは「pub-2222222222222222」である

  シナリオ: AdSense のアカウント一覧を取得できなくても、リフレッシュトークンは残り、手入力で連携を完了できる
    前提 管理者としてログインする
    かつ Analytics を確かめるためのプロジェクトがある
    かつ そのプロジェクトにパブリッシャーIDなしでAdSenseのOAuthクライアントが登録されている
    かつ 認可コード「e2e-stub-adsense-accounts-error-code」でAdSenseのOAuth連携を完了する
    ならば AdSenseはGoogleアカウントと連携済みだがパブリッシャーIDは未取得である
    もし Google AdSense設定の画面を開き直す
    ならば AdSenseのアカウント一覧を取得できなかった理由が表示される
    かつ Google AdSense設定の状態に「連携済み(パブリッシャーID未設定)」と表示される
    もし パブリッシャーID「pub-9999999999999999」を手入力して保存する
    ならば Google AdSense設定の状態に「連携済み(パブリッシャーID: pub-9999999999999999)」と表示される
    かつ AdSenseの保存されたパブリッシャーIDは「pub-9999999999999999」である
    かつ 保存されたリフレッシュトークンでAdSenseのレポートを取得できる
