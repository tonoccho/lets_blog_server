# language: ja
@response-budget @retries:2 @slow
機能: プロジェクトのガベージコレクションの Server Action が3秒の予算内に返る

  `docs/ACCEPTANCE_CRITERIA.md` §10.5 の `app/projects/[id]/actions.ts` のうち、ガベージコレクション
  (未参照メディアのスキャン・削除、削除ジョブの進捗の取得)(issue #1477)。書式と計り方は
  `server-action-project-bulk.feature` の冒頭を読むこと。

  - **実 WordPress** を相手にする。一括管理と同じ AT-7 の比較用プロジェクト(ローカル環境 = `at7cmptarget`)を
    使い、wp-cli でどの投稿からも参照されないメディアを1件だけ作る(後片付けで必ず消す)。
    共有のプロジェクトとサイトは消さない(`responseBudgetWp.ts` の `useBulkProject`)。
  - 削除のシナリオは、削除の開始(`deleteMediaGarbageAction`)に加えて、画面が完了まで自動で送る
    ジョブの進捗の取得(`fetchGenerationJobAction`)と削除後の再スキャン(`fetchMediaGarbageScanAction`)も
    同じ操作の往復なので、そのうち**最も遅い往復**を判定する(どれか1つでも3秒を超えれば落ちる)。
  - 削除対象は、そのシナリオが作った1件だけを選ぶ(共有サイトにほかの未参照メディアがあっても消さない)。

  背景:
    前提 応答時間予算の検証のために管理者としてログインしている
    かつ 応答時間予算の検証用に、AT-7 のテストとローカルの実 WordPress を紐付けた比較用プロジェクトを使う

  @budget-action:fetchMediaGarbageScanAction
  シナリオ: 未参照メディアのスキャン(Server Action)の往復が3秒以内に返る
    前提 ローカルの実 WordPress にどの投稿からも参照されない検証用のメディアがある
    もし ガベージコレクションでローカル環境をスキャンして Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る

  @budget-action:deleteMediaGarbageAction @budget-action:fetchGenerationJobAction
  シナリオ: 未参照メディアの削除とジョブの進捗の取得(Server Action)の往復が3秒以内に返る
    前提 ローカルの実 WordPress にどの投稿からも参照されない検証用のメディアがある
    もし ガベージコレクションでローカル環境をスキャンして検証用のメディアだけを削除し Server Action の往復を計測する
    ならば Server Action の往復は「3000」ミリ秒以内に返る
