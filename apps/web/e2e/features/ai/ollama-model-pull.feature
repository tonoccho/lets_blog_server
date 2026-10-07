# language: ja
@project @stub @ai @stub-isolation:llm
機能: プロジェクトのOllama接続先へのモデルのインストール(pull)

  プロジェクトの設定タブの「Ollamaの接続情報」から、Ollamaのモデルをインストール(pull)できる
  (issue #1675。API は `POST /api/projects/{id}/ai-models/ollama/pull`)。
  対象は、そのプロジェクトの実効Ollama接続先(プロジェクトの上書き → システム設定)である。
  pull は時間がかかるので、ボタンを押すと GenerationJob(type `ollama_model_pull`)を作ってすぐ応答し、
  ai-service が Ollama の `POST /api/pull`(ストリーミング応答)を非同期に実行して、`completed` / `total` を
  ジョブの進捗に反映する。画面はジョブをポーリングして、進捗・完了・失敗の理由を表示する。

  ## 何を、どの層で確かめるか

  受け入れ環境では実 Ollama を起動しない(#1090)。Ollama の代わりに LLM スタブ
  (infra/e2e-stubs/llm/server.js)が `POST /api/pull` を受け、受け取ったモデル名を `/__control/state` の
  `pullRequests` に残す。スタブはモデル名で応答を変える:

  - `slow` を含む: 数秒かけて進捗(completed / total)を流してから success(画面で進捗を観測するため)
  - `fail` を含む: ストリームの途中で `{"error": …}` を流して終わる
  - `missing` を含む: HTTP 404 と `{"error": …}` を返す
  - それ以外: すぐに進捗を流して success

  LLM スタブの受信履歴と注入を触るので、`@stub-isolation:llm` で LLM スタブに触れる他の feature と直列にする。
  ジョブが running のまま残らないこと(failed / done で終わること)は、画面の表示に加えて
  `GET /api/generation-jobs/{id}` のステータスで確かめる。
  「接続先に届かない」は、名前解決できない `.invalid` ドメインをプロジェクトの上書きにして再現する
  (プロジェクトの上書きは接続時に宛先検査を通る。#1547)。

  背景:
    前提 接続情報パネル検証用のプロジェクトがある

  シナリオ: モデル名を入力してインストールを押すと、実効Ollama接続先へそのモデル名でpullが送られ、画面に進捗が表示される
    もし 接続情報パネル検証用のプロジェクトの設定タブを開く
    かつ Ollamaのモデル名欄に「e2e-pull-slow:1b」を入力してインストールを押す
    ならば 画面にインストールの進捗が百分率つきで表示される
    かつ Ollamaのスタブが「e2e-pull-slow:1b」のpullを受け取っている

  シナリオ: pullが最後まで成功すると、ジョブがdoneになり、画面に完了が表示される
    もし 接続情報パネル検証用のプロジェクトの設定タブを開く
    かつ Ollamaのモデル名欄に「e2e-pull-ok:1b」を入力してインストールを押す
    ならば 画面に「e2e-pull-ok:1b」のインストール完了が表示される
    かつ モデル「e2e-pull-ok:1b」のpullジョブはdoneで終わっている

  シナリオ: Ollamaがストリームの途中でエラーを返すと、ジョブがfailedで終わり、画面に失敗の理由が表示される
    もし 接続情報パネル検証用のプロジェクトの設定タブを開く
    かつ Ollamaのモデル名欄に「e2e-pull-fail:1b」を入力してインストールを押す
    ならば 画面にインストールの失敗の理由として「file does not exist」が表示される
    かつ モデル「e2e-pull-fail:1b」のpullジョブはfailedで終わっている

  シナリオ: OllamaがHTTPエラーを返すと、ジョブがfailedで終わり、画面に失敗の理由が表示される
    もし 接続情報パネル検証用のプロジェクトの設定タブを開く
    かつ Ollamaのモデル名欄に「e2e-pull-missing:1b」を入力してインストールを押す
    ならば 画面にインストールの失敗の理由として「file does not exist」が表示される
    かつ モデル「e2e-pull-missing:1b」のpullジョブはfailedで終わっている

  シナリオ: 接続先に届かないと、ジョブがfailedで終わり、runningのまま残らず、画面に失敗の理由が表示される
    前提 プロジェクトのOllama接続先が「http://at-1675-down.invalid:11434/v1」に設定されている
    もし 接続情報パネル検証用のプロジェクトの設定タブを開く
    かつ Ollamaのモデル名欄に「e2e-pull-down:1b」を入力してインストールを押す
    ならば 画面にインストールの失敗の理由として「名前解決できない」が表示される
    かつ モデル「e2e-pull-down:1b」のpullジョブはfailedで終わっている

  シナリオ: 管理者でない利用者は、プロジェクトのメンバーであってもpullを開始できず403になる
    前提 プロジェクトのメンバーである一般利用者がいる
    もし 一般利用者がモデル「e2e-pull-forbidden:1b」のpullを開始しようとする
    ならば pullの開始は403で拒否される
    かつ Ollamaのスタブが「e2e-pull-forbidden:1b」のpullを受け取っていない

  シナリオ: 空や不正な文字を含むモデル名は400で拒否され、Ollamaへは送られない
    もし 管理者がモデル「」のpullを開始しようとする
    ならば pullの開始は400で拒否される
    もし 管理者がモデル「bad name;rm -rf」のpullを開始しようとする
    ならば pullの開始は400で拒否される
    かつ Ollamaのスタブが「bad name;rm -rf」のpullを受け取っていない

  シナリオ: 同じモデルのpullが実行中のときは、二重に開始せず、実行中のジョブを返す
    もし 管理者がモデル「e2e-pull-slow-dup:1b」のpullを開始しようとする
    ならば pullの開始は受け付けられ、新しいジョブが作られる
    もし 管理者がモデル「e2e-pull-slow-dup:1b」のpullを開始しようとする
    ならば pullの開始は受け付けられ、実行中の同じジョブが返る
