# language: ja
@media @api @slow @destructive @requires-real-ai-cpu @timeout:1800000
機能: CPU構成のComfyUIでの画像生成(実機AIレーン)

  CPU 構成の ComfyUI(`docker-compose.yml` の `comfyui-cpu`、コンテナ `lbs-comfyui-cpu`、
  issue #1395)で、実際に画像が 1 枚生成されることを確かめる(issue #1401)。
  網羅ではなく「一応動く」ことの確認で、画質・枚数・seed はスタブ側のシナリオが受け持つ。

  ## なぜ専用のレーン(`@requires-real-ai-cpu`)か

  AT 環境の ComfyUI 経路は常にスタブ(`comfyui-stub`)へ向く(`docker-compose.e2e-stubs.yml` の
  `COMFYUI_BASE_URL`)。実機を相手にした生成は、起動しているコンテナと数 GB のモデルを要求し、
  既定の実行(`test:at:fast`)やリリース検証の所要時間・安定性を損なう。そこで
  `@requires-gpu`(`AT_EXCLUDE_REQUIRES_GPU`)と同型の生成時タグ式
  (`AT_EXCLUDE_REQUIRES_REAL_AI_CPU=1`、`apps/web/playwright.config.ts`)で除外できる別タグにした。

  - `test:at:fast` は `@slow` を除くので、このシナリオは含まれない。
  - リリース検証(`scripts/release-verify-tag.py`)は `AT_EXCLUDE_REQUIRES_REAL_AI_CPU=1` を設定して
    このタグを除外し、除外したシナリオの一覧を実行ログとタグ注釈に記録する。
  - 除外指定なしの手動の全件実行(`test:at` / `test:at:clean`)では対象に含まれ、CPU 構成の
    ComfyUI が起動していなければ**明示的に失敗する**(暗黙のスキップにしない)。
    起動は `docker compose --profile cpu up -d comfyui-cpu`。

  ## 向き先の切り替え

  システム設定 `comfyui_base_url`(DB)は環境変数より優先される。これを `http://lbs-comfyui-cpu:8188` へ
  書き換えて生成し、**必ずスタブ向き(元の設定)へ戻す**。システム全体を書き換えるので `@destructive`
  (`at-destructive`、`workers: 1`)に置く。

  ## 120秒の予算

  media の ComfyUI ポーリング予算は batchSize=1 で 120 秒(`ComfyUiClient`)。これを広げずに、
  1 ステップで生成できる SD-Turbo(`stabilityai/sd-turbo`)・512x512 を使って収める。
  初回だけ約 5.2GB のチェックポイントを導入する(`comfyui_models` は保全ボリュームなので以後は不要)。

  シナリオ: CPU構成のComfyUIへ向けて画像を1枚生成すると、生成画像の一覧に現れ、終了後は向き先がスタブに戻る
    前提 CPU構成のComfyUIが起動している
    かつ ComfyUIの向き先をCPU構成へ切り替える
    かつ 画像生成にComfyUIを使うプロジェクトがある
    かつ CPU向けの小さなチェックポイントが導入されている
    もし そのプロジェクトで「a red apple on a table」をCPU向けの低ステップ設定で画像生成を要求する
    ならば 実機の生成が120秒の予算内に1枚の画像を返す
    かつ 返った画像がすべて生成画像の一覧に現れる
    もし ComfyUIの向き先をスタブへ戻す
    ならば ComfyUIの向き先はスタブになっている
