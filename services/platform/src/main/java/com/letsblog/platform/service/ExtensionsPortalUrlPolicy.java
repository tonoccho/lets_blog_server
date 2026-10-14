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
 * extensions のポータルのURLの検査(issue #1783)。ループバック・未指定・リンクローカル(169.254/16 等)は拒否する。
 * 同じ PC の経路は共有ネットワーク上の {@code lbx-portal} を指すので、dockerネットワーク内のアドレスは拒否しない
 * (Ollama / ComfyUI のシステム設定と同じ扱い)。解決された全アドレスを検査する。
 */
@Component
public class ExtensionsPortalUrlPolicy {

    private static final Pattern HTTP_SCHEME = Pattern.compile("(?i)https?");
    private static final Pattern BRACKETS = Pattern.compile("^\\[|]$");

    private final HostResolver resolver;

    @Autowired
    public ExtensionsPortalUrlPolicy() {
        this(InetAddress::getAllByName);
    }

    public ExtensionsPortalUrlPolicy(HostResolver resolver) {
        this.resolver = resolver;
    }

    public static ExtensionsPortalUrlPolicy system() {
        return new ExtensionsPortalUrlPolicy();
    }

    /** @throws IllegalArgumentException 許可しないURL */
    public void check(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("ポータルのURLを指定してください");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("ポータルのURLを解釈できません", e);
        }
        if (!HTTP_SCHEME.matcher(String.valueOf(uri.getScheme())).matches()) {
            throw new IllegalArgumentException("ポータルのURLはhttp://またはhttps://から始めてください");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("ポータルのURLにホストがありません");
        }
        // IPv6リテラルは角括弧付きで返るので外す
        host = BRACKETS.matcher(host).replaceAll("");
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("ポータルのホスト(" + host + ")を名前解決できません", e);
        }
        for (InetAddress address : addresses) {
            if (DestinationAddressRules.isDeniedAddress(address.getAddress())) {
                throw new IllegalArgumentException(
                        "ポータルのホスト(" + host + ")がループバック・リンクローカルなどの禁止されたアドレスを指しています");
            }
        }
    }
}
