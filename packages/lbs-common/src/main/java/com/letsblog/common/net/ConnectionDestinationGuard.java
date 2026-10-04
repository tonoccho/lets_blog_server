package com.letsblog.common.net;

import java.io.UncheckedIOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * プロジェクト単位の接続先(Ollama / ComfyUIの上書き値)の接続時検査(issue #1547。保存時検査はissue #1518)。
 *
 * <p>接続のたびにホストを名前解決し、<b>解決された全アドレス</b>が (a) 自プロセスが参加するdockerネットワークの
 * 範囲、(b) {@link DestinationAddressRules#isDeniedAddress}の禁止アドレス、のどちらにも入らないことを確かめる。
 * 検査を通ったら、そのアドレスへ置き換えたURLを返す。呼び出し側はそれへ接続し、改めて名前解決しない
 * (検査と接続の間に別アドレスへ向け直すDNS rebindingを防ぐ)。
 *
 * <p>dockerネットワーク範囲は、非loopbackインタフェースのアドレスとプレフィックス長から求める
 * (dockerソケットに触れない設計のためDocker APIは使わない)。コンテナ内でなければ(開発時にJVMを直接起動)
 * 空とし、ホストのLANを誤って拒否しない。同梱のOllama / ComfyUIを<b>名前で</b>指定した場合に限り(a)は適用しない。
 */
public class ConnectionDestinationGuard {

    /** 名前解決。テストで差し替える。 */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** issue #1518が拒否リストから意図的に外した同梱サービス名(とそのlbs-付き)。 */
    private static final Set<String> BUNDLED_NAMES = Set.of(
            "ollama", "comfyui", "comfyui-cpu", "lbs-ollama", "lbs-comfyui", "lbs-comfyui-cpu");

    private static final Pattern NUMERIC_HOST = Pattern.compile("[0-9.]+");

    private final HostResolver resolver;
    private final Supplier<List<InterfaceAddr>> interfaces;
    private final BooleanSupplier inContainer;

    public ConnectionDestinationGuard(
            HostResolver resolver, Supplier<List<InterfaceAddr>> interfaces, BooleanSupplier inContainer) {
        this.resolver = resolver;
        this.interfaces = interfaces;
        this.inContainer = inContainer;
    }

    /** 実環境用: OSの名前解決、実インタフェース、{@code /.dockerenv}の有無。 */
    public static ConnectionDestinationGuard system() {
        return new ConnectionDestinationGuard(
                InetAddress::getAllByName, ConnectionDestinationGuard::systemInterfaces,
                () -> Files.exists(Path.of("/.dockerenv")));
    }

    /** 自プロセスの、起動中で非loopbackのインタフェースに付いたアドレス。 */
    static List<InterfaceAddr> systemInterfaces() {
        try {
            List<InterfaceAddr> result = new ArrayList<>();
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (nic.isUp() && !nic.isLoopback()) {
                    for (InterfaceAddress ia : nic.getInterfaceAddresses()) {
                        result.add(new InterfaceAddr(ia.getAddress().getAddress(), ia.getNetworkPrefixLength()));
                    }
                }
            }
            return result;
        } catch (SocketException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 拒否するdockerネットワーク範囲(CIDR表記)。コンテナ外なら空。 */
    public List<String> dockerRanges() {
        List<String> ranges = new ArrayList<>();
        for (Cidr cidr : dockerCidrs()) {
            ranges.add(cidr.toString());
        }
        return ranges;
    }

    private List<Cidr> dockerCidrs() {
        List<Cidr> cidrs = new ArrayList<>();
        if (inContainer.getAsBoolean()) {
            for (InterfaceAddr a : interfaces.get()) {
                cidrs.add(Cidr.of(DestinationAddressRules.unmapIpv4(a.address()), a.prefixLength()));
            }
        }
        return cidrs;
    }

    /**
     * {@code url}のホストを解決して検査し、検査したアドレスへ置き換えた接続先を返す。
     *
     * @param settingLabel エラーメッセージに出す、原因のプロジェクト設定名(例: "Ollama")
     * @throws ForbiddenDestinationException 拒否(接続は試みない)
     */
    public GuardedTarget check(String settingLabel, String url) {
        URI uri = parse(settingLabel, url);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.endsWith(".") && !host.contains(":")) {
            host = host.substring(0, host.length() - 1);
        }
        InetAddress[] addresses = resolve(settingLabel, host);
        boolean bundledByName = BUNDLED_NAMES.contains(host);
        List<Cidr> docker = bundledByName ? List.of() : dockerCidrs();
        for (InetAddress address : addresses) {
            byte[] bytes = DestinationAddressRules.unmapIpv4(address.getAddress());
            if (DestinationAddressRules.isDeniedAddress(bytes) || inAny(docker, bytes)) {
                throw new ForbiddenDestinationException(
                        "プロジェクト設定の「" + settingLabel + " の接続先」(" + host
                                + ")がサーバ内部のネットワーク(または禁止された宛先)のため接続しませんでした");
            }
        }
        boolean literal = host.contains(":") || NUMERIC_HOST.matcher(host).matches();
        String sni = "https".equals(uri.getScheme()) && !literal ? host : null;
        return new GuardedTarget(pin(uri, addresses[0]), sni);
    }

    private URI parse(String settingLabel, String url) {
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null || uri.getHost().isEmpty()) {
                throw new URISyntaxException(url, "no host");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new ForbiddenDestinationException(
                    "プロジェクト設定の「" + settingLabel + " の接続先」のホストを解釈できないため接続しませんでした");
        }
    }

    private InetAddress[] resolve(String settingLabel, String host) {
        try {
            return resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new ForbiddenDestinationException(
                    "プロジェクト設定の「" + settingLabel + " の接続先」(" + host + ")を名前解決できないため接続しませんでした");
        }
    }

    private static boolean inAny(List<Cidr> cidrs, byte[] address) {
        for (Cidr cidr : cidrs) {
            if (cidr.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /** ホスト部だけを検査したアドレスのリテラルへ置き換える(userinfo・ポート・パス・クエリは保つ)。 */
    private static String pin(URI uri, InetAddress address) {
        String literal = address.getHostAddress();
        if (address instanceof Inet6Address) {
            int scope = literal.indexOf('%');
            literal = "[" + (scope >= 0 ? literal.substring(0, scope) : literal) + "]";
        }
        StringBuilder sb = new StringBuilder(uri.getScheme()).append("://");
        if (uri.getRawUserInfo() != null) {
            sb.append(uri.getRawUserInfo()).append('@');
        }
        sb.append(literal);
        if (uri.getPort() != -1) {
            sb.append(':').append(uri.getPort());
        }
        sb.append(uri.getRawPath());
        if (uri.getRawQuery() != null) {
            sb.append('?').append(uri.getRawQuery());
        }
        return sb.toString();
    }

    /** ネットワークアドレス + プレフィックス長。 */
    private record Cidr(byte[] network, int prefixLength) {

        static Cidr of(byte[] address, int prefixLength) {
            byte[] network = address.clone();
            for (int i = 0; i < network.length; i++) {
                int keep = Math.max(0, Math.min(8, prefixLength - i * 8));
                network[i] &= (byte) (0xff << (8 - keep));
            }
            return new Cidr(network, prefixLength);
        }

        boolean contains(byte[] address) {
            return address.length == network.length && java.util.Arrays.equals(of(address, prefixLength).network, network);
        }

        @Override
        public String toString() {
            try {
                return InetAddress.getByAddress(network).getHostAddress() + "/" + prefixLength;
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
