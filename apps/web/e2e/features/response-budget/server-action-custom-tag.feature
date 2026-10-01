# language: ja
@response-budget
機能: カスタムタグ関連の Server Action が3秒の予算内に返る

  `docs/ACCEPTANCE_CRITERIA.md` §10.5 の `app/custom-tag-templates/actions.ts` と
  `app/custom-tags/actions.ts`(issue #1477)。書式と計り方は `page-first-display.feature` /
  `server-action-admin.feature` の冒頭を読むこと。

  - テンプレートギャラリーの4操作(公開・非公開に戻す・複製・プロジェクトで使う)は、シナリオごとに
    API で作るテンプレート・プロジェクトを対象にする(後片付けは `responseBudgetCleanup.steps.ts`)。
  - `createCustomTagTemplateAction` / `updateCustomTagTemplateAction` / `deleteCustomTagTemplateAction` は
    画面のどこからも呼ばれていないので、シナリオを置けない。§10.5 の該当行に
    「受け入れシナリオから到達できない」と理由を記録してある(照合は `scripts/check-response-budget-coverage.py`)。
  - `validateCustomTagAction` は、AI でカスタムタグを生成した**直後に自動で**走る(生成フォームの
    `useCustomTagValidation`)。生成(`generateCustomTagAction`)自体は予算対象外(外部LLMの応答時間に依存。
    §10.5)なので、LLM スタブ(`@stub`)に生成させ、続けて送られる **2回目の Server Action の往復**
    (検証)だけを計る。

  背景:
    前提 応答時間予算の検証のために管理者としてログインしている

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
    もし カスタムタグ管理画面でAIにタグを生成させ、続いて自動で走る検証の Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る
