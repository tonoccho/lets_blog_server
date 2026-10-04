package com.letsblog.publishing.cms;

/**
 * letsblog プラグインへの同期(issue #1558)の結果。
 *
 * @param syncHash プラグインが保存した内容のハッシュ(アプリ側が送った内容のハッシュと一致している)
 */
public record LetsblogSyncResult(String syncHash) {
}
