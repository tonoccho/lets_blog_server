package com.letsblog.common.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * プロジェクト単位の接続先の接続時検査(issue #1547)。名前解決・インタフェース列挙・コンテナ判定を
 * 差し替えて決定論的に検証する(docker ネットワークのサブネットとDNS応答は環境依存のため)。
 */
class ConnectionDestinationGuardTest {

    private final Map<String, List<String>> dns = new HashMap<>();
    private final List<String> resolved = new ArrayList<>();

    private ConnectionDestinationGuard guard(boolean inContainer, InterfaceAddr... interfaces) {
        return new ConnectionDestinationGuard(host -> {
            resolved.add(host);
            List<String> answers = dns.get(host);
            if (answers == null) {
                throw new UnknownHostException(host);
            }
            InetAddress[] result = new InetAddress[answers.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = InetAddress.getByName(answers.get(i));
            }
            return result;
        }, () -> List.of(interfaces), () -> inContainer);
    }

    private static InterfaceAddr iface(String address, int prefix) throws UnknownHostException {
        return new InterfaceAddr(InetAddress.getByName(address).getAddress(), prefix);
    }

    private ConnectionDestinationGuard dockerGuard() throws UnknownHostException {
        return guard(true, iface("172.18.0.7", 16));
    }

    @Test
    void dockerRangeIsTheSubnetOfNonLoopbackInterfaceAddresses() throws Exception {
        ConnectionDestinationGuard g = guard(true, iface("172.18.0.7", 16), iface("fd00:dead:beef::5", 64));
        assertThat(g.dockerRanges()).containsExactly("172.18.0.0/16", "fd00:dead:beef:0:0:0:0:0/64");
    }

    @Test
    void dockerRangeIsEmptyOutsideAContainer() throws Exception {
        assertThat(guard(false, iface("172.18.0.7", 16)).dockerRanges()).isEmpty();
    }

    @Test
    void ipLiteralInsideDockerRangeIsRejectedWithSettingName() throws Exception {
        dns.put("172.18.0.5", List.of("172.18.0.5"));
        assertThatThrownBy(() -> dockerGuard().check("Ollama", "http://172.18.0.5:3306"))
                .isInstanceOf(ForbiddenDestinationException.class)
                .hasMessageContaining("Ollama")
                .hasMessageContaining("サーバ内部のネットワーク");
    }

    @Test
    void hostnameResolvingIntoDockerRangeIsRejected() throws Exception {
        dns.put("evil.example", List.of("172.18.0.9"));
        assertThatThrownBy(() -> dockerGuard().check("ComfyUI", "http://evil.example:8188"))
                .isInstanceOf(ForbiddenDestinationException.class)
                .hasMessageContaining("ComfyUI");
    }

    @Test
    void hostnameResolvingToDeniedAddressIsRejectedEvenOutsideAContainer() throws Exception {
        dns.put("meta.example", List.of("169.254.169.254"));
        dns.put("loop.example", List.of("127.0.0.1"));
        ConnectionDestinationGuard g = guard(false);
        assertThatThrownBy(() -> g.check("Ollama", "http://meta.example")).isInstanceOf(ForbiddenDestinationException.class);
        assertThatThrownBy(() -> g.check("Ollama", "http://loop.example")).isInstanceOf(ForbiddenDestinationException.class);
    }

    @Test
    void ipv4MappedIpv6DeniedAddressIsRejected() throws Exception {
        dns.put("mapped.example", List.of("::ffff:127.0.0.1"));
        assertThatThrownBy(() -> guard(false).check("Ollama", "http://mapped.example"))
                .isInstanceOf(ForbiddenDestinationException.class);
    }

    @Test
    void anyOneBadAddressAmongSeveralRejects() throws Exception {
        dns.put("mixed.example", List.of("192.168.1.50", "172.18.0.3"));
        assertThatThrownBy(() -> dockerGuard().check("Ollama", "http://mixed.example"))
                .isInstanceOf(ForbiddenDestinationException.class);
    }

    @Test
    void privateAddressesOutsideDockerRangeAreAllowed() throws Exception {
        dns.put("192.168.1.50", List.of("192.168.1.50"));
        dns.put("10.0.0.5", List.of("10.0.0.5"));
        dns.put("lan.example", List.of("192.168.1.60"));
        ConnectionDestinationGuard g = dockerGuard();
        assertThat(g.check("Ollama", "http://192.168.1.50:11434").baseUrl()).isEqualTo("http://192.168.1.50:11434");
        assertThat(g.check("Ollama", "http://10.0.0.5:11434").baseUrl()).isEqualTo("http://10.0.0.5:11434");
        assertThat(g.check("Ollama", "http://lan.example:11434/v1").baseUrl()).isEqualTo("http://192.168.1.60:11434/v1");
    }

    @Test
    void connectsToTheCheckedAddressAndNeverResolvesAgain() throws Exception {
        // DNS rebinding: 1回目は許可アドレス、2回目は docker 内部を返す名前。
        List<List<String>> answers = List.of(List.of("192.168.1.60"), List.of("172.18.0.3"));
        int[] calls = {0};
        ConnectionDestinationGuard g = new ConnectionDestinationGuard(
                host -> new InetAddress[] {InetAddress.getByName(answers.get(Math.min(calls[0]++, 1)).get(0))},
                () -> {
                    try {
                        return List.of(iface("172.18.0.7", 16));
                    } catch (UnknownHostException e) {
                        throw new IllegalStateException(e);
                    }
                }, () -> true);
        GuardedTarget target = g.check("Ollama", "http://rebind.example:11434");
        assertThat(calls[0]).isEqualTo(1);
        assertThat(target.baseUrl()).isEqualTo("http://192.168.1.60:11434");
        assertThat(target.baseUrl()).doesNotContain("rebind.example").doesNotContain("172.18");
    }

    @Test
    void httpsHostnameKeepsSniHostWhileIpLiteralHasNone() throws Exception {
        dns.put("llm.example", List.of("192.168.1.70"));
        dns.put("192.168.1.71", List.of("192.168.1.71"));
        ConnectionDestinationGuard g = dockerGuard();
        GuardedTarget named = g.check("Ollama", "https://user@llm.example:8443/v1?x=1");
        assertThat(named.baseUrl()).isEqualTo("https://user@192.168.1.70:8443/v1?x=1");
        assertThat(named.sniHost()).isEqualTo("llm.example");
        assertThat(g.check("Ollama", "https://192.168.1.71").sniHost()).isNull();
        assertThat(g.check("Ollama", "http://llm.example").sniHost()).isNull();
    }

    @Test
    void ipv6AddressIsBracketedInThePinnedUrl() throws Exception {
        dns.put("v6.example", List.of("fd12:3456::9"));
        assertThat(guard(false).check("Ollama", "http://v6.example:11434").baseUrl())
                .isEqualTo("http://[fd12:3456:0:0:0:0:0:9]:11434");
    }

    @Test
    void bundledNamesAreExemptFromDockerRangeOnlyWhenGivenByName() throws Exception {
        for (String name : List.of("ollama", "comfyui", "comfyui-cpu", "lbs-ollama", "lbs-comfyui", "lbs-comfyui-cpu")) {
            dns.put(name, List.of("172.18.0.20"));
            assertThat(dockerGuard().check("Ollama", "http://" + name + ":11434/").baseUrl())
                    .isEqualTo("http://172.18.0.20:11434/");
        }
        dns.put("OLLAMA.", List.of("172.18.0.20"));
        dns.put("172.18.0.20", List.of("172.18.0.20"));
        assertThat(dockerGuard().check("Ollama", "http://OLLAMA.:11434").baseUrl()).isEqualTo("http://172.18.0.20:11434");
        assertThatThrownBy(() -> dockerGuard().check("Ollama", "http://172.18.0.20:11434"))
                .isInstanceOf(ForbiddenDestinationException.class);
    }

    @Test
    void bundledNameResolvingToDeniedAddressIsStillRejected() throws Exception {
        dns.put("ollama", List.of("127.0.0.1"));
        assertThatThrownBy(() -> dockerGuard().check("Ollama", "http://ollama:11434"))
                .isInstanceOf(ForbiddenDestinationException.class);
    }

    @Test
    void unresolvableHostIsRejectedWithoutConnecting() throws Exception {
        assertThatThrownBy(() -> dockerGuard().check("ComfyUI", "http://nowhere.example"))
                .isInstanceOf(ForbiddenDestinationException.class)
                .hasMessageContaining("ComfyUI")
                .hasMessageContaining("名前解決");
    }

    @Test
    void urlWithoutAHostIsRejected() throws Exception {
        assertThatThrownBy(() -> dockerGuard().check("Ollama", "http://"))
                .isInstanceOf(ForbiddenDestinationException.class);
        assertThatThrownBy(() -> dockerGuard().check("Ollama", "http://exa mple"))
                .isInstanceOf(ForbiddenDestinationException.class);
        assertThat(resolved).isEmpty();
    }

    @Test
    void addressRulesMatchTheStoredDenylist() throws Exception {
        assertThat(DestinationAddressRules.isDeniedAddress(InetAddress.getByName("::").getAddress())).isTrue();
        assertThat(DestinationAddressRules.isDeniedAddress(InetAddress.getByName("::1").getAddress())).isTrue();
        assertThat(DestinationAddressRules.isDeniedAddress(InetAddress.getByName("fe80::1").getAddress())).isTrue();
        assertThat(DestinationAddressRules.isDeniedAddress(InetAddress.getByName("fd00:ec2::254").getAddress())).isTrue();
        assertThat(DestinationAddressRules.isDeniedAddress(InetAddress.getByName("0.0.0.0").getAddress())).isTrue();
        assertThat(DestinationAddressRules.isDeniedAddress(InetAddress.getByName("fd12::1").getAddress())).isFalse();
        assertThat(DestinationAddressRules.isDeniedAddress(InetAddress.getByName("8.8.8.8").getAddress())).isFalse();
        // IPv4射影(16バイト)でも 127/8 は拒否、そうでなければ許可
        byte[] mappedLoopback = new byte[16];
        mappedLoopback[10] = (byte) 0xff;
        mappedLoopback[11] = (byte) 0xff;
        mappedLoopback[12] = 127;
        mappedLoopback[15] = 1;
        assertThat(DestinationAddressRules.isDeniedAddress(mappedLoopback)).isTrue();
        mappedLoopback[12] = 8;
        assertThat(DestinationAddressRules.isDeniedAddress(mappedLoopback)).isFalse();
    }

    @Test
    void systemInterfaceEnumerationYieldsOnlyNonLoopbackAddresses() {
        for (InterfaceAddr a : ConnectionDestinationGuard.systemInterfaces()) {
            assertThat(a.address().length).isIn(4, 16);
            assertThat(a.prefixLength()).isPositive();
        }
        assertThat(ConnectionDestinationGuard.system()).isNotNull();
    }

    @Test
    void cidrContainmentHandlesPartialByteAndFamilyMismatch() throws Exception {
        ConnectionDestinationGuard g = guard(true, iface("172.18.0.7", 12));
        assertThat(g.dockerRanges()).containsExactly("172.16.0.0/12");
        dns.put("a.example", List.of("172.31.255.1"));
        dns.put("b.example", List.of("172.32.0.1"));
        dns.put("c.example", List.of("fd12::1"));
        assertThatThrownBy(() -> g.check("Ollama", "http://a.example")).isInstanceOf(ForbiddenDestinationException.class);
        assertThat(g.check("Ollama", "http://b.example").baseUrl()).isEqualTo("http://172.32.0.1");
        assertThat(g.check("Ollama", "http://c.example").baseUrl()).startsWith("http://[");
    }

    @Test
    void pinnedHttpClientCarriesTheSniHost() {
        assertThat(PinnedHttpClients.builder(null, java.time.Duration.ofSeconds(1)).build().sslParameters().getServerNames())
                .isNull();
        assertThat(PinnedHttpClients.builder("llm.example", java.time.Duration.ofSeconds(1)).build()
                .sslParameters().getServerNames()).hasSize(1);
    }

    @Test
    void bracketedIpv6LiteralUrlIsCheckedAndHasNoSni() throws Exception {
        dns.put("fd12::9", List.of("fd12::9"));
        dns.put("::1", List.of("::1"));
        ConnectionDestinationGuard g = guard(false);
        GuardedTarget target = g.check("Ollama", "https://[fd12::9]:8443/v1");
        assertThat(target.baseUrl()).isEqualTo("https://[fd12:0:0:0:0:0:0:9]:8443/v1");
        assertThat(target.sniHost()).isNull();
        assertThatThrownBy(() -> g.check("Ollama", "http://[::1]:11434")).isInstanceOf(ForbiddenDestinationException.class);
    }

    @Test
    void scopedIpv6AddressIsPinnedWithoutItsScope() throws Exception {
        byte[] bytes = InetAddress.getByName("fd12::9").getAddress();
        ConnectionDestinationGuard g = new ConnectionDestinationGuard(
                host -> new InetAddress[] {java.net.Inet6Address.getByAddress(host, bytes, 1)},
                List::of, () -> false);
        assertThat(g.check("Ollama", "http://scoped.example").baseUrl()).isEqualTo("http://[fd12:0:0:0:0:0:0:9]");
    }

    @Test
    void ec2MetadataAddressRequiresEveryByteToMatch() throws Exception {
        for (String near : List.of("fd01:ec2::254", "fd00:1ec2::254", "fd00:ec2::255", "fd00:ec3::254",
                "fd00:ec2::1:254", "fd00:ec2:0:1::254", "fd00:ec2::154", "fe40::1")) {
            assertThat(DestinationAddressRules.isDeniedAddress(InetAddress.getByName(near).getAddress()))
                    .as(near).isFalse();
        }
        assertThat(DestinationAddressRules.isDeniedAddress(InetAddress.getByName("::2").getAddress())).isFalse();
        assertThat(DestinationAddressRules.isDeniedAddress(InetAddress.getByName("169.253.1.1").getAddress())).isFalse();
        assertThat(DestinationAddressRules.isDeniedAddress(InetAddress.getByName("1.0.0.1").getAddress())).isFalse();
        assertThat(DestinationAddressRules.isDeniedAddress(InetAddress.getByName("0.0.0.1").getAddress())).isFalse();
        assertThat(DestinationAddressRules.isDeniedAddress(InetAddress.getByName("0.0.1.0").getAddress())).isFalse();
        assertThat(DestinationAddressRules.isDeniedAddress(InetAddress.getByName("0.1.0.0").getAddress())).isFalse();
    }
}
