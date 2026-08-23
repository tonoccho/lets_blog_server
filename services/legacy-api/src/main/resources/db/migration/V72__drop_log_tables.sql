-- ログの所有権をlog-writerへ完全移管する(#572)。audit_logs/operation_logs/frontend_error_logsの
-- 3テーブルをlets_blogスキーマから削除する。
--
-- 重要: このマイグレーションを環境へ適用する前に、必ず以下の順序を守ること。
--   1. log-writerサービスのFlyway V1(services/log-writer/src/main/resources/db/migration/
--      V1__create_log_tables.sql)を適用し、lbs_logスキーマに空の3テーブルを作成する。
--   2. scripts/migrate-log-tables-to-lbs-log.sql を実行し、既存データをlets_blog(このスキーマ)
--      からlbs_logへコピーする。スクリプト末尾の件数突合を確認する。
--   3. 上記が確認できてから、legacy-apiを再ビルド/再起動してこのV72を適用する
--      (=このファイルをlegacy-apiのFlyway管理下に置いたままapiコンテナを再起動すると自動適用
--      されるため、1・2を済ませていない環境ではapiコンテナの再起動タイミングに注意すること)。
--
-- bulk_operation_logsはこのマイグレーションの対象外(BulkOperationLog.javaのJavadoc、
-- および#572のPR説明を参照。projectsへのFKを持つワークフロー状態のためlegacy-apiに残す)。

DROP TABLE IF EXISTS audit_logs;
DROP TABLE IF EXISTS operation_logs;
DROP TABLE IF EXISTS frontend_error_logs;
