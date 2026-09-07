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

  # 「実行する」ではなく「設定が宣言する」。globalSetup が受け取る FullConfig['projects'] には
  # --project の絞り込みが反映されず、常に全プロジェクトが入る(browser-prerequisite.ts 参照)。
  シナリオ: 設定が宣言するプロジェクトが使うブラウザだけを起動確認する
    前提 実行対象のプロジェクトのブラウザ指定が "at-main=defaultBrowserType:chromium, firefox=browserName:firefox, Mobile Safari=defaultBrowserType:webkit" である
    ならば 前提確認が起動を試すブラウザは "chromium, firefox, webkit" である

  シナリオ: ブラウザを明示しないプロジェクトは chromium として扱う
    前提 実行対象のプロジェクトのブラウザ指定が "at-seed=" である
    ならば 前提確認が起動を試すブラウザは "chromium" である

  シナリオ: 同じブラウザを使うプロジェクトが並んでも起動確認は1回だけにする
    前提 実行対象のプロジェクトのブラウザ指定が "at-setup=defaultBrowserType:chromium, at-main=browserName:chromium" である
    ならば 前提確認が起動を試すブラウザは "chromium" である

  シナリオ: このホストで実際に起動を試し、失敗しても生の Playwright エラーは投げない
    もし このホストで chromium の起動を実際に試す
    ならば 起動できたか、さもなくば導入コマンド付きの前提エラーとして報告される
