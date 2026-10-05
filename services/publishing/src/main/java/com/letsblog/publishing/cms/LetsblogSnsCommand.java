package com.letsblog.publishing.cms;

/**
 * letsblog プラグインの `wp letsblog sns ...` のうち、アプリが呼ぶサブコマンド(issue #1574)。
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
    LOG("log", false);

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
