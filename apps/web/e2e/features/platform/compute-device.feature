# language: ja
@platform @destructive @docker-engine-stub
機能: ComfyUI の演算デバイス(GPU / CPU)の切り替え

  管理者がシステム設定画面で ComfyUI の演算デバイスを GPU と CPU から選んで適用できる(issue #1399)。
  platform-service は docker-socket-proxy 経由でコンテナの start / stop だけを行い、
  GPU 構成(`lbs-comfyui`)と CPU 構成(`lbs-comfyui-cpu`)の両方があるホストでだけ切り替えられる。
  GPU 構成が無いホストは CPU に固定される。

  実コンテナの start / stop は共有の受け入れ環境を壊すため、platform-service の切り替え専用の向き先を
  Docker Engine API スタブ(`infra/e2e-stubs/docker-engine`)へ向けて検証する。ダッシュボードのコンテナ一覧は
  実物の docker-socket-proxy を向いたままなので影響しない。スタブの状態と platform-service の進行状態を
  共有するため、他のシナリオと並列に走らない `@destructive` とする。
  待機時間の上限は受け入れ環境で 10 秒に短縮している(`docker-compose.e2e-stubs.yml`、既定は 180 秒)。

  シナリオ: 両構成があるホストでGPUからCPUへ切り替えると、適用中の後に完了が表示される
    前提 Docker Engine APIスタブに、GPU構成が稼働中でCPU構成が停止中の両構成がある
    かつ 管理者としてログインする
    もし システム設定画面の演算デバイス欄でCPUを選んで適用する
    ならば 演算デバイス欄に「適用中」が表示され、その後「適用が完了しました」が表示される
    かつ 演算デバイス欄の現在の構成は「CPU」と表示される
    かつ Docker Engine APIスタブではCPU構成だけが稼働している

  シナリオ: GPU構成が無いホストでは「CPU(固定)」と表示され、GPUは選べず理由が表示される
    前提 Docker Engine APIスタブに、CPU構成だけがありGPU構成のコンテナは無い
    かつ 管理者としてログインする
    もし システム設定画面へ移動する
    ならば 演算デバイス欄の現在の構成は「CPU(固定)」と表示される
    かつ 演算デバイス欄ではGPUを選べず、理由として「GPU 構成の ComfyUI」が表示される

  @api
  シナリオ: GPU構成が無いホストでGPUへの適用をAPIへ直接送っても拒否され、どのコンテナも操作されない
    前提 Docker Engine APIスタブに、CPU構成だけがありGPU構成のコンテナは無い
    もし 管理者としてAPIにGPUへの適用を直接送る
    ならば 適用APIは4xxで拒否される
    かつ Docker Engine APIスタブでは、どのコンテナも起動も停止もされていない

  シナリオ: 選んだ構成が上限時間内に稼働しないと、失敗の理由が表示され元の構成に戻る
    前提 Docker Engine APIスタブは、CPU構成を起動しても稼働状態にならない
    かつ 管理者としてログインする
    もし システム設定画面の演算デバイス欄でCPUを選んで適用する
    ならば 演算デバイス欄の適用結果に「適用に失敗しました」と上限時間内に完了しなかった理由が表示される
    かつ Docker Engine APIスタブでは元のGPU構成が稼働し、選んだ構成は止まっている

  シナリオ: 一般ユーザーには演算デバイス欄が表示されない
    前提 一般ユーザーとしてログインする
    もし システム設定画面へ移動する
    ならば 演算デバイス欄は表示されない

  @api
  シナリオ: 一般ユーザーは演算デバイスの参照APIも適用APIも403になる
    もし 一般ユーザーとして演算デバイスの参照APIを呼ぶ
    かつ 一般ユーザーとして演算デバイスの適用APIを呼ぶ
    ならば 演算デバイスのAPIは403を返す

  # ---- Ollama(issue #1585)。ComfyUI と同じ機構を、独立した2つ目の対象として使う ----
  # GPU 構成の有無は `lbs-ollama` の HostConfig.Runtime が nvidia かどうかで判定する。
  # 成功判定は目的のコンテナが running かつヘルスチェックが healthy になること。

  シナリオ: Ollamaを両構成があるホストでGPUからCPUへ切り替えると、完了が表示されComfyUIは変わらない
    前提 Docker Engine APIスタブに、ComfyUIとOllamaそれぞれにGPU構成が稼働中でCPU構成が停止中の両構成がある
    かつ 管理者としてログインする
    もし システム設定画面のOllamaの演算デバイス欄でCPUを選んで適用する
    ならば Ollamaの演算デバイス欄に「適用中」が表示され、その後「適用が完了しました」が表示される
    かつ Ollamaの演算デバイス欄の現在の構成は「CPU」と表示される
    かつ Docker Engine APIスタブではOllamaのCPU構成だけが稼働している
    かつ Docker Engine APIスタブでは、ComfyUIのコンテナは起動も停止もされていない

  シナリオ: ComfyUIを切り替えても、Ollamaのコンテナは変わらない
    前提 Docker Engine APIスタブに、ComfyUIとOllamaそれぞれにGPU構成が稼働中でCPU構成が停止中の両構成がある
    かつ 管理者としてログインする
    もし システム設定画面の演算デバイス欄でCPUを選んで適用する
    ならば 演算デバイス欄に「適用中」が表示され、その後「適用が完了しました」が表示される
    かつ Docker Engine APIスタブでは、Ollamaのコンテナは起動も停止もされていない

  シナリオ: Ollamaのruntimeがnvidiaでないホストでは「CPU(固定)」と表示され、GPUは選べず理由が表示される
    前提 Docker Engine APIスタブに、runtimeがnvidiaでないOllamaのコンテナだけがある
    かつ 管理者としてログインする
    もし システム設定画面へ移動する
    ならば Ollamaの演算デバイス欄の現在の構成は「CPU(固定)」と表示される
    かつ Ollamaの演算デバイス欄ではGPUを選べず、理由として「nvidia」が表示される

  @api
  シナリオ: Ollamaのruntimeがnvidiaでないホストで、GPUへの適用をAPIへ直接送っても拒否され、どのコンテナも操作されない
    前提 Docker Engine APIスタブに、runtimeがnvidiaでないOllamaのコンテナだけがある
    もし 管理者としてAPIにOllamaのGPUへの適用を直接送る
    ならば 適用APIは409で拒否される
    かつ Docker Engine APIスタブでは、どのコンテナも起動も停止もされていない

  シナリオ: Ollamaの選んだ構成が上限時間内にhealthyにならないと、失敗の理由が表示され元の構成に戻る
    前提 Docker Engine APIスタブは、ComfyUIとOllamaの両構成があり、OllamaのCPU構成は起動してもhealthyにならない
    かつ 管理者としてログインする
    もし システム設定画面のOllamaの演算デバイス欄でCPUを選んで適用する
    ならば Ollamaの演算デバイス欄の適用結果に「適用に失敗しました」と上限時間内に完了しなかった理由が表示される
    かつ Docker Engine APIスタブでは元のOllamaのGPU構成が稼働し、選んだ構成は止まっている
