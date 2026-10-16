# language: ja
@auth @api
機能: extensions のツールを呼ぶ専用クライアントのトークン

  lets_blog から extensions のツール(Ollama・ComfyUI・PlantUML・ポータル)を呼ぶときは、ロールを持たない専用の
  Keycloak クライアント(`letsblog-tools-client`、issue #1804)の client credentials トークンを付ける。
  既存の `letsblog-services` のサービスアカウントは `realm-management` の管理ロールを持つので、
  外部のツールへ送るトークンには使えない。専用クライアントのトークンは、管理 API を呼べないことと、
  audience が固定(`lbx-tools`)であることを確かめる。

  シークレットはコンテナの環境変数から読み、ステップの引数にも出力にも載せない。
  トークンはコンテナ内で取得し、`curl` の結果のステータスコードとクレームの形だけを Node 側へ返す。

  シナリオ: 専用クライアントのトークンでは Keycloak の Admin REST API を呼べない
    もし 専用クライアントの client credentials でトークンを取得して Admin REST API のユーザー一覧を呼ぶ
    ならば Admin REST API の応答は 403 である

  シナリオ: 専用クライアントのトークンの audience は固定で、アプリケーションのロールを持たない
    もし 専用クライアントの client credentials でトークンを取得してクレームを読む
    ならば トークンの azp は「letsblog-tools-client」で、audience に「lbx-tools」を含む
    かつ トークンに admin・editor・viewer のロールが無い
    かつ トークンに realm-management のロールが無い
