# language: ja
@cross-cutting @ui
機能: Penpot のログイン画面と CORS (issue #1093)

  #1012 で `/penpot/` の中継先そのものは直った。しかし penpot-frontend / penpot-backend の
  `PENPOT_PUBLIC_URI` は `http://localhost:9001` のままで、Penpot の SPA はこの絶対URLを
  自分のAPI呼び出し先として `js/config.js` に焼き込む。`https://localhost/penpot/` から見ると
  ブラウザのoriginは `https://localhost` であり、`http://localhost:9001` へのAPI呼び出しは
  スキームもポートも異なるクロスオリジンリクエストになる。penpot-backend は
  `Access-Control-Allow-Origin` を返さないため、プリフライトが失敗し SPA は
  ログイン画面を組み立てられないまま真っ黒になる。

  `PENPOT_PUBLIC_URI` を `https://localhost/penpot` に変えるだけでは直せない。
  そのURLは `http://localhost:9001/` 直接アクセス(ハンドオフURL生成が依存する経路、#1012の
  Out of Scope)にもそのまま焼き込まれ、今度は直接アクセスのほうが自分自身に対する
  クロスオリジンリクエストになって同じ壊れ方をする。したがって `PENPOT_PUBLIC_URI` は
  `http://localhost:9001` のまま据え置き、penpot-frontend の nginx (`/etc/nginx/overrides/`、
  Penpotの公式拡張ポイント)側で `https://localhost` からの `/api` 呼び出しにだけ
  CORSヘッダーを追加する。

  シナリオ: https://localhost/penpot/ を開くとログイン画面が組み上がる
    もし "https://localhost/penpot/" を開く
    ならば ログインフォームのメールアドレス欄とパスワード欄が表示される

  シナリオ: https://localhost/penpot/ を開いている間、コンソールにCORSエラーが出ない
    もし "https://localhost/penpot/" を開く
    ならば ブラウザコンソールにCORSエラーが出ていない

  シナリオ: http://localhost:9001/ への直接アクセスは引き続き成立する
    もし "http://localhost:9001/" を開く
    ならば ログインフォームのメールアドレス欄とパスワード欄が表示される
    かつ ブラウザコンソールにCORSエラーが出ていない
