package com.letsblog.publishing.cms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * サイトに導入された letsblog プラグインの状態(issue #1557)。`wp letsblog status`(issue #1556)の
 * 出力から判定する。アプリとプラグインのやり取りは wp-cli だけで行う(REST API は使わない)。
 *
 * @param state           導入済み / 未導入 / 要更新(プロトコル非互換)
 * @param version         プラグイン自体のバージョン。未導入ならnull
 * @param protocolVersion プラグインが話すプロトコルのバージョン。未導入や取得できなければnull
 * @param syncHash        プラグインが保存している同期済みの内容のハッシュ(issue #1558)。まだ同期されていなければnull
 */
public record LetsblogPluginStatus(State state, String version, Integer protocolVersion, String syncHash) {

    public LetsblogPluginStatus(State state, String version, Integer protocolVersion) {
        this(state, version, protocolVersion, null);
    }

    /** このアプリが話せるプロトコルのバージョン。プラグイン側の LETSBLOG_PROTOCOL_VERSION と一致すれば互換。 */
    public static final int SUPPORTED_PROTOCOL_VERSION = 1;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public enum State {
        INSTALLED,
        NOT_INSTALLED,
        NEEDS_UPDATE
    }

    public static LetsblogPluginStatus notInstalled() {
        return new LetsblogPluginStatus(State.NOT_INSTALLED, null, null, null);
    }

    /** 導入済み(プロトコルも互換)で、投稿・プレビューの対象にできるか。 */
    public boolean installed() {
        return state == State.INSTALLED;
    }

    /**
     * wp-cli が「`letsblog` は登録されたコマンドではない」と答えたか。プラグインが無い・停止しているときの
     * 応答で、これだけが「未導入」を意味する。wpPathの誤り・wp-cli未導入・PHPの致命的エラー等は別の失敗で、
     * 未導入と取り違えない。
     */
    public static boolean isCommandMissing(String output) {
        return output != null && output.contains("is not a registered wp command");
    }

    /**
     * `wp letsblog status` が正常終了したときの標準出力から判定する。JSONオブジェクトとして読めなければ
     * (PHPの警告・致命的エラー等)判定できないので、未導入にせず例外にする。
     *
     * @throws IllegalArgumentException 出力がJSONオブジェクトでないとき
     */
    public static LetsblogPluginStatus fromStatusOutput(String stdout) {
        JsonNode node;
        try {
            node = stdout == null ? null : OBJECT_MAPPER.readTree(stdout.strip());
        } catch (Exception e) {
            throw new IllegalArgumentException("wp letsblog statusの出力を解釈できません: " + abbreviate(stdout), e);
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("wp letsblog statusの出力を解釈できません: " + abbreviate(stdout));
        }
        String version = node.path("plugin_version").asText(null);
        Integer protocol = node.path("protocol_version").isInt() ? node.path("protocol_version").asInt() : null;
        State state = protocol != null && protocol == SUPPORTED_PROTOCOL_VERSION
                ? State.INSTALLED : State.NEEDS_UPDATE;
        String syncHash = node.path("sync_hash").isTextual() ? node.path("sync_hash").asText() : null;
        return new LetsblogPluginStatus(state, version, protocol, syncHash);
    }

    /**
     * `wp letsblog sync` が正常終了したときの標準出力から、プラグインが保存した内容のハッシュを取り出す(issue #1558)。
     *
     * @throws IllegalArgumentException 出力がJSONオブジェクトでない、またはsync_hashが無いとき
     */
    public static String syncHashFromSyncOutput(String stdout) {
        JsonNode node;
        try {
            node = stdout == null ? null : OBJECT_MAPPER.readTree(stdout.strip());
        } catch (Exception e) {
            throw new IllegalArgumentException("wp letsblog syncの出力を解釈できません: " + abbreviate(stdout), e);
        }
        if (node == null || !node.isObject() || !node.path("sync_hash").isTextual()
                || node.path("sync_hash").asText().isEmpty()) {
            throw new IllegalArgumentException("wp letsblog syncの出力を解釈できません: " + abbreviate(stdout));
        }
        return node.path("sync_hash").asText();
    }

    private static String abbreviate(String text) {
        String value = text == null ? "" : text.strip();
        return value.length() > 200 ? value.substring(0, 200) + "…" : value;
    }
}
