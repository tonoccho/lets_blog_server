package com.letsblog.api.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * GoogleAnalyticsClientの回帰テスト。ComfyUiClientTestと同様、MockRestServiceServerでHTTP通信を検証する
 * (実際のGoogleサーバーへは通信しない。JWT署名には自己生成したテスト専用鍵を使う)。
 */
class GoogleAnalyticsClientTest {

    private static final String DATA_API_BASE_URL = "https://analyticsdata.test";
    private static final String TOKEN_URI = "https://oauth2.test/token";
    private static final String PRIVATE_KEY_PEM = """
            -----BEGIN PRIVATE KEY-----
            MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC2mlclKKyllzeV
            Cx3Qgwdk7Jrrpk6lA77G6TbnXjmNB9EDbn0IqYa/eqmbixUKywLHPRi2ZZ/4pjcf
            XgCPsJo72/yVmlA0pHSFsxhS61RZqfJRtZx+jW/isty30OimWvwJtCHgMTdsWUFt
            LJ264s30CiQ1ZNVtPCzCoYJ/qNOR+rvoEr/CMCU9ZU8TGQD08csdurBW2+SY7Buw
            o2NnypgGH98gVLdF9BY6xVRGvCvWHfM3kfo8gzQz4amVyKfXw8GllPScn1wcpu9m
            2EU7Eoct34GVITVzD7qvY+jHvtsZgBoxUvL6H3MMmaxtzh0nrA3Xtw7FX32f0ZH3
            snBQch+PAgMBAAECggEAJokKxAJB8Q4pAjCe4Z6NRGy0Qu/NYACa1bJozknxvkP8
            hYtfIqFYGPejbHpc/fKayv4nRXLL4Db/ogR9/NTpr6E8vDudGobsOjzx8KnOGsAF
            Ld40QPbLOl3Bu58AQf8oeknD7mKkjh6F8qq8PLDZgttTCduWON++mHJqLlOsFn2m
            hV3sdIJOvFxFEAEz/+wS1bWmYcCkDYyiSAlvCAAECWBAsxG3QumH0AuvJKTETEw5
            m18W9w9PcdSvIk5y3SLp7zXZPDoDBVnNq/22VM50XP3UhG+uwvnslVQUdMt9Inn9
            6syXxmQqqKEW8jSpoIII5RJ20xqdD5VKPpz7Q1MjgQKBgQDoSo/zXdSQfZN4MboM
            0bQlQFh3Aw8Ygx3gsAkhB6oNZH5kH4cj1D/io5CiF2luu8g+AJLCmNMbJk5aVLQV
            B+s/ikd4qCU/Ld1bLTAvdaUis2DbXh8CVId/8Fe1/buQ+MP6gvpuBASvLgiCV7Ih
            3QX5Lv5puls1pcAqT6VOAkRCgQKBgQDJPX4Gsv3oWIQscjbtFUYXxzftZIORFOUo
            c82+OdRWAC7NMKSpw75GpSTPpXt2G3al2ZMDEZpzV18mSsvmmrCNsQX4+AkbI82D
            DUprXpc7FRENcG582aT5X8sJ4B+0FCVV5BIBPuxZz9s9Qc8YTXWRemATv70LB5U5
            ynmyROE6DwKBgQDn6JDgoju2aXiSFesuIypbymrHnokyqqxohrcGf9VZe4vnv8Y2
            kg+Z4DxkZ0U+ZUFcDUx39QVF5K9y5X/IQ1is3gvOvOg6tDp7bZjeuPA9vaIkQEpr
            FCMXKscWjZP1/zYBY0RME7zte+LI5m6T+kqdZTpgKconvCwm0c8yG3c0gQKBgQDE
            +Z+lxwWoqxuUtab1oOEe3Szs/HmbRKyZT+CO1eP02fD1fyttz98rHvJNHVkfXfpg
            k/rGAjD/vQGxZXz3l2pBBokmDQI8wmqiYBv7xHaaqiAq22YKZq6IOS9v1ySxCxcQ
            X1EQTxrhPgcGiqe+zfLKFtJ8Ai1z4lQ6YOmFiM48GQKBgCexCI5KqOZyqHTiY771
            7qyurXk4LWahFSZDGIH2KZDp/pi6yyvjdWDkx9lTDFgVCwN1Tqv7PEgLhJMsiVds
            doXjpGoqdYqqPDRvBuui5cx3j4tmJX+lidWmays+JCk54vyvJ0rm4JxCAZgN6XoS
            nlG1wg12bvzlmVtRVjDv67mH
            -----END PRIVATE KEY-----
            """;

    private GoogleAnalyticsClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GoogleAnalyticsClient(
                builder, DATA_API_BASE_URL, TOKEN_URI, new GoogleServiceAccountJwtSigner(new ObjectMapper()));
    }

    private GoogleServiceAccountKey testKey() {
        return new GoogleServiceAccountKey("svc@example.iam.gserviceaccount.com", PRIVATE_KEY_PEM, null);
    }

    @Test
    void fetchReport_トークン取得後にrunReportを呼びメトリクスを取得する() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"access_token\":\"token-abc\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));

        server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                .andExpect(method(POST))
                .andExpect(header("Authorization", "Bearer token-abc"))
                .andRespond(withSuccess(
                        "{\"rows\":[{\"metricValues\":[{\"value\":\"120\"},{\"value\":\"80\"},{\"value\":\"300\"}]}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"rows\":[{\"dimensionValues\":[{\"value\":\"20240101\"}],"
                                + "\"metricValues\":[{\"value\":\"60\"},{\"value\":\"40\"},{\"value\":\"150\"}]}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"rows\":[{\"dimensionValues\":[{\"value\":\"Organic Search\"}],"
                                + "\"metricValues\":[{\"value\":\"120\"},{\"value\":\"80\"},{\"value\":\"300\"}]}]}",
                        MediaType.APPLICATION_JSON));

        GoogleAnalyticsReport report = client.fetchReport(testKey(), "123456789", 28);

        assertEquals(120, report.sessions());
        assertEquals(80, report.activeUsers());
        assertEquals(300, report.pageViews());
        assertEquals(1, report.dailyDataPoints().size());
        assertEquals("2024-01-01", report.dailyDataPoints().get(0).date());
        assertEquals(60, report.dailyDataPoints().get(0).sessions());
        assertEquals(1, report.channelBreakdown().size());
        assertEquals("Organic Search", report.channelBreakdown().get(0).channel());
        server.verify();
    }

    @Test
    void fetchReport_rowsが無ければ全て0を返す() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"access_token\":\"token-abc\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        GoogleAnalyticsReport report = client.fetchReport(testKey(), "123456789", 28);

        assertEquals(0, report.sessions());
        assertEquals(0, report.activeUsers());
        assertEquals(0, report.pageViews());
        assertEquals(0, report.dailyDataPoints().size());
        assertEquals(0, report.channelBreakdown().size());
    }

    @Test
    void fetchReport_トークン取得に失敗すると例外() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("{\"error\":\"invalid_grant\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThrows(GoogleAnalyticsException.class, () -> client.fetchReport(testKey(), "123456789", 28));
    }

    @Test
    void fetchReport_runReport呼び出しに失敗すると例外() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"access_token\":\"token-abc\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .body("{\"error\":\"permission_denied\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThrows(GoogleAnalyticsException.class, () -> client.fetchReport(testKey(), "123456789", 28));
    }
}
