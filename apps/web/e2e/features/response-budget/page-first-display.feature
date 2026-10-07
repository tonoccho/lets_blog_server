# language: ja
@response-budget @retries:2
機能: 全ページの初回表示が3秒の予算内に収まる

  利用者の要望は「すべてのクリックに対する応答を3秒以内にする」(issue #1476)。
  対象は `docs/ACCEPTANCE_CRITERIA.md` §10.4 の全27ページの初回表示で、本ファイルは
  そのうち `click-response-budget.feature` が持つ `/projects` と `/users` を除く25ページを
  1ページ1シナリオで検証する(issue #1477)。Server Action は §10.5 の側で別に置く。

  ## 計り方

  **画面ごとに独自の計測方法を作らない。** 共通ステップ(`e2e/support/responseBudget.steps.ts`)の
  「ウォームアップ後に「…」を開く」が、1回目の遷移(Next.js devモードのルートコンパイル)を捨てて
  2回目の `page.goto` だけを測る(§10.2 の①)。閾値 3,000ms は全ページ共通。
  既存シナリオへの追記ではなく独立したシナリオにしてあるので、既存シナリオの実行時間は増えない。

  - 経路のIDは `{projectId}` / `{siteId}` / `{userId}` で書き、シナリオごとに作る検証用の
    プロジェクト・サイト・利用者のIDに置き換える。
  - `/login` と `/setup` は未ログインで到達できる。どちらもこの環境ではKeycloakのホスト型ログイン画面へ
    リダイレクトするので、**リダイレクト先の表示完了まで**を測る(§10.4 の注記と同じ)。
  - 管理者権限でしか入れないページも、一般ユーザーで入れるページも、管理者でログインして測る
    (権限の網羅は `cross-cutting` の認可マトリクスが担う)。

  ## 宣言(タグ)の書式 — `scripts/check-response-budget-coverage.py` が読む

  シナリオの**直前**のタグ行で、対象一覧のどの行を検証するシナリオかを宣言する。

  - `@budget-page:/projects/[id]/plan` — §10.4 の画面。経路は一覧の表記のまま(`[id]` を含む)
  - `@budget-action:updateProjectNameAction` — §10.5 の Server Action。関数名(表の関数名と同じ)

  - 1つのシナリオが複数を宣言してよい。
  - 機能・背景・`例:` の直前に置いたタグは宣言として数えず、置き間違いとして照合が失敗する。
  - 照合が失敗するのは、予算対象の行にシナリオが無い / 一覧に無いものを宣言している /
    予算対象でない行(予算対象外・非同期ハンドオフ待ち)を宣言している、のとき。
  - ページや Server Action を足したら、§10 に行を足し、ここに宣言付きのシナリオを足す。

  @budget-page:/
  シナリオ: 「/」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/admin/backup
  シナリオ: 「/admin/backup」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/admin/backup」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/admin/roles
  シナリオ: 「/admin/roles」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/admin/roles」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/admin/ssh-keys
  シナリオ: 「/admin/ssh-keys」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/admin/ssh-keys」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/admin/system-settings
  シナリオ: 「/admin/system-settings」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/admin/system-settings」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/admin/tag-design
  シナリオ: 「/admin/tag-design」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/admin/tag-design」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/custom-tag-templates
  シナリオ: 「/custom-tag-templates」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/custom-tag-templates」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/image-gallery
  シナリオ: 「/image-gallery」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/image-gallery」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/login
  シナリオ: 「/login」画面の初回表示が3秒以内に完了する
    もし ウォームアップ後に「/login」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/login/error
  シナリオ: 「/login/error」画面の初回表示が3秒以内に完了する
    もし ウォームアップ後に「/login/error」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/operation-logs
  シナリオ: 「/operation-logs」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/operation-logs」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/operation-logs/slow
  シナリオ: 「/operation-logs/slow」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/operation-logs/slow」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/posts
  シナリオ: 「/posts」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/posts」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/projects/[id]
  シナリオ: 「/projects/[id]」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証用のプロジェクトがある
    かつ 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/projects/{projectId}」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/projects/[id]/dashboard
  シナリオ: 「/projects/[id]/dashboard」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証用のプロジェクトがある
    かつ 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/projects/{projectId}/dashboard」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/projects/[id]/plan
  シナリオ: 「/projects/[id]/plan」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証用のプロジェクトがある
    かつ 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/projects/{projectId}/plan」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/projects/[id]/posts
  シナリオ: 「/projects/[id]/posts」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証用のプロジェクトがある
    かつ 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/projects/{projectId}/posts」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/projects/[id]/settings/adsense
  シナリオ: 「/projects/[id]/settings/adsense」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証用のプロジェクトがある
    かつ 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/projects/{projectId}/settings/adsense」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/projects/[id]/settings/google-analytics
  シナリオ: 「/projects/[id]/settings/google-analytics」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証用のプロジェクトがある
    かつ 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/projects/{projectId}/settings/google-analytics」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/projects/[id]/settings/sns
  シナリオ: 「/projects/[id]/settings/sns」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証用のプロジェクトがある
    かつ 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/projects/{projectId}/settings/sns」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/projects/[id]/tags
  シナリオ: 「/projects/[id]/tags」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証用のプロジェクトがある
    かつ 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/projects/{projectId}/tags」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/setup
  シナリオ: 「/setup」画面の初回表示が3秒以内に完了する
    もし ウォームアップ後に「/setup」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/sites
  シナリオ: 「/sites」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/sites」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/sites/[id]/edit
  シナリオ: 「/sites/[id]/edit」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証用のサイトと利用者がある
    かつ 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/sites/{siteId}/edit」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-page:/users/[id]/edit
  シナリオ: 「/users/[id]/edit」画面の初回表示が3秒以内に完了する
    前提 応答時間予算の検証用のサイトと利用者がある
    かつ 応答時間予算の検証のために管理者としてログインしている
    もし ウォームアップ後に「/users/{userId}/edit」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する
