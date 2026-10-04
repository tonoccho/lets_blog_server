package com.letsblog.common.net;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * 検査したIPアドレスへhttpsで接続するとき、サーバ証明書を<b>元のホスト名</b>に対して検証する信頼マネージャ。
 * JDKのHttpClientはIPリテラルのURLではホスト名を検証しないため、チェーンの検証は委譲先(既定の信頼ストア)に任せ、
 * 加えて証明書のSAN(DNS名。ワイルドカードは先頭1ラベルのみ)が元のホスト名に一致することを確かめる(issue #1547)。
 */
final class SniHostnameTrustManager extends X509ExtendedTrustManager {

    private static final int SAN_DNS_NAME = 2;

    private final X509ExtendedTrustManager delegate;
    private final String host;

    SniHostnameTrustManager(X509ExtendedTrustManager delegate, String host) {
        this.delegate = delegate;
        this.host = host;
    }

    static boolean hostMatches(String host, X509Certificate certificate) {
        Collection<List<?>> names;
        try {
            names = certificate.getSubjectAlternativeNames();
        } catch (CertificateParsingException e) {
            return false;
        }
        if (names == null) {
            return false;
        }
        String wanted = host.toLowerCase(Locale.ROOT);
        for (List<?> name : names) {
            if ((Integer) name.get(0) == SAN_DNS_NAME && nameMatches(wanted, ((String) name.get(1)).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean nameMatches(String host, String pattern) {
        if (!pattern.startsWith("*.")) {
            return host.equals(pattern);
        }
        int dot = host.indexOf('.');
        return dot > 0 && host.substring(dot).equals(pattern.substring(1));
    }

    private void requireHost(X509Certificate[] chain) throws CertificateException {
        if (!hostMatches(host, chain[0])) {
            throw new CertificateException("証明書が接続先のホスト名(" + host + ")と一致しません");
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType, socket);
        requireHost(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType, engine);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType, engine);
        requireHost(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
        requireHost(chain);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }
}
