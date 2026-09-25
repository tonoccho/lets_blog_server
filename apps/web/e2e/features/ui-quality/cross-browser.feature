# language: ja
@ui-quality @ui @stage:cross-browser
機能: クロスブラウザ

  Firefox / WebKit でも認証フローと主要画面のレンダリングが成立することを検証する
  (issue #944 / AT-18)。

  AT-0(#926)の `CROSS_BROWSER_SPECS` は Playwright直書きの `.spec.ts` を対象にした
  仕組みで、`.feature` から生成される spec(`.features-gen/` 配下、`testDir('./e2e')` の
  意図的に外)は元々その`testMatch`では拾えない。`accessibility.spec.ts` をこのフィーチャへ
  移行するにあたり、`playwright.config.ts` に `at-cross-browser-firefox` /
  `at-cross-browser-webkit` という専用の bdd プロジェクトを追加した。`@stage:cross-browser`
  タグを付けたシナリオだけをそれぞれ firefox / webkit の `devices` で実行する
  (`at-provision` に依存させ、フィクスチャ用アカウントが発行済みの状態で走らせる)。
  `test:at` / `test:at:fast` は既定で `at-main` / `at-destructive` のみを対象にするため、
  この2プロジェクトは `--project=at-cross-browser-firefox` 等を明示するか、
  `test:e2e`(全プロジェクト)経由で実行する。

  # /projects は requireAdminSession() で管理者限定(apps/web/src/app/projects/page.tsx)。
  # 「一般ユーザーとしてログインする」の後に開くのは、一般ユーザーでも requireSession() だけで
  # 到達できる /posts にする(admin専用ページを一般ユーザーで開くと /login へリダイレクトされ、
  # 「主要画面が表示される」の検証にならない)。
  シナリオ: 認証フローと主要画面がレンダリングされる
    もし 一般ユーザーとしてログインする
    ならば ホーム画面が表示され、致命的なレンダリング崩れが無い
    もし 投稿履歴画面を開く
    ならば 投稿履歴が表示され、致命的なレンダリング崩れが無い
