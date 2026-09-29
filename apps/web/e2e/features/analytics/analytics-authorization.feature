# language: ja
@analytics @api @stub-isolation:analytics
機能: Analytics 資格情報の認可

  他人の Google の資格情報とレポートが、誰からでも読み書きできる状態になっていないことを
  固定する(issue #939 / AT-13、AC-ANA-001 / AC-ANA-003)。

  ## `@stub-isolation:analytics` の理由(issue #1372)

  2番目のシナリオは対照実験として`putAdSenseClient` / `connectGoogleAnalyticsFor`で
  `ga-stub` / `adsense-stub`へ実際にOAuthトークン交換を行う。1番目のシナリオは
  スタブへは触れないが、`@analytics`が付いた全シナリオの後に無条件で走る
  `analytics.steps.ts`の`After({ tags: '@analytics' })`は本ファイルの両シナリオにも
  掛かり、`resetStub('google-analytics')` / `resetStub('adsense')`でスタブ全体の
  状態を消す。並行して走る`analytics/report-failures.feature`の仕込みを消しうるため、
  このファイル全体を`@stub-isolation:analytics`の対象にした。詳細は
  `analytics/report-failures.feature`と`apps/web/playwright.config.ts`の
  `atAnalyticsExclusive`のコメントを参照。

  ## 401 と 403 を分けて見る

  `diagram-authorization.feature` と同じ理由による。401 は analytics-service の
  SecurityConfig(認証ゲート、ADR-0008)、403 は `AdminAuthorizationService` の
  プロジェクトメンバー判定(#830)で、担っている層が違う。片方だけを見ると、
  もう片方が外れたときに素通りする。

  ## 「他プロジェクト」を非メンバーで代用しないこと

  一般利用者を**片方のプロジェクトのメンバーにしてから**確かめる。そうしないと
  「非adminは何も触れない」だけを見ていることになり、プロジェクト単位で仕切られて
  いるかどうかは分からない。自分のプロジェクトの資格情報は読み書きできる(対照)ことも
  同じシナリオで確かめる。

  ## 対象は11本すべて

  資格情報のエンドポイントは google-analytics の6本(GET / DELETE / `client` / `oauth-callback` /
  `properties` / `property`、#1231 でOAuth化)と、adsense の5本(GET / PUT / DELETE /
  `client-secret` / `oauth-callback`)がある。
  1本でも認可が外れれば秘密情報が漏れるので、まとめて確かめる。
  ダッシュボードの2本も同じ判定を通るため併せて見る。

  シナリオ: 未認証では資格情報の読み書きができない
    もし 認証なしでAnalyticsの資格情報エンドポイントをすべて要求する
    ならば すべて認証が必要だとして拒否される
    もし 認証なしでAnalyticsのダッシュボードを要求する
    ならば すべて認証が必要だとして拒否される

  シナリオ: 自分がメンバーでないプロジェクトの資格情報は読み書きできない
    前提 Analyticsを確かめるプロジェクトが2つあり、一般利用者は片方だけのメンバーである
    ならば 一般利用者は自分のプロジェクトの資格情報を読み書きできる
    もし 一般利用者が他プロジェクトのAnalyticsの資格情報エンドポイントをすべて要求する
    ならば すべてプロジェクトメンバーではないとして拒否される
    もし 一般利用者が他プロジェクトのAnalyticsのダッシュボードを要求する
    ならば すべてプロジェクトメンバーではないとして拒否される
    かつ 他プロジェクトの資格情報は書き換えられていない
