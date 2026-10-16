package com.letsblog.content.contentcache;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

/**
 * 実物の{@link OutboundUrlGuard}を、名前解決(DNS、外部との通信)だけ差し替えて作る部品(issue #1821)。
 *
 * <p>{@link OutboundUrlGuard}の分岐(アドレスの分類)は実物のまま動かす。差し替えるのは
 * {@link OutboundUrlGuard.HostResolver}(DNSの問い合わせ)だけ。{@code service}パッケージのテストからは
 * package-privateのコンストラクタに届かないため、同じパッケージ名のテスト部品として置く。
 */
public final class TestOutboundUrlGuards {

    private TestOutboundUrlGuards() {
    }

    /**
     * ホスト名ごとに解決先のアドレス(文字列。IPリテラル)を返す名前解決で作る。表に無いホスト名は
     * {@link UnknownHostException}(DNSで解決できない状態)。IPリテラルのホストはそのまま解決する。
     */
    public static OutboundUrlGuard resolving(Map<String, String> hosts) {
        return new OutboundUrlGuard(host -> {
            String address = hosts.get(host);
            if (address == null) {
                // IPリテラルはDNSを使わずに解決できる(InetAddress.getAllByNameと同じ)。
                if (host.matches("[0-9.]+") || host.contains(":")) {
                    return InetAddress.getAllByName(host);
                }
                throw new UnknownHostException(host);
            }
            return InetAddress.getAllByName(address);
        });
    }
}
