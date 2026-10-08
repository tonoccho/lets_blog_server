# language: ja
@response-budget @retries:2
機能: プロジェクトの AI・アセットタブの Server Action が3秒の予算内に返る

  `docs/ACCEPTANCE_CRITERIA.md` §10.5 の `app/projects/[id]/actions.ts` のうち、プロジェクト詳細の
  「AI・アセット」タブ(AIモデル管理・アセット画像生成)が呼ぶ操作(issue #1477)。書式と計り方は
  `page-first-display.feature` / `server-action-project-settings.feature` の冒頭を読むこと。

  - 計るのは**サーバへ実際に送られた Server Action の POST の往復**(§10.3)。共通ステップ
    「Server Action の往復は「…」ミリ秒以内に返る」で判定する。
  - 一覧の取得系(`fetch*Action`)は、タブ・サブタブ・パネルを開いたときに画面が自動で送る往復。
    1つの操作が複数の往復を送るときは**最も遅い往復**を判定する(どれか1つでも3秒を超えれば落ちる)。
  - プロバイダー・モデル・校正ステップの保存は、保存後に画面が一覧を取り直すので、保存と取り直しの両方が
    予算内でなければ落ちる。
  - LLM は LLM スタブ、ComfyUI は ComfyUI スタブを向く(`docker-compose.e2e-stubs.yml`)。
    **実機のモデルは測れない**(実 LLM・実 ComfyUI は §10.5 の外部依存の分類)。
  - `deleteComfyUiCheckpointAction` は受け入れシナリオから到達できない(安全に消せる検証用のチェックポイントを
    ComfyUI スタブの一覧へ出せない)。理由は §10.5 の該当行に記録してある。
  - `uploadProjectAssetImageAction`(生成画像を全環境の WordPress へアップロード)は、実測で3秒を超えたため
    利用者の判断(2026-10-01)で「非同期ハンドオフ待ち(#1478)」に再分類された(#1552、§10.5)。ここには置かない。

  背景:
    前提 応答時間予算の検証のために管理者としてログインしている

  @budget-action:fetchLlmModelsAction @budget-action:fetchLlmProviderAction @budget-action:fetchReviewStepSettingsAction
  シナリオ: AIモデル管理のLLMタブの初回取得(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし プロジェクト詳細画面で「AI・アセット」タブを開いて Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:selectLlmProviderAction
  シナリオ: LLMプロバイダーの切り替え(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし AIモデル管理のLLMプロバイダーを「OPENAI」に切り替えて Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:selectLlmModelAction
  シナリオ: LLMモデルの選択(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし AIモデル管理のLLMモデルを一覧の先頭の候補にして保存し Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:updateReviewStepSettingAction
  シナリオ: 校正ステップ別のモデル設定の保存(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし AIモデル管理の「日本語チェック」の行でプロバイダーを「OPENAI」にして保存し Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:fetchImageProviderAction @budget-action:fetchComfyUiCheckpointsAction
  シナリオ: AIモデル管理の画像生成タブの初回取得(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし AIモデル管理の「画像生成」サブタブを開いて Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:selectImageProviderAction
  シナリオ: 画像生成AIの切り替え(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし AIモデル管理の画像生成AIを「CHATGPT」に切り替えて Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:selectComfyUiCheckpointAction
  シナリオ: ComfyUIチェックポイントの切り替え(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし AIモデル管理の選択中でないチェックポイントに切り替えて Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:fetchImageGenerationOptionsAction
  シナリオ: アセット画像生成パネルの選択肢の取得(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし アセット画像生成パネルを開いて Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:fetchGeneratedImagesAction
  シナリオ: アセット画像生成パネルの画像ギャラリーの取得(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし アセット画像生成パネルの画像ギャラリーを開いて Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:requestProjectImageJobAction
  シナリオ: アセット画像生成ジョブの依頼(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし アセット画像生成パネルで画像生成ジョブを依頼して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:fetchImageJobResultAction
  シナリオ: 画像生成ジョブの結果の取得(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    かつ 応答時間予算の検証用の完了した画像生成ジョブがある
    もし 処理キューの「結果を見る」と同じ経路でそのジョブの結果を開いて Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:pullOllamaModelAction
  シナリオ: Ollamaのモデルのpull受付(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし 「/projects/{projectId}」の設定タブを開いておく
    かつ Ollamaのモデル名欄に「e2e-budget-pull:1b」を入れてインストールを押し Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る
