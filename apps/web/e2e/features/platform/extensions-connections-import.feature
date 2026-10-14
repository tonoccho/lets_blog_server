# language: ja
@platform
機能: extensions のポータルから接続先をシステム設定へ取り込む

  管理者が lets_blog_server_extensions のポータルの URL と経路(同じ PC / 別ホスト)を指定して
  「取得」を押すと、platform-service がポータルの `GET /api/connections` を呼び、Ollama / ComfyUI / Penpot の
  接続先の候補を「現在の値」と「取り込む値」で並べて表示する。管理者が「反映」を押したときだけ保存する
  (issue #1783。親 Epic #1755、スタブは #1782)。

  ## 何を、どの層で確かめるか

  ポータルは受け入れテスト用のスタブ(`lbx-portal:3100`、#1782)を使う。実物の extensions は使わない。
  対応づけの表(Ollama / ComfyUI / Penpot × 同じ PC / 別ホスト)は、システム設定画面の操作で確かめる。
  認可は API で確かめる(画面は管理者にしか出ない)。
  システム設定とポータルのスタブの応答を書き換えるので、`@destructive` で単独実行し、After で元の値に戻す。
  同じ PC の経路で Penpot の `publicUrl` が `*.lbx.localhost` のままだと、コンテナ内ではループバックへ
  解決され、Penpot の保存だけが検査で拒否される(その理由が画面に出ることを「一部だけ拒否」のシナリオで確かめる)。

  @destructive
  シナリオ: 同じPCの経路で取得して反映すると、システム設定のOllamaとComfyUIの接続先がポータルの内部URLになる
    前提 システム設定のOllama・ComfyUI・PenpotのURLを既知の値にして、元の値を控える
    かつ ポータルスタブの応答は既定である
    かつ 管理者としてログインする
    もし システム設定画面の「extensions から取り込む」欄で、ポータルのURLを「http://lbx-portal:3100」、経路を「同じ PC」にして取得する
    ならば 取り込み候補の「llm_ollama_base_url」の取り込む値は「http://lbx-ollama:11434/v1」である
    かつ 取り込み候補の「comfyui_base_url」の取り込む値は「http://lbx-comfyui:8188」である
    もし 取り込み候補を反映する
    ならば システム設定画面を開き直すと「llm_ollama_base_url」は「http://lbx-ollama:11434/v1」と表示される
    かつ システム設定画面を開き直すと「comfyui_base_url」は「http://lbx-comfyui:8188」と表示される
    かつ システム設定画面を開き直すと、ポータルのURLに「http://lbx-portal:3100」が入力済みである

  @destructive
  シナリオ: 別ホストの経路で取得すると、候補が公開URLから組み立てた値で表示される
    前提 システム設定のOllama・ComfyUI・PenpotのURLを既知の値にして、元の値を控える
    かつ ポータルスタブの応答は既定である
    かつ 管理者としてログインする
    もし システム設定画面の「extensions から取り込む」欄で、ポータルのURLを「http://lbx-portal:3100」、経路を「別ホスト」にして取得する
    ならば 取り込み候補の「llm_ollama_base_url」の取り込む値は「https://ollama.lbx.localhost:8443/v1」である
    かつ 取り込み候補の「comfyui_base_url」の取り込む値は「https://comfyui.lbx.localhost:8443」である
    かつ 取り込み候補の「penpot_base_url」の取り込む値は「https://penpot.lbx.localhost:8443」である
    かつ 取り込み候補の「llm_ollama_base_url」の現在の値は「http://before.example:11434/v1」である
    かつ システム設定の「llm_ollama_base_url」はまだ「http://before.example:11434/v1」のままである

  @destructive
  シナリオ: 検証で拒否される候補は保存されず、キーと理由が表示され、他のキーは保存される
    前提 システム設定のOllama・ComfyUI・PenpotのURLを既知の値にして、元の値を控える
    かつ ポータルスタブの応答は、Penpotの公開URLが「http://127.0.0.1:3000」である
    かつ 管理者としてログインする
    もし システム設定画面の「extensions から取り込む」欄で、ポータルのURLを「http://lbx-portal:3100」、経路を「同じ PC」にして取得する
    かつ 取り込み候補を反映する
    ならば 反映結果に、保存されなかった「penpot_base_url」とその理由が表示される
    かつ システム設定の「penpot_base_url」はまだ「http://192.168.77.10:3000」のままである
    かつ システム設定の「llm_ollama_base_url」は「http://lbx-ollama:11434/v1」である
    かつ システム設定の「comfyui_base_url」は「http://lbx-comfyui:8188」である

  @destructive
  シナリオアウトライン: ポータルが「<状況>」のとき、取得は原因の分かるエラーを表示し、システム設定は変わらない
    前提 システム設定のOllama・ComfyUI・PenpotのURLを既知の値にして、元の値を控える
    かつ ポータルスタブは「<状況>」になっている
    かつ 管理者としてログインする
    もし システム設定画面の「extensions から取り込む」欄で、ポータルのURLを「http://lbx-portal:3100」、経路を「同じ PC」にして取得する
    ならば 取り込みのエラーとして「<エラー>」が表示される
    かつ システム設定の「llm_ollama_base_url」はまだ「http://before.example:11434/v1」のままである
    かつ システム設定の「comfyui_base_url」はまだ「http://before.example:8188」のままである
    かつ システム設定の「penpot_base_url」はまだ「http://192.168.77.10:3000」のままである

    例:
      | 状況                | エラー                                   |
      | 500を返す           | ポータルがエラーを返しました(HTTP 500)   |
      | 応答に5秒かかる     | ポータルへの接続がタイムアウトしました   |
      | JSONでない本文を返す | ポータルの応答がJSONではありません       |

  @api @destructive
  シナリオ: 管理者以外は、取得も反映も403で拒否され、システム設定は変わらない
    前提 システム設定のOllama・ComfyUI・PenpotのURLを既知の値にして、元の値を控える
    かつ ポータルスタブの応答は既定である
    かつ 一般ユーザーのアクセストークンを取得する
    もし 一般ユーザーがポータルのURL「http://lbx-portal:3100」で取り込みの取得を要求する
    ならば 取り込みの要求は403で拒否される
    もし 一般ユーザーがOllamaの接続先「http://lbx-ollama:11434/v1」の取り込みの反映を要求する
    ならば 取り込みの要求は403で拒否される
    かつ システム設定の「llm_ollama_base_url」はまだ「http://before.example:11434/v1」のままである

  @api @destructive
  シナリオ: ポータルのURLがループバックやリンクローカルを指すと、取得は拒否される
    前提 システム設定のOllama・ComfyUI・PenpotのURLを既知の値にして、元の値を控える
    もし 管理者がポータルのURL「http://127.0.0.1:3100」で取り込みの取得を要求する
    ならば 取り込みの要求はポータルのURLの検証エラー(409)で拒否される
    もし 管理者がポータルのURL「http://169.254.169.254/」で取り込みの取得を要求する
    ならば 取り込みの要求はポータルのURLの検証エラー(409)で拒否される
