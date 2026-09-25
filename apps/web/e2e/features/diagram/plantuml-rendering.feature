# language: ja
@diagram @api
機能: PlantUML のレンダリング

  テキストで書いた図を画像にして記事へ載せられることを固定する
  (issue #937 / AT-11、AC-DIAG-004)。

  ## 画面ではなくAPIから確かめる理由(`@api`)

  Web のダイアグラムギャラリーは #662 で削除済みで、図のUIは VSCode 拡張だけにある。
  つまり**この受け入れ基準に対応する画面がそもそも存在しない。** したがって全シナリオを
  `@api` にし、ブラウザのログインを前提に置かない(docs/ACCEPTANCE_TESTING.md §4)。

  ## gateway ではなく lbs-net の中から呼ぶ理由

  `/api/render/**` は gateway のルート表に**載っていない**(issue #830。
  `services/gateway/.../application.yml` の「`/api/render/**` は載せない」)。
  content-service / publishing-service が `app.media-service-uri` へコンテナ間で
  直接呼ぶ経路しか無いので、受け入れテストも同じ経路で確かめる —
  lbs-net 上の踏み台コンテナから `http://media:8080/...` を叩く
  (`support/services.ts` の `postJsonToServiceDirectly`)。
  なお media-service は `/api/render/**` にも認証を要求する(SecurityConfig、issue #772)ため、
  呼び出しには管理者のトークンを添える。

  ## 200 を返したことだけで合格としない

  #937 の受け入れ基準は「返却された画像の中身を検証していること」を要求している。
  PlantUML サーバーが返す PNG は、描画に使ったソースを `iTXt` チャンク(キー `plantuml`)へ
  zlib 圧縮で埋め込む。そこで **PNG のシグネチャ・IHDR の寸法・埋め込みソース**の3つを見る。
  加えて、要素を増やした図の方が**大きく描かれる**ことを確かめる。埋め込みソースだけでは
  「送った文字列が返ってきた」ことしか言えず、実際に描かれたかどうかは分からないため。

  シナリオ: 妥当なPlantUMLソースを渡すと、ノード名を含む図がPNGとして返る
    もし PlantUMLソース「@startuml\nAlice -> Bob: Hello\n@enduml」の描画を要求する
    ならば PNG画像が返る
    かつ 返ったPNGに埋め込まれたソースに「Alice」「Bob」が含まれる
    かつ 返ったPNGは幅も高さも1px以上ある
    もし PlantUMLソース「@startuml\nAlice -> Bob: Hello\nBob -> Carol: Relay\nCarol -> Dave: Forward\n@enduml」の描画を要求する
    ならば 返ったPNGに埋め込まれたソースに「Carol」「Dave」が含まれる
    かつ 今回のPNGは前回より幅も高さも大きい

  シナリオ: 不正な構文では、理由の分かるエラーが返り、生の500にはならない
    もし PlantUMLソース「これはPlantUMLではありません\n???」の描画を要求する
    ならば 描画は失敗し、理由の分かるエラーが返る
    かつ 応答は画像ではない

  # 「巨大」の実体: PlantUML の描画は符号化したソースをURLパスへ載せて要求するため、
  # 一定量を超えると PlantUML サーバーが URI 長の上限で拒否する。media-service は
  # それを 502 と説明文へ翻訳して返す。ここで確かめたいのは「上限に達したことが
  # 呼び出し側に分かる形で返り、応答が返らないまま待たされたり落ちたりしない」ことである。
  シナリオ: 巨大な入力にはサイズ上限のエラーが返り、待たされ続けない
    もし 要素を「10000」個含む巨大なPlantUMLソースの描画を要求する
    ならば 描画は失敗し、理由の分かるエラーが返る
    かつ エラーの説明からサイズ上限に達したと分かる
    かつ 応答は画像ではない
