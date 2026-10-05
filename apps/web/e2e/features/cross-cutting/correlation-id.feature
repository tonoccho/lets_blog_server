# language: ja
@api @cross-cutting
機能: 相関IDの伝播

  「障害を横断的に追跡できる」を固定する(issue #943 / AT-17、#582)。
  1つの要求が gateway と下流サービスをまたいでも、同じ相関IDで追えること。

  シナリオ: クライアントが送った相関IDが下流サービスのログに現れる
    もし 相関IDを指定してgateway経由で要求する
    ならば 下流サービスのログにその相関IDが記録される

  シナリオ: 下流サービスがリクエスト1件ごとに所要時間つきの1行をログへ出す(issue #1470)
    もし 相関IDを指定してgateway経由で要求する
    ならば 下流サービスのログにメソッド・パス・ステータス・所要時間・その相関IDを含む1行が記録される

  シナリオ: クライアントが相関IDを送らないとgatewayが採番して応答ヘッダで返す
    もし 相関IDを指定せずにgateway経由で要求する
    ならば 応答ヘッダに採番された相関IDが付く

  シナリオ: 1つの操作のログをgatewayと下流サービスで同じ相関IDから追える
    もし 相関IDを指定してgateway経由で要求する
    ならば gatewayと下流サービスの双方のログを同じ相関IDで串刺しできる

  シナリオアウトライン: 1つの操作のログをgatewayと「<サービス>」で同じ相関IDから追える(issue #992)
    もし 相関IDを指定して「<メソッド>」「<パス>」をgateway経由で要求する
    ならば gatewayと「<コンテナ>」の双方のログを同じ相関IDで串刺しできる

    例:
      | サービス           | メソッド | パス                      | コンテナ       |
      | project-service    | POST    | /api/tag-design-settings  | lbs-project    |
      | publishing-service | GET     | /api/taxonomy/resolve     | lbs-publishing |
