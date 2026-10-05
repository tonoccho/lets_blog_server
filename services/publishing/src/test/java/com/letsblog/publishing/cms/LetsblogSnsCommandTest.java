package com.letsblog.publishing.cms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SNS 告知の wp-cli コマンド名(ブリッジとエージェントの間で使う文字列)の相互変換(issue #1574)。 */
class LetsblogSnsCommandTest {

    @Test
    void 文字列からコマンドを引ける() {
        assertEquals(LetsblogSnsCommand.CONFIG_SET, LetsblogSnsCommand.fromWire("config-set"));
        assertEquals(LetsblogSnsCommand.CONFIG_CLEAR, LetsblogSnsCommand.fromWire("config-clear"));
        assertEquals(LetsblogSnsCommand.STATUS, LetsblogSnsCommand.fromWire("status"));
        assertEquals(LetsblogSnsCommand.TEST, LetsblogSnsCommand.fromWire("test"));
        assertEquals(LetsblogSnsCommand.LOG, LetsblogSnsCommand.fromWire("log"));
    }

    @Test
    void コマンドの文字列表現は元の文字列と一致する() {
        for (LetsblogSnsCommand command : LetsblogSnsCommand.values()) {
            assertEquals(command, LetsblogSnsCommand.fromWire(command.wire()));
        }
    }

    @Test
    void 未知のコマンドとnullは拒否する() {
        assertThrows(IllegalArgumentException.class, () -> LetsblogSnsCommand.fromWire("rm-rf"));
        assertThrows(IllegalArgumentException.class, () -> LetsblogSnsCommand.fromWire(null));
    }

    @Test
    void 標準入力が要るのはconfig_setだけ() {
        assertEquals(true, LetsblogSnsCommand.CONFIG_SET.requiresStdin());
        assertEquals(false, LetsblogSnsCommand.CONFIG_CLEAR.requiresStdin());
        assertEquals(false, LetsblogSnsCommand.STATUS.requiresStdin());
        assertEquals(false, LetsblogSnsCommand.TEST.requiresStdin());
        assertEquals(false, LetsblogSnsCommand.LOG.requiresStdin());
    }

    @Test
    void PV達成ルールのコマンドも文字列から引ける_issue_1578() {
        assertEquals(LetsblogSnsCommand.PV_CONFIG_SET, LetsblogSnsCommand.fromWire("pv-config-set"));
        assertEquals(LetsblogSnsCommand.PV_CONFIG_CLEAR, LetsblogSnsCommand.fromWire("pv-config-clear"));
        assertEquals(LetsblogSnsCommand.PV_STATUS, LetsblogSnsCommand.fromWire("pv-status"));
        assertEquals(LetsblogSnsCommand.PV_RULES_SET, LetsblogSnsCommand.fromWire("pv-rules-set"));
    }

    @Test
    void PVのコマンドで標準入力が要るのは_config_setとrules_setだけ() {
        assertEquals(true, LetsblogSnsCommand.PV_CONFIG_SET.requiresStdin());
        assertEquals(true, LetsblogSnsCommand.PV_RULES_SET.requiresStdin());
        assertEquals(false, LetsblogSnsCommand.PV_CONFIG_CLEAR.requiresStdin());
        assertEquals(false, LetsblogSnsCommand.PV_STATUS.requiresStdin());
    }
}
