# language: ja
@stub @api @destructive @mode:serial
機能: PlantUML のスタブ

  受け入れテストは extensions のツールをすべてモックで受ける(issue #1802、docs/ACCEPTANCE_TESTING.md §9)。
  PlantUML は固定の PNG を返すスタブで、`GET /png/<符号化したソース>` を受ける。スタブは符号化
  (独自の base64 と deflate)を復号して受け取ったソースを記録し、決定的な規則でエラーを返す。
  lbs-net 上では `lbx-plantuml:8080` として届く。

  - `@startuml` を含まないソース、復号できないパス: 400 と `X-PlantUML-Diagram-Error`
  - 8192 バイトを超える URI: 414(Node の要求ヘッダー上限 16KB を超える URI も、431 ではなく 414 にする)

  記録と注入の状態はスタブ全体で共有され、reset と注入は他の図のシナリオを巻き込むので、
  `@destructive`(at-main の完了後に単独で走る)にして直列に走らせる。

  背景:
    前提 PlantUMLスタブが起動していて初期状態である

  シナリオ: 妥当なソースには、何度呼んでも同じ固定のPNGが返る
    もし PlantUMLスタブへソース「@startuml\nAlice -> Bob: Hello\n@enduml」を渡す
    かつ PlantUMLスタブへソース「@startuml\nA -> B\nB -> C\nC -> D\n@enduml」を渡す
    ならば どちらの応答もステータス 200 の image/png で、同じバイト列である
    かつ 応答は PNG のシグネチャで始まる

  シナリオ: 受け取ったソースを復号して状態から読める
    もし PlantUMLスタブへソース「@startuml\nAlice -> Bob: こんにちは\n@enduml」を渡す
    ならば PlantUMLスタブの状態の直近のソースは「@startuml\nAlice -> Bob: こんにちは\n@enduml」である
    かつ PlantUMLスタブの状態の記録は 1 件である

  シナリオ: 構文の誤ったソースには 400 と X-PlantUML-Diagram-Error が返る
    もし PlantUMLスタブへソース「これはPlantUMLではありません\n???」を渡す
    ならば PlantUMLスタブの応答のステータスは 400 である
    かつ PlantUMLスタブの応答は X-PlantUML-Diagram-Error ヘッダを持つ

  シナリオ: 復号できないパスには 400 が返る
    もし PlantUMLスタブへ符号化されていないパス「!!!」を渡す
    ならば PlantUMLスタブの応答のステータスは 400 である
    かつ PlantUMLスタブの応答は X-PlantUML-Diagram-Error ヘッダを持つ

  シナリオ: 長すぎるURIには 414 が返る
    もし PlantUMLスタブへ 9000 文字のパスを渡す
    ならば PlantUMLスタブの応答のステータスは 414 である

  シナリオ: Node の要求ヘッダー上限を超える巨大なURIにも 414 が返り、431 にはならない
    もし PlantUMLスタブへ 40000 文字のパスを渡す
    ならば PlantUMLスタブの応答のステータスは 414 である

  シナリオ: 制御エンドポイントで次の1回を失敗させられる
    前提 PlantUMLスタブへ次の1回を 503 にする指示を出す
    もし PlantUMLスタブへソース「@startuml\nA\n@enduml」を渡す
    ならば PlantUMLスタブの応答のステータスは 503 である
    もし PlantUMLスタブへソース「@startuml\nA\n@enduml」を渡す
    ならば PlantUMLスタブの応答のステータスは 200 である

  シナリオ: リセットすると記録が消える
    前提 PlantUMLスタブへソース「@startuml\nA\n@enduml」を渡している
    もし PlantUMLスタブをリセットする
    ならば PlantUMLスタブの状態の記録は 0 件である
