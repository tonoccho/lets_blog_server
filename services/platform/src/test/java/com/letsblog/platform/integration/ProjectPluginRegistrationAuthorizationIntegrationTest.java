package com.letsblog.platform.integration;

import com.letsblog.common.client.ActorProfile;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.platform.client.IdentityBridgeClient;
import com.letsblog.platform.repository.ProjectPluginRegistrationRepository;
import org.junit.jupiter.api.AfterEach;
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

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** プロジェクト単位のプラグインの登録のAPIが、メンバーでない利用者を403にする(issue #1808)。identity-serviceは外部境界なのでモックする。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("platform-service: プロジェクト単位のプラグインの登録の認可(issue #1808)")
class ProjectPluginRegistrationAuthorizationIntegrationTest {

    private static final String PATH = "/api/projects/10/plugin-registrations";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectPluginRegistrationRepository repository;

    @MockitoBean
    private JwtDecoder jwtDecoder;
    @MockitoBean
    private IdentityClient identityClient;
    @MockitoBean
    private IdentityBridgeClient identityBridgeClient;

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
        when(identityClient.lookupProfile("Bearer user-jwt")).thenReturn(Optional.of(new ActorProfile(5L, "user")));
        when(identityBridgeClient.isProjectMember(10L, 5L, "Bearer user-jwt")).thenReturn(false);

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
        when(identityClient.lookupProfile("Bearer member-jwt")).thenReturn(Optional.of(new ActorProfile(6L, "user")));
        when(identityBridgeClient.isProjectMember(10L, 6L, "Bearer member-jwt")).thenReturn(true);

        mockMvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer member-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer member-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"PENPOT\",\"name\":\"p\",\"url\":\"http://x:1\"}"))
                .andExpect(status().isConflict());
    }
}
