# language: ja
@response-budget
機能: 後から追加された画面と Server Action も3秒の予算内に収まる

  `docs/ACCEPTANCE_CRITERIA.md` §10 の対象一覧に後から載せた画面・Server Action のうち、
  「予算対象」に分類したものの予算検証(issue #1544)。計測は #1476 の共通計測ステップ
  (`e2e/support/responseBudget.ts` / `responseBudget.steps.ts`)で、閾値は全行 3,000ms。
  コードと一覧の一致そのものは `scripts/check-budget-target-list.py` が検査する。

  ## 計測する Server Action

  - 画面を開くと送られるもの: `fetchQueueJobsAction` / `fetchRecentOperationLogsAction`(情報表示レール)、
    `fetchAiConnectionsAction` / `fetchProjectConnectionsAction`(AI・アセットタブの接続情報)
  - 操作で送られるもの: `setClaudeApiKeyAction` / `clearClaudeApiKeyAction` /
    `setOpenAiApiKeyAction` / `clearOpenAiApiKeyAction` / `updateProjectConnectionAction` /
    `fetchGalleryImagesPageAction`(ギャラリーのタグ絞り込み)

  `fetchAiConnectionsAction` は ai-service の疎通確認を呼び、到達できないプロバイダーがあると
  構造的に3秒を超えうる。超過が観測されたら分類の変更を利用者に仰ぐ(Issue #1544 の Open Questions)。

  背景:
    前提 応答時間予算の検証用のプロジェクトがある
    かつ 応答時間予算の検証のために管理者としてログインしている

  @budget-page:/projects/[id]/article-review
  シナリオ: 記事レビュー画面の初回表示が3秒以内に完了する
    もし ウォームアップ後に「/projects/{projectId}/article-review」を開く
    ならば ページロードは「3000」ミリ秒以内に完了する

  @budget-action:fetchQueueJobsAction
  シナリオ: 情報表示レールの処理キュー取得(Server Action)の往復が3秒以内に返る
    もし 「/projects」を開いて情報表示レールの Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:fetchRecentOperationLogsAction
  シナリオ: 情報表示レールの操作ログ取得(Server Action)の往復が3秒以内に返る
    もし 「/projects」を開く
    かつ 情報表示レールの「操作ログ」タブを選んで Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:fetchProjectConnectionsAction @budget-action:fetchAiConnectionsAction
  シナリオ: AI・アセットタブの接続情報取得(Server Action)の往復が3秒以内に返る
    もし 「/projects/{projectId}」を開いて AI・アセットタブを選び Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:setClaudeApiKeyAction
  シナリオ: Claude の APIキー保存(Server Action)の往復が3秒以内に返る
    もし 「/projects/{projectId}」のAI・アセットタブを開いておく
    かつ Anthropic APIキー欄に「sk-at-1544-set」を入れて接続し Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:clearClaudeApiKeyAction
  シナリオ: Claude の接続解除(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトに「claude-api-key」が「sk-at-1544-clear」で設定されている
    もし 「/projects/{projectId}」のAI・アセットタブを開いておく
    かつ Claude の接続を解除して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:setOpenAiApiKeyAction
  シナリオ: ChatGPT の APIキー保存(Server Action)の往復が3秒以内に返る
    もし 「/projects/{projectId}」のAI・アセットタブを開いておく
    かつ OpenAI APIキー欄に「sk-at-1544-set」を入れて接続し Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:clearOpenAiApiKeyAction
  シナリオ: ChatGPT の接続解除(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトに「openai-api-key」が「sk-at-1544-clear」で設定されている
    もし 「/projects/{projectId}」のAI・アセットタブを開いておく
    かつ ChatGPT の接続を解除して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:updateProjectConnectionAction
  シナリオ: Ollama の接続先URL保存(Server Action)の往復が3秒以内に返る
    もし 「/projects/{projectId}」のAI・アセットタブを開いておく
    かつ Ollama の接続先URLに「http://at-1544-ollama.invalid:11434/v1」を入れて保存し Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @media @budget-action:fetchGalleryImagesPageAction
  シナリオ: ギャラリーのタグ絞り込み(Server Action)の往復が3秒以内に返る
    前提 ギャラリーに固定画像の生成画像を29件作成し、最新と最初に作成した1件にだけタグ「e2e-1544-tag」を付ける
    もし 「/image-gallery」を開く
    かつ ギャラリーをタグ「e2e-1544-tag」で絞り込んで Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る
