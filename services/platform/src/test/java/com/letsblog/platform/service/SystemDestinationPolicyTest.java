package com.letsblog.platform.service;

import com.letsblog.common.net.ConnectionDestinationGuard.HostResolver;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * システム全体の登録の接続先の検査(issue #1807、ADR-0013)。禁止アドレスだけを拒否し、dockerネットワーク内は通す。
 * {@code checkFormat}はサーバーが接続しない先(drawioUrl)のためにURL形式だけを検査する。
 */
class SystemDestinationPolicyTest {

    private static final HostResolver RESOLVER = host -> {
        switch (host) {
            case "lbx-ollama":
                return new InetAddress[] {InetAddress.getByName("172.20.0.9")};
            case "gpu.lan":
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

    private final SystemDestinationPolicy policy = new SystemDestinationPolicy(RESOLVER);

    @Test
    void dockerネットワーク内のlbxとLANのアドレスは拒否しない() {
        assertDoesNotThrow(() -> policy.check("接続先", "http://lbx-ollama:11434/v1"));
        assertDoesNotThrow(() -> policy.check("接続先", "http://172.20.0.9:8188"));
        assertDoesNotThrow(() -> policy.check("接続先", "https://gpu.lan:8443"));
    }

    @Test
    void 禁止アドレスを指すURLは理由つきで拒否する() {
        for (String url : new String[] {
                "http://127.0.0.1:11434", "http://[::1]:11434", "http://169.254.169.254/latest/meta-data/",
                "http://[fe80::1]:11434", "http://0.0.0.0:11434", "http://metadata.example"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> policy.check("接続先", url), url);
            assertTrue(e.getMessage().startsWith("接続先のホスト"), e.getMessage());
            assertTrue(e.getMessage().contains("禁止されたアドレス"), e.getMessage());
        }
    }

    @Test
    void 解決された全アドレスのうち1つでも禁止なら拒否する() {
        assertThrows(IllegalArgumentException.class, () -> policy.check("接続先", "http://sneaky.example:11434"));
    }

    @Test
    void 空_スキーム違い_解釈できない_ホスト無し_名前解決できないは拒否し理由を述べる() {
        assertReason(null, "指定してください");
        assertReason("  ", "指定してください");
        assertReason("ftp://lbx-ollama", "http://またはhttps://");
        assertReason("lbx-ollama:11434", "http://またはhttps://");
        assertReason("http://a b", "解釈できません");
        assertReason("http://", "解釈できません");
        assertReason("http:///path", "ホストがありません");
        assertReason("http://unknown.invalid", "名前解決できません");
    }

    private void assertReason(String url, String fragment) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> policy.check("接続先", url));
        assertTrue(e.getMessage().startsWith("接続先の"), e.getMessage());
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }

    @Test
    void 形式だけの検査は名前解決をせず_httpとhttpsを通す() {
        SystemDestinationPolicy noResolve = new SystemDestinationPolicy(host -> {
            throw new UnknownHostException(host);
        });
        assertDoesNotThrow(() -> noResolve.checkFormat("draw.io", "https://drawio.example.com/"));
        assertDoesNotThrow(() -> noResolve.checkFormat("draw.io", "HTTP://localhost:8080"));
    }

    @Test
    void 形式だけの検査も_空_スキーム違い_ホスト無し_解釈できないは拒否する() {
        for (String url : new String[] {null, "", "ftp://drawio", "drawio:8080", "http://", "http:///path", "http://a b"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> policy.checkFormat("draw.io", url), String.valueOf(url));
            assertTrue(e.getMessage().startsWith("draw.ioの"), e.getMessage());
        }
    }

    @Test
    void 既定コンストラクタはシステムのリゾルバを使い_ループバックを拒否する() {
        assertThrows(IllegalArgumentException.class, () -> new SystemDestinationPolicy().check("接続先", "http://127.0.0.1:1"));
        assertDoesNotThrow(() -> SystemDestinationPolicy.system().checkFormat("draw.io", "http://127.0.0.1:1"));
    }
}
