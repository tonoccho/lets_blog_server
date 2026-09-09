# language: ja
@auth @api
機能: 連続したパスワード誤入力によるアカウントの一時ロック

  パスワードを実際に検証する唯一の経路(`/auth/realms/letsblog/...`)を、gatewayの
  RateLimitWebFilterは一度も見ない(nginxがKeycloakへ直接転送するため)。
  この経路を守るのはKeycloak自身のbrute force detection
  (`infra/keycloak/realm-export.json`の`bruteForceProtected`)であり、issue #1056で
  有効化した。

  @destructive
  シナリオ: 設定した失敗回数に達すると、正しいパスワードでもログインできなくなる
    前提 ブルートフォース検証用の使い捨てアカウントを作成する
    かつ そのアカウントは正しいパスワードでログインできる
    もし そのアカウントに対して、設定した失敗回数に達するまで誤ったパスワードで認証を試みる
    ならば 正しいパスワードで認証してもアカウントが一時ロックされていて拒否される
