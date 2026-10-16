package com.letsblog.gateway.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.letsblog.common.testfixtures.JwksTestServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * issue #1806: gatewayの実{@code ReactiveJwtDecoder}が、{@code azp}の許可リストでトークンを絞る。
 *
 * <p>デコーダーを差し替えず、テスト用の鍵で署名したJWT({@link JwksTestServer})を通す。
 * issuerと署名は正しく{@code azp}だけが無い(または許可リスト外の)トークンは、下流へ転送される前に401になる。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("gateway: azp許可リストによるJWT検証(issue #1806)")
class JwtAzpAllowListIntegrationTest {

    private static final String PROBE = "/api/azp-allow-list-probe";

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void realJwtDecoder(DynamicPropertyRegistry registry) {
        registry.add("keycloak.jwk-set-uri", () -> JwksTestServer.instance().jwkSetUri());
        registry.add("keycloak.issuer", () -> JwksTestServer.instance().issuer());
    }

    private int statusWith(String token) {
        WebTestClient client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
        return client.get().uri(PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().returnResult(String.class).getStatus().value();
    }

    @Test
    @DisplayName("issuerと署名は正しいがazpが無いトークンは401")
    void missingAzpIs401() {
        assertThat(statusWith(JwksTestServer.instance().token(null))).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"letsblog-tools-client", "lbx-proxy", "admin-cli"})
    @DisplayName("許可リストに無いazpのトークンは401")
    void otherAzpIs401(String azp) {
        assertThat(statusWith(JwksTestServer.instance().token(azp))).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"letsblog-web", "letsblog-services", "letsblog-vscode", "letsblog-e2e"})
    @DisplayName("許可リストのazpのトークンは認証を通る(401にならない)")
    void allowedAzpPassesAuthentication(String azp) {
        assertThat(statusWith(JwksTestServer.instance().token(azp))).isNotEqualTo(401);
    }
}
