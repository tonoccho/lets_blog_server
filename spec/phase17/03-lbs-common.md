# 03. 共通ライブラリ lbs-common の抽出

Issue: [#554](https://github.com/tonoccho/lets_blog_server/issues/554)

## 内容

各サービスが暗号化・エラー応答・ログメッセージ型・相関IDを個別に実装すると仕様がずれるため、
`libs/lbs-common` にサービス間で共有する横断的な部品のみを移設した。**ドメインロジックは
置かない**(`GlobalExceptionHandler` のような、ドメイン例外に依存するクラスはそのまま
各サービスに残し、共通化できる「JSON形状の規約」の部分だけを抽出している)。

## 成果物(`com.letsblog.common.*`)

- `crypto.CredentialCipher` — AES-256-GCM暗号化(全サービス共通の`APP_ENCRYPTION_KEY`)
- `messaging.{OperationLogMessage,ErrorLogMessage,AuditLogMessage}` — RabbitMQペイロード型
  (`services/legacy-api`と`services/log-writer`の重複定義を解消)
- `messaging.LogExchanges` — exchange名/routing key定数
- `util.StackTraceUtil`
- `web.ErrorResponse` — `{error, details}` の共通エラーレスポンス形状
- `web.CorrelationIdFilter` — 相関IDフィルタ(このIssue時点では未登録。C13で使用予定)
- `migration.SchemaMigrationJob` 等 — ワンショットデータ移行ジョブの基盤(C1で追加)

`libs/lbs-common`は`java-library` + Spring Bootのdependency-managementプラグインで、
`bootJar`無効/`jar`有効の通常ライブラリとしてビルドされる。

## 検証

- `CredentialCipher`・`CorrelationIdFilter`の単体テストを新規追加(移設前は未テストだった)
- `./gradlew lint test` で全サブプロジェクトが成功することを確認
