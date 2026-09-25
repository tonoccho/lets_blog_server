package com.letsblog.logwriter.controller;

import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.logwriter.domain.FrontendErrorLog;
import com.letsblog.logwriter.service.FrontendErrorLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1059: {@code POST /api/logs/errors}の入力検証(受け入れ基準1・2)。message無し/
 * TEXT上限超過のいずれも400で入口で弾かれ、{@link FrontendErrorLogService}(≒キューへの発行)へ
 * 到達しないことを確認する。AuthorizationMatrixIntegrationTest(#772)と同じ、認証ゲート込みの
 * {@code @SpringBootTest}+MockMvcスタイル(このエンドポイントは認証必須・認可不要、issue #830参照)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FrontendErrorLogValidationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    @MockitoBean
    private FrontendErrorLogService frontendErrorLogService;

    @Test
    @DisplayName("issue #1059 AC1: messageが無いと400になり、サービス(≒キュー発行)に到達しない")
    void messageが無いと400になりキューに到達しない() throws Exception {
        String body = "{\"level\":\"ERROR\"}";

        mockMvc.perform(post("/api/logs/errors")
                        .with(JwtTestFixtures.jwtRequestPostProcessor("sub-1059", "user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(frontendErrorLogService, never()).logError(any(FrontendErrorLog.class));
    }

    @Test
    @DisplayName("issue #1059 AC2: messageがTEXT上限を超えると400になり、サービス(≒キュー発行)に到達しない")
    void messageがTEXT上限を超えると400になりキューに到達しない() throws Exception {
        String tooLong = "a".repeat(65536);
        String body = "{\"level\":\"ERROR\",\"message\":\"" + tooLong + "\"}";

        mockMvc.perform(post("/api/logs/errors")
                        .with(JwtTestFixtures.jwtRequestPostProcessor("sub-1059", "user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verify(frontendErrorLogService, never()).logError(any(FrontendErrorLog.class));
    }

    @Test
    @DisplayName("issue #1059 回帰確認: 妥当なmessageなら201のまま(検証追加による正常系の後退がない)")
    void 妥当なmessageなら201になる() throws Exception {
        String body = "{\"level\":\"ERROR\",\"message\":\"boom\"}";

        mockMvc.perform(post("/api/logs/errors")
                        .with(JwtTestFixtures.jwtRequestPostProcessor("sub-1059", "user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        verify(frontendErrorLogService).logError(any(FrontendErrorLog.class));
    }
}
