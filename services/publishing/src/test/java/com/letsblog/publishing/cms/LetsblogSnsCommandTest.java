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
}
