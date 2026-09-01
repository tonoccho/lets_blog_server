package com.letsblog.common.migration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 既存の単一スキーマから各サービススキーマへデータを移すワンショット移行ジョブの基盤(#570)。
 * 実際のテーブル/カラムに関する知識は持たず、{@link MigrationBatchStep} として渡された
 * 1バッチ分のコピー処理を、コピーする行が無くなるまで繰り返し呼び出すだけの汎用フレームワーク。
 *
 * <ul>
 *   <li>冪等性: 同じ {@code jobName} は、ターゲットスキーマの管理テーブル
 *       ({@value #STATE_TABLE}) に COMPLETED として記録された後は再実行されない。</li>
 *   <li>再実行可能性: 途中で失敗しても、{@link MigrationBatchStep} の実装が
 *       「まだコピーしていない行」から再開できる形であれば、ジョブを再実行するだけで続きから進む。</li>
 *   <li>進捗ログ: 一定件数ごとに進捗をINFOログへ出力する。</li>
 * </ul>
 */
public class SchemaMigrationJob {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrationJob.class);
    private static final String STATE_TABLE = "_migration_state";
    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final long PROGRESS_LOG_INTERVAL_ROWS = 5000;

    private final DataSource targetDataSource;

    public SchemaMigrationJob(DataSource targetDataSource) {
        this.targetDataSource = targetDataSource;
    }

    public MigrationResult run(String jobName, MigrationBatchStep step) {
        return run(jobName, step, DEFAULT_BATCH_SIZE);
    }

    public MigrationResult run(String jobName, MigrationBatchStep step, int batchSize) {
        try (Connection connection = targetDataSource.getConnection()) {
            ensureStateTable(connection);

            if (isCompleted(connection, jobName)) {
                log.info("移行ジョブ[{}]は完了済みのためスキップします", jobName);
                return new MigrationResult(jobName, 0, true);
            }

            long totalCopied = 0;
            long lastLoggedAt = 0;
            int copiedInBatch;
            do {
                copiedInBatch = step.copyBatch(connection, batchSize);
                totalCopied += copiedInBatch;
                if (copiedInBatch > 0 && totalCopied - lastLoggedAt >= PROGRESS_LOG_INTERVAL_ROWS) {
                    log.info("移行ジョブ[{}]: {}件コピー済み", jobName, totalCopied);
                    lastLoggedAt = totalCopied;
                }
            } while (copiedInBatch > 0);

            markCompleted(connection, jobName, totalCopied);
            log.info("移行ジョブ[{}]完了: 合計{}件コピーしました", jobName, totalCopied);
            return new MigrationResult(jobName, totalCopied, false);
        } catch (SQLException e) {
            throw new MigrationException("移行ジョブ[" + jobName + "]の実行に失敗しました", e);
        }
    }

    private void ensureStateTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS " + STATE_TABLE + " ("
                            + "job_name VARCHAR(255) NOT NULL PRIMARY KEY, "
                            + "status VARCHAR(20) NOT NULL, "
                            + "rows_copied BIGINT NOT NULL DEFAULT 0, "
                            + "updated_at TIMESTAMP NOT NULL"
                            + ")");
        }
    }

    private boolean isCompleted(Connection connection, String jobName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM " + STATE_TABLE + " WHERE job_name = ?")) {
            statement.setString(1, jobName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && "COMPLETED".equals(rs.getString("status"));
            }
        }
    }

    private void markCompleted(Connection connection, String jobName, long rowsCopied) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + STATE_TABLE + " (job_name, status, rows_copied, updated_at) "
                        + "VALUES (?, 'COMPLETED', ?, CURRENT_TIMESTAMP) "
                        + "ON DUPLICATE KEY UPDATE status = 'COMPLETED', rows_copied = ?, updated_at = CURRENT_TIMESTAMP")) {
            statement.setString(1, jobName);
            statement.setLong(2, rowsCopied);
            statement.setLong(3, rowsCopied);
            statement.executeUpdate();
        }
    }
}
