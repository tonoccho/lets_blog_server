package com.letsblog.publishing.testsupport;

import com.letsblog.publishing.cms.FakeSshCommandExecutor;

/**
 * {@link FakeSshCommandExecutor}へ、WordPress(wp-cli、SSHトランスポート)の代表的な応答を登録する部品(issue #1815)。
 * 自前のクラス({@code WordPressAdapter}等)をモックせず、SSHの向こうのWordPressの応答だけを作る。
 */
public final class WpCliStubs {

    private WpCliStubs() {
    }

    /**
     * メディアのアップロード(mu-pluginは配置済み、{@code media import}がID順に採番、guidはURL)が成功する応答。
     * {@code idsAndUrls}は「ID, URL, ID, URL, ...」の並び。
     */
    public static void mediaUploads(FakeSshCommandExecutor ssh, String... idsAndUrls) {
        com.letsblog.publishing.cms.ssh.SshCommandExecutor.SshCommandResult[] ids =
                new com.letsblog.publishing.cms.ssh.SshCommandExecutor.SshCommandResult[idsAndUrls.length / 2];
        for (int i = 0; i < idsAndUrls.length; i += 2) {
            ids[i / 2] = FakeSshCommandExecutor.ok(idsAndUrls[i]);
            ssh.whenOk("post get '" + idsAndUrls[i] + "' --fields=guid", "{\"guid\":\"" + idsAndUrls[i + 1] + "\"}");
        }
        ssh.whenOk("test -f", "").whenSeq("media import", ids).whenOk("post meta update", "");
    }
}
