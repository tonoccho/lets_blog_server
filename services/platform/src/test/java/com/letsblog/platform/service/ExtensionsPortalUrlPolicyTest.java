package com.letsblog.platform.service;

import com.letsblog.common.net.ConnectionDestinationGuard.HostResolver;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** extensions のポータルのURLの検査(issue #1783)。ループバックとリンクローカルは拒否し、dockerネットワーク内は拒否しない。 */
class ExtensionsPortalUrlPolicyTest {

    private static final HostResolver RESOLVER = host -> {
        switch (host) {
            case "lbx-portal":
                return new InetAddress[] {InetAddress.getByName("172.20.0.9")};
            case "portal.lan":
                return new InetAddress[] {InetAddress.getByName("192.168.1.20")};
            case "sneaky.example":
                return new InetAddress[] {InetAddress.getByName("192.168.1.20"), InetAddress.getByName("127.0.0.1")};
            case "metadata.example":
                return new InetAddress[] {InetAddress.getByName("169.254.169.254")};
            default:
                if (host.matches("[0-9.]+") || host.contains(":")) {
                    return new InetAddress[] {InetAddress.getByName(host)};
                }
                throw new UnknownHostException(host);
        }
    };

    private final ExtensionsPortalUrlPolicy policy = new ExtensionsPortalUrlPolicy(RESOLVER);

    @Test
    void dockerネットワーク内のlbx_portalとLANのアドレスは拒否しない() {
        assertDoesNotThrow(() -> policy.check("http://lbx-portal:3100"));
        assertDoesNotThrow(() -> policy.check("http://172.20.0.9:3100/"));
        assertDoesNotThrow(() -> policy.check("https://portal.lan:8443"));
    }

    @Test
    void ループバックとリンクローカルは拒否する() {
        for (String url : new String[] {
                "http://127.0.0.1:3100", "http://[::1]:3100", "http://169.254.169.254/latest/meta-data/",
                "http://[fe80::1]:3100", "http://0.0.0.0:3100", "http://metadata.example"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> policy.check(url), url);
            assertTrue(e.getMessage().contains("ループバック"), e.getMessage());
        }
    }

    @Test
    void 解決された全アドレスのうち1つでも禁止なら拒否する() {
        assertThrows(IllegalArgumentException.class, () -> policy.check("http://sneaky.example:3100"));
    }

    @Test
    void 空_スキーム違い_解釈できない_ホスト無し_名前解決できないは拒否する() {
        for (String url : new String[] {null, "", "  ", "ftp://lbx-portal", "lbx-portal:3100", "http://", "http://a b",
                "http://unknown.invalid:3100"}) {
            assertThrows(IllegalArgumentException.class, () -> policy.check(url), String.valueOf(url));
        }
    }

    @Test
    void 実環境用のポリシーはループバックを拒否する() {
        assertThrows(IllegalArgumentException.class, () -> ExtensionsPortalUrlPolicy.system().check("http://127.0.0.1:3100"));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionsPortalUrlPolicy().check("http://localhost:3100"));
    }
}
