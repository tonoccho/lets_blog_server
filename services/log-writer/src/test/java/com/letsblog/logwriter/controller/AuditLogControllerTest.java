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

/** GET /api/audit-logs(issue #1829で書き直し)。コントローラ・サービス・認可・リポジトリは実物(実 MySQL)で、MockMvc 越しに呼ぶ。 */
class AuditLogControllerTest extends LogWriterIntegrationTestBase {

    private static final LocalDateTime AT = LocalDateTime.of(2026, 10, 1, 0, 0);

    @BeforeEach
    void users() {
        login("admin", "sub-admin", 1L, "admin");
        login("user", "sub-user", 2L, "editor");
        saveAudit(1L, "USER_CREATED", "USER", 1L, null, AT);
        saveAudit(2L, "USER_DELETED", "USER", 2L, null, AT.plusDays(1));
        saveAudit(2L, "LOGIN", null, null, "127.0.0.1", AT.plusDays(2));
    }

    private ResultActions list(String token, String query) throws Exception {
        return mockMvc.perform(get("/api/audit-logs" + query).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    @Test
    void list_admin以外はForbidden() throws Exception {
        list("user", "").andExpect(status().isForbidden());
    }

    @Test
    void list_userId指定ならそのユーザーの行だけを新しい順に返す() throws Exception {
        list("admin", "?userId=2").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].action").value("LOGIN"))
                .andExpect(jsonPath("$.content[1].action").value("USER_DELETED"));
    }

    @Test
    void list_action指定ならそのactionの行だけを返す() throws Exception {
        list("admin", "?action=USER_CREATED").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].userId").value(1));
    }

    @Test
    void list_startDateとendDateが両方あれば期間で絞る() throws Exception {
        list("admin", "?startDate=2026-10-02T00:00:00&endDate=2026-10-02T23:59:59").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("USER_DELETED"));
    }

    @Test
    void list_startDateだけでは期間で絞らず全件を返す() throws Exception {
        list("admin", "?startDate=2026-10-03T00:00:00").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void list_endDateだけでも期間で絞らず全件を返す() throws Exception {
        list("admin", "?endDate=2026-10-01T00:00:00").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void list_フィルタ無指定なら全件を新しい順に返す() throws Exception {
        list("admin", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].action").value("LOGIN"));
    }
}
