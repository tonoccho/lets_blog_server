# language: ja
@ui-quality @ui @dark-mode-single-signal
機能: ダークモードの判定信号を data-theme 属性に一本化する

  `globals.css` のCSS変数は `prefers-color-scheme` メディアクエリにも反応していたが、
  Tailwind の `dark:` ユーティリティは `data-theme` 属性しか見ない(issue #1239)。
  テーマ初期化スクリプトが実行されない環境(JS無効など)では、背景色だけがダークで
  `dark:` 由来の色がライトのままという食い違いが起きていた。
  判定信号を `data-theme` に統一し、未設定ならCSS変数もライト側に揃える。

  シナリオアウトライン: data-themeが未設定ならOSの配色に関わらずCSS変数はライトで揃う
    前提 JavaScriptを無効にしOSの配色設定が「<OS配色>」のブラウザでログイン画面を開く
    ならば html要素にdata-theme属性が付いていない
    かつ body要素の背景色は「rgb(255, 255, 255)」である

    例:
      | OS配色 |
      | dark   |
      | light  |
