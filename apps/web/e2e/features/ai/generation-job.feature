# language: ja
@ai @destructive @api @mode:serial
機能: 生成ジョブの状態照会

  メディアのガベージコレクション削除(`POST /api/projects/{id}/media-garbage-collection/delete`、
  `MediaGarbageCollectionService#startDelete`)が作る実ジョブを題材に、`GET /api/generation-jobs`
  (一覧)と `GET /api/generation-jobs/{id}`(詳細)が実行中/失敗の状態を利用者に返すことを
  固定する(issue #1151 / AT-8-6、issue #934のシナリオ12・13を引き取る子issue)。

  ## なぜAI執筆支援(draft/ask/section)ではなくメディアGCを使うか

  `AiAssistService` の draft / ask / section は全て同期呼び出しで、非同期ジョブ経路を
  持たない。ジョブの作成・更新は `InternalGenerationJobController`(`/api/internal/**`)に
  移設済みで、gatewayはこの配下をルーティングしないため外部から到達できない(#830)。
  一方 `MediaGarbageCollectionService#startDelete` は `GenerationJobClient` 経由で
  実ジョブを作り、ComfyUIにもGPUにも依存しない(issue #1151のDecision Record)。
  ジョブ完了(成功)状態は `media/media-garbage-collection.feature` が既に押さえているため、
  ここでは一覧・実行中・失敗の3点だけを追加する。

  ## なぜ存在しないメディアIDで足りるか

  `InternalGenerationJobController#create` はジョブ作成直後の状態を常に "running" にする
  (実装コメント参照)。そのため、削除対象が実在するかに関わらず、作成直後に照会すれば
  "running" を観測できる。さらに存在しないメディアIDを渡すと
  `MediaGarbageCollectionJobRunner#runDelete` が全件失敗し、最終状態が "failed" になる
  (`deleted.isEmpty() && !failures.isEmpty()` の分岐)。この2点を使えば、実メディアを
  一切用意しなくても一覧・実行中・失敗の3つを確認できる。

  ## なぜ `@destructive` か

  マネージドWordPressサイトを新規に構築する(docs/ACCEPTANCE_TESTING.md §10)。

  背景:
    前提 生成ジョブの状態照会を確かめるためのWordPressサイトを持つプロジェクトがある

  シナリオ: 起動直後の生成ジョブは、ID照会でも一覧でも実行中として現れる
    もし 存在しないメディアIDを指定してガベージコレクションの削除を起動する
    ならば 起動直後のジョブをIDで照会すると実行中の状態が返る
    かつ 生成ジョブの一覧にそのジョブが含まれ、状態が返る

  シナリオ: 削除できるメディアが1件も無いジョブは、最終的に失敗として理由付きで返る
    もし 存在しないメディアIDを指定してガベージコレクションの削除を起動する
    ならば そのジョブは最終的に失敗の状態になる
    かつ 失敗の理由が結果に含まれる
