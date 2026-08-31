package com.letsblog.content.contentcache;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * コンテンツキャッシュAPIの宛先制限(issue #902)。
 *
 * <p>名前解決は継ぎ目({@link OutboundUrlGuard.HostResolver})で差し替えるので、実際のDNSは引かない。
 */
class OutboundUrlGuardTest {

    /** 与えたアドレスを常に返す解決器。 */
    private static OutboundUrlGuard guardResolvingTo(String... addresses) {
        return new OutboundUrlGuard(host -> {
            InetAddress[] result = new InetAddress[addresses.length];
            for (int i = 0; i < addresses.length; i++) {
                result[i] = InetAddress.getByName(addresses[i]);
            }
            return result;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1",        // ループバック
            "127.1.2.3",        // 127.0.0.0/8 全域
            "0.0.0.0",          // ワイルドカード
            "169.254.169.254",  // クラウドのメタデータエンドポイント
            "10.0.0.5",         // プライベート 10/8
            "172.16.0.5",       // プライベート 172.16/12
            "172.31.255.254",   // プライベート 172.16/12 の端
            "192.168.1.1",      // プライベート 192.168/16
            "100.64.0.1",       // 共有アドレス空間(CGNAT)
            "198.18.0.1",       // ベンチマーク用
            "224.0.0.1",        // マルチキャスト
            "::1",              // IPv6 ループバック
            "fe80::1",          // IPv6 リンクローカル
            "fc00::1",          // IPv6 ユニークローカル
            "fd12:3456::1",     // IPv6 ユニークローカル(fd 始まり)
    })
    @DisplayName("内部・特殊用途のアドレスへ解決される宛先は拒否する")
    void 内部アドレスは拒否する(String address) throws UnknownHostException {
        assertTrue(OutboundUrlGuard.isBlocked(InetAddress.getByName(address)), address);

        OutboundUrlGuard guard = guardResolvingTo(address);
        ContentScrapingException e = assertThrows(ContentScrapingException.class,
                () -> guard.requireAllowed("https://example.test/page"));
        // どの内部ホストへ到達したかは応答に載せない(到達可否が探索に使えるため)。
        assertFalse(e.getMessage().contains(address), e.getMessage());
        assertFalse(guard.isAllowed("https://example.test/page"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "8.8.8.8", "2606:2800:220:1:248:1893:25c8:1946"})
    @DisplayName("外部の公開アドレスへ解決される宛先は通す")
    void 外部アドレスは通す(String address) throws UnknownHostException {
        assertFalse(OutboundUrlGuard.isBlocked(InetAddress.getByName(address)), address);

        OutboundUrlGuard guard = guardResolvingTo(address);
        assertDoesNotThrow(() -> guard.requireAllowed("https://example.test/page"));
        assertTrue(guard.isAllowed("https://example.test/page"));
    }

    @Test
    @DisplayName("複数アドレスへ解決される名前は、1つでも内部なら拒否する")
    void 複数解決のうち1つでも内部なら拒否する() {
        // 外部アドレスと内部アドレスの両方を返すDNS応答で、内部側だけをすり抜けさせない。
        OutboundUrlGuard guard = guardResolvingTo("93.184.216.34", "127.0.0.1");

        assertThrows(ContentScrapingException.class, () -> guard.requireAllowed("https://example.test/"));
    }

    @Test
    @DisplayName("docker network 内のサービス名も、解決先が内部なら拒否する")
    void サービス名でも解決先で判定する() {
        // http://mysql:3306 のようにホスト名が内部を指す場合。名前ではなく解決結果で判定する。
        OutboundUrlGuard guard = guardResolvingTo("172.18.0.5");

        assertThrows(ContentScrapingException.class, () -> guard.requireAllowed("http://mysql:3306/"));
    }

    @Test
    @DisplayName("解決できないホストは拒否する")
    void 解決できないホストは拒否する() {
        OutboundUrlGuard guard = new OutboundUrlGuard(host -> {
            throw new UnknownHostException(host);
        });

        assertThrows(ContentScrapingException.class, () -> guard.requireAllowed("https://nonexistent.test/"));
    }

    @Test
    @DisplayName("解決結果が空でも通さない")
    void 解決結果が空なら拒否する() {
        OutboundUrlGuard guard = new OutboundUrlGuard(host -> new InetAddress[0]);

        assertThrows(ContentScrapingException.class, () -> guard.requireAllowed("https://example.test/"));
    }

    @Test
    @DisplayName("ホスト名の無いURLは拒否する")
    void ホスト名が無ければ拒否する() {
        OutboundUrlGuard guard = guardResolvingTo("93.184.216.34");

        assertThrows(ContentScrapingException.class, () -> guard.requireAllowed("file:///etc/passwd"));
        assertThrows(ContentScrapingException.class, () -> guard.requireAllowed("not a url"));
    }
}
