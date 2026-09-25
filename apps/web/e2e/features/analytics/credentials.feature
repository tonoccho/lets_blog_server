# language: ja
@analytics @stub
機能: Google Analytics / AdSense 資格情報の管理

  プロジェクト単位に Google の資格情報を預けて、ダッシュボードに自分の計測データと
  収益を出せるようにする(issue #939 / AT-13、AC-ANA-001 / AC-ANA-003 / AC-ANA-004 /
  AC-ANA-005)。

  ## 実 Google を叩かない

  向き先は `docker-compose.e2e-stubs.yml` が `ga-stub` / `adsense-stub` へ差し替える
  (docs/ACCEPTANCE_TESTING.md §9)。したがって全シナリオが `@stub` である。
  スタブが起動していなければスキップではなく失敗する(#843 の再発防止)。

  サービスアカウントJSONに `token_uri` は**書かない**。書くと
  `GOOGLE_ANALYTICS_OAUTH_TOKEN_URI` より優先され、実 Google へ出ていく
  (docs/ACCEPTANCE_TESTING.md §9 の注意書き)。

  ## 秘密情報を後から読み出せないこと(シナリオ2)

  #939 の受け入れ基準が名指ししている。「マスク表示になっている」ではなく
  **保存した値そのものが、画面のHTMLにもAPI応答にも現れない**ことを確かめる。
  そのために、登録する秘密鍵とクライアントシークレットには**そのシナリオ限りの目印**を
  埋め込み、その目印を探す。目印が見つからないことが「読み出せない」ことの証拠になる。

  ## OAuth のコールバック2本を `@api` にする理由

  シナリオ5・6が通る経路には、ブラウザから到達できない区間がある。連携の起点
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

  シナリオ: Google Analytics の資格情報を登録すると、設定済みとして表示される
    前提 管理者としてログインする
    かつ Analytics を確かめるためのプロジェクトがある
    もし そのプロジェクトのGoogle Analytics設定でプロパティID「987654321」とサービスアカウントのJSON鍵を保存する
    ならば 保存しましたと表示される
    かつ Google Analytics設定の状態に「設定済み(プロパティID: 987654321)」と表示される

  シナリオ: 登録済みの資格情報は、画面にもAPI応答にも平文で再表示されない
    前提 管理者としてログインする
    かつ Analytics を確かめるためのプロジェクトがある
    かつ そのプロジェクトにGoogle AnalyticsとAdSenseの資格情報が登録されている
    もし Google Analytics設定の画面を開き直す
    ならば 画面にサービスアカウントの秘密鍵は現れない
    かつ Google Analytics設定のAPI応答にサービスアカウントの秘密鍵は含まれない
    もし Google AdSense設定の画面を開き直す
    ならば 画面にクライアントシークレットは現れない
    かつ Google AdSense設定のAPI応答にクライアントシークレットもリフレッシュトークンも含まれない

  シナリオ: Google Analytics の資格情報を削除すると、未設定状態に戻る
    前提 管理者としてログインする
    かつ Analytics を確かめるためのプロジェクトがある
    かつ そのプロジェクトにGoogle Analyticsの資格情報が登録されている
    もし そのプロジェクトのGoogle Analytics設定を削除する
    ならば Google Analytics設定の状態に「未設定」と表示される
    かつ Google Analytics設定のAPI応答は未設定を示す

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
