# language: ja
@media @api @stub @mode:serial
機能: 画像生成のジョブとしての非同期受理

  画像生成は完了まで応答を返さない同期API(`POST /api/ai/image`)のほかに、要求を
  ジョブとして受理して即座にジョブIDを返す口(`POST /api/ai/image/jobs`)を持つ
  (issue #1405、親 #1404 の第1段)。生成は背景で進み、`GET /api/generation-jobs/{id}` で
  状態と結果(生成画像のID)を取得する。同期APIは変えない(`image-batch-count.feature` ほか
  既存の `@api` シナリオが無改変で通ることが、その保証である)。

  ## GPU を要求しない(docs/ACCEPTANCE_TESTING.md §9)

  確かめたいのは「即座に受理される」「結果が画像IDで引ける」「失敗の理由が読める」ことで、
  絵の中身ではない。外部APIは ChatGPT の画像生成スタブ(`@stub`)へ置き換える。
  失敗の注入はスタブ全体の状態を触るため、`image-generation-chatgpt.feature` と同じ理由で
  フィーチャ全体を直列にする(`@mode:serial`)。

  ## 受け入れテストで確かめられない観点

  「実行中は進行状況が返る」は、スタブが即座に応答するため実行中の状態を観測できない。
  進捗の書き込みは `ImageGenerationJobRunnerTest`(サービスレベル)が固定している。
  ここでは受理直後のジョブが `running` として返ることと、完了後に結果が引けることを見る。

  ## レート制限バケット(docs/ACCEPTANCE_TESTING.md §9)

  `POST /api/ai/image/jobs` は upload-endpoint 枠(プロセス全体で1時間に10回)に**入れない**
  (api-global に置く)。したがって本フィーチャは upload-endpoint 枠を消費しない。
  判断の理由は `RateLimitWebFilter#UPLOAD_BUCKET_EXACT_PATHS` のJavadocを参照。

  シナリオ: 画像生成をジョブとして要求すると、完了を待たずにジョブIDが返り、結果として生成画像のIDが引ける
    前提 画像生成にChatGPTを使うプロジェクトがある
    もし そのプロジェクトで「a paper plane over a desk」の画像生成をジョブとして要求する
    ならば 画像生成のジョブIDが「running」の状態で即座に返る
    かつ 画像生成のジョブが終わるまで待つ
    ならば そのジョブは「done」で終わり、結果に生成画像のIDが「1」件示される
    かつ 結果が示す画像はすべて生成画像の一覧に現れる

  シナリオ: ジョブとして要求した画像生成が失敗すると、ジョブがfailedになり理由が引ける
    前提 画像生成にChatGPTを使うプロジェクトがある
    かつ ChatGPTの画像生成が次の1回だけ「500」で失敗するようにする
    もし そのプロジェクトで「a broken generation attempt」の画像生成をジョブとして要求する
    かつ 画像生成のジョブが終わるまで待つ
    ならば そのジョブは「failed」で終わり、理由に失敗した画像生成AIと状態コード「500」が示される
    かつ そのプロジェクトの生成画像は1枚も残っていない
