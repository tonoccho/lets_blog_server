# language: ja
@auth @api
機能: phpMyAdmin の認証

  reverse-proxy が中継する `/phpmyadmin/` は、アプリの認証ゲート(ADR-0008)を通らない。
  ここが無認証だと Keycloak も各サービスの SecurityConfig も迂回して MySQL を直接
  読み書きできてしまう(#978)。phpMyAdmin 自身のログインを必ず経由させる。

  シナリオ: 未認証ではデータベース一覧が見えない
    もし 未認証で phpMyAdmin のデータベース一覧を開く
    ならば データベース一覧は返らない
    かつ phpMyAdmin のログインを求められる

  シナリオ: 未認証ではトップページからもデータベースを操作できない
    もし 未認証で phpMyAdmin のトップページを開く
    ならば データベース一覧は返らない
    かつ phpMyAdmin のログインを求められる

  シナリオ: MySQLの資格情報でログインすれば利用できる
    前提 phpMyAdmin のログイン画面を開く
    もし MySQLの資格情報でログインする
    ならば phpMyAdmin の管理画面が表示される
