# language: ja
@logging @slow @destructive @timeout:600000
機能: ログの非同期経路が落ちたときのふるまい

  「ログのために業務操作を失敗させない」「停止中のログがどうなるかが決まっている」を
  固定する(issue #941 / AT-15)。

  RabbitMQ と log-writer のコンテナを実際に停止する。共有状態を壊すので `@destructive`
  として最後の段階で単独実行し、停止したサービスは
  `steps/degradation.steps.ts` の後始末フックが起動し直す。

  ## 停止中に発生したログの扱い(実装から確定させた仕様。#941 の未決事項)

  発行側は3系統あり、**RabbitMQ が落ちているときのふるまいが系統ごとに違う**。

  | ログ | 発行元 | RabbitMQ 停止中 | 根拠 |
  | --- | --- | --- | --- |
  | 操作ログ | log-writer 自身 | **保持される**(同期DB書き込みへフォールバック) | `OperationLogService#record` の `catch (AmqpException)` |
  | フロントエンドエラーログ | log-writer 自身 | **保持される**(同上) | `FrontendErrorLogService#logError` の `catch (AmqpException)` |
  | 監査ログ | 各ドメインサービス | **失われる**(ERRORログに残るだけ) | `project/AuditLogService#log` ほかの `catch (AmqpException)` |

  この表の1行目は、#941 でこのシナリオを書いた時点では**成り立っていなかった**。
  フォールバックの `repository.save(entry)` が `created_at` を設定しておらず
  (`OperationLogRequest#toDomain` は設定せず、キュー経由なら受信側が埋めていた)、
  RabbitMQ 停止中の `POST /api/operation-logs` は
  `DataIntegrityViolationException: Column 'created_at' cannot be null` で500になっていた。
  操作ログは残らず、しかも BFF の `recordOperationLog` が記録の失敗を握り潰すため
  **落ちていること自体が誰にも見えない**。#941 で修正済み。

  違いは能力の差である。前2つは log-writer 自身が発行元なので `lbs_log` へ直接書ける。
  監査ログの発行元は別サービスで、ADR-0004 により `lbs_log` へは書けない。
  発行できなかったメッセージを溜めて後で送り直す仕組み(outbox)は無いので、
  **復帰後に取りこぼしが自動で追いつくことはない**。

  なお exchange とキューは durable、メッセージは Spring AMQP 既定の persistent、
  ack は既定の AUTO で `default-requeue-rejected: true` である
  (log-writer の `RabbitMqConfig` と `application.yml`)。したがって
  **ブローカーに入ったあと**のメッセージはブローカー再起動をまたいで残り、
  消費に失敗しても requeue される。失われうるのは「ブローカーに入る前」だけである。

  シナリオ: RabbitMQが停止していても業務操作は成功する
    前提 管理者としてログインする
    もし RabbitMQを停止する
    ならば 業務操作(プロジェクトの作成・一覧・削除)は成功する
    かつ 画面はエラーにならずに表示される

  シナリオ: RabbitMQ停止中のログは、定義どおり操作ログとエラーログが残り監査ログが失われる
    前提 管理者としてログインする
    もし RabbitMQを停止する
    かつ 停止中に操作ログ・エラーログ・監査ログをそれぞれ1件発生させる
    かつ RabbitMQを復旧させる
    ならば 停止中の操作ログとエラーログは残っている
    かつ 停止中の監査ログは記録されていない

  シナリオ: log-writerが停止していても画面からの業務操作は成功する
    前提 管理者としてログインする
    もし log-writerコンテナを停止する
    ならば 画面からプロジェクトを作成する操作は成功する
