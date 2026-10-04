# language: ja
@media @api @slow
機能: ComfyUIでの画像生成

  記事に載せる挿絵を、プロンプトから作る(issue #936 / AT-10、AC-IMG-001 / AC-IMG-009)。
  画像生成AIは ComfyUI(自前ホスト・GPU必須)と ChatGPT(外部API)の2つで、
  どちらを使うかはプロジェクトごとに選べる。

  ## 実機の ComfyUI を要求する(docs/ACCEPTANCE_TESTING.md §9)

  実際に絵が出ることはスタブでは分からないので、**生成そのもの**は実機の ComfyUI で
  確かめる。したがってこのフィーチャ全体に `@slow` を付け、GPU を持たないホストでは
  **明示的に失敗させる**。暗黙のスキップにはしない — スキップに落とすと、その経路が
  恒常的に未検証のまま誰にも気づかれずに残る(#843 の轍)。
  `docker-compose.e2e-stubs.yml` を重ねた構成でこのフィーチャを回さないこと。
  その構成では `COMFYUI_BASE_URL` がスタブを指すため、実機で生成できることを
  誰も検証しなくなる。

  プロバイダの切り替えと失敗時のふるまい(#936 のシナリオ3・4)は実機を要求しない。
  ChatGPT の画像生成スタブを使う `image-generation-chatgpt.feature` にある。

  ## 画面ではなくAPIから確かめる理由(`@api`)

  ここは既に `asset-image-batch-form.feature` が画面から押さえている「フォームが何を送るか」
  ではなく、**送った要求に対してサーバーが何をしたか**を見る。同じ理由で
  `image-batch-count.feature`(#1102)も API から確かめている。画面としての一覧・詳細は
  `image-gallery.feature`(シナリオ5・6)が受け持つ。

  したがって全シナリオに `@api` を付け、ブラウザのログインを前提に置かない
  (docs/ACCEPTANCE_TESTING.md §4)。前提のプロジェクトは管理者のトークンで API から作る。

  ## upload-endpoint 枠(docs/ACCEPTANCE_TESTING.md §9)

  `POST /api/ai/image` は gateway の upload-endpoint バケット(**プロセス全体で1時間に10回**)
  に属する。このフィーチャの消費は2で、いずれも `@slow` なので通常実行(`test:at:fast`)では
  消費しない。`@slow` を除いた通常実行の内訳は `image-batch-count.feature` が5、
  `image-generation-chatgpt.feature` が3、`image-settings.feature` が1で、合計9である
  (`asset-image-batch-form.feature` は #1408 で非同期経路 `POST /api/ai/image/jobs`
  (api-global)へ移り、この枠を消費しない)。`@slow` を含めると合計11になり、本番の枠(10)を1つ超える(以前は12で2つ超過)。
  枠を増やしてよいという意味ではない。
  生成を伴うシナリオをこれ以上足すときは、この内訳を数え直すこと。

  シナリオ: プロンプトを指定してComfyUIで画像を生成すると、生成画像の一覧に現れる
    前提 画像生成にComfyUIを使うプロジェクトがある
    もし そのプロジェクトで「an orange cat sitting on a blue chair」の画像生成を要求する
    ならば 生成された画像が「1」枚返る
    かつ 返った画像がすべて生成画像の一覧に現れる

  シナリオ: 生成に使ったプロンプト・サイズ・チェックポイントが生成画像の詳細に残る
    前提 画像生成にComfyUIを使うプロジェクトがある
    もし そのプロジェクトで「a red bicycle in the rain」を「512」x「512」で画像生成を要求する
    ならば 生成された画像の詳細のプロンプトに「a red bicycle in the rain」が含まれる
    かつ 生成された画像の詳細のサイズは「512」x「512」である
    かつ 生成された画像の詳細にチェックポイント名が残っている
