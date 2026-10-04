package com.letsblog.common.net;

/**
 * 接続時検査を通った接続先。{@code baseUrl}は検査したアドレス(IPリテラル)へ置き換え済みで、
 * これへ接続すれば検査後に改めて名前解決されない。{@code sniHost}はhttpsで元がホスト名だったときだけ入り、
 * TLSのSNIと証明書検証に使う元のホスト名(それ以外はnull)。
 */
public record GuardedTarget(String baseUrl, String sniHost) {
}
