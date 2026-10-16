package com.letsblog.analytics.controller;

import com.letsblog.analytics.testsupport.AnalyticsWorld;
import com.letsblog.analytics.testsupport.GoogleFakes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * legacy-apiから移設(issue #578)。ProjectDashboardControllerが各サービスの結果をそのまま返すことを検証する。
 *
 * <p>issue #1827: サービスのモックをやめ、実物のコントローラ・サービス・クライアントをMockMvcに載せる。
 * 外部との通信(identity-service・project-service・Google)はサーバー側フェイクのHTTP、DBはメモリ上のリポジトリ。
 */
class ProjectDashboardControllerTest {

    private AnalyticsWorld world;

    @BeforeEach
    void setUp() {
        world = new AnalyticsWorld();
        world.actor("admin-token", 1L, "admin").project(1L, true);
        world.http.json("POST", "/oauth/token", "{\"access_token\":\"access-token\"}");
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void getGoogleAnalyticsReport_サービスの結果をそのまま返す() throws Exception {
        world.seedGa(1L, "123456789", "cid", "secret", "refresh");
        GoogleFakes.gaReport(world.http, "123456789");

        world.mvc.perform(get("/api/projects/1/dashboard/google-analytics")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.sessions").value(120))
                .andExpect(jsonPath("$.activeUsers").value(80))
                .andExpect(jsonPath("$.pageViews").value(300))
                .andExpect(jsonPath("$.periodLabel").value("過去28日間"))
                .andExpect(jsonPath("$.dailyDataPoints[0].date").value("2024-01-01"))
                .andExpect(jsonPath("$.channelBreakdown[0].channel").value("Organic Search"));
    }

    @Test
    void getAdSenseReport_サービスの結果をそのまま返す() throws Exception {
        world.seedAdSense(1L, "pub-1", "cid", "secret", "refresh");
        GoogleFakes.adSenseReport(world.http, "pub-1");

        world.mvc.perform(get("/api/projects/1/dashboard/adsense")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(true))
                .andExpect(jsonPath("$.estimatedEarnings").value("12.34"))
                .andExpect(jsonPath("$.clicks").value(100))
                .andExpect(jsonPath("$.impressions").value(5000))
                .andExpect(jsonPath("$.periodLabel").value("過去30日間"))
                .andExpect(jsonPath("$.platformBreakdown[0].platform").value("Desktop"));
    }

    @Test
    void 未設定のプロジェクトは未対象として200で返す() throws Exception {
        world.mvc.perform(get("/api/projects/1/dashboard/google-analytics")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false));
        world.mvc.perform(get("/api/projects/1/dashboard/adsense")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligible").value(false));
    }

    @Test
    void プロジェクトメンバーでもadminでもなければ403() throws Exception {
        world.actor("user-token", 10L, "user");

        world.mvc.perform(get("/api/projects/1/dashboard/google-analytics")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("この操作にはプロジェクトメンバーまたはadmin権限が必要です"));
    }

    @Test
    void プロジェクトが存在しなければ404() throws Exception {
        world.mvc.perform(get("/api/projects/9/dashboard/adsense")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isNotFound());
    }

    @Test
    void identity_serviceに届かなければ502() throws Exception {
        world.http.status("GET", "/api/identity/me", 500, "{}");

        world.mvc.perform(get("/api/projects/1/dashboard/adsense")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isBadGateway());
    }
}
