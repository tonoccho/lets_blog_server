# language: ja
@api @stub @ai @mode:serial @stub-isolation:llm
機能: LLMのモデルを、プロバイダーが実際に提供する一覧からドロップダウンで選ぶ

  プロジェクト詳細の「AI・アセット」→ LLM で、モデル欄はドロップダウンで、選択肢は選んでいるプロバイダーへ
  実際に問い合わせて取得した一覧である(issue #1674)。取得元は、Ollama が実効接続先の `GET /api/tags`、
  ChatGPT(OpenAI)が `GET /v1/models`、Claude(Anthropic)が `GET /v1/models`(`x-api-key` と
  `anthropic-version` つき)。OpenAI は返った ID を絞り込まずにすべて出す。手入力欄は無い。

  取得に失敗したとき(接続先に届かない・APIキー未設定・認証エラー)は、システム設定のモデル一覧
  (`availableModelsFor`)を候補に出し、一覧を取得できなかったことを画面に表示する。そのとき、
  選択中のモデルは、一覧に無くても選択肢に残り、選択された状態のまま表示される。

  ## どの層で確かめるか

  受け入れ環境の LLM スタブ(infra/e2e-stubs/llm/server.js)が、OpenAI 向けに `GET /v1/models`、
  Ollama 向けに `GET /api/tags` を返す(どちらも2件)。AT の既定の Ollama 接続先(システム設定)はこのスタブである。
  Claude にはスタブが無いため、`x-api-key` / `anthropic-version` の送信と返ったIDの反映は
  サービス層のテスト(ProviderModelCatalogTest)で確かめる。認証エラー(401/403)もそこで確かめる。
  プロジェクトの Ollama 接続先の上書きが宛先検査(#1547)を通ること、`/v1` を除いた根へ問い合わせること、
  3秒以内のタイムアウトも ProviderModelCatalogTest / RemoteLlmConfigProviderTest で確かめる。

  シナリオ: ChatGPTのプロジェクトでは、モデル欄がドロップダウンで、選択肢はLLMスタブの返すモデルIDと一致し、選んで保存したモデルが以後の生成に使われる
    前提 AI設定用のプロジェクトが用意されている
    もし プロジェクト詳細の「AI・アセット」のLLM画面を開く
    かつ 画面でAIプロバイダーを「OPENAI」に切り替える
    ならば 画面のモデル欄はドロップダウンで、選択肢に「e2e-stub-gpt-a」と「e2e-stub-gpt-b」が含まれる
    かつ モデルの一覧のAPIの選択肢は「e2e-stub-gpt-a」と「e2e-stub-gpt-b」だけである
    もし 画面でモデル「e2e-stub-gpt-b」を選んで保存する
    かつ プロジェクト詳細の「AI・アセット」のLLM画面を開く
    ならば 画面の選択中のモデルが「e2e-stub-gpt-b」になっている
    もし 「猫のイラストの画像を作りたい」という発言で画像プロンプト生成を依頼する
    ならば LLMスタブが受け取った直近のリクエストに、画面で保存した「e2e-stub-gpt-b」を使ったものが含まれる

  シナリオ: Ollamaのプロジェクトでは、選択肢は実効Ollama接続先の /api/tags が返すモデル名と一致する
    前提 AI設定用のプロジェクトが用意されている
    もし プロジェクト詳細の「AI・アセット」のLLM画面を開く
    かつ 画面でAIプロバイダーを「OLLAMA」に切り替える
    ならば 画面のモデル欄はドロップダウンで、選択肢に「e2e-stub-ollama-a:1b」と「e2e-stub-ollama-b:1b」が含まれる
    かつ モデルの一覧のAPIの選択肢は「e2e-stub-ollama-a:1b」と「e2e-stub-ollama-b:1b」だけである

  シナリオ: Ollamaの接続先に届かないとき、一覧を取得できなかった旨が表示され、システム設定の一覧が候補になり、選択中のモデルは残る
    前提 AI設定用のプロジェクトが用意されている
    かつ AI設定用のプロジェクトのOllama接続先が「http://at-1674-down.invalid:11434/v1」に設定されている
    かつ AI設定用のプロジェクトのOllamaのモデルが「e2e-unlisted-model」に保存されている
    もし プロジェクト詳細の「AI・アセット」のLLM画面を開く
    ならば 画面に、モデル一覧を取得できなかった旨が表示される
    かつ 画面の選択中のモデルが「e2e-unlisted-model」になっている
    かつ 画面のモデル欄の選択肢に、LLMスタブが返すモデルは含まれない
    かつ 画面のモデル欄の選択肢に「e2e-unlisted-model」が含まれる
    かつ 画面の保存ボタンとAIプロバイダーの選択は操作できる

  シナリオ: ClaudeのAPIキーが未設定のとき、一覧を取得できなかった旨が表示され、選択中のモデルは残る
    前提 AI設定用のプロジェクトが用意されている
    もし プロジェクト詳細の「AI・アセット」のLLM画面を開く
    かつ 画面でAIプロバイダーを「CLAUDE」に切り替える
    ならば 画面に、モデル一覧を取得できなかった旨が表示される
    かつ 画面の保存ボタンとAIプロバイダーの選択は操作できる
    かつ 画面の選択中のモデルがシステム既定のClaudeモデルになっている
