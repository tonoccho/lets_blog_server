package com.letsblog.ai.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.letsblog.common.testfixtures.JwksTestServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * issue #1806: ai-serviceの実{@code JwtDecoder}が、{@code azp}の許可リストでトークンを絞る。
 *
 * <p>{@code JwtDecoder}を差し替えず、テスト用の鍵で署名したJWT({@link JwksTestServer})を実物のデコーダーに通す。
 * issuerと署名は正しく、{@code azp}だけが無い(または許可リスト外の)トークンは401になる。Keycloakは
 * {@code azp}の無いアクセストークンを発行しないため、このケースはAT(Gherkin)ではなくここで確かめる。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("ai-service: azp許可リストによるJWT検証(issue #1806)")
class JwtAzpAllowListIntegrationTest {

    private static final String PROBE = "/api/azp-allow-list-probe";

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void realJwtDecoder(DynamicPropertyRegistry registry) {
        registry.add("keycloak.jwk-set-uri", () -> JwksTestServer.instance().jwkSetUri());
        registry.add("keycloak.issuer", () -> JwksTestServer.instance().issuer());
    }

    private int statusWith(String token) throws Exception {
        return mockMvc.perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("issuerと署名は正しいがazpが無いトークンは401")
    void missingAzpIs401() throws Exception {
        assertThat(statusWith(JwksTestServer.instance().token(null))).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"letsblog-tools-client", "lbx-proxy", "admin-cli"})
    @DisplayName("許可リストに無いazpのトークンは401")
    void otherAzpIs401(String azp) throws Exception {
        assertThat(statusWith(JwksTestServer.instance().token(azp))).isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"letsblog-web", "letsblog-services", "letsblog-vscode", "letsblog-e2e"})
    @DisplayName("許可リストのazpのトークンは認証を通る(401にならない)")
    void allowedAzpPassesAuthentication(String azp) throws Exception {
        assertThat(statusWith(JwksTestServer.instance().token(azp))).isNotEqualTo(401);
    }
}
