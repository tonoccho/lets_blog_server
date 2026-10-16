package com.letsblog.analytics.service;

import com.letsblog.analytics.dto.AdSenseReportResponse;
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
 * AdSenseReportServiceの回帰テスト(issue #387、issue #578でanalytics-serviceへ移設)。AdSense未設定/
 * 本番サイト未紐付けの場合にeligible=falseを返しAPIへ問い合わせないこと、取得失敗時にeligible=trueの
 * ままerrorMessageを設定する(例外を投げない)ことを検証する。legacy-api版と異なり、本番サイトの
 * 有無はProjectRepositoryではなくProjectBridgeClient経由のブリッジ呼び出しで判定し、リフレッシュ
 * トークン/クライアントシークレットの復号はProjectApiKeyServiceへの委譲ではなくこのサービス自身が
 * CredentialCipherで行う。
 *
 * <p>issue #1827: 認可・資格情報・{@code AdSenseClient}・{@code ProjectBridgeClient}はすべて実物。
 * 外部との通信である identity-service / project-service / Google(サーバー側フェイクのHTTP)と
 * DB(メモリ上のリポジトリ)だけを差し替える。
 */
class AdSenseReportServiceTest {

    private static final String ACCOUNT = "pub-1234567890123456";

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
    void getReport_AdSense未設定なら未対象でAPIを呼ばない() {
        world.project(1L, true);

        AdSenseReportResponse response = world.adSenseReports.getReport(1L);

        assertFalse(response.eligible());
        assertTrue(world.http.requests("*", "/(adsense|oauth)/.*").isEmpty());
    }

    @Test
    void getReport_本番サイト未紐付けなら未対象でAPIを呼ばない() {
        world.project(1L, false);
        world.seedAdSense(1L, ACCOUNT, "client-id", "client-secret", "refresh-token");

        AdSenseReportResponse response = world.adSenseReports.getReport(1L);

        assertFalse(response.eligible());
        assertTrue(world.http.requests("*", "/(adsense|oauth)/.*").isEmpty());
    }

    @Test
    void getReport_設定済みかつ本番サイトありならレポートを取得する() {
        world.project(1L, true);
        world.seedAdSense(1L, ACCOUNT, "client-id", "client-secret", "refresh-token");
        world.http.json("POST", "/oauth/token", "{\"access_token\":\"access-token\"}");
        GoogleFakes.adSenseReport(world.http, ACCOUNT);

        AdSenseReportResponse response = world.adSenseReports.getReport(1L);

        assertTrue(response.eligible());
        assertEquals("12.34", response.estimatedEarnings());
        assertEquals(100L, response.clicks());
        assertEquals(5000L, response.impressions());
        assertNotNull(response.periodLabel());
        assertEquals(1, response.dailyDataPoints().size());
        assertEquals("2024-01-01", response.dailyDataPoints().get(0).date());
        assertEquals(1, response.platformBreakdown().size());
        assertEquals("Desktop", response.platformBreakdown().get(0).platform());
        String form = world.http.requests("POST", "/oauth/token").get(0).bodyText();
        assertTrue(form.contains("refresh_token=refresh-token"), form);
        assertTrue(form.contains("client_secret=client-secret"), form);
        assertEquals("Bearer access-token", world.http.requests("GET", "/adsense/v2/accounts/" + ACCOUNT
                + "/reports:generate").get(0).header("Authorization"));
    }

    @Test
    void getReport_クライアントシークレットが未保存ならnullを渡しクライアント側で失敗として返す() {
        world.project(1L, true);
        world.seedAdSense(1L, ACCOUNT, "client-id", null, "refresh-token");

        AdSenseReportResponse response = world.adSenseReports.getReport(1L);

        assertTrue(response.eligible());
        assertTrue(response.errorMessage().contains("クライアントID/シークレットが設定されていません"), response.errorMessage());
        assertTrue(world.http.requests("*", "/(adsense|oauth)/.*").isEmpty());
    }

    @Test
    void getReport_取得に失敗したら対象のままerrorMessageを設定する() {
        world.project(1L, true);
        world.seedAdSense(1L, ACCOUNT, "client-id", "client-secret", "refresh-token");
        world.http.status("POST", "/oauth/token", 400, "{\"error\":\"invalid_grant\"}");

        AdSenseReportResponse response = world.adSenseReports.getReport(1L);

        assertTrue(response.eligible());
        assertTrue(response.errorMessage().contains("Google OAuthトークン取得に失敗しました"), response.errorMessage());
        assertNull(response.clicks());
    }

    @Test
    void getReport_プロジェクトメンバーでなければForbiddenでproject_serviceへ問い合わせない() {
        world.actor("user-token", 10L, "user");
        world.asBearer("user-token");

        assertThrows(ForbiddenException.class, () -> world.adSenseReports.getReport(1L));
        assertTrue(world.http.requests("*", "/api/internal/project/.*").isEmpty());
    }
}
