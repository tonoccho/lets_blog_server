package com.letsblog.logwriter.integration;

import com.letsblog.common.client.ActorProfile;
import com.letsblog.common.client.IdentityClient;
import com.letsblog.common.testfixtures.JwtTestFixtures;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * issue #1471: 操作ログ集計APIの実MySQL統合テスト(ADR-0006、{@code lbs_log_test}スキーマ)。
 * AC3(認可)とAC5(10,000行で2つの集計APIがそれぞれ3秒以内)を押さえる。
 * 受け入れ環境の{@code lbs_log}へ大量投入すると他シナリオと30日削除に干渉するため、
 * Gherkinではなくここで行う。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("操作ログ集計API: 認可と10,000行の性能(issue #1471)")
class OperationLogStatsIntegrationTest {

    private static final String PREFIX = "stats1471-";
    private static final String START = "2001-01-01T00:00:00";
    private static final String END = "2001-01-02T00:00:00";
    private static final LocalDateTime BASE = LocalDateTime.of(2001, 1, 1, 0, 0);
    private static final int ROWS = 10_000;
    private static final long BUDGET_MS = 3_000;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private IdentityClient identityClient;

    @BeforeEach
    void seed() {
        // Authorizationヘッダーは認証フィルタ(JwtDecoder)とCurrentActorService(identity-service照会)の双方が読む。
        when(jwtDecoder.decode("admin")).thenReturn(JwtTestFixtures.jwt("sub-admin", "admin"));
        when(jwtDecoder.decode("user")).thenReturn(JwtTestFixtures.jwt("sub-user", "user"));
        when(identityClient.lookupProfile("Bearer admin")).thenReturn(Optional.of(new ActorProfile(1L, "admin")));
        when(identityClient.lookupProfile("Bearer user")).thenReturn(Optional.of(new ActorProfile(2L, "user")));
        cleanup();
        String[] paths = {
                "/api/projects/%d/posts?page=2", "/api/projects/%d/posts", "/api/sites/%d",
                "/api/ai/jobs/123e4567-e89b-12d3-a456-42661417%04d", "/api/users/me", "/api/metadata/roles"
        };
        List<Object[]> batch = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            String path = paths[i % paths.length];
            path = path.contains("%") ? String.format(path, i % 4000) : path;
            batch.add(new Object[] {
                    PREFIX + (i % 2000), (long) (1 + i % 5), "GET", path, 200, (long) (i % 977), true,
                    Timestamp.valueOf(BASE.plusSeconds(i * 8L))
            });
        }
        // 1トランザクションにまとめる。自動コミットのままだと1行ごとにfsyncして10,000行で2分近くかかる。
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.batchUpdate("INSERT INTO operation_logs (operation_id, user_id, method, path, status_code, duration_ms,"
                + " success, created_at) VALUES (?,?,?,?,?,?,?,?)", batch));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM operation_logs WHERE operation_id LIKE ?", PREFIX + "%");
    }

    private MvcResult call(String path, String bearer, int expectedStatus) throws Exception {
        return mockMvc.perform(get(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer))
                .andExpect(status().is(expectedStatus)).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("10,000行でルート別集計が3秒以内に応答し、集約された行を返す")
    void routeStatsWithin3sFor10kRows() throws Exception {
        String url = "/api/operation-logs/stats/routes?startDate=" + START + "&endDate=" + END;

        long t0 = System.nanoTime();
        MvcResult r = call(url, "admin", 200);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(elapsedMs).as("ルート別集計の応答時間(ms)").isLessThan(BUDGET_MS);
        JsonNode body = json(r);
        assertThat(body).hasSize(5); // 6つの生パスが正規化で5ルートに集約される
        long total = 0;
        for (JsonNode n : body) {
            total += n.get("count").asLong();
            assertThat(n.get("path").asText()).doesNotContain("?").doesNotContain("e4567");
        }
        assertThat(total).isEqualTo(ROWS);
    }

    @Test
    @DisplayName("10,000行で操作別集計が3秒以内に応答する")
    void operationStatsWithin3sFor10kRows() throws Exception {
        String url = "/api/operation-logs/stats/operations?startDate=" + START + "&endDate=" + END + "&limit=500";

        long t0 = System.nanoTime();
        MvcResult r = call(url, "admin", 200);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertThat(elapsedMs).as("操作別集計の応答時間(ms)").isLessThan(BUDGET_MS);
        JsonNode body = json(r);
        assertThat(body).hasSize(500);
        assertThat(body.get(0).get("callCount").asLong()).isEqualTo(5);
        assertThat(body.get(0).get("totalDurationMs").asLong())
                .isGreaterThanOrEqualTo(body.get(1).get("totalDurationMs").asLong());
    }

    @Test
    @DisplayName("admin以外は2つの集計APIで403")
    void nonAdminGets403() throws Exception {
        call("/api/operation-logs/stats/routes?startDate=" + START + "&endDate=" + END, "user", 403);
        call("/api/operation-logs/stats/operations?startDate=" + START + "&endDate=" + END, "user", 403);
    }

    @Test
    @DisplayName("期間が無ければ400")
    void periodIsRequired() throws Exception {
        call("/api/operation-logs/stats/routes", "admin", 400);
        call("/api/operation-logs/stats/operations?startDate=" + START, "admin", 400);
    }

    @Test
    @DisplayName("トレース: adminは他利用者のoperationIdの全行、非adminは他人のoperationIdに空、自分のは取得できる")
    void traceAuthorization() throws Exception {
        // PREFIX+0 の行は user_id=1(i%5==0 の i)のみ。user_id=2 の行は i%5==1 の operation。
        String othersOp = PREFIX + "0";
        String ownOp = PREFIX + "1";

        JsonNode adminSees = json(call("/api/operation-logs/" + othersOp, "admin", 200));
        assertThat(adminSees.size()).isGreaterThan(0);
        assertThat(adminSees.get(0).get("userId").asLong()).isEqualTo(1L);

        assertThat(json(call("/api/operation-logs/" + othersOp, "user", 200))).isEmpty();
        JsonNode own = json(call("/api/operation-logs/" + ownOp, "user", 200));
        assertThat(own.size()).isGreaterThan(0);
        assertThat(own.get(0).get("userId").asLong()).isEqualTo(2L);
    }
}
