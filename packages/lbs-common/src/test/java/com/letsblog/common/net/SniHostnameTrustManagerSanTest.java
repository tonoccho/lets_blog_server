package com.letsblog.common.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SAN が DNS 名ではない証明書(IPアドレスのSAN)は、ホスト名の照合に使わない(#1831)。keytoolで実物を作る。 */
class SniHostnameTrustManagerSanTest {

    @TempDir
    Path dir;

    private X509Certificate certificateWithSan(String san) throws Exception {
        Path store = dir.resolve("k.p12");
        Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "k", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=ignored",
                "-validity", "30", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", "changeit", "-ext", "san=" + san)
                .redirectErrorStream(true).start();
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).isZero();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            keys.load(in, "changeit".toCharArray());
        }
        return (X509Certificate) keys.getCertificate("k");
    }

    @Test
    void anIpSanDoesNotMatchAHostName() throws Exception {
        X509Certificate cert = certificateWithSan("ip:127.0.0.1");

        assertThat(SniHostnameTrustManager.hostMatches("127.0.0.1", cert)).isFalse();
    }

    @Test
    void anIpSanBesideADnsSanStillMatchesTheDnsName() throws Exception {
        X509Certificate cert = certificateWithSan("ip:127.0.0.1,dns:mixed.test");

        assertThat(SniHostnameTrustManager.hostMatches("mixed.test", cert)).isTrue();
        assertThat(SniHostnameTrustManager.hostMatches("other.test", cert)).isFalse();
    }
}
