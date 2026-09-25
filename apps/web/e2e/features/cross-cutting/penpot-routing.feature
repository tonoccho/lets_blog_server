# language: ja
@cross-cutting @api
機能: Penpot のサブパス中継

  reverse-proxy が `/penpot` を Penpot へ中継する経路を固定する(issue #1012)。
  docs/DOCUMENTATION.md と docs/e2e-validation-guide.md は https://localhost/penpot を
  Penpot の入口として案内しているが、`default.conf` の宛先は実在しないホスト名
  `penpot:80` を指しており、常に 502 を返していた。

  この誤りは起動時には現れない。`proxy_pass` の宛先を変数で書くと nginx は設定読み込み時
  ではなくリクエストごとに名前解決するため、`nginx -t` も起動も成功してしまう。
  だからこの経路は「設定が読めること」ではなく「実際に中継できること」で確かめる。

  シナリオ: /penpot が Penpot に到達する
    もし 未認証で Penpot の入口を開く
    ならば その応答は中継の失敗ではない
    かつ その応答は Penpot 自身が返している

  シナリオ: Penpot のログイン画面が同じ経路で成立する
    もし 未認証で Penpot の入口を開く
    ならば Penpot のログイン画面の HTML が返る
    かつ ログイン画面が読み込むアセットも同じ経路で取得できる

  シナリオ: reverse-proxy が中継先の名前解決に失敗しない
    もし 未認証で Penpot の入口を開く
    ならば その間に reverse-proxy は名前解決に失敗していない

  シナリオ: reverse-proxy の全ての中継先へ到達できる
    もし 稼働中の reverse-proxy から中継先を全て取り出す
    ならば その全てが名前解決でき TCP 接続もできる
