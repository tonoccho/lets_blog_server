# language: ja
@cross-cutting @api
機能: reverse-proxy の中継先

  reverse-proxy(nginx)は中継先を `set $upstream_<name> <host>:<port>;` という変数に入れてから
  `proxy_pass` している。nginx は変数を使った宛先を設定読み込み時ではなくリクエストごとに
  名前解決するため、宛先が存在しないホスト名でも `nginx -t` も起動も成功してしまう(issue #1012)。
  だからこの経路は「設定が読めること」ではなく「実際に中継できること」で確かめる。

  Penpot を削除した(issue #1752)ので、`/penpot/` はどこにも中継されない。

  シナリオ: reverse-proxy が中継先の名前解決に失敗しない
    もし 認証なしで web の入口を開く
    ならば その間に reverse-proxy は名前解決に失敗していない

  シナリオ: reverse-proxy の全ての中継先へ到達できる
    もし 稼働中の reverse-proxy から中継先を全て取り出す
    ならば その全てが名前解決でき TCP 接続もできる

  # 未認証の要求は web の認証ゲートがログイン画面へ寄せるので、404 を確かめるには認証済みで開く。
  シナリオ: 削除した Penpot の入口 /penpot/ と /penpot はどこにも中継されず 404 になる
    前提 管理者としてログインする
    もし 管理者として "/penpot/" を開く
    かつ 管理者として "/penpot" を開く
    ならば どちらの応答も 404 で、Penpot の画面ではない
