# language: ja
@response-budget @retries:2
機能: プロジェクトのタグ画面の Server Action が3秒の予算内に返る

  `docs/ACCEPTANCE_CRITERIA.md` §10.5 の `app/projects/[id]/custom-tags/actions.ts` と
  `app/projects/[id]/tag-design/actions.ts` のうち保存・削除の操作(issue #1477)。書式と計り方は
  `page-first-display.feature` / `server-action-project-settings.feature` の冒頭を読むこと。

  - 舞台はプロジェクトの `/projects/[id]/tags`(「組み込みタグのデザイン」「カスタムタグ管理」の2タブ)。
    前提(プロジェクト・カスタムタグ)は API で用意し、操作は実際の画面で行う。
  - 汎用ステップ(`responseBudgetProject.steps.ts`)の「入力して押す」を使う。ページ内のタブは
    `/tags#カスタムタグ管理` のように場所の後ろへ `#` で書く。
  - カスタムタグはプロジェクトを消しても残る(ADR-0004)ので、シナリオが作ったタグは後片付けで個別に消す
    (`responseBudgetProjectTags.steps.ts`)。
  - `generateTagDesignAction` は外部LLM / 画像生成に依存するので予算対象外(§10.5)。ここには置かない。

  背景:
    前提 応答時間予算の検証のために管理者としてログインしている

  @budget-action:saveTagDesignSettingAction
  シナリオ: 組み込みタグのデザインの保存(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし プロジェクトの「/tags」画面で入力「customCss=.e2e1477-budget{color:rgb(1,2,3)}」して「保存」を押し「保存しました。」を確認して「組み込みタグのデザインの保存」の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:updateProjectCssSelectorPrefixAction
  シナリオ: CSSセレクタ接頭辞の保存(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    もし プロジェクトの「/tags#カスタムタグ管理」画面で入力「cssSelectorPrefix=e2e1477」して「接頭辞を保存」を押し「CSSセレクタ接頭辞を保存しました。」を確認して「CSSセレクタ接頭辞の保存」の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:upsertProjectCustomTagAction
  シナリオ: プロジェクトのカスタムタグの追加(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    かつ そのプロジェクトのカスタムタグは後片付けの対象にする
    もし カスタムタグ管理で「e2e1477add」のタグを追加して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:deleteProjectCustomTagAction
  シナリオ: プロジェクトのカスタムタグの削除(Server Action)の往復が3秒以内に返る
    前提 応答時間予算の検証用のプロジェクトがある
    かつ そのプロジェクトのカスタムタグは後片付けの対象にする
    かつ そのプロジェクトにカスタムタグ「e2e1477del」がある
    もし カスタムタグ管理で「e2e1477del」のタグを削除して Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る
