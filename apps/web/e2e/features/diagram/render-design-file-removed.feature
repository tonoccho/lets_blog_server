# language: ja
@diagram @api
機能: カスタムタグ用のデザインファイル作成API(削除済み)

  Penpot をシステムから削除したため(issue #1750)、カスタムタグの AI 生成時に
  デザインファイルを作る media-service の API は無い。旧 AC-DIAG-006(Penpot 未起動時の
  デザインファイル要求)は、機能ごと削除したので受け入れ基準から外した。

  `/api/render/**` は gateway のルート表に載っていない(issue #830)ため、
  lbs-net 上の踏み台コンテナから media-service を直接呼ぶ(`plantuml-rendering.feature` の冒頭と同じ)。

  シナリオ: デザインファイルの作成を要求しても、そのAPIは存在しない
    もし カスタムタグ用のデザインファイル作成APIを要求する
    ならば そのAPIは存在せず、404が返る
