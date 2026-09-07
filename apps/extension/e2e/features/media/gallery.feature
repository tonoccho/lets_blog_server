# language: ja
@extension @api @media
機能: 生成画像ギャラリー

  背景:
    前提 拡張の設定が既定値である
    かつ 管理者としてログイン済みである
    かつ 受け入れテスト用のプロジェクトが存在する

  シナリオ: プロジェクトの生成画像を一覧できる
    もし 生成画像の一覧を取得する
    ならば 生成画像の一覧が取得できる

  # 生成は実際にGPUを回し、gatewayのupload枠(1時間に10回)も消費するため@slowにする。
  @slow
  シナリオ: batch sizeで指定した枚数の生成画像がすべて返る
    もし batch size 2 で画像を生成する
    ならば 生成画像が 2 枚返る

  # batch count は batch size 枚の生成を繰り返すため、生成時間も枚数に比例する(issue #1105)。
  @slow
  シナリオ: batch count で指定した回数ぶん繰り返して生成される
    もし batch size 2 と batch count 2 で画像を生成する
    ならば 生成画像が 4 枚返る
