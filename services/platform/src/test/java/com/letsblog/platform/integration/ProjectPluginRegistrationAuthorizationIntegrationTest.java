package com.letsblog.platform.integration;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.platform.repository.ProjectPluginRegistrationRepository;
import com.letsblog.platform.testsupport.SharedIdentityFake;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** プロジェクト単位のプラグインの登録のAPIが、メンバーでない利用者を403にする(issue #1808)。identity-serviceはHTTPのサーバー側フェイクで模す(issue #1825。IdentityClient・IdentityBridgeClientは実物)。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("platform-service: プロジェクト単位のプラグインの登録の認可(issue #1808)")
class ProjectPluginRegistrationAuthorizationIntegrationTest extends IdentityFakeIntegrationTestBase {

    private static final String PATH = "/api/projects/10/plugin-registrations";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectPluginRegistrationRepository repository;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void resetIdentity() {
        SharedIdentityFake.reset();
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("Authorizationヘッダーなしは401")
    void 未認証は401() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("メンバーでない一般利用者は一覧も追加も403")
    void 非メンバーは403() throws Exception {
        when(jwtDecoder.decode("user-jwt")).thenReturn(JwtTestFixtures.jwt("sub-1", "user"));
        SharedIdentityFake.profile("Bearer user-jwt", 5L, "user");
        SharedIdentityFake.member(10L, 5L, false);

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer user-jwt")).andExpect(status().isForbidden());
        mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer user-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"LLM\",\"name\":\"a\",\"url\":\"http://x:1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("メンバーは一覧を取得できる。Penpotの上書きは409で作れない")
    void メンバーは通り_Penpotは400() throws Exception {
        when(jwtDecoder.decode("member-jwt")).thenReturn(JwtTestFixtures.jwt("sub-2", "user"));
        SharedIdentityFake.profile("Bearer member-jwt", 6L, "user");
        SharedIdentityFake.member(10L, 6L, true);

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer member-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer member-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PENPOT\",\"name\":\"p\",\"url\":\"http://x:1\"}"))
                .andExpect(status().isConflict());
    }
}
