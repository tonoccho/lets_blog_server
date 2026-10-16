package com.letsblog.media.integration;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.media.testsupport.ServiceStubbedIntegrationTest;
import com.letsblog.media.testsupport.ServiceStubs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
@DisplayName("media-service: Penpotデザインファイル作成APIは無い(issue #1750)")
class DesignFileApiRemovedIntegrationTest extends ServiceStubbedIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("管理者でも404")
    void デザインファイル作成APIは管理者でも404() throws Exception {
        when(jwtDecoder.decode("admin-jwt")).thenReturn(JwtTestFixtures.jwt("sub-admin", "admin"));
        ServiceStubs.profile("Bearer admin-jwt", 1L, "admin");

        mockMvc.perform(post("/api/render/penpot" + "/design-file")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer admin-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fileName\":\"カスタムタグ: note\",\"promptContext\":\"p\"}"))
                .andExpect(status().isNotFound());
    }
}
