package com.letsblog.common.migration;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * {@link SchemaMigrationJob} が繰り返し呼び出す1バッチ分のコピー処理。
 * 呼び出し側(旧スキーマ→新サービススキーマへの実際の移行ロジック)が、
 * 「まだコピーしていない行のうち先頭 batchSize 件」をコピーし、コピーした件数を返す。
 * 0 を返すと、そのジョブは完了したとみなされる。
 *
 * <p>再実行可能性(resumability)は実装側の責務: 例えば「コピー済みの最大IDより大きい行を
 * batchSize件取得してコピーする」ようにすれば、途中で失敗しても次回はその続きから再開できる。
 * 各行のコピー自体も {@code INSERT ... ON DUPLICATE KEY UPDATE} 等で冪等にしておくこと。
 */
@FunctionalInterface
public interface MigrationBatchStep {

    int copyBatch(Connection targetConnection, int batchSize) throws SQLException;
}
