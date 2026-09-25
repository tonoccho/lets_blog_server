# language: ja
@platform @timeout:90000
機能: VSCode拡張の配布

  サーバーからVSCode拡張(.vsix)を入手できる(issue #1153 / 親issue #940 シナリオ16・17)。
  オンデマンドビルド(`VscodeExtensionBuildService`)は毎回コンパイルとvsce packageを
  行うため既定の30秒テストタイムアウトを超えうる。`@timeout:90000` で猶予を広げる。

  シナリオ: ダウンロードした.vsixは妥当なVSIX(zip)である
    前提 管理者としてログインする
    もし VSCode拡張をダウンロードする
    ならば ダウンロードしたファイルは有効なzipであり、extension.vsixmanifestを含む

  シナリオ: ビルドされた.vsixにcoverage/やsrc/が含まれない(#773の退行検知)
    前提 管理者としてログインする
    もし VSCode拡張をダウンロードする
    ならば ダウンロードしたファイルにcoverage/やsrc/配下のファイルは含まれない
