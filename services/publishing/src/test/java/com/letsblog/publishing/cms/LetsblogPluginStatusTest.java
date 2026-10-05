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
    void サブコマンド未登録のエラーだけが旧プラグインを意味し_要更新になる_issue1618() {
        assertTrue(LetsblogPluginStatus.isSubcommandMissing(
                "Error: 'preview' is not a registered subcommand of 'letsblog'."));
        assertFalse(LetsblogPluginStatus.isSubcommandMissing("Error: タイトルがありません"));
        assertFalse(LetsblogPluginStatus.isSubcommandMissing(null));
        assertFalse(LetsblogPluginStatus.needsUpdate().installed());
        assertEquals(LetsblogPluginStatus.State.NEEDS_UPDATE, LetsblogPluginStatus.needsUpdate().state());
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

    // ---- issue #1558: status が同期済みの内容のハッシュを返す ----

    @Test
    void statusの出力のsync_hashを同期ハッシュとして持つ() {
        LetsblogPluginStatus status = LetsblogPluginStatus.fromStatusOutput(
                "{\"plugin_version\":\"1.0.0\",\"protocol_version\":1,\"sync_hash\":\"abc123\"}");

        assertEquals("abc123", status.syncHash());
        assertEquals(LetsblogPluginStatus.State.INSTALLED, status.state());
    }

    @Test
    void sync_hashがnullや無しなら同期ハッシュはnull() {
        assertNull(LetsblogPluginStatus.fromStatusOutput(
                "{\"plugin_version\":\"1.0.0\",\"protocol_version\":1,\"sync_hash\":null}").syncHash());
        assertNull(LetsblogPluginStatus.fromStatusOutput(
                "{\"plugin_version\":\"1.0.0\",\"protocol_version\":1}").syncHash());
        assertNull(LetsblogPluginStatus.notInstalled().syncHash());
    }

    @Test
    void wp_letsblog_syncの出力からハッシュを取り出す() {
        assertEquals("deadbeef", LetsblogPluginStatus.syncHashFromSyncOutput("{\"sync_hash\":\"deadbeef\"}"));
    }

    @Test
    void wp_letsblog_syncの出力が解釈できなければ例外() {
        assertThrows(IllegalArgumentException.class, () -> LetsblogPluginStatus.syncHashFromSyncOutput("Success"));
        assertThrows(IllegalArgumentException.class, () -> LetsblogPluginStatus.syncHashFromSyncOutput(null));
        assertThrows(IllegalArgumentException.class, () -> LetsblogPluginStatus.syncHashFromSyncOutput("{\"x\":1}"));
        assertThrows(IllegalArgumentException.class, () -> LetsblogPluginStatus.syncHashFromSyncOutput("{\"sync_hash\":\"\"}"));
    }

    @Test
    void wp_letsblog_syncの出力がJSONオブジェクトでなければ例外() {
        assertThrows(IllegalArgumentException.class, () -> LetsblogPluginStatus.syncHashFromSyncOutput("[1]"));
        assertThrows(IllegalArgumentException.class, () -> LetsblogPluginStatus.syncHashFromSyncOutput("{\"sync_hash\":5}"));
    }

    @Test
    void sync_hashが文字列でなければ同期ハッシュはnull() {
        assertNull(LetsblogPluginStatus.fromStatusOutput(
                "{\"plugin_version\":\"1.0.0\",\"protocol_version\":1,\"sync_hash\":5}").syncHash());
    }
}
