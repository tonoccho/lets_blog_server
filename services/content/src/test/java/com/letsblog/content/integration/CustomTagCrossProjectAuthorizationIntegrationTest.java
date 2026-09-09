package com.letsblog.content.integration;

import com.letsblog.common.client.ActorProfile;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.content.client.IdentityBridgeClient;
import com.letsblog.content.client.ProjectBridgeClient;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1057: {@code GET /api/custom-tags?projectId=}系列と{@code GET /api/custom-tag-templates
 * ?showAll=true}系列に、対になる{@code /api/projects/{projectId}/custom-tags}系列
 * (ProjectCustomTagControllerが持つ{@code requireProjectMemberOrAdmin})が無く、
 * 非メンバーでも{@code projectId}をクエリで指定するだけで他プロジェクトのカスタムタグ/
 * 未公開テンプレートを読めていたことを固定する。
 *
 * <p>{@link AdminAuthorizationIntegrationTest}と同じ観点・同じモック方針(ADR-0006、
 * identity-service/project-serviceは外部境界のため{@code @MockitoBean}で置き換える)を、
 * {@code CustomTagController}/{@code CustomTagTemplateController}のGET経路に対して行う。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("content-service: カスタムタグのクロスプロジェクト認可(issue #1057)")
class CustomTagCrossProjectAuthorizationIntegrationTest {

    private static final long OTHER_PROJECT_ID = 77L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    @MockitoBean
    private IdentityBridgeClient identityBridgeClient;

    @MockitoBean
    private ProjectBridgeClient projectBridgeClient;

    private String nonMemberHeader() throws Exception {
        String token = "cross-project-jwt";
        when(jwtDecoder.decode(token)).thenReturn(JwtTestFixtures.jwt("sub-1057", "user"));
        when(identityClient.lookupProfile("Bearer " + token))
                .thenReturn(Optional.of(new ActorProfile(20L, "user")));
        when(identityBridgeClient.isProjectMember(OTHER_PROJECT_ID, 20L, "Bearer " + token)).thenReturn(false);
        return "Bearer " + token;
    }

    @Test
    @DisplayName("GET /api/custom-tags?projectId=他プロジェクト は非メンバーに403")
    void customTags一覧は非メンバーに403() throws Exception {
        mockMvc.perform(get("/api/custom-tags").queryParam("projectId", String.valueOf(OTHER_PROJECT_ID))
                        .header(HttpHeaders.AUTHORIZATION, nonMemberHeader()))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("プロジェクトメンバー")));
    }

    @Test
    @DisplayName("GET /api/custom-tags/css-bundle?projectId=他プロジェクト は非メンバーに403")
    void customTagsCssBundleは非メンバーに403() throws Exception {
        mockMvc.perform(get("/api/custom-tags/css-bundle").queryParam("projectId", String.valueOf(OTHER_PROJECT_ID))
                        .header(HttpHeaders.AUTHORIZATION, nonMemberHeader()))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("プロジェクトメンバー")));
    }

    @Test
    @DisplayName("GET /api/custom-tag-templates?projectId=他プロジェクト&showAll=true は非メンバーに403")
    void customTagTemplatesShowAllは非メンバーに403() throws Exception {
        mockMvc.perform(get("/api/custom-tag-templates")
                        .queryParam("projectId", String.valueOf(OTHER_PROJECT_ID))
                        .queryParam("showAll", "true")
                        .header(HttpHeaders.AUTHORIZATION, nonMemberHeader()))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("プロジェクトメンバー")));
    }

    @Test
    @DisplayName("退行防止: GET /api/projects/他プロジェクト/custom-tags は引き続き非メンバーに403")
    void projectCustomTags一覧は引き続き非メンバーに403() throws Exception {
        mockMvc.perform(get("/api/projects/" + OTHER_PROJECT_ID + "/custom-tags")
                        .header(HttpHeaders.AUTHORIZATION, nonMemberHeader()))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("プロジェクトメンバー")));
    }
}
