# 05. Docker Compose のマルチサービス構成への整備

Issue: [#556](https://github.com/tonoccho/lets_blog_server/issues/556)

## 内容

将来のgateway/Keycloak/各ドメインサービス追加を見据え、`docker-compose.yml` を
テンプレート化し、リソース実測を行った。**この Issue ではサービス定義の実体
(gateway等)は追加しない** — 枠組みとリソース実測のみが対象。

## 変更内容

- YAMLアンカー(`x-common-service` / `x-actuator-healthcheck` / `x-rabbitmq-env`)で
  restart/networks/ヘルスチェック/RabbitMQ接続情報を共通化し、全19サービスへ適用
- `api` / `log-writer` にactuatorベースのヘルスチェックを追加
  (追加した結果、2つの既存バグを発見・修正: log-writerにWebサーバーが無く
  actuatorのHTTPエンドポイントが公開されていなかった問題、legacy-apiの
  `MailHealthIndicator`が未設定の`MAIL_HOST`で失敗し集約healthが常にDOWNだった問題)
- ポート割当を内部8080に統一(`log-writer`を8081→8080に変更)
- GPU/メモリを満たさない環境向けの縮退起動は、既存のCOMFYUI_IMAGEタグ切り替えで
  GPU要件は緩和済みと判断し、追加のcompose profileは見送り(検討過程は
  [docs/DOCKER_COMPOSE_ARCHITECTURE.md](../../docs/DOCKER_COMPOSE_ARCHITECTURE.md)参照)
- 全19コンテナのアイドル時メモリ実測(約10.3GiB)をREADMEのハードウェア要件へ反映

## 成果物

- [docs/DOCKER_COMPOSE_ARCHITECTURE.md](../../docs/DOCKER_COMPOSE_ARCHITECTURE.md) —
  テンプレート規約・ポート割当表・リソース実測値

## 検証

- `docker compose config` の検証
- `docker compose up -d` で全サービスが起動し、ヘルスチェック対象
  (mysql/rabbitmq/penpot-postgres/penpot-valkey/api/log-writer)が全てhealthyになることを確認
