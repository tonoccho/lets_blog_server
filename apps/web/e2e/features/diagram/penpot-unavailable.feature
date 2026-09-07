# language: ja
@diagram @api @destructive
機能: Penpot 未起動時のデザインファイル要求

  Penpot は**任意サービス**である。GPU や外部SaaSと同じく、無くても他の機能は使える
  という前提でスタックに置かれている(`scripts/wait-for-stack-healthy.sh` は
  penpot / comfyui / drawio を待たない)。したがって「起動していないときに何が返るか」が
  受け入れ基準になる(issue #937 / AT-11、AC-DIAG-006)。

  ## なぜ `@destructive` なのか

  この開発スタックでは Penpot は**起動している**。未起動時のふるまいを確かめるには
  実際に止めるしかなく、止めている間 Penpot は誰からも使えない。共有された状態を
  一時的に壊すので `@destructive` を付け、`at-destructive` 段階で単独実行させる
  (docs/ACCEPTANCE_TESTING.md §10)。シナリオは自分で復旧させる。

  「Penpot が起動していない環境で回せばよい」とはしない。起動している環境では
  シナリオが黙って通ってしまい(要求が成功するので)、未起動時の経路は誰にも
  検証されないまま残る。加えて、起動している状態で成功経路を叩くと共有の Penpot
  ワークスペースに実ファイルが残る — 消す手段を持たない副作用なので、そもそも叩かない。

  ## 経路

  `/api/render/**` は gateway のルート表に載っていない(issue #830)ため、
  lbs-net 上の踏み台コンテナから media-service を直接呼ぶ。
  詳細は `plantuml-rendering.feature` の冒頭と同じ。

  シナリオ: Penpot が起動していないとき、デザインファイルの要求は理由の分かるエラーになる
    前提 Penpot を停止する
    もし Penpot のデザインファイルの作成を要求する
    ならば デザインファイルの作成は失敗し、理由の分かるエラーが返る
    かつ エラーの説明から Penpot へ届かなかったと分かる
    かつ 応答は生の500エラーではない
