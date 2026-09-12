# language: ja
@api @harness
機能: Playwright ブラウザの前提確認

  受け入れテストの「土台」の検証(#1045)。外部依存スタブ(features/stubs/)と同じ位置づけで、
  製品機能ではなく **受け入れテストが成立するための前提** をここで固定する。

  ブラウザが起動できないホストでは、Playwright は1本目のシナリオの中で生のエラーを出して
  落ち、残りは「did not run」になる。実測(#1045)では 1 failed / 72 did not run で、
  原因は 60 行のブラウザ起動ログの中に埋もれていた。しかも症状は2種類あり、
  対処が違う ——

    - ブラウザ本体が無い     : `Executable doesn't exist at ...`  → sudo は要らない
    - OS の共有ライブラリが無い: `error while loading shared libraries: libatk-1.0.so.0`
                                → root 権限が要る(自動実行しない。#1045 Requirement 4)

  global-setup の前提確認が、この2つを区別して **導入コマンド付きで** 明示的に落とす。

  シナリオ: ブラウザ本体が未導入なら、sudo の要らない導入コマンドを示して落ちる
    もし ブラウザの起動が次のエラーで失敗する:
      """
      browserType.launch: Executable doesn't exist at /home/dev/.cache/ms-playwright/chromium_headless_shell-1234/chrome-headless-shell-linux64/chrome-headless-shell
      """
    ならば 前提確認は失敗する
    かつ 失敗の説明に "npm run playwright:install" が含まれる
    かつ 失敗の説明に "ブラウザ本体" が含まれる
    かつ 失敗の説明に "sudo" は含まれない

  シナリオ: 共有ライブラリが不足していれば、不足しているライブラリ名と root 権限の要るコマンドを示して落ちる
    もし ブラウザの起動が次のエラーで失敗する:
      """
      browserType.launch: Target page, context or browser has been closed
      Browser logs:

      <launched> pid=27909
      [pid=27909][err] /home/dev/.cache/ms-playwright/chromium_headless_shell-1234/chrome-headless-shell-linux64/chrome-headless-shell: error while loading shared libraries: libatk-1.0.so.0: cannot open shared object file: No such file or directory
      [pid=27909] <process did exit: exitCode=127, signal=null>
      """
    ならば 前提確認は失敗する
    かつ 失敗の説明に "libatk-1.0.so.0" が含まれる
    かつ 失敗の説明に "sudo npx playwright install-deps" が含まれる
    かつ 失敗の説明に apt で導入するパッケージが全て列挙されている
    かつ 失敗の説明に "npm run playwright:install" は含まれない

  シナリオ: 原因を特定できない失敗でも、元のエラーを握りつぶさない
    もし ブラウザの起動が次のエラーで失敗する:
      """
      browserType.launch: Timeout 30000ms exceeded while waiting for the browser to start
      """
    ならば 前提確認は失敗する
    かつ 失敗の説明に "Timeout 30000ms exceeded" が含まれる

  # globalSetup が受け取る FullConfig['projects'] 自体は Playwright が返す時点で既に
  # --project の絞り込みを反映していない(設定が宣言した全プロジェクトが入る)。これは
  # Playwright 自身の仕様で変えようがない。#1194 より前はこれを理由に「絞り込みは
  # 不可能」と諦め、常に全プロジェクトのブラウザを確認していた。しかし CLI の
  # `--project` 引数(process.argv)は globalSetup からも読めるので、#1194 以降は
  # このコードが `--project` を自分で解析し、選択されたプロジェクトとその依存先
  # (playwright.config.ts の `dependencies`)だけに絞ってから起動確認する。
  # `--project` が指定されない実行(全プロジェクトを回す)では、これまで通り
  # 宣言された全プロジェクトを確認する(下のシナリオがその場合)。
  シナリオ: --project を指定しない実行では、宣言された全プロジェクトが使うブラウザを起動確認する
    前提 宣言されている全プロジェクトのブラウザ指定が "at-main=defaultBrowserType:chromium, firefox=browserName:firefox, Mobile Safari=defaultBrowserType:webkit" である
    ならば 前提確認が起動を試すブラウザは "chromium, firefox, webkit" である

  シナリオ: ブラウザを明示しないプロジェクトは chromium として扱う
    前提 宣言されている全プロジェクトのブラウザ指定が "at-seed=" である
    ならば 前提確認が起動を試すブラウザは "chromium" である

  シナリオ: 同じブラウザを使うプロジェクトが並んでも起動確認は1回だけにする
    前提 宣言されている全プロジェクトのブラウザ指定が "at-setup=defaultBrowserType:chromium, at-main=browserName:chromium" である
    ならば 前提確認が起動を試すブラウザは "chromium" である

  シナリオ: --project で選択したプロジェクトとその依存先だけが使うブラウザを起動確認する
    前提 宣言されているプロジェクトが "at-setup=defaultBrowserType:chromium, at-seed=defaultBrowserType:chromium|deps:at-setup, at-provision=defaultBrowserType:chromium|deps:at-seed, at-main=defaultBrowserType:chromium|deps:at-provision, at-destructive=defaultBrowserType:chromium|deps:at-main, at-cross-browser-firefox=browserName:firefox|deps:at-provision" である
    かつ 選択されたプロジェクトが "at-main" である
    ならば 前提確認が起動を試すブラウザは "chromium" である

  # 依存先の at-provision(chromium)は両方の選択が共有するため、chromium も含めて
  # 3ブラウザとも確認対象になる(依存先を含めた解決が正しく効いていることの確認)。
  シナリオ: --project で複数選択した場合はそれぞれの依存先も合わせて起動確認する
    前提 宣言されているプロジェクトが "at-provision=defaultBrowserType:chromium, at-cross-browser-firefox=browserName:firefox|deps:at-provision, at-cross-browser-webkit=browserName:webkit|deps:at-provision" である
    かつ 選択されたプロジェクトが "at-cross-browser-firefox, at-cross-browser-webkit" である
    ならば 前提確認が起動を試すブラウザは "chromium, firefox, webkit" である

  シナリオ: --project=X 形式のコマンドライン引数から選択されたプロジェクト名を取り出す
    前提 Playwright に渡されたコマンドライン引数が "--project=at-main --grep-invert @slow" である
    ならば プロジェクトの選択は "at-main" である

  シナリオ: --project X 形式(値が別トークン)のコマンドライン引数からも選択されたプロジェクト名を取り出す
    前提 Playwright に渡されたコマンドライン引数が "--project at-destructive" である
    ならば プロジェクトの選択は "at-destructive" である

  シナリオ: --project を複数回指定した場合はその全てを選択されたプロジェクトとみなす
    前提 Playwright に渡されたコマンドライン引数が "--project=at-cross-browser-firefox --project=at-cross-browser-webkit" である
    ならば プロジェクトの選択は "at-cross-browser-firefox, at-cross-browser-webkit" である

  シナリオ: --project が指定されていなければ選択なし(全プロジェクト対象)と判定する
    前提 Playwright に渡されたコマンドライン引数が "--grep-invert @slow" である
    ならば プロジェクトの選択は "指定なし" である

  シナリオ: このホストで実際に起動を試し、失敗しても生の Playwright エラーは投げない
    もし このホストで chromium の起動を実際に試す
    ならば 起動できたか、さもなくば導入コマンド付きの前提エラーとして報告される
