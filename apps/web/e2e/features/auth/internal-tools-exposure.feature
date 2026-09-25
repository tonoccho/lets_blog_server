# language: ja
@auth @api
機能: 同梱ツール(ComfyUI / PlantUML / draw.io)の公開面

  reverse-proxy が中継する経路のうち、同梱ツールへ向かうものはアプリの認証ゲート
  (ADR-0008)を通らない。docs/DOCKER_COMPOSE_ARCHITECTURE.md はこの3つを「非公開」と
  記載していたが、実際には無認証で外部へ中継されていた(#979)。
  ブラウザから到達する必要があるのは draw.io だけ(VSCode拡張の
  apps/extension/src/diagramEditorPanel.ts が webview の iframe に読み込む)であり、
  ComfyUI と PlantUML はサービス間で lbs-net 経由でしか使われない。

  シナリオ: ComfyUI の UI は外部から開けない
    もし 未認証で ComfyUI のパスを開く
    ならば その応答は ComfyUI のものではない
    かつ その応答は Web アプリが返している

  シナリオ: PlantUML サーバーで外部から図を描画できない
    もし 未認証で PlantUML の図描画パスを開く
    ならば その応答は PlantUML が描画した画像ではない
    かつ その応答は Web アプリが返している

  シナリオ: PlantUML サーバーのトップは外部から開けない
    もし 未認証で PlantUML のトップをリダイレクトを追わずに開く
    ならば その応答は PlantUML サーバー自身のリダイレクトではない

  シナリオ: draw.io のエディタは VSCode 拡張のために公開したままにする
    もし 拡張が使う URL で draw.io のエディタを開く
    ならば draw.io のエディタが返る
    かつ draw.io の静的アセットも同じ経路で取得できる

  シナリオ: 図表レンダリングと画像生成は内部ネットワーク経由で行われる
    もし media-service から PlantUML サーバーへ図を描画する
    ならば 図の描画に成功する
    かつ media-service の PlantUML 参照先は内部ホスト名である
    かつ media-service の ComfyUI 参照先は内部ホスト名である
