package com.letsblog.project.cms;

/**
 * publishing-serviceの内部CMSブリッジ({@code /sync-letsblog-plugin})の応答(issue #1558)。
 *
 * @param syncHash プラグインが保存した内容のハッシュ
 */
public record LetsblogSyncResult(String syncHash) {
}
