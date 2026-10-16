package com.letsblog.logwriter.controller;

import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.logwriter.testsupport.LogWriterIntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1059: {@code POST /api/logs/errors}の入力検証(受け入れ基準1・2)。message無し/
 * TEXT上限超過のいずれも400で入口で弾かれ、{@link FrontendErrorLogService}(≒キューへの発行)へ
 * 到達しないことを確認する。AuthorizationMatrixIntegrationTest(#772)と同じ、認証ゲート込みの
 * {@code @SpringBootTest}+MockMvcスタイル(このエンドポイントは認証必須・認可不要、issue #830参照)。
 */
class FrontendErrorLogValidationIntegrationTest extends LogWriterIntegrationTestBase {

    @Test
    @DisplayName("issue #1059 AC1: messageが無いと400になり、キューへ発行されない")
    void messageが無いと400になりキューに到達しない() throws Exception {
        String body = "{\"level\":\"ERROR\"}";

        mockMvc.perform(post("/api/logs/errors")
                        .with(JwtTestFixtures.jwtRequestPostProcessor("sub-1059", "user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    @DisplayName("issue #1059 AC2: messageがTEXT上限を超えると400になり、キューへ発行されない")
    void messageがTEXT上限を超えると400になりキューに到達しない() throws Exception {
        String tooLong = "a".repeat(65536);
        String body = "{\"level\":\"ERROR\",\"message\":\"" + tooLong + "\"}";

        mockMvc.perform(post("/api/logs/errors")
                        .with(JwtTestFixtures.jwtRequestPostProcessor("sub-1059", "user"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(rabbitTemplate);
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

        verify(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class));
    }
}
