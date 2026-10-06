# language: ja
@ai @api @slow @destructive @requires-real-ai-cpu @timeout:1800000
機能: CPU構成のOllamaでのAI生成(実機AIレーン)

  CPU 構成の Ollama(`docker-compose.yml` の `ollama-cpu`、コンテナ `lbs-ollama-cpu`、
  issue #1585)で、実際に LLM の応答が返ることを確かめる(issue #1402)。
  網羅ではなく「一応動く」ことの確認で、応答内容の品質は見ない。

  ## なぜ専用のレーン(`@requires-real-ai-cpu`)か

  AT 環境の LLM 経路は常にスタブ(`llm-stub`)へ向き、`ollama` / `ollama-model-init` は
  起動しない(`docker-compose.e2e-stubs.yml` の `profiles: ["ollama"]`、#1090)。
  実機を相手にした生成は、コンテナの起動とモデル取得を要求し、既定の実行(`test:at:fast`)や
  リリース検証の所要時間・安定性を損なう。そこで #1401 が作った `@requires-real-ai-cpu`
  (`AT_EXCLUDE_REQUIRES_REAL_AI_CPU=1`)に乗る。新しいタグ・環境変数・プロジェクトは足さない。

  - `test:at:fast` は `@slow` を除くので、このシナリオは含まれない。
  - リリース検証は `AT_EXCLUDE_REQUIRES_REAL_AI_CPU=1` でこのタグを除外し、除外した一覧をログに記録する。
  - 除外指定なしの手動の全件実行では対象に含まれ、CPU 構成の Ollama を起動できなければ**明示的に失敗する**。

  ## 向き先の切り替え

  システム設定 `llm_provider` / `llm_ollama_base_url` / `llm_ollama_model`(DB)は環境変数より
  優先される。これを CPU 構成の Ollama と小さなモデルへ書き換えて呼び出し、**必ず元へ戻す**。
  システム全体を書き換えるので `@destructive`(`at-destructive`、`workers: 1`)に置く。

  ## 120秒の予算

  `LLM_REQUEST_TIMEOUT_SECONDS`(既定120秒)は広げない。既定モデル(`qwen2.5:7b-instruct`、4.7GB)は
  使わず、小さいモデル(`qwen2.5:0.5b-instruct`、約400MB)と短い入力で収める。

  シナリオ: CPU構成のOllamaへ向けてタグ提案を呼ぶと応答が返り、終了後は向き先が元に戻る
    前提 CPU構成のOllamaを起動し小さなモデルを用意する
    かつ システム全体のLLM接続設定をCPU構成のOllamaへ切り替える
    もし 短い本文でタグ提案を要求する
    ならば 実機のLLMが120秒の予算内に応答を返す
    もし システム全体のLLM接続設定を切り替え前へ戻す
    ならば システム全体のLLM接続設定は切り替え前になっている
