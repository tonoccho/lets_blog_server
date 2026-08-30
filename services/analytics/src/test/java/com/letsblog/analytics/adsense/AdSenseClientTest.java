package com.letsblog.analytics.adsense;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * AdSenseClientの回帰テスト。GoogleAnalyticsClientTestと同様、MockRestServiceServerでHTTP通信を検証する
 * (実際のGoogleサーバーへは通信しない)。
 */
class AdSenseClientTest {

    private static final String TOKEN_URI = "https://oauth2.test/token";
    private static final String DATA_API_BASE_URL = "https://adsense.test";

    private AdSenseClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new AdSenseClient(builder, TOKEN_URI, DATA_API_BASE_URL);
    }

    @Test
    void exchangeAuthorizationCode_成功時はアクセストークンとリフレッシュトークンを返す() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"access_token\":\"access-abc\",\"refresh_token\":\"refresh-abc\"}", MediaType.APPLICATION_JSON));

        GoogleOAuthTokens tokens = client.exchangeAuthorizationCode(
                "client-id", "client-secret", "auth-code", "https://example.com/callback");

        assertEquals("access-abc", tokens.accessToken());
        assertEquals("refresh-abc", tokens.refreshToken());
        server.verify();
    }

    @Test
    void exchangeAuthorizationCode_refresh_tokenが含まれなければ例外() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"access_token\":\"access-abc\"}", MediaType.APPLICATION_JSON));

        assertThrows(AdSenseException.class,
                () -> client.exchangeAuthorizationCode(
                        "client-id", "client-secret", "auth-code", "https://example.com/callback"));
    }

    @Test
    void exchangeAuthorizationCode_トークンエンドポイントが失敗すると例外() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"error\":\"invalid_grant\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThrows(AdSenseException.class,
                () -> client.exchangeAuthorizationCode(
                        "client-id", "client-secret", "auth-code", "https://example.com/callback"));
    }

    @Test
    void refreshAccessToken_成功時はアクセストークンを返す() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"access_token\":\"access-abc\"}", MediaType.APPLICATION_JSON));

        String accessToken = client.refreshAccessToken("client-id", "client-secret", "refresh-abc");

        assertEquals("access-abc", accessToken);
    }

    @Test
    void fetchReport_totalsのcellsからメトリクスを取得する() {
        server.expect(requestTo(DATA_API_BASE_URL
                        + "/v2/accounts/pub-123/reports:generate?dateRange=LAST_30_DAYS"
                        + "&metrics=ESTIMATED_EARNINGS&metrics=CLICKS&metrics=IMPRESSIONS"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer access-abc"))
                .andRespond(withSuccess(
                        "{\"totals\":{\"cells\":[{\"value\":\"12.34\"},{\"value\":\"100\"},{\"value\":\"5000\"}]}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(DATA_API_BASE_URL
                        + "/v2/accounts/pub-123/reports:generate?dateRange=LAST_30_DAYS&dimensions=DATE"
                        + "&metrics=ESTIMATED_EARNINGS&metrics=CLICKS&metrics=IMPRESSIONS"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"rows\":[{\"cells\":[{\"value\":\"2024-01-01\"},{\"value\":\"1.23\"},"
                                + "{\"value\":\"10\"},{\"value\":\"500\"}]}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(DATA_API_BASE_URL
                        + "/v2/accounts/pub-123/reports:generate?dateRange=LAST_30_DAYS&dimensions=PLATFORM_TYPE_NAME"
                        + "&metrics=ESTIMATED_EARNINGS&metrics=CLICKS&metrics=IMPRESSIONS"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"rows\":[{\"cells\":[{\"value\":\"Desktop\"},{\"value\":\"12.34\"},"
                                + "{\"value\":\"100\"},{\"value\":\"5000\"}]}]}",
                        MediaType.APPLICATION_JSON));

        AdSenseReport report = client.fetchReport("access-abc", "pub-123", "LAST_30_DAYS");

        assertEquals("12.34", report.estimatedEarnings());
        assertEquals(100, report.clicks());
        assertEquals(5000, report.impressions());
        assertEquals(1, report.dailyDataPoints().size());
        assertEquals("2024-01-01", report.dailyDataPoints().get(0).date());
        assertEquals("1.23", report.dailyDataPoints().get(0).estimatedEarnings());
        assertEquals(1, report.platformBreakdown().size());
        assertEquals("Desktop", report.platformBreakdown().get(0).platform());
    }

    @Test
    void fetchReport_totalsが無ければ0を返す() {
        server.expect(requestTo(DATA_API_BASE_URL
                        + "/v2/accounts/pub-123/reports:generate?dateRange=LAST_30_DAYS"
                        + "&metrics=ESTIMATED_EARNINGS&metrics=CLICKS&metrics=IMPRESSIONS"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(DATA_API_BASE_URL
                        + "/v2/accounts/pub-123/reports:generate?dateRange=LAST_30_DAYS&dimensions=DATE"
                        + "&metrics=ESTIMATED_EARNINGS&metrics=CLICKS&metrics=IMPRESSIONS"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(DATA_API_BASE_URL
                        + "/v2/accounts/pub-123/reports:generate?dateRange=LAST_30_DAYS&dimensions=PLATFORM_TYPE_NAME"
                        + "&metrics=ESTIMATED_EARNINGS&metrics=CLICKS&metrics=IMPRESSIONS"))
                .andExpect(method(GET))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        AdSenseReport report = client.fetchReport("access-abc", "pub-123", "LAST_30_DAYS");

        assertEquals("0", report.estimatedEarnings());
        assertEquals(0, report.clicks());
        assertEquals(0, report.impressions());
        assertEquals(0, report.dailyDataPoints().size());
        assertEquals(0, report.platformBreakdown().size());
    }

    @Test
    void fetchReport_呼び出しに失敗すると例外() {
        server.expect(requestTo(DATA_API_BASE_URL
                        + "/v2/accounts/pub-123/reports:generate?dateRange=LAST_30_DAYS"
                        + "&metrics=ESTIMATED_EARNINGS&metrics=CLICKS&metrics=IMPRESSIONS"))
                .andExpect(method(GET))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .body("{\"error\":\"permission_denied\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThrows(AdSenseException.class, () -> client.fetchReport("access-abc", "pub-123", "LAST_30_DAYS"));
    }

    @Test
    void クライアント資格情報が未設定なら例外() {
        assertThrows(AdSenseException.class,
                () -> client.exchangeAuthorizationCode("", "", "code", "https://example.com/callback"));
    }
}
