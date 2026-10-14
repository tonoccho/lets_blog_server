# language: ja
@stub @api @mode:serial
機能: extensions ポータルのスタブ

  lets_blog_server_extensions のポータルが返す `GET /api/connections` を、受け入れテスト用に
  決定的に返すスタブ(issue #1782)。実物の extensions を AT 環境で動かさず、接続先の取り込み(#1783)を
  検証するために、応答の中身と異常系(5xx・遅延・壊れた本文)をテストから制御できる。

  既定の応答は extensions の既定値(BASE_DOMAIN=lbx.localhost、HTTPS_PORT=8443)で
  buildConnections が返すものと同じ5要素である。スタブは lbs-net 上で `lbx-portal:3100` として届く。
  応答の本文と注入の状態はこのスタブ全体で共有されるので、直列に走らせる。

  背景:
    前提 ポータルスタブが起動していて初期状態である

  シナリオ: 既定の応答は extensions の既定値と同じ5要素である
    もし ポータルスタブの GET /api/connections を呼ぶ
    ならば ステータスは 200 で Content-Type は application/json である
    かつ 応答は plantuml・comfyui・ollama・penpot・gitlab の順の5要素である
    かつ 各要素は extensions の既定値と同じ項目と値を持つ
    かつ ollama だけが openaiBaseUrl「http://lbx-ollama:11434/v1」を持つ

  シナリオ: 同じ呼び出しには常に同じ応答を返す
    もし ポータルスタブの GET /api/connections を3回呼ぶ
    ならば 3回とも同一の応答である

  シナリオ: 制御エンドポイントで応答本文を差し替えられる
    前提 ポータルスタブの応答を ollama の internalUrl を「http://llm-stub:8080」にした5要素へ差し替える
    もし ポータルスタブの GET /api/connections を呼ぶ
    ならば 応答の ollama の internalUrl は「http://llm-stub:8080」である

  シナリオ: JSON でない本文にも差し替えられる
    前提 ポータルスタブの応答を本文「<html>not json</html>」に差し替える
    もし ポータルスタブの GET /api/connections を呼ぶ
    ならば 応答本文は「<html>not json</html>」である
    かつ 応答本文は JSON として読めない

  シナリオ: 配列でない JSON にも差し替えられる
    前提 ポータルスタブの応答を JSON「{"connections":[]}」に差し替える
    もし ポータルスタブの GET /api/connections を呼ぶ
    ならば 応答本文は JSON「{"connections":[]}」である

  シナリオ: リセットすると既定の5要素に戻る
    前提 ポータルスタブの応答を本文「<html>not json</html>」に差し替える
    もし ポータルスタブをリセットする
    かつ ポータルスタブの GET /api/connections を呼ぶ
    ならば 応答は plantuml・comfyui・ollama・penpot・gitlab の順の5要素である

  シナリオ: ステータス 500 を仕込むと次の呼び出しが 500 になる
    前提 ポータルスタブへ次の1回を 500 にする指示を出す
    もし ポータルスタブの GET /api/connections を呼ぶ
    ならば ステータスは 500 である
    もし ポータルスタブの GET /api/connections を呼ぶ
    ならば ステータスは 200 で Content-Type は application/json である

  シナリオ: 遅延を仕込むと次の呼び出しが指定時間だけ遅れる
    前提 ポータルスタブへ次の1回を 400 ミリ秒遅らせる指示を出す
    もし ポータルスタブの GET /api/connections を呼び応答を待つ
    ならば 呼び出しは 400 ミリ秒以上経ってから応答なしで終わる

  シナリオ: 呼ばれた回数を状態の確認で読める
    もし ポータルスタブの GET /api/connections を3回呼ぶ
    ならば ポータルスタブの状態の受信件数は 3 である
    かつ ポータルスタブの状態の GET /api/connections の受信件数は 3 である
