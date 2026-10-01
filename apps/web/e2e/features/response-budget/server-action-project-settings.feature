# language: ja
@response-budget
機能: プロジェクトの作成・環境・設定の Server Action が3秒の予算内に返る

  `docs/ACCEPTANCE_CRITERIA.md` §10.5 の `app/projects/actions.ts` と `app/projects/[id]/actions.ts` のうち、
  プロジェクトの作成・削除、環境の紐付け、設定フォーム(GitHub・APIキー・Google 連携・画像生成の既定値)
  (issue #1477)。書式と計り方は `page-first-display.feature` / `server-action-admin.feature` の冒頭を読むこと。

  - 計るのは**サーバへ実際に送られた Server Action の POST の往復**だけ(§10.3)。
    共通ステップ「Server Action の往復は「…」ミリ秒以内に返る」で判定する。
  - 前提(プロジェクト・サイト・連携済みの状態)は API で用意し、操作は実際の画面で行う。シナリオごとの
    使い捨てで、後片付けは `responseBudgetCleanup.steps.ts`。
  - 汎用ステップ(`responseBudgetProject.steps.ts`)の「入力」「選択」「チェック」「押す」は、確認ダイアログを
    承諾し、「…を確認して」で保存後の表示を確かめる。表示が空のものは、往復が起きたことだけを確かめる。
  - Google Analytics / AdSense は E2E のスタブ(`ga-stub` / `adsense-stub`)を向く。OAuth の同意画面は通れないので、
    連携済みの状態は API(OAuth コールバック)で作る。実 Google の応答時間は測れない。
  - 設定値は保存の往復を測るためのダミーで、実在の資格情報ではない。

  背景:
    前提 応答時間予算の検証のために管理者としてログインしている

  @budget-action:createProjectAction
  シナリオ: プロジェクトの作成(Server Action)の往復が3秒以内に返る
    もし プロジェクト一覧画面でプロジェクトを作成して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:deleteProjectAction
  シナリオ: プロジェクトの削除(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用に削除してよいプロジェクトがある
    もし プロジェクト詳細画面でプロジェクトを削除して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:bindEnvironmentAction
  シナリオ: 環境へのサイトの紐付け(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    かつ 応答時間予算の検証用のサイトがある
    もし プロジェクト詳細画面でテスト環境にサイトを紐付けて Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:unbindEnvironmentAction
  シナリオ: 環境からのサイトの切離し(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    かつ 応答時間予算の検証用のサイトがある
    かつ そのプロジェクトのテスト環境にサイトが紐付いている
    もし プロジェクト詳細画面でテスト環境のサイトを切り離して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:updateMasterEnvironmentAction
  シナリオ: マスター環境の変更(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし プロジェクトの「概要」タブで選択「masterEnvironment=production」して「保存」を押し「保存しました。」を確認して「マスター環境の変更」の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:updateProjectGithubRepositoryAction
  シナリオ: GitHubリポジトリの保存(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし プロジェクトの「設定」タブで入力「githubRepository=e2e-1477/budget-fixture」して「保存」を押し「保存しました。」を確認して「GitHubリポジトリの保存」の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:setProjectGithubTokenAction
  シナリオ: GitHubトークンの保存(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし プロジェクトの「設定」タブで入力「githubToken=ghp_e2e1477budgetfixture0000000000000000」して「保存」を押し「保存しました。」を確認して「GitHubトークンの保存」の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:clearProjectGithubTokenAction
  シナリオ: GitHubトークンの削除(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    かつ そのプロジェクトにGitHubトークンが設定されている
    もし プロジェクトの「設定」タブで「プロジェクト設定を削除」を押し「」を確認して「GitHubトークンの削除」の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:setProjectBraveSearchApiKeyAction
  シナリオ: Brave Search APIキーの保存(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし プロジェクトの「設定」タブで入力「apiKey=BSA-e2e1477-budget-fixture」して「保存」を押し「保存しました。」を確認して「Brave Search APIキーの保存」の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:clearProjectBraveSearchApiKeyAction
  シナリオ: Brave Search APIキーの削除(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    かつ そのプロジェクトにBrave Search APIキーが設定されている
    もし プロジェクトの「設定」タブで「プロジェクト設定を削除」を押し「」を確認して「Brave Search APIキーの削除」の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:setProjectGoogleAnalyticsClientAction
  シナリオ: Google Analytics の OAuth クライアントの保存(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし プロジェクトの「/settings/google-analytics」画面で入力「clientId=at1231-ga.apps.googleusercontent.com;clientSecret=e2e-1477-budget-secret」して「クライアントを保存」を押し「保存しました。」を確認して「Google Analytics の OAuth クライアントの保存」の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:selectProjectGoogleAnalyticsPropertyAction
  シナリオ: Google Analytics のプロパティの選択(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    かつ そのプロジェクトにGoogle Analyticsが連携済みでプロパティを選べる
    もし プロジェクトの「/settings/google-analytics」画面で選択「propertyId=987654321」して「プロパティを保存」を押し「プロパティを保存しました。」を確認して「Google Analytics のプロパティの選択」の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:clearProjectGoogleAnalyticsCredentialsAction
  シナリオ: Google Analytics の連携解除(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    かつ そのプロジェクトにGoogle Analyticsが連携済みでプロパティを選べる
    もし プロジェクトの「/settings/google-analytics」画面で「連携を解除」を押し「」を確認して「Google Analytics の連携解除」の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:setProjectAdSenseSettingsAction
  シナリオ: AdSense の設定の保存(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし プロジェクトの「/settings/adsense」画面で入力「accountId=pub-1234567890123456;clientId=at13-acceptance.apps.googleusercontent.com;clientSecret=e2e-1477-budget-secret」して「まとめて保存」を押し「保存しました。」を確認して「AdSense の設定の保存」の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:selectProjectAdSenseAccountAction
  シナリオ: AdSense のアカウントの選択(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    かつ そのプロジェクトにAdSenseが連携済みでアカウントを選べる
    もし プロジェクトの「/settings/adsense」画面で選択「selectedAccountId=pub-2222222222222222」して「パブリッシャーIDを保存」を押し「パブリッシャーIDを保存しました。」を確認して「AdSense のアカウントの選択」の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:clearProjectAdSenseCredentialsAction
  シナリオ: AdSense の設定の削除(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    かつ そのプロジェクトにAdSenseが連携済みでアカウントを選べる
    もし プロジェクトの「/settings/adsense」画面で「設定を削除」を押し「」を確認して「AdSense の設定の削除」の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:updateImageGenerationPromptDefaultsAction
  シナリオ: 画像生成の既定プロンプトの保存(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし プロジェクトの「AI・アセット」タブで入力「defaultNegativePrompt=e2e-1477 budget negative;defaultQualityPrompt=e2e-1477 budget quality」して「保存」を押し「保存しました。」を確認して「画像生成の既定プロンプトの保存」の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:updateImageGenerationSizeDefaultsAction
  シナリオ: 画像生成の既定サイズの保存(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし プロジェクトの「AI・アセット」タブで入力「defaultGeneratedImageWidth=768;defaultGeneratedImageHeight=512」して「保存」を押し「保存しました。」を確認して「画像生成の既定サイズの保存」の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:updateArticleImageResizeDefaultAction
  シナリオ: 記事画像の既定リサイズの保存(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし プロジェクトの「AI・アセット」タブで入力「defaultArticleImageLongEdgePx=1280」して「保存」を押し「保存しました。」を確認して「記事画像の既定リサイズの保存」の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:updateImageContentFilterSettingsAction
  シナリオ: 画像の内容フィルタ設定の保存(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし プロジェクトの「AI・アセット」タブでチェック「blockSexualContent」を切り替えて「保存」を押し「保存しました。」を確認して「画像の内容フィルタ設定の保存」の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る
