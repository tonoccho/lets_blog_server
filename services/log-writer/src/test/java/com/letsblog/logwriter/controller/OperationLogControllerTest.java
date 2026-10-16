package com.letsblog.logwriter.controller;

import com.letsblog.common.messaging.LogExchanges;
import com.letsblog.common.messaging.OperationLogMessage;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import com.letsblog.logwriter.testsupport.LogWriterIntegrationTestBase;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/operation-logs(issue #1829で書き直し)。コントローラ・各サービス・認可・リポジトリは実物(実 MySQL)で、
 * 外部との通信は identity-service と ai-service(HTTP のフェイク)、MQ への発行(RabbitTemplate)、JWT の復号(JwtDecoder)だけ。
 */
class OperationLogControllerTest extends LogWriterIntegrationTestBase {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 1, 0, 0);

    @BeforeEach
    void users() {
        login("admin", "sub-admin", 1L, "admin");
        login("user", "sub-user", 2L, "editor");
        // JWT は有効だが identity-service が操作者を解決できない(未登録のトークン = 401)ユーザー。
        when(jwtDecoder.decode("ghost")).thenReturn(JwtTestFixtures.jwt("sub-ghost", "editor"));
        HTTP.json("GET", "/api/generation-jobs", "[]");
    }

    private ResultActions fetch(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions record(String token) throws Exception {
        return mockMvc.perform(post("/api/operation-logs")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"operationId\":\"op-1\",\"method\":\"GET\",\"path\":\"/api/sites\","
                        + "\"statusCode\":200,\"durationMs\":42,\"success\":true}"));
    }

    @Test
    void record_actorが解決できればuserIdで記録する() throws Exception {
        record("user").andExpect(status().isCreated());

        ArgumentCaptor<OperationLogMessage> captor = ArgumentCaptor.forClass(OperationLogMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(LogExchanges.LOG_EXCHANGE), eq(LogExchanges.OPERATION_LOG_ROUTING_KEY), captor.capture());
        assertEquals(2L, captor.getValue().userId());
        assertEquals("sub-user", captor.getValue().actorKeycloakSub());
        assertEquals("op-1", captor.getValue().operationId());
        assertEquals(42L, captor.getValue().durationMs());
    }

    @Test
    void record_所要時間が無ければ0で記録する() throws Exception {
        mockMvc.perform(post("/api/operation-logs")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationId\":\"op-2\",\"method\":\"GET\",\"path\":\"/api/x\",\"success\":false}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<OperationLogMessage> captor = ArgumentCaptor.forClass(OperationLogMessage.class);
        verify(rabbitTemplate).convertAndSend(
                eq(LogExchanges.LOG_EXCHANGE), eq(LogExchanges.OPERATION_LOG_ROUTING_KEY), captor.capture());
        assertEquals(0L, captor.getValue().durationMs());
    }

    @Test
    void record_操作者を解決できなければUnauthorizedで記録しない() throws Exception {
        record("ghost").andExpect(status().isUnauthorized());

        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void list_本人のログのみ取得する() throws Exception {
        saveOperation(2L, "mine", "GET", "/a", 1L, AT);
        saveOperation(1L, "theirs", "GET", "/b", 1L, AT);

        fetch("user", "/api/operation-logs").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].operationId").value("mine"));
    }

    @Test
    void list_操作者を解決できなければForbidden() throws Exception {
        fetch("ghost", "/api/operation-logs").andExpect(status().isForbidden());
    }

    @Test
    void trace_operationIdに紐づく本人のログを昇順で取得する() throws Exception {
        saveOperation(2L, "op-1", "POST", "/second", 1L, AT.plusSeconds(1));
        saveOperation(2L, "op-1", "GET", "/first", 1L, AT);
        saveOperation(1L, "op-1", "GET", "/theirs", 1L, AT);

        fetch("user", "/api/operation-logs/op-1").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].path").value("/first"))
                .andExpect(jsonPath("$[1].path").value("/second"));
    }

    @Test
    void trace_adminなら利用者を問わずoperationIdの全行を取得する() throws Exception {
        saveOperation(2L, "op-other", "GET", "/a", 1L, AT);
        saveOperation(3L, "op-other", "GET", "/b", 1L, AT.plusSeconds(1));

        fetch("admin", "/api/operation-logs/op-other").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void trace_操作者を解決できなければForbidden() throws Exception {
        fetch("ghost", "/api/operation-logs/op-1").andExpect(status().isForbidden());
    }

    @Test
    void listUnified_ログイン中ユーザーのadmin区分に従い呼び出し元のトークンをai_serviceへ転送する() throws Exception {
        saveOperation(1L, "op-a", "GET", "/api/draft", 1L, AT);
        saveAudit(9L, "LOGIN", null, null, "127.0.0.1", AT.plusMinutes(1));
        HTTP.json("GET", "/api/generation-jobs",
                "[{\"id\":5,\"type\":\"draft\",\"status\":\"done\",\"createdAt\":\"2026-10-01T00:02:00\",\"updatedAt\":null}]");

        fetch("admin", "/api/operation-logs/unified?type=AI_JOB&q=draft").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sourceType").value("AI_JOB"));
        fetch("admin", "/api/operation-logs/unified").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
        fetch("user", "/api/operation-logs/unified").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        assertEquals("Bearer admin", HTTP.requests("GET", "/api/generation-jobs").get(0).header("Authorization"));
    }

    @Test
    void listUnified_操作者を解決できなければForbidden() throws Exception {
        fetch("ghost", "/api/operation-logs/unified").andExpect(status().isForbidden());
    }

    @Test
    void listUnified_日時の範囲で絞る() throws Exception {
        saveOperation(2L, "in", "GET", "/in", 1L, AT.plusHours(1));
        saveOperation(2L, "out", "GET", "/out", 1L, AT.plusDays(3));

        fetch("user", "/api/operation-logs/unified?startDate=2026-10-01T00:00:00&endDate=2026-10-02T00:00:00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("GET /in"));
    }
}
