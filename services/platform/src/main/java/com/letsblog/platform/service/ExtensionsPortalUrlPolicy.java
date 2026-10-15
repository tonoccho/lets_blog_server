package com.letsblog.platform.service;

import com.letsblog.common.net.ConnectionDestinationGuard.HostResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

/**
 * extensions のポータルのURLの検査(issue #1783)。規則は{@link SystemDestinationPolicy}(issue #1807、ADR-0013)と同じで、
 * ループバック・未指定・リンクローカルは拒否し、dockerネットワーク内のアドレスは拒否しない。メッセージの対象は「ポータル」。
 */
@Component
public class ExtensionsPortalUrlPolicy {

    private static final String LABEL = "ポータル";

    private final SystemDestinationPolicy delegate;

    @Autowired
    public ExtensionsPortalUrlPolicy() {
        this(InetAddress::getAllByName);
    }

    public ExtensionsPortalUrlPolicy(HostResolver resolver) {
        this.delegate = new SystemDestinationPolicy(resolver);
    }

    public static ExtensionsPortalUrlPolicy system() {
        return new ExtensionsPortalUrlPolicy();
    }

    /** @throws IllegalArgumentException 許可しないURL */
    public void check(String url) {
        delegate.check(LABEL, url);
    }
}
