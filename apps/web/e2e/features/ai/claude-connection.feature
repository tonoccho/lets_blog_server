# language: ja
@project @api @ai
機能: AI・アセットタブでのClaude接続情報の表示とAPIキーによる接続

  プロジェクト詳細画面の「AI・アセット」タブ(LLMタブ)で、Claude(Anthropic)の接続状態と設定の出所を
  確認し、AnthropicコンソールでAPIキーを発行して貼り付けることでそのプロジェクトだけが接続できる
  ことを固定する(issue #1507)。状態は `GET /api/projects/{id}/ai-connections`(#1499)の
  CLAUDE行、保存・削除は `PUT` / `DELETE /api/projects/{id}/api-keys/claude-api-key`。

  実際のAnthropicへはリクエストを送らない(保存時にキーの有効性は検証しない)。キーの値は画面にも
  HTMLソースにも `ai-connections` の応答にも現れない。プロジェクトのキーがLLM生成に使われること
  (受け入れ基準4)はWeb UIから観測できないため、ai-serviceのサービスレベルテスト
  (`RemoteLlmConfigProviderTest`)で検証する。

  背景:
    前提 Claude接続検証用のプロジェクトがある

  シナリオ: Claudeの接続状態と設定の出所が表示され、保存済みでもキーの値はどこにも現れない
    前提 プロジェクトのClaude APIキーが「sk-at-1507-visible-check」で設定されている
    もし Claude接続検証用のプロジェクトのLLMタブを開く
    ならば Claudeの接続状態が「接続済み」で設定の出所が「プロジェクト設定」と表示される
    かつ 画面のHTMLソースに「sk-at-1507-visible-check」が含まれない
    かつ Claudeのai-connectionsの応答に「sk-at-1507-visible-check」が含まれない

  シナリオ: キーを発行するリンクはAnthropicコンソールを新規タブで開く
    もし Claude接続検証用のプロジェクトのLLMタブを開く
    ならば Claudeの「キーを発行する」リンクの遷移先が「https://platform.claude.com/settings/keys」で新規タブで開く
    かつ APIキーは従量課金でClaudeのサブスクリプションとは別契約である旨が表示される

  シナリオ: APIキーを入力して接続すると接続済み・プロジェクト設定になり、再読み込み後も維持される
    もし Claude接続検証用のプロジェクトのLLMタブを開く
    かつ Anthropic APIキー欄に「sk-at-1507-connect」を入力して接続する
    ならば Claudeの接続状態が「接続済み」で設定の出所が「プロジェクト設定」と表示される
    もし 画面を再読み込みしてClaude接続検証用のLLMタブを開く
    ならば Claudeの接続状態が「接続済み」で設定の出所が「プロジェクト設定」と表示される

  シナリオ: 空のまま接続するとエラーが表示され、何も保存されない
    もし Claude接続検証用のプロジェクトのLLMタブを開く
    かつ Anthropic APIキー欄を空のまま接続する
    ならば ClaudeのAPIキー未入力のエラーが表示される
    かつ プロジェクトのClaude APIキーは保存されていない

  シナリオ: 接続を解除するとプロジェクトのキーが削除され、システム設定へのフォールバックに戻る
    前提 プロジェクトのClaude APIキーが「sk-at-1507-disconnect」で設定されている
    もし Claude接続検証用のプロジェクトのLLMタブを開く
    かつ Claudeの接続を解除する
    ならば Claudeの設定の出所が「プロジェクト設定」ではなくなる
    かつ プロジェクトのClaude APIキーは保存されていない
