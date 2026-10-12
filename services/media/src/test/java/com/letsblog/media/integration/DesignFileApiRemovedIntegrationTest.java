package com.letsblog.media.integration;

import com.letsblog.common.client.ActorProfile;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import java.util.Optional;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1750: Penpotをシステムから削除したので、カスタムタグ用のデザインファイル作成API
 * (POST /api/render の penpot 配下)は無い。管理者として認証済みでも404になる。
 *
 * <p>このAPIは gateway のルート表に載っておらず(issue #830)、画面からは到達できないため、
 * Gherkin ではなくサービスレベルのテストで固定する。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("media-service: Penpotデザインファイル作成APIは無い(issue #1750)")
class DesignFileApiRemovedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    @Test
    @DisplayName("管理者でも404")
    void デザインファイル作成APIは管理者でも404() throws Exception {
        when(jwtDecoder.decode("admin-jwt")).thenReturn(JwtTestFixtures.jwt("sub-admin", "admin"));
        when(identityClient.lookupProfile("Bearer admin-jwt"))
                .thenReturn(Optional.of(new ActorProfile(1L, "admin")));

        mockMvc.perform(post("/api/render/penpot" + "/design-file")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"カスタムタグ: note\",\"promptContext\":\"p\"}"))
                .andExpect(status().isNotFound());
    }
}
