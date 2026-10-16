package com.letsblog.analytics.service;

import com.letsblog.analytics.dto.GoogleAnalyticsReportResponse;
import com.letsblog.analytics.testsupport.AnalyticsWorld;
import com.letsblog.analytics.testsupport.GoogleFakes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GoogleAnalyticsReportServiceの回帰テスト(issue #386、issue #578でanalytics-serviceへ移設)。
 * GA未設定/本番サイト未紐付けの場合にeligible=falseを返しGA4 Data APIへ問い合わせないこと、
 * レポート取得失敗時にeligible=trueのままerrorMessageを設定する(例外を投げない)ことを検証する。
 * legacy-api版と異なり、本番サイトの有無はProjectRepositoryではなくProjectBridgeClient経由の
 * ブリッジ呼び出しで判定する(project-service未抽出のためprojectsテーブルはlegacy-apiに残るため)。
 *
 * <p>issue #1827: 認可・資格情報・{@code GoogleAnalyticsClient}・{@code ProjectBridgeClient}はすべて実物。
 * 外部との通信である identity-service / project-service / Google(サーバー側フェイクのHTTP)と
 * DB(メモリ上のリポジトリ)だけを差し替える。
 */
class GoogleAnalyticsReportServiceTest {

    private static final String RUN_REPORT = "/ga-data/v1beta/properties/123456789:runReport";

    private AnalyticsWorld world;

    @BeforeEach
    void setUp() {
        world = new AnalyticsWorld();
        world.actor("admin-token", 1L, "admin");
        world.asBearer("admin-token");
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    @Test
    void getReport_GA未設定なら未対象でAPIを呼ばない() {
        world.project(1L, true);

        GoogleAnalyticsReportResponse response = world.gaReports.getReport(1L);

        assertFalse(response.eligible());
        assertNull(response.errorMessage());
        assertTrue(world.http.requests("*", "/(ga-data|oauth)/.*").isEmpty());
    }

    @Test
    void getReport_本番サイト未紐付けなら未対象でAPIを呼ばない() {
        world.project(1L, false);
        world.seedGa(1L, "123456789", "client-id", "client-secret", "refresh-token");

        GoogleAnalyticsReportResponse response = world.gaReports.getReport(1L);

        assertFalse(response.eligible());
        assertTrue(world.http.requests("*", "/(ga-data|oauth)/.*").isEmpty());
    }

    @Test
    void getReport_設定済みかつ本番サイトありならレポートを取得する() {
        world.project(1L, true);
        world.seedGa(1L, "123456789", "client-id", "client-secret", "refresh-token");
        world.http.json("POST", "/oauth/token", "{\"access_token\":\"access-token\"}");
        GoogleFakes.gaReport(world.http, "123456789");

        GoogleAnalyticsReportResponse response = world.gaReports.getReport(1L);

        assertTrue(response.eligible());
        assertEquals(120L, response.sessions());
        assertEquals(80L, response.activeUsers());
        assertEquals(300L, response.pageViews());
        assertNotNull(response.periodLabel());
        assertEquals(1, response.dailyDataPoints().size());
        assertEquals("2024-01-01", response.dailyDataPoints().get(0).date());
        assertEquals(1, response.channelBreakdown().size());
        assertEquals("Organic Search", response.channelBreakdown().get(0).channel());
        // 保存された資格情報を復号して、Googleへ渡している。
        String form = world.http.requests("POST", "/oauth/token").get(0).bodyText();
        assertTrue(form.contains("refresh_token=refresh-token"), form);
        assertTrue(form.contains("client_id=client-id"), form);
        assertTrue(form.contains("client_secret=client-secret"), form);
        assertEquals("Bearer access-token", world.http.requests("POST", RUN_REPORT).get(0).header("Authorization"));
        // 呼び出し元のトークンを project-service へ転送している。
        assertEquals("Bearer admin-token",
                world.http.requests("GET", "/api/internal/project/projects/1/eligibility").get(0).header("Authorization"));
    }

    @Test
    void getReport_クライアントシークレットが未保存ならnullを渡しクライアント側で失敗として返す() {
        world.project(1L, true);
        world.seedGa(1L, "123456789", "client-id", null, "refresh-token");

        GoogleAnalyticsReportResponse response = world.gaReports.getReport(1L);

        assertTrue(response.eligible());
        assertTrue(response.errorMessage().contains("クライアントID/シークレットが設定されていません"), response.errorMessage());
        assertTrue(world.http.requests("*", "/(ga-data|oauth)/.*").isEmpty());
    }

    @Test
    void getReport_取得に失敗したら対象のままerrorMessageを設定する() {
        world.project(1L, true);
        world.seedGa(1L, "123456789", "client-id", "client-secret", "refresh-token");
        world.http.json("POST", "/oauth/token", "{\"access_token\":\"access-token\"}");
        world.http.status("POST", RUN_REPORT, 500, "{\"error\":\"boom\"}");

        GoogleAnalyticsReportResponse response = world.gaReports.getReport(1L);

        assertTrue(response.eligible());
        assertTrue(response.errorMessage().contains("Google Analytics Data APIの呼び出し"), response.errorMessage());
        assertNull(response.sessions());
    }

    @Test
    void getReport_プロジェクトメンバーでなければForbiddenでproject_serviceへ問い合わせない() {
        world.actor("user-token", 10L, "user");
        world.asBearer("user-token");

        assertThrows(ForbiddenException.class, () -> world.gaReports.getReport(1L));
        assertTrue(world.http.requests("*", "/api/internal/project/.*").isEmpty());
    }

    @Test
    void getReport_プロジェクトメンバーなら取得できる() {
        world.actor("member-token", 10L, "user").member(1L, 10L);
        world.asBearer("member-token");
        world.project(1L, true);

        assertFalse(world.gaReports.getReport(1L).eligible());
        assertEquals(1, world.http.requests("GET", "/api/internal/identity/projects/1/members/10").size());
    }

    @Test
    void getReport_プロジェクトが存在しなければProjectNotFoundException() {
        assertThrows(ProjectNotFoundException.class, () -> world.gaReports.getReport(1L));
    }
}
