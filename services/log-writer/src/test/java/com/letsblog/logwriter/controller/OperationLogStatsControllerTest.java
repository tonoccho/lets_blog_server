package com.letsblog.logwriter.controller;

import com.letsblog.logwriter.testsupport.LogWriterIntegrationTestBase;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** issue #1471: 集計APIはadmin限定。issue #1829で、コントローラ・サービス・認可・リポジトリを実物にして MockMvc 越しに呼ぶ形へ書き直した。 */
class OperationLogStatsControllerTest extends LogWriterIntegrationTestBase {

    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 1, 0, 0);
    private static final String PERIOD = "startDate=2026-10-01T00:00:00&endDate=2026-10-02T00:00:00";

    @BeforeEach
    void data() {
        login("admin", "sub-admin", 1L, "admin");
        login("user", "sub-user", 2L, "editor");
        saveOperation(1L, "op-1", "GET", "/a", 10L, START.plusHours(1));
        saveOperation(1L, "op-1", "GET", "/a", 30L, START.plusHours(2));
        saveOperation(2L, "op-2", "GET", "/b", 100L, START.plusHours(3));
    }

    private ResultActions call(String token, String path) throws Exception {
        return mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    @Test
    void routes_admin以外はForbidden() throws Exception {
        call("user", "/api/operation-logs/stats/routes?" + PERIOD).andExpect(status().isForbidden());
    }

    @Test
    void routes_adminなら集計を返し_並び順と上限の指定が効く() throws Exception {
        call("admin", "/api/operation-logs/stats/routes?" + PERIOD + "&sort=p50&direction=asc&limit=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].path").value("/a"))
                .andExpect(jsonPath("$[0].count").value(2));
    }

    @Test
    void routes_不正なsortは400() throws Exception {
        call("admin", "/api/operation-logs/stats/routes?" + PERIOD + "&sort=bogus").andExpect(status().isBadRequest());
    }

    @Test
    void operations_admin以外はForbidden() throws Exception {
        call("user", "/api/operation-logs/stats/operations?" + PERIOD).andExpect(status().isForbidden());
    }

    @Test
    void operations_adminなら集計を返す() throws Exception {
        call("admin", "/api/operation-logs/stats/operations?" + PERIOD).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].operationId").value("op-2"))
                .andExpect(jsonPath("$[0].totalDurationMs").value(100))
                .andExpect(jsonPath("$[1].operationId").value("op-1"))
                .andExpect(jsonPath("$[1].callCount").value(2));
    }
}
