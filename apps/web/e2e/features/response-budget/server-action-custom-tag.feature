# language: ja
@response-budget @retries:2
機能: カスタムタグ関連の Server Action が3秒の予算内に返る

  `docs/ACCEPTANCE_CRITERIA.md` §10.5 の `app/custom-tag-templates/actions.ts` と
  `app/custom-tags/actions.ts`(issue #1477)。書式と計り方は `page-first-display.feature` /
  `server-action-admin.feature` の冒頭を読むこと。

  - テンプレートギャラリーの7操作(作成・編集・削除・公開・非公開に戻す・複製・プロジェクトで使う)は、
    シナリオごとに API で作るテンプレート・プロジェクトを対象にする(後片付けは `responseBudgetCleanup.steps.ts`)。
    作成(`createCustomTagTemplateAction`)だけは、画面から作ったテンプレートが対象になる。名前を
    `E2E1477 ` で始めて、後片付けが名前で拾って消す。編集・削除は画面の詳細パネルの「保存」「削除」から呼ぶ
    (issue #1550。それまでこの3つは画面のどこからも呼ばれておらず、シナリオを置けなかった)。
  - `validateCustomTagAction` は、AI でカスタムタグを生成した結果(未保存)を処理キューの「結果を見る」から
    表示したときに**自動で**走る(結果パネルの `useCustomTagValidation`、issue #1409)。生成の要求
    (`generateCustomTagAction`)は非同期ジョブの受付だけだが、予算対象外のまま
    (§10.5。生成そのものは外部LLMの応答時間に依存する)。LLM スタブ(`@stub`)に生成させ、完了を待って
    結果を開き、そのとき送られる検証の Server Action の往復だけを計る。

  背景:
    前提 応答時間予算の検証のために管理者としてログインしている

  @budget-action:createCustomTagTemplateAction
  シナリオ: テンプレートの作成(Server Action)の往復が3秒以内に返る
    もし テンプレートギャラリーで新しいテンプレートを作成して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:updateCustomTagTemplateAction
  シナリオ: テンプレートの編集(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用に未公開のカスタムタグテンプレートがある
    もし テンプレートギャラリーでそのテンプレートの名前を変えて保存し Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:deleteCustomTagTemplateAction
  シナリオ: テンプレートの削除(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用に未公開のカスタムタグテンプレートがある
    もし テンプレートギャラリーでそのテンプレートを削除して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:publishCustomTagTemplateAction
  シナリオ: テンプレートの公開(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用に未公開のカスタムタグテンプレートがある
    もし テンプレートギャラリーでそのテンプレートを公開して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:unpublishCustomTagTemplateAction
  シナリオ: テンプレートの公開取り下げ(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用に公開済みのカスタムタグテンプレートがある
    もし テンプレートギャラリーでそのテンプレートを非公開に戻して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:cloneCustomTagTemplateAction
  シナリオ: テンプレートの複製(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトに公開済みのカスタムタグテンプレートがある
    もし テンプレートギャラリーでそのテンプレートを複製して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:applyCustomTagTemplateAction
  シナリオ: テンプレートをプロジェクトで使う(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトに公開済みのカスタムタグテンプレートがある
    もし テンプレートギャラリーでそのテンプレートを新しいタグ名でプロジェクトで使い Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @stub @budget-action:validateCustomTagAction
  シナリオ: カスタムタグの検証(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のカスタムタグ用プロジェクトがある
    もし カスタムタグ管理画面でAIにタグの生成を要求し、結果を開いて自動で走る検証の Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る
