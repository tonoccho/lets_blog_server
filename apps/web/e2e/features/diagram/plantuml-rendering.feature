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

  ## PlantUML は固定画像のスタブで受ける(2026-10-15 利用者の決定、#1802)

  受け入れテストは extensions のツールをすべてモックで受ける(docs/ACCEPTANCE_TESTING.md §9)。
  PlantUML は #1784 で本物の plantuml-server を `lbx-plantuml` として起動していたが、
  `infra/e2e-stubs/plantuml/` のスタブへ置き換えた。スタブは固定の PNG を返すので、
  かつて見ていた次の2つは AT では確かめられない。

  - PNG に埋め込まれたソースの確認 → 「**スタブが受け取ったソースに Alice / Bob が含まれる**」へ置き換えた。
    media-service が正しいソースをスタブへ送ったこと(符号化を含む)を、スタブが復号した値で見る。
  - 「要素を増やすと図が大きくなる」シナリオ → AT から外し、docs/ACCEPTANCE_TESTING.md §19 の UAT へ移した
    (本物の PlantUML で描かせないと確かめようがないため。#1714 の前例)。

  400 / 414 は、スタブの決定的な規則で保つ(`@startuml` が無ければ 400 と `X-PlantUML-Diagram-Error`、
  一定の長さを超える URI は 414)。#937 の「200 を返しただけで合格としない」意図は、
  PNG のシグネチャ・寸法に加え、スタブが受け取ったソースで満たす。

  シナリオ: 妥当なPlantUMLソースを渡すと、ノード名を含む図がPNGとして返る
    もし PlantUMLソース「@startuml\nAlice -> Bob: Hello\n@enduml」の描画を要求する
    ならば PNG画像が返る
    かつ PlantUMLスタブが受け取ったソースに「Alice」「Bob」が含まれる
    かつ 返ったPNGは幅も高さも1px以上ある

  # PlantUML サーバーが図を拒否したときは、入力の誤りとして 400 を返す(issue #1776)。
  # 「接続できません」(502)とは区別され、利用者は自分の図を直せばよいと分かる。
  シナリオ: 不正な構文では、理由の分かるエラーが返り、生の500にはならない
    もし PlantUMLソース「これはPlantUMLではありません\n???」の描画を要求する
    ならば 描画は失敗し、理由の分かるエラーが返る
    かつ 応答は画像ではない
    かつ PlantUML が図を拒否した入力の誤りとして、ステータス「400」で返る
    かつ エラーの説明は「PlantUML が図を描けませんでした: 」で始まり、「接続できません」を含まない

  シナリオ: 構文の誤った図を含む本文のプレビューは、図を描けなかった旨を示し、接続できないとは示さない
    前提 PlantUMLのプレビューを確かめるプロジェクトがある
    もし 構文の誤った PlantUML タグを含む本文のプレビューを要求する
    ならば プレビューのHTMLに「PlantUML が図を描けませんでした」が含まれる
    かつ プレビューのHTMLに「接続できません」は含まれない

  # 「巨大」の実体: PlantUML の描画は符号化したソースをURLパスへ載せて要求するため、
  # 一定量を超えると PlantUML サーバーが URI 長の上限で拒否する。media-service は
  # それを 502 と説明文へ翻訳して返す。ここで確かめたいのは「上限に達したことが
  # 呼び出し側に分かる形で返り、応答が返らないまま待たされたり落ちたりしない」ことである。
  シナリオ: 巨大な入力にはサイズ上限のエラーが返り、待たされ続けない
    もし 要素を「10000」個含む巨大なPlantUMLソースの描画を要求する
    ならば 描画は失敗し、理由の分かるエラーが返る
    かつ エラーの説明からサイズ上限に達したと分かる
    かつ 応答は画像ではない
