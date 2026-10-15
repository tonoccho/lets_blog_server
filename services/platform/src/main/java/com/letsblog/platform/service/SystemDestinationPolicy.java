package com.letsblog.platform.service;

import com.letsblog.common.net.ConnectionDestinationGuard.HostResolver;
import com.letsblog.common.net.DestinationAddressRules;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

/**
 * 管理者が設定するシステム全体の接続先の保存時検査(issue #1807、ADR-0013)。
 * ループバック・未指定・リンクローカル(169.254/16 等)の禁止アドレスだけを拒否し、dockerネットワーク内のアドレスは
 * 拒否しない(同じ PC の extensions の {@code lbx-*} を登録できるようにするため)。解決された全アドレスを検査する。
 * プロジェクト単位の上書きは別で、{@code ConnectionDestinationGuard}全体(dockerネットワークも拒否)を通す。
 * {@link ExtensionsPortalUrlPolicy}(issue #1783)の一般化で、メッセージの先頭の名詞を呼び出し側が指定する。
 */
@Component
public class SystemDestinationPolicy {

    private static final Pattern HTTP_SCHEME = Pattern.compile("(?i)https?");
    private static final Pattern BRACKETS = Pattern.compile("^\\[|]$");

    private final HostResolver resolver;

    @Autowired
    public SystemDestinationPolicy() {
        this(InetAddress::getAllByName);
    }

    public SystemDestinationPolicy(HostResolver resolver) {
        this.resolver = resolver;
    }

    public static SystemDestinationPolicy system() {
        return new SystemDestinationPolicy();
    }

    /**
     * URL形式とホストの名前解決、解決した全アドレスが禁止アドレスでないことを検査する。
     *
     * @param label エラーメッセージの先頭に付ける対象の名前(例: 「接続先」「ポータル」)
     * @throws IllegalArgumentException 許可しないURL
     */
    public void check(String label, String url) {
        String host = parseHost(label, url);
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException(label + "のホスト(" + host + ")を名前解決できません", e);
        }
        for (InetAddress address : addresses) {
            if (DestinationAddressRules.isDeniedAddress(address.getAddress())) {
                throw new IllegalArgumentException(
                        label + "のホスト(" + host + ")がループバック・リンクローカルなどの禁止されたアドレスを指しています");
            }
        }
    }

    /** サーバーが接続しない先(利用者のブラウザが開くURL。drawioUrl)のために、URL形式だけを検査する。名前解決はしない。 */
    public void checkFormat(String label, String url) {
        parseHost(label, url);
    }

    private static String parseHost(String label, String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException(label + "のURLを指定してください");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(label + "のURLを解釈できません", e);
        }
        if (!HTTP_SCHEME.matcher(String.valueOf(uri.getScheme())).matches()) {
            throw new IllegalArgumentException(label + "のURLはhttp://またはhttps://から始めてください");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException(label + "のURLにホストがありません");
        }
        // IPv6リテラルは角括弧付きで返るので外す
        return BRACKETS.matcher(host).replaceAll("");
    }
}
