# language: ja
@project @ai
機能: プロジェクトダッシュボードのAI接続状況ウィジェット

  ダッシュボードを見るだけで、Ollama / ComfyUI / ChatGPT / Claude の4つのAIの利用可否と、
  利用不可なら接続導線(設定タブへのリンク)が分かることを固定する(issue #1501。親Epic #1498)。
  データは `GET /api/projects/{id}/ai-connections`(#1499)。利用可能は configured=true かつ
  status が NORMAL / WARNING、それ以外は利用不可。

  次の項目はWebの画面操作からは再現できないため、jestテストで固定する
  (`dashboard/__tests__/AiConnectionWidget.test.tsx` と `ProjectDashboardPage.test.tsx`)。
  - status=WARNING が利用可能として扱われること、status=ERROR が利用不可になること(状態を作れない)
  - 取得失敗時に「0件」ではなく取得失敗を示すこと(APIの失敗を画面単位で作り分けられない)
  - 取得が終わらなくても他ウィジェットが表示されること(ページ描画の非ブロックはUIから観測できない)

  背景:
    前提 AI接続状況ウィジェット検証用のプロジェクトがある

  シナリオ: ダッシュボードにOllama / ComfyUI / ChatGPT / Claudeの4行が利用可否バッジつきで表示される
    もし AI接続状況を見るためにダッシュボードを開く
    ならば AI接続状況ウィジェットにOllama、ComfyUI、ChatGPT、Claudeの4行が表示される
    かつ 4行のどれにも「利用可能」か「利用不可」のバッジが表示される

  シナリオ: 利用不可のChatGPTの行から設定タブへ進める
    もし AI接続状況を見るためにダッシュボードを開く
    ならば AI接続状況のChatGPTの行は「利用不可」で設定タブへのリンクがある
    もし AI接続状況のChatGPTの行のリンクを押す
    ならば プロジェクト詳細の設定タブが選択されている

  シナリオ: 利用可能なChatGPTの行には接続へのリンクが表示されない
    前提 AI接続状況検証用のプロジェクトのChatGPT APIキーが設定されている
    もし AI接続状況を見るためにダッシュボードを開く
    ならば AI接続状況のChatGPTの行は「利用可能」でリンクがない
