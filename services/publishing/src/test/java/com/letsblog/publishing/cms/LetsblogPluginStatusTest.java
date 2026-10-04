package com.letsblog.publishing.cms;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** `wp letsblog status` の出力から導入状態を判定する(issue #1557)。 */
class LetsblogPluginStatusTest {

    @Test
    void 互換のプロトコルなら導入済みでバージョンを持つ() {
        LetsblogPluginStatus status = LetsblogPluginStatus.fromStatusOutput(
                "{\"plugin_version\":\"1.0.0\",\"protocol_version\":" + LetsblogPluginStatus.SUPPORTED_PROTOCOL_VERSION + "}");

        assertEquals(LetsblogPluginStatus.State.INSTALLED, status.state());
        assertEquals("1.0.0", status.version());
        assertEquals(LetsblogPluginStatus.SUPPORTED_PROTOCOL_VERSION, status.protocolVersion());
        assertTrue(status.installed());
    }

    @Test
    void プロトコルが互換でなければ要更新() {
        LetsblogPluginStatus status = LetsblogPluginStatus.fromStatusOutput(
                "{\"plugin_version\":\"0.9.0\",\"protocol_version\":" + (LetsblogPluginStatus.SUPPORTED_PROTOCOL_VERSION + 1) + "}");

        assertEquals(LetsblogPluginStatus.State.NEEDS_UPDATE, status.state());
        assertEquals("0.9.0", status.version());
        assertFalse(status.installed());
    }

    @Test
    void プロトコルのバージョンが無ければ要更新() {
        LetsblogPluginStatus status = LetsblogPluginStatus.fromStatusOutput("{\"plugin_version\":\"0.1.0\"}");

        assertEquals(LetsblogPluginStatus.State.NEEDS_UPDATE, status.state());
        assertNull(status.protocolVersion());
    }

    @Test
    void 出力がJSONでなければ判定できず例外() {
        assertThrows(IllegalArgumentException.class, () -> LetsblogPluginStatus.fromStatusOutput("PHP Fatal error: boom"));
        assertThrows(IllegalArgumentException.class, () -> LetsblogPluginStatus.fromStatusOutput(""));
        assertThrows(IllegalArgumentException.class, () -> LetsblogPluginStatus.fromStatusOutput(null));
        assertThrows(IllegalArgumentException.class, () -> LetsblogPluginStatus.fromStatusOutput("[1,2]"));
    }

    @Test
    void コマンド未登録のエラーだけがプラグイン無しを意味する() {
        assertTrue(LetsblogPluginStatus.isCommandMissing(
                "Error: 'letsblog' is not a registered wp command. See 'wp help' for available commands."));
        assertFalse(LetsblogPluginStatus.isCommandMissing("Error: This does not seem to be a WordPress installation."));
        assertFalse(LetsblogPluginStatus.isCommandMissing("wp: command not found"));
        assertFalse(LetsblogPluginStatus.isCommandMissing(""));
        assertFalse(LetsblogPluginStatus.isCommandMissing(null));
    }

    @Test
    void 未導入は導入済みではない() {
        LetsblogPluginStatus status = LetsblogPluginStatus.notInstalled();

        assertEquals(LetsblogPluginStatus.State.NOT_INSTALLED, status.state());
        assertNull(status.version());
        assertFalse(status.installed());
    }

    @Test
    void 利用できない状態の例外は状態と再導入の案内を含む() {
        LetsblogPluginUnavailableException notInstalled =
                new LetsblogPluginUnavailableException(LetsblogPluginStatus.notInstalled());
        LetsblogPluginUnavailableException needsUpdate = new LetsblogPluginUnavailableException(
                new LetsblogPluginStatus(LetsblogPluginStatus.State.NEEDS_UPDATE, "0.9.0", 0));

        assertTrue(notInstalled.getMessage().contains("未導入"));
        assertTrue(notInstalled.getMessage().contains("再導入"));
        assertTrue(needsUpdate.getMessage().contains("要更新"));
        assertTrue(needsUpdate.getMessage().contains("再導入"));
        assertEquals(LetsblogPluginStatus.State.NEEDS_UPDATE, needsUpdate.getStatus().state());
    }

    @Test
    void 解釈できない長い出力は省略してメッセージに含める() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LetsblogPluginStatus.fromStatusOutput("x".repeat(500)));

        assertTrue(e.getMessage().contains("…"));
        assertTrue(e.getMessage().length() < 300);
    }
}
