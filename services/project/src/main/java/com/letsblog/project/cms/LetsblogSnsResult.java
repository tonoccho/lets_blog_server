package com.letsblog.project.cms;

/**
 * publishing-serviceの内部CMSブリッジ({@code /letsblog-sns})の応答(issue #1574)。
 *
 * @param stdout wp-cli の標準出力(JSON)。プラグインは認証情報を出力しないので秘密は含まれない
 */
public record LetsblogSnsResult(String stdout) {
}
