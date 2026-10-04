package com.letsblog.common.net;

import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * 検査したアドレス(IPリテラル)へ接続するJDK {@link HttpClient}の組み立て。httpsで元がホスト名だったときは
 * SNIにその元のホスト名を載せ、サーバ証明書も元のホスト名に対して検証する({@link SniHostnameTrustManager})。
 * リダイレクトは追わない(JDKの既定。追うと検査していない宛先へ移れてしまう)。
 */
public final class PinnedHttpClients {

    private PinnedHttpClients() {
    }

    public static HttpClient.Builder builder(String sniHost, Duration connectTimeout) {
        return builder(sniHost, connectTimeout, sniHost == null ? null : defaultTrustManager());
    }

    /** テスト用に、チェーン検証を行う信頼マネージャを差し替えられる。{@code sniHost}がnullなら使わない。 */
    static HttpClient.Builder builder(String sniHost, Duration connectTimeout, X509ExtendedTrustManager trust) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout);
        if (sniHost != null) {
            SSLParameters parameters = new SSLParameters();
            parameters.setServerNames(List.of(new SNIHostName(sniHost)));
            builder.sslParameters(parameters);
            try {
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, new TrustManager[] {new SniHostnameTrustManager(trust, sniHost)}, null);
                builder.sslContext(context);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }
        return builder;
    }

    /** JVM既定の信頼ストアによる信頼マネージャ。 */
    static X509ExtendedTrustManager defaultTrustManager() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            return Arrays.stream(factory.getTrustManagers())
                    .filter(X509ExtendedTrustManager.class::isInstance)
                    .map(X509ExtendedTrustManager.class::cast)
                    .findFirst().orElseThrow();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
