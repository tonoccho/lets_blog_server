# language: ja
@response-budget @retries:2
機能: 画像ギャラリーの Server Action が3秒の予算内に返る

  `docs/ACCEPTANCE_CRITERIA.md` §10.5 の `app/image-gallery/actions.ts`(issue #1477)。書式と計り方は
  `page-first-display.feature` / `server-action-admin.feature` の冒頭を読むこと。

  対象の画像は、保存経路(`POST /api/generated-images`)でシナリオごとに作る検証用の1枚で、
  後片付けは `responseBudgetCleanup.steps.ts`。生成そのものはここでは行わない(ギャラリーの
  操作の往復だけを測る。画像生成は §10.5 で別の分類)。

  背景:
    前提 応答時間予算の検証のために管理者としてログインしている
    かつ 応答時間予算の検証用の生成画像がギャラリーにある

  @budget-action:getGeneratedImageAction
  シナリオ: 生成画像の詳細の取得(Server Action)の往復が3秒以内に返る
    もし 画像ギャラリーでその画像を開いて Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:updateGeneratedImageTagsAction
  シナリオ: 生成画像のタグ保存(Server Action)の往復が3秒以内に返る
    もし 画像ギャラリーでその画像の詳細を開いてタグを追加し Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:deleteGeneratedImageAction
  シナリオ: 生成画像の削除(Server Action)の往復が3秒以内に返る
    もし 画像ギャラリーでその画像の詳細を開いて削除し Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:bulkDeleteGeneratedImagesAction
  シナリオ: 生成画像の一括削除(Server Action)の往復が3秒以内に返る
    もし 画像ギャラリーでその画像を選択して一括削除し Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:createGeneratedImageFolderAction
  シナリオ: 生成画像のフォルダ作成(Server Action)の往復が3秒以内に返る
    もし 画像ギャラリーでフォルダを作成し Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:setGeneratedImageFolderAction
  シナリオ: 生成画像のフォルダ割り当て(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のフォルダがある
    もし 画像ギャラリーでその画像の詳細を開いてフォルダへ割り当て Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る
