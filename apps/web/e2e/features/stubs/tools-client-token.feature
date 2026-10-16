# language: ja
@stub @api @destructive @project @ai
機能: lets_blog から extensions のツールへの呼び出しに専用クライアントのトークンが付く

  システム設定の接続先(Ollama・ComfyUI・ポータル)と `PLANTUML_BASE_URL` の宛先への呼び出しには、
  専用クライアント(`letsblog-tools-client`、issue #1804)の Keycloak トークンが付く。
  スタブは受けた Authorization を `/__control/state` の `authorizationsByPath` に記録するだけで、検証しない
  (値はスタブのログにも応答の本文にも出ない)。トークンの検証は、ここのステップが行う:
  JWT の署名を Keycloak の JWKS で検証し、`iss`・`azp`(`letsblog-tools-client`)・`aud`(`lbx-tools`)を確かめる。

  ## 前提

  サービスに `KEYCLOAK_TOOLS_CLIENT_SECRET` が渡っていて、Keycloak に `letsblog-tools-client` がある
  (新規構築の realm import、または既存環境では `infra/keycloak/README.md` の kcadm の手順)。
  スタブの受信履歴をリセットするので、他のスタブのシナリオと並列に走らせないよう `@destructive` で単独実行する。

  背景:
    前提 extensions のツールのスタブの受信履歴が空である

  シナリオ: LLM の生成で、システム設定の Ollama へ専用クライアントのトークンが付く
    前提 システム全体のLLMプロバイダーをOllamaへ変更する
    もし 「E2Eスタブの下書きテーマ」というお題で下書き生成を依頼する
    ならば LLMスタブが受けた「POST /chat/completions」の Authorization は専用クライアントのトークンである

  シナリオ: モデルの一覧の取得で、システム設定の Ollama へ専用クライアントのトークンが付く
    前提 AI設定用のプロジェクトが用意されている
    もし プロジェクト詳細の「AI・アセット」のLLM画面を開く
    かつ 画面でAIプロバイダーを「OLLAMA」に切り替える
    ならば LLMスタブが受けた「GET /api/tags」の Authorization は専用クライアントのトークンである

  シナリオ: モデルの pull で、システム設定の Ollama へ専用クライアントのトークンが付く
    前提 接続情報パネル検証用のプロジェクトがある
    もし 接続情報パネル検証用のプロジェクトの設定タブを開く
    かつ Ollamaのモデル名欄に「e2e-pull-ok:1b」を入力してインストールを押す
    ならば 画面に「e2e-pull-ok:1b」のインストール完了が表示される
    かつ LLMスタブが受けた「POST /api/pull」の Authorization は専用クライアントのトークンである

  シナリオ: 接続テストで、システム設定の Ollama と ComfyUI へ専用クライアントのトークンが付く
    前提 接続情報パネル検証用のプロジェクトがある
    もし 接続情報パネル検証用のプロジェクトの設定タブを開く
    ならば LLMスタブが受けた「GET /models」の Authorization は専用クライアントのトークンである
    かつ ComfyUIスタブが受けた「GET /system_stats」の Authorization は専用クライアントのトークンである

  シナリオ: ComfyUI の画像生成で、システム設定の ComfyUI へ専用クライアントのトークンが付く
    前提 画像生成にComfyUIを使うプロジェクトがある
    もし そのプロジェクトで参照画像を付けずに画像生成をジョブとして要求する
    かつ 画像生成のジョブが終わるまで待つ
    ならば ComfyUIスタブが受けた「POST /prompt」の Authorization は専用クライアントのトークンである

  シナリオ: PlantUML の描画で、PLANTUML_BASE_URL の宛先へ専用クライアントのトークンが付く
    前提 PlantUMLスタブが起動していて初期状態である
    もし PlantUMLソース「@startuml\nA->B\n@enduml」の描画を要求する
    ならば PlantUMLスタブが受けた「GET /png/」の Authorization は専用クライアントのトークンである

  シナリオ: 連携サービスの状況で、Ollama・ComfyUI・PlantUML へ専用クライアントのトークンが付く
    前提 システム全体のLLMプロバイダーをOllamaへ変更する
    かつ 管理者としてログインする
    もし トップページを開く
    ならば 連携サービスの状況の「ComfyUI」の行に演算デバイス「cpu」が表示される
    かつ LLMスタブが受けた「GET /models」の Authorization は専用クライアントのトークンである
    かつ ComfyUIスタブが受けた「GET /system_stats」の Authorization は専用クライアントのトークンである
    かつ PlantUMLスタブが受けた「GET /png/」の Authorization は専用クライアントのトークンである

  シナリオ: ポータルからの取り込みで、ポータルへ専用クライアントのトークンが付く
    前提 システム設定のOllama・ComfyUI・PenpotのURLを既知の値にして、元の値を控える
    かつ ポータルスタブが起動していて初期状態である
    かつ 管理者としてログインする
    もし システム設定画面の「extensions から取り込む」欄で、ポータルのURLを「http://lbx-portal:3100」、経路を「同じ PC」にして取得する
    ならば ポータルスタブが受けた「GET /api/connections」の Authorization は専用クライアントのトークンである
