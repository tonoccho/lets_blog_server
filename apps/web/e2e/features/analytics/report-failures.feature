# language: ja
@analytics @stub @mode:serial @stub-isolation:analytics
機能: Analyticsレポート取得の異常系

  外部SaaSは落ちるし、絞られるし、黙る。そのとき利用者が**何が起きたのか・次に何を
  すればよいのか**を画面から判断できることを固定する(issue #939 / AT-13、
  AC-ANA-002 / AC-ANA-006 の異常系)。

  ## `@mode:serial` の理由、および `@stub-isolation:analytics`(issue #1372)

  下の2つのシナリオはスタブの制御エンドポイント(`POST /__control/force`)で
  エラーを注入する。この経路は**スタブ全体の状態を変える**ので、同じスタブを触る
  シナリオが並列に走ると互いの仕込みを奪い合う(docs/ACCEPTANCE_TESTING.md §9)。
  GA / AdSense スタブへ制御経由で注入するシナリオは、この1ファイルに閉じてあるので
  `@mode:serial`(本ファイル内の直列化)で足りる。

  `@mode:serial`は**同一ファイル内**にしか効かない。`analytics/dashboard-report.feature`
  等**別ファイル**が同じ`ga-stub`/`adsense-stub`へ通常系のリクエストを送ると、仕込んだ
  エラーを横取りされたり(このファイル自身の注入が消費し尽くされて`200`に化ける)、
  逆に通常系のリクエストが横取りされて仕込んだエラーを受け取ったりする(issue #1188で
  `llm-stub`について実測した2つの故障モードと同型、issue #1372で`ga-stub`について
  実測: `POST /__control/force`で仕込んだ直後に別リクエストが割り込むと、割り込んだ側が
  注入を受け取り、このファイル自身の後続リクエストは仕込みを失って`200`が返る)。

  さらに、`analytics.steps.ts`の`After({ tags: '@analytics' })`は**`@analytics`が付いた
  全シナリオの後**に無条件で`resetStub('google-analytics')` / `resetStub('adsense')`を
  呼ぶ。これも仕込みを触っていないシナリオ(例: 資格情報の認可チェックのみのシナリオ)
  であっても、並行して走っていればこのファイルの仕込みを消してしまう(issue #1372で
  実測)。

  これを防ぐのが`@stub-isolation:analytics` — `apps/web/playwright.config.ts`の
  `at-analytics-exclusive`プロジェクト(`workers:1`)が、`ga-stub`/`adsense-stub`へ
  実際にトラフィックを送る、または制御エンドポイントでその共有状態を仕込む/読む
  (前述の`After`フックによる暗黙の読み書きを含む)全ファイルを1レーンへ集めて
  完全直列化する。対象ファイルと詳細は`apps/web/playwright.config.ts`の
  `atAnalyticsExclusive`のコメントと`docs/ACCEPTANCE_TESTING.md` §9を参照。

  ## 失効(401)だけは制御エンドポイントを使わない

  `ga-stub` は、サービスアカウントJSONの `client_email` が `invalid@` で始まるとき
  トークン交換を401にする(docs/ACCEPTANCE_TESTING.md §9「資格情報の不正を再現する」)。
  「失効した資格情報を持っている利用者に何が見えるか」は、まさに**そういう資格情報が
  登録されている状態**であって、スタブ全体を壊した状態ではない。仕込みを残さない分、
  他のシナリオを巻き込まない。

  ## エラーでもHTTPは200である

  `ProjectDashboardController` は失敗を `errorMessage` に載せて 200 で返す
  (`GoogleAnalyticsReportResponse.error`)。ページ全体を落とさずウィジェット内だけを
  エラー表示にするための契約なので、シナリオも「画面が壊れないこと」を併せて見る。

  シナリオ: 資格情報が失効していると、再認証が必要と分かるメッセージが出る
    前提 管理者としてログインする
    かつ Analytics を確かめるためのプロジェクトがある
    かつ そのプロジェクトに失効したGoogle Analyticsの資格情報が登録されている
    もし そのプロジェクトのダッシュボードを開く
    ならば Google Analyticsのパネルに取得失敗が表示される
    かつ 取得失敗の説明から再認証が必要だと分かる
    かつ 取得失敗の説明にGoogleからの生の応答本文は含まれない
    かつ ダッシュボードの見出しは表示され続けている

  シナリオ: 外部APIがレート制限を返したとき、利用者に分かる形で示され画面が壊れない
    前提 管理者としてログインする
    かつ Analytics を確かめるためのプロジェクトがある
    かつ そのプロジェクトにGoogle Analyticsの資格情報が登録されている
    かつ Google Analyticsの外部APIが次の1回だけ「429」を返す
    もし そのプロジェクトのダッシュボードを開く
    ならば Google Analyticsのパネルに取得失敗が表示される
    かつ 取得失敗の説明から呼び出し回数の制限に達したと分かる
    かつ ダッシュボードの見出しは表示され続けている

  # 縮退(#939 シナリオ12)。GA を黙らせても AdSense のパネルは値を出し続ける。
  # 遅延は 8 秒。GA クライアントは読み取りタイムアウトを持たないため、スタブが接続を
  # 切るまで待つ = この秒数がそのままシナリオの待ち時間になる。
  シナリオ: 外部APIがタイムアウトしても、ダッシュボードの他のパネルは表示され続ける
    前提 管理者としてログインする
    かつ Analytics を確かめるためのプロジェクトがある
    かつ そのプロジェクトにGoogle AnalyticsとAdSenseの資格情報が登録されている
    かつ Google Analyticsの外部APIが次の1回だけ「8000」ミリ秒応答しない
    もし そのプロジェクトのダッシュボードを開く
    ならば Google Analyticsのパネルに取得失敗が表示される
    かつ Google AdSenseのパネルに「推定収益」として「12.34」が表示される
    かつ ダッシュボードの見出しは表示され続けている
