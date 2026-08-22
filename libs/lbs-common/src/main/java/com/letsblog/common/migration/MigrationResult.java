package com.letsblog.common.migration;

/**
 * {@link SchemaMigrationJob} の実行結果。
 *
 * @param jobName        ジョブ名(冪等性判定のキー)
 * @param rowsCopied     今回の実行でコピーした行数(既に完了済みでスキップした場合は0)
 * @param alreadyCompleted 過去の実行で既に完了済みだったため今回は何もしなかった場合true
 */
public record MigrationResult(String jobName, long rowsCopied, boolean alreadyCompleted) {
}
