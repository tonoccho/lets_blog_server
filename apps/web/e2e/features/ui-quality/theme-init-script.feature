# language: ja
@ui-quality @ui @theme-init-script
機能: テーマ初期化スクリプトをReactツリーの外から出力する

  `layout.tsx` は初回ペイント前に `html[data-theme]` を確定させるインラインスクリプトを
  JSX の `<script>` として描画していた。React 19 はクライアントレンダラが描画した
  `<script>` を実行せず、dev のコンソールに
  「Encountered a script tag while rendering React component」を出す(issue #1238)。
  `useServerInsertedHTML` でHTMLストリームへ直接出力する形へ変え、実行タイミング
  (`</head>` より前)と結果は変えない。

  `/login` はマウント時にKeycloakへ遷移するため、ハイドレート後の観測には認証不要で
  遷移しない `/login/theme-check`(ルートレイアウトを描画する404画面)を使う。

  シナリオ: SSRのHTMLの</head>より前にテーマ初期化スクリプトがちょうど1個ある
    もし ログイン画面のSSR HTMLを取得する
    ならば head終了タグより前にテーマ初期化スクリプトがちょうど1個含まれる
    かつ SSR HTML全体でテーマ初期化スクリプトはちょうど1個だけである

  # 警告の原因は、スクリプトがReactのクライアント向けペイロード(self.__next_f)に
  # `<script>` 要素として載り、クライアントのレンダラが描画し得ることにある。コールドロード
  # ではReactがSSR済みのノードを引き継ぐため警告が出ないことがあり、ブラウザのコンソール
  # だけでは回帰を捉えられない。ペイロードに載っていないことを直接確かめる。
  シナリオ: テーマ初期化スクリプトがReactのクライアント向けペイロードに載らない
    もし ログイン画面のSSR HTMLを取得する
    ならば テーマ初期化スクリプトのソースはself.__next_fのペイロードに含まれない

  シナリオ: localStorageがdarkなら初回ロードでdata-themeがdarkになりhead内のスクリプトは1個のまま
    前提 テーマをダークにする
    もし テーマ確認用の公開ページを開く
    ならば html要素のdata-themeが「dark」になる
    かつ ハイドレート後もhead内のテーマ初期化スクリプトは1個である

  シナリオ: 初回ロードでReactの「scriptタグを描画した」警告がコンソールに出ない
    もし テーマ確認用の公開ページを開く
    ならば コンソールにscriptタグ描画の警告が記録されない

  シナリオアウトライン: localStorageが未設定ならOSの配色設定にdata-themeが従う
    前提 OSの配色設定が「<OS配色>」である
    もし テーマ確認用の公開ページを開く
    ならば html要素のdata-themeが「<期待>」になる

    例:
      | OS配色 | 期待  |
      | dark   | dark  |
      | light  | light |
