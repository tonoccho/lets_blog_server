package com.letsblog.publishing.cms;

/**
 * `wp letsblog sns ...` の結果(issue #1574)。
 *
 * @param stdout wp-cli の標準出力(JSON)。秘密は含まれない(プラグインは認証情報を出力しない)
 */
public record LetsblogSnsResult(String stdout) {
}
