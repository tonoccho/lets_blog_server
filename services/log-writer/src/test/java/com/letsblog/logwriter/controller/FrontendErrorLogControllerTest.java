package com.letsblog.logwriter.controller;

import com.letsblog.common.messaging.ErrorLogMessage;
import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.logwriter.testsupport.LogWriterIntegrationTestBase;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** /api/logs/errors(issue #1829で書き直し)。コントローラ・サービス・認可・リポジトリは実物で、外部との通信は MQ への発行(RabbitTemplate)だけ。 */
class FrontendErrorLogControllerTest extends LogWriterIntegrationTestBase {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 1, 0, 0);

    @BeforeEach
    void users() {
        login("admin", "sub-admin", 1L, "admin");
        login("user", "sub-user", 2L, "editor");
    }

    private ResultActions getErrors(String token, String query) throws Exception {
        return mockMvc.perform(get("/api/logs/errors" + query).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions postError(String body) throws Exception {
        return mockMvc.perform(post("/api/logs/errors")
                .header(HttpHeaders.AUTHORIZATION, "Bearer user")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void logError_登録できる() throws Exception {
        postError("{\"message\":\"boom\",\"level\":\"error\",\"url\":\"https://example.com\",\"userAgent\":\"agent\"}")
                .andExpect(status().isCreated());

        ArgumentCaptor<ErrorLogMessage> captor = ArgumentCaptor.forClass(ErrorLogMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(LogExchanges.LOG_EXCHANGE), eq(LogExchanges.ERROR_LOG_ROUTING_KEY), captor.capture());
        assertEquals("boom", captor.getValue().message());
        assertEquals("ERROR", captor.getValue().level());
        assertEquals(2L, captor.getValue().userId());
        assertEquals("sub-user", captor.getValue().actorKeycloakSub());
        assertNotNull(captor.getValue().timestamp());
    }

    @Test
    void logError_levelが不正なら400でキューへ発行しない() throws Exception {
        postError("{\"message\":\"boom\",\"level\":\"FATAL\"}").andExpect(status().isBadRequest());

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void getErrors_admin以外はForbidden() throws Exception {
        getErrors("user", "").andExpect(status().isForbidden());
    }

    @Test
    void getErrors_level指定は大文字化して絞る() throws Exception {
        saveError("e1", "ERROR", "https://a.example", AT);
        saveError("e2", "WARN", "https://a.example", AT);

        getErrors("admin", "?level=warn").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].message").value("e2"));
    }

    @Test
    void getErrors_startDateとendDateが両方あれば期間で絞る() throws Exception {
        saveError("inside", "ERROR", "u", AT.plusHours(1));
        saveError("outside", "ERROR", "u", AT.plusDays(5));

        getErrors("admin", "?startDate=2026-10-01T00:00:00&endDate=2026-10-02T00:00:00").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].message").value("inside"));
    }

    @Test
    void getErrors_startDateのみでは期間で絞らず全件を返す() throws Exception {
        saveError("e1", "ERROR", "u", AT);
        saveError("e2", "ERROR", "u", AT.plusDays(5));

        getErrors("admin", "?startDate=2026-10-03T00:00:00").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void getErrors_url指定で絞る() throws Exception {
        saveError("e1", "ERROR", "https://a.example", AT);
        saveError("e2", "ERROR", "https://b.example", AT);

        getErrors("admin", "?url=https://b.example").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].message").value("e2"));
    }

    @Test
    void getErrors_条件無しなら全件を返す() throws Exception {
        saveError("e1", "ERROR", "u", AT);
        saveError("e2", "WARN", "u", AT);

        getErrors("admin", "").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
    }
}
