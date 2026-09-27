package com.letsblog.analytics.analytics;

import com.letsblog.analytics.adsense.GoogleOAuthTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * GoogleAnalyticsClientの回帰テスト。MockRestServiceServerでHTTP通信を検証する
 * (実際のGoogleサーバーへは通信しない)。issue #1231でサービスアカウントJWTからユーザーOAuthへ移行した。
 */
class GoogleAnalyticsClientTest {

    private static final String DATA_API_BASE_URL = "https://analyticsdata.test";
    private static final String ADMIN_API_BASE_URL = "https://analyticsadmin.test";
    private static final String TOKEN_URI = "https://oauth2.test/token";
    private GoogleAnalyticsClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GoogleAnalyticsClient(builder, DATA_API_BASE_URL, ADMIN_API_BASE_URL, TOKEN_URI);
    }

    @Test
    void fetchReport_トークン取得後にrunReportを呼びメトリクスを取得する() {
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

        GoogleAnalyticsReport report = client.fetchReport("token-abc", "123456789", 28);

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
        server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        GoogleAnalyticsReport report = client.fetchReport("token-abc", "123456789", 28);

        assertEquals(0, report.sessions());
        assertEquals(0, report.activeUsers());
        assertEquals(0, report.pageViews());
        assertEquals(0, report.dailyDataPoints().size());
        assertEquals(0, report.channelBreakdown().size());
    }

    @Test
    void fetchReport_runReport呼び出しに失敗すると例外() {
        server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .body("{\"error\":\"permission_denied\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        assertThrows(GoogleAnalyticsException.class, () -> client.fetchReport("token-abc", "123456789", 28));
    }

    /** issue #939 (AT-13) 受け入れ基準11。レート制限は「認証に失敗」ではない。 */
    @Test
    void fetchReport_レポート取得が429なら回数制限と分かるメッセージになる() {
        server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .body("{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        GoogleAnalyticsException exception = assertThrows(
                GoogleAnalyticsException.class, () -> client.fetchReport("token-abc", "123456789", 28));

        assertTrue(exception.getMessage().contains("回数制限"), exception.getMessage());
        assertFalse(exception.getMessage().contains("認証"), exception.getMessage());
        assertFalse(exception.getMessage().contains("RESOURCE_EXHAUSTED"), exception.getMessage());
    }

    /** rows が「空の配列」の場合。{@code {}}(rowsそのものが無い)とは別の分岐を通る。 */
    @Test
    void fetchReport_rowsが空配列でも全て0を返す() {
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                    .andExpect(method(POST))
                    .andRespond(withSuccess("{\"rows\":[]}", MediaType.APPLICATION_JSON));
        }

        GoogleAnalyticsReport report = client.fetchReport("token-abc", "123456789", 28);

        assertEquals(0, report.sessions());
        assertEquals(0, report.dailyDataPoints().size());
        assertEquals(0, report.channelBreakdown().size());
    }

    /** 本文の無い200。{@code body(JsonNode.class)} が null を返す経路。 */
    @Test
    void fetchReport_レポート応答に本文が無くても全て0を返す() {
        expectEmptyReports();

        GoogleAnalyticsReport report = client.fetchReport("token-abc", "123456789", 28);

        assertEquals(0, report.sessions());
        assertEquals(0, report.dailyDataPoints().size());
        assertEquals(0, report.channelBreakdown().size());
    }

    /** GA4のdateディメンションが想定の "yyyyMMdd" でないときは、加工せずそのまま渡す。 */
    @Test
    void fetchReport_日付ディメンションが8桁でなければそのまま返す() {
        server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"rows\":[{\"dimensionValues\":[{\"value\":\"2024-01\"}],"
                                + "\"metricValues\":[{\"value\":\"1\"},{\"value\":\"2\"},{\"value\":\"3\"}]}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        GoogleAnalyticsReport report = client.fetchReport("token-abc", "123456789", 28);

        assertEquals("2024-01", report.dailyDataPoints().get(0).date());
    }

    @Test
    void fetchReport_日付ディメンションが無ければnullのままになる() {
        server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"rows\":[{\"metricValues\":[{\"value\":\"1\"},{\"value\":\"2\"},{\"value\":\"3\"}]}]}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        GoogleAnalyticsReport report = client.fetchReport("token-abc", "123456789", 28);

        assertNull(report.dailyDataPoints().get(0).date());
    }

    // ---- OAuth化(issue #1231): 認可コード交換 / リフレッシュ / プロパティ一覧 ----

    @Test
    void exchangeAuthorizationCode_成功時はリフレッシュトークンを返す() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("grant_type=authorization_code")))
                .andExpect(content().string(containsString("code=auth-code")))
                .andExpect(content().string(containsString("client_id=cid")))
                .andExpect(content().string(containsString("client_secret=csecret")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"access-abc\",\"refresh_token\":\"refresh-abc\"}",
                        MediaType.APPLICATION_JSON));

        GoogleOAuthTokens tokens =
                client.exchangeAuthorizationCode("cid", "csecret", "auth-code", "https://x.test/callback");

        assertEquals("refresh-abc", tokens.refreshToken());
        server.verify();
    }

    @Test
    void exchangeAuthorizationCode_リフレッシュトークンが無ければ例外() {
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"access-abc\"}", MediaType.APPLICATION_JSON));

        assertThrows(GoogleAnalyticsException.class,
                () -> client.exchangeAuthorizationCode("cid", "csecret", "code", "https://x.test/cb"));
    }

    @Test
    void exchangeAuthorizationCode_クライアントが未設定なら例外でGoogleへは問い合わせない() {
        assertThrows(GoogleAnalyticsException.class,
                () -> client.exchangeAuthorizationCode(null, "csecret", "code", "https://x.test/cb"));
        assertThrows(GoogleAnalyticsException.class,
                () -> client.exchangeAuthorizationCode("cid", null, "code", "https://x.test/cb"));
        assertThrows(GoogleAnalyticsException.class,
                () -> client.exchangeAuthorizationCode(" ", "csecret", "code", "https://x.test/cb"));
        assertThrows(GoogleAnalyticsException.class,
                () -> client.exchangeAuthorizationCode("cid", " ", "code", "https://x.test/cb"));
        server.verify();
    }

    @Test
    void exchangeAuthorizationCode_トークン応答が空なら例外() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withSuccess());

        assertThrows(GoogleAnalyticsException.class,
                () -> client.exchangeAuthorizationCode("cid", "csecret", "code", "https://x.test/cb"));
    }

    @Test
    void refreshAccessToken_リフレッシュトークンからアクセストークンを取得する() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("grant_type=refresh_token")))
                .andExpect(content().string(containsString("refresh_token=refresh-abc")))
                .andRespond(withSuccess("{\"access_token\":\"token-abc\"}", MediaType.APPLICATION_JSON));

        assertEquals("token-abc", client.refreshAccessToken("cid", "csecret", "refresh-abc"));
        server.verify();
    }

    @Test
    void refreshAccessToken_アクセストークンが空なら例外() {
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withSuccess("{\"access_token\":\"\"}", MediaType.APPLICATION_JSON));

        assertThrows(GoogleAnalyticsException.class, () -> client.refreshAccessToken("cid", "csecret", "r"));
    }

    @Test
    void refreshAccessToken_アクセストークン項目が無ければ例外() {
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThrows(GoogleAnalyticsException.class, () -> client.refreshAccessToken("cid", "csecret", "r"));
    }

    /**
     * issue #939 (AT-13) 受け入れ基準10の引き継ぎ。資格情報が失効したとき、利用者は
     * <b>再認証すればよい</b>と分かる必要があり、Googleの生の応答本文は載せない。
     */
    @Test
    void refreshAccessToken_401なら再認証を促すメッセージになる() {
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("{\"error\":\"invalid_grant\",\"error_description\":\"Token revoked\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        GoogleAnalyticsException exception = assertThrows(
                GoogleAnalyticsException.class, () -> client.refreshAccessToken("cid", "csecret", "r"));

        assertTrue(exception.getMessage().contains("再認証"), exception.getMessage());
        assertFalse(exception.getMessage().contains("invalid_grant"), exception.getMessage());
    }

    @Test
    void listProperties_全ページを取得して結合する() {
        server.expect(requestTo(ADMIN_API_BASE_URL + "/v1beta/accountSummaries?pageSize=200"))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer token-abc"))
                .andRespond(withSuccess("""
                        {"accountSummaries":[{"account":"accounts/1","displayName":"Account One",
                          "propertySummaries":[
                            {"property":"properties/111","displayName":"Site A"},
                            {"property":"properties/222","displayName":"Site B"}]}],
                         "nextPageToken":"page-2"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(ADMIN_API_BASE_URL + "/v1beta/accountSummaries?pageSize=200&pageToken=page-2"))
                .andExpect(method(GET))
                .andRespond(withSuccess("""
                        {"accountSummaries":[{"account":"accounts/2","displayName":"Account Two",
                          "propertySummaries":[{"property":"properties/333","displayName":"Site C"}]}]}
                        """, MediaType.APPLICATION_JSON));

        List<GoogleAnalyticsPropertySummary> properties = client.listProperties("token-abc");

        assertEquals(3, properties.size());
        assertEquals("111", properties.get(0).propertyId());
        assertEquals("Site A", properties.get(0).displayName());
        assertEquals("Account One", properties.get(0).accountDisplayName());
        assertEquals("333", properties.get(2).propertyId());
        assertEquals("Account Two", properties.get(2).accountDisplayName());
        server.verify();
    }

    @Test
    void listProperties_プロパティが無いアカウントや空応答は空リスト() {
        server.expect(requestTo(ADMIN_API_BASE_URL + "/v1beta/accountSummaries?pageSize=200"))
                .andRespond(withSuccess(
                        "{\"accountSummaries\":[{\"account\":\"accounts/1\",\"displayName\":\"Empty\"}]}",
                        MediaType.APPLICATION_JSON));

        assertTrue(client.listProperties("token-abc").isEmpty());
    }

    @Test
    void listProperties_本文が無い応答でも空リスト() {
        server.expect(requestTo(ADMIN_API_BASE_URL + "/v1beta/accountSummaries?pageSize=200"))
                .andRespond(withSuccess());

        assertTrue(client.listProperties("token-abc").isEmpty());
    }

    @Test
    void listProperties_失敗時は例外() {
        server.expect(requestTo(ADMIN_API_BASE_URL + "/v1beta/accountSummaries?pageSize=200"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).body("{}").contentType(MediaType.APPLICATION_JSON));

        assertThrows(GoogleAnalyticsException.class, () -> client.listProperties("token-abc"));
    }

    /** fetchReport は runReport を3回(合計・日次・チャネル別)呼ぶ。全て本文の無い200で返す。 */
    private void expectEmptyReports() {
        for (int i = 0; i < 3; i++) {
            server.expect(requestTo(DATA_API_BASE_URL + "/v1beta/properties/123456789:runReport"))
                    .andExpect(method(POST))
                    .andRespond(withSuccess());
        }
    }
}
