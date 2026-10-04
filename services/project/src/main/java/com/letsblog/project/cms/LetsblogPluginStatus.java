package com.letsblog.project.cms;

/**
 * publishing-serviceの内部CMSブリッジ({@code /letsblog-plugin-status}、{@code /install-letsblog-plugin})の応答
 * (issue #1557)。サイトの letsblog プラグインの導入状態。
 *
 * @param state           導入済み / 未導入 / 要更新(プロトコル非互換)
 * @param version         プラグイン自体のバージョン。未導入ならnull
 * @param protocolVersion プラグインが話すプロトコルのバージョン。未導入や取得できなければnull
 * @param syncHash        プラグインが保存している同期済みの内容のハッシュ(issue #1558)。未同期ならnull
 */
public record LetsblogPluginStatus(State state, String version, Integer protocolVersion, String syncHash) {

    public LetsblogPluginStatus(State state, String version, Integer protocolVersion) {
        this(state, version, protocolVersion, null);
    }

    public enum State {
        INSTALLED,
        NOT_INSTALLED,
        NEEDS_UPDATE
    }
}
