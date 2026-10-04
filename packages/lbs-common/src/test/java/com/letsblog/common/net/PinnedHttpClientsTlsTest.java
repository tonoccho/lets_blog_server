package com.letsblog.common.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 検査したIPアドレスへhttpsで接続しても、証明書は元のホスト名に対して検証される(issue #1547)。
 * JDKのHttpClientはIPリテラルのURLではホスト名検証をしないため、元のホスト名での照合を自前で足している。
 * 証明書はtest/resources/net(テスト用CAが署名した、pinned.test と *.wild.test のSAN付き。有効期間100年)。
 */
class PinnedHttpClientsTlsTest {

    private HttpsServer server;
    private X509ExtendedTrustManager caTrust;
    private X509Certificate serverCert;
    private String url;

    private static X509Certificate pem(String name) throws Exception {
        try (InputStream in = PinnedHttpClientsTlsTest.class.getResourceAsStream("/net/" + name)) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = getClass().getResourceAsStream("/net/pinned-server.p12")) {
            keys.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(keys, "changeit".toCharArray());
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(kmf.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/", exchange -> {
            byte[] bytes = "ok".getBytes();
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        url = "https://127.0.0.1:" + server.getAddress().getPort() + "/";

        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("ca", pem("test-ca.pem"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
        tmf.init(trust);
        caTrust = (X509ExtendedTrustManager) tmf.getTrustManagers()[0];
        serverCert = pem("pinned-server.pem");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private String get(String sniHost) throws Exception {
        HttpClient client = PinnedHttpClients.builder(sniHost, Duration.ofSeconds(5), caTrust).build();
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void connectsToThePinnedAddressWhenTheCertificateMatchesTheOriginalHostname() throws Exception {
        assertThat(get("pinned.test")).isEqualTo("ok");
    }

    @Test
    void refusesACertificateThatDoesNotMatchTheOriginalHostname() {
        assertThatThrownBy(() -> get("other.test")).isInstanceOf(javax.net.ssl.SSLException.class);
    }

    @Test
    void hostnameMatchingCoversExactCaseInsensitiveAndWildcardNames() {
        assertThat(SniHostnameTrustManager.hostMatches("pinned.test", serverCert)).isTrue();
        assertThat(SniHostnameTrustManager.hostMatches("PINNED.test", serverCert)).isTrue();
        assertThat(SniHostnameTrustManager.hostMatches("a.wild.test", serverCert)).isTrue();
        assertThat(SniHostnameTrustManager.hostMatches("a.b.wild.test", serverCert)).isFalse();
        assertThat(SniHostnameTrustManager.hostMatches("wild.test", serverCert)).isFalse();
        assertThat(SniHostnameTrustManager.hostMatches("other.test", serverCert)).isFalse();
        assertThat(SniHostnameTrustManager.hostMatches("singlelabel", serverCert)).isFalse();
    }

    @Test
    void certificateWithoutDnsNamesMatchesNothing() throws Exception {
        // 同梱のCA証明書にはSANが無い。
        assertThat(SniHostnameTrustManager.hostMatches("pinned.test", pem("test-ca.pem"))).isFalse();
    }

    /** 委譲先の呼び出しを記録するだけの信頼マネージャ。 */
    private static final class Recording extends X509ExtendedTrustManager {
        final List<String> calls = new ArrayList<>();

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, java.net.Socket socket) {
            calls.add("client-socket");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, java.net.Socket socket) {
            calls.add("server-socket");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            calls.add("client-engine");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            calls.add("server-engine");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            calls.add("client");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            calls.add("server");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            calls.add("issuers");
            return new X509Certificate[0];
        }
    }

    @Test
    void everyCheckDelegatesAndServerChecksAlsoMatchTheHostname() throws Exception {
        Recording delegate = new Recording();
        SniHostnameTrustManager good = new SniHostnameTrustManager(delegate, "pinned.test");
        X509Certificate[] chain = {serverCert};
        good.checkClientTrusted(chain, "RSA");
        good.checkClientTrusted(chain, "RSA", (SSLEngine) null);
        good.checkClientTrusted(chain, "RSA", (java.net.Socket) null);
        good.checkServerTrusted(chain, "RSA");
        good.checkServerTrusted(chain, "RSA", (SSLEngine) null);
        good.checkServerTrusted(chain, "RSA", (java.net.Socket) null);
        assertThat(good.getAcceptedIssuers()).isEmpty();
        assertThat(delegate.calls).containsExactly("client", "client-engine", "client-socket",
                "server", "server-engine", "server-socket", "issuers");

        SniHostnameTrustManager bad = new SniHostnameTrustManager(delegate, "other.test");
        assertThatThrownBy(() -> bad.checkServerTrusted(chain, "RSA")).isInstanceOf(CertificateException.class);
        assertThatThrownBy(() -> bad.checkServerTrusted(chain, "RSA", (SSLEngine) null))
                .isInstanceOf(CertificateException.class);
        assertThatThrownBy(() -> bad.checkServerTrusted(chain, "RSA", (java.net.Socket) null))
                .isInstanceOf(CertificateException.class);
    }

    @Test
    void trustManagerArrayHelperPicksTheExtendedManager() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        TrustManager[] managers = tmf.getTrustManagers();
        assertThat(PinnedHttpClients.defaultTrustManager()).isInstanceOf(X509ExtendedTrustManager.class);
        assertThat(managers).isNotEmpty();
    }
}
