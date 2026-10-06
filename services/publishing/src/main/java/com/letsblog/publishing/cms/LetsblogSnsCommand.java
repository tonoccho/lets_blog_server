package com.letsblog.publishing.cms;

/**
 * letsblog プラグインの `wp letsblog sns ...`・`wp letsblog pv ...` のうち、アプリが呼ぶサブコマンド(issue #1574、#1578、#1583)。
 * ブリッジとエージェントの間では {@link #wire()} の文字列で表し、許可リスト以外は受け付けない
 * (任意の引数をシェルやwp-cliへ渡さないため)。
 */
public enum LetsblogSnsCommand {
    /** `config set`。認証情報(秘密を含む)は引数ではなく標準入力のJSONで渡す。 */
    CONFIG_SET("config-set", true),
    /** `config clear [<sns>]`。SNS名を省略するとすべて消す。 */
    CONFIG_CLEAR("config-clear", false),
    /** `status`。SNSごとの接続状態。 */
    STATUS("status", false),
    /** `test <sns>`。テスト投稿。 */
    TEST("test", false),
    /** `log --format=json`。告知履歴。 */
    LOG("log", false),
    /** `pv config set`(issue #1578)。GA4 の認証情報(秘密を含む)は引数ではなく標準入力のJSONで渡す。 */
    PV_CONFIG_SET("pv-config-set", true),
    /** `pv config clear`。GA4 の認証情報を消す。 */
    PV_CONFIG_CLEAR("pv-config-clear", false),
    /** `pv status`。GA4 の設定状態。 */
    PV_STATUS("pv-status", false),
    /** `pv rules set`。標準入力のJSON(ルールの配列)で、ルールを丸ごと置き換える。 */
    PV_RULES_SET("pv-rules-set", true),
    /** `sns templates set`(issue #1583)。公開時と PV 達成時の告知文テンプレートを、標準入力のJSONで丸ごと置き換える。 */
    TEMPLATES_SET("templates-set", true);

    private final String wire;
    private final boolean requiresStdin;

    LetsblogSnsCommand(String wire, boolean requiresStdin) {
        this.wire = wire;
        this.requiresStdin = requiresStdin;
    }

    public String wire() {
        return wire;
    }

    public boolean requiresStdin() {
        return requiresStdin;
    }

    public static LetsblogSnsCommand fromWire(String value) {
        for (LetsblogSnsCommand command : values()) {
            if (command.wire.equals(value)) {
                return command;
            }
        }
        throw new IllegalArgumentException("未対応のSNSコマンドです: " + value);
    }
}
