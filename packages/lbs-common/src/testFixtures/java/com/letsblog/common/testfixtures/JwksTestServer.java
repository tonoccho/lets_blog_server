package com.letsblog.common.testfixtures;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * テスト用の鍵で署名したJWTと、その公開鍵を配るJWKSエンドポイントを提供する(#1806)。
 *
 * <p>各サービスの実{@code JwtDecoder}(署名・有効期限・issuer・azpの検査を含む)を、Keycloakなしで
 * 通すために使う。Keycloakは{@code azp}の無いアクセストークンを発行しないため、「issuerと署名は
 * 正しく{@code azp}だけが無い」トークンは、このクラスでしか作れない。
 *
 * <p>{@code @DynamicPropertySource}で{@code keycloak.jwk-set-uri}と{@code keycloak.issuer}へ
 * {@link #jwkSetUri()}・{@link #issuer()}を渡して使う。JVMごとに1つ起動し続ける(デーモンスレッド)。
 */
public final class JwksTestServer {

    private static final JwksTestServer INSTANCE = new JwksTestServer();

    private final RSAKey key;
    private final HttpServer server;

    private JwksTestServer() {
        try {
            this.key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/certs", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.setExecutor(runnable -> {
                Thread t = new Thread(runnable, "jwks-test-server");
                t.setDaemon(true);
                t.start();
            });
            server.start();
        } catch (Exception e) {
            throw new IllegalStateException("JWKSテストサーバーを起動できない", e);
        }
    }

    public static JwksTestServer instance() {
        return INSTANCE;
    }

    public String jwkSetUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/certs";
    }

    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/auth/realms/letsblog";
    }

    /** issuer・署名・有効期限は正しく、{@code azp}を持つトークン。{@code azp}がnullなら{@code azp}クレームを付けない。 */
    public String token(String azp) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer())
                .subject("sub-1806")
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 60_000));
        if (azp != null) {
            claims.claim("azp", azp);
        }
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("テスト用JWTに署名できない", e);
        }
    }
}
