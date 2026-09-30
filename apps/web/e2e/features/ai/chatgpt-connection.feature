# language: ja
@project @api @ai
機能: AI・アセットタブでのChatGPT接続情報の表示とAPIキーによる接続

  プロジェクト詳細画面の「AI・アセット」タブ(LLMタブ)で、ChatGPT(OpenAI)の接続状態と設定の出所を
  確認し、OpenAIコンソールでAPIキーを発行して貼り付けることでそのプロジェクトだけが接続できる
  ことを固定する(issue #1506)。状態は `GET /api/projects/{id}/ai-connections`(#1499)の
  OPENAI行、保存・削除は `PUT` / `DELETE /api/projects/{id}/api-keys/openai-api-key`。

  実際のOpenAIへはリクエストを送らない(保存時にキーの有効性は検証しない)。キーの値は画面にも
  HTMLソースにも `ai-connections` の応答にも現れない。プロジェクトのキーがLLM生成に使われること
  (受け入れ基準4)はWeb UIから観測できないため、ai-serviceのサービスレベルテスト
  (`RemoteLlmConfigProviderTest`)で検証する。

  背景:
    前提 ChatGPT接続検証用のプロジェクトがある

  シナリオ: ChatGPTの接続状態と設定の出所が表示され、保存済みでもキーの値はどこにも現れない
    前提 プロジェクトのChatGPT APIキーが「sk-at-1506-visible-check」で設定されている
    もし ChatGPT接続検証用のプロジェクトのLLMタブを開く
    ならば ChatGPTの接続状態が「接続済み」で設定の出所が「プロジェクト設定」と表示される
    かつ 画面のHTMLソースに「sk-at-1506-visible-check」が含まれない
    かつ ai-connectionsの応答に「sk-at-1506-visible-check」が含まれない

  シナリオ: キーを発行するリンクはOpenAIコンソールを新規タブで開く
    もし ChatGPT接続検証用のプロジェクトのLLMタブを開く
    ならば 「キーを発行する」リンクの遷移先が「https://platform.openai.com/api-keys」で新規タブで開く
    かつ APIキーは従量課金でサブスクリプションとは別契約である旨が表示される

  シナリオ: APIキーを入力して接続すると接続済み・プロジェクト設定になり、再読み込み後も維持される
    もし ChatGPT接続検証用のプロジェクトのLLMタブを開く
    かつ OpenAI APIキー欄に「sk-at-1506-connect」を入力して接続する
    ならば ChatGPTの接続状態が「接続済み」で設定の出所が「プロジェクト設定」と表示される
    もし 画面を再読み込みしてChatGPT接続検証用のLLMタブを開く
    ならば ChatGPTの接続状態が「接続済み」で設定の出所が「プロジェクト設定」と表示される

  シナリオ: 空のまま接続するとエラーが表示され、何も保存されない
    もし ChatGPT接続検証用のプロジェクトのLLMタブを開く
    かつ OpenAI APIキー欄を空のまま接続する
    ならば ChatGPTのAPIキー未入力のエラーが表示される
    かつ プロジェクトのChatGPT APIキーは保存されていない

  シナリオ: 接続を解除するとプロジェクトのキーが削除され、システム設定へのフォールバックに戻る
    前提 プロジェクトのChatGPT APIキーが「sk-at-1506-disconnect」で設定されている
    もし ChatGPT接続検証用のプロジェクトのLLMタブを開く
    かつ ChatGPTの接続を解除する
    ならば ChatGPTの設定の出所が「プロジェクト設定」ではなくなる
    かつ プロジェクトのChatGPT APIキーは保存されていない
