# language: ja
@logging @timeout:300000
機能: フロントエンドのエラーログ

  「画面側の異常が記録され、記録が落ちたら気付ける」を固定する(issue #941 / AT-15)。

  ## なぜブラウザを通すのか(#791 の退行検知)

  `POST /api/logs/errors` を `request` から直接叩けば、記録から読み取りまでは通る。
  だが #791 で落ちたのはそこではない。**ブラウザがどこへ送っているか**が落ちていた。

  #772 で log-writer に認証ゲート(ADR-0008)が戻るまで、`lib/errorLogger.ts` は
  ブラウザから gateway の `/api/logs/errors` を直接叩いており、Authorization ヘッダーが
  無かった。ゲート復元でこのPOSTは401になり、error boundary 5本の try/catch が
  失敗を握りつぶすため、**UIは壊れないままエラーログだけが無言で全滅した**。

  修正(#791 / コミット 03cb334f)は送信経路そのものを変えている。

    ブラウザ → `/client-errors`(同一オリジンのBFF。apps/web/src/app/client-errors/route.ts)
             → セッション確認 → apiClient 経由で Bearer 付きで log-writer へ中継

  したがって退行を検知するには、**製品自身の error boundary を実際に発火させ、
  ブラウザが出したリクエストの宛先を見る**しかない。ここは `page.evaluate` で
  `/client-errors` を叩いたりせず、ダッシュボードの稼働状況パネルへ想定外の形の
  ペイロードを配って描画時の `TypeError` を起こし、`app/error.tsx` の
  `logErrorToBackend()` を本物の経路で走らせる(詳細は steps/logging.steps.ts の
  `triggerClientSideError` を参照)。

  シナリオ: 画面でクライアント側エラーが起きると、そのエラーが読み取りAPIで取得できる
    前提 管理者としてログインする
    もし 画面でクライアント側エラーを発生させる
    ならば そのエラーがエラーログ取得APIに現れるまで待つ
    かつ そのエラーログには発生画面のURLと操作者が記録されている

  # このシナリオは #791 の修正を戻すと落ちる(退行検知)。
  シナリオ: エラーログの送信は認証済みの経路で行われ、認証ゲートが有効でも欠落しない
    前提 log-writerのエラーログ記録APIは未認証では受け付けない
    かつ 管理者としてログインする
    もし ブラウザの送信先を記録しながら、画面でクライアント側エラーを発生させる
    ならば ブラウザは同一オリジンのBFFへ送信し、gatewayのエラーログAPIを直接は叩かない
    かつ そのエラーがエラーログ取得APIに現れるまで待つ
