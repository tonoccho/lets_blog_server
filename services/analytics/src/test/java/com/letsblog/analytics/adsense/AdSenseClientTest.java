package com.letsblog.analytics.adsense;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    /**
     * issue #939 (AT-13) 受け入れ基準10。リフレッシュトークンが失効すると
     * ダッシュボードのAdSenseパネルにこの文言がそのまま出る
     * ({@code AdSenseReportResponse.error} → 「取得に失敗しました: …」)。
     * 利用者が次に何をすればよいか(= 再認証)が読み取れる必要がある。
     */
    @Test
    void refreshAccessToken_401なら再認証を促すメッセージになる() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(POST))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        AdSenseException exception = assertThrows(
                AdSenseException.class,
                () -> client.refreshAccessToken("client-id", "client-secret", "refresh-abc"));

        assertTrue(exception.getMessage().contains("再認証"), exception.getMessage());
        assertFalse(exception.getMessage().contains("invalid_grant"), exception.getMessage());
        assertFalse(exception.getMessage().contains("error_description"), exception.getMessage());
    }

    /** issue #939 (AT-13) 受け入れ基準11。レート制限は「認証に失敗」ではない。 */
    @Test
    void fetchReport_429なら回数制限と分かるメッセージになる() {
        server.expect(requestTo(DATA_API_BASE_URL
                        + "/v2/accounts/pub-123/reports:generate?dateRange=LAST_30_DAYS"
                        + "&metrics=ESTIMATED_EARNINGS&metrics=CLICKS&metrics=IMPRESSIONS"))
                .andExpect(method(GET))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .body("{\"error\":{\"status\":\"RESOURCE_EXHAUSTED\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        AdSenseException exception = assertThrows(
                AdSenseException.class, () -> client.fetchReport("access-abc", "pub-123", "LAST_30_DAYS"));

        assertTrue(exception.getMessage().contains("回数制限"), exception.getMessage());
        assertFalse(exception.getMessage().contains("認証"), exception.getMessage());
        assertFalse(exception.getMessage().contains("RESOURCE_EXHAUSTED"), exception.getMessage());
    }

    // ---- issue #939 (AT-13): 変更したファイルの分岐カバレッジ(C1/C2)を基準まで上げる ----
    //
    // 新しいふるまいではなく、既にあった分岐のうち到達していなかったものを埋める。
    // CLAUDE.md の Coverage が求める 90% は「このIssueで変更したファイル」に掛かる。

    @Test
    void exchangeAuthorizationCode_refresh_tokenが空文字なら例外() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(POST))
                .andRespond(withSuccess(
                        "{\"access_token\":\"access-abc\",\"refresh_token\":\"\"}", MediaType.APPLICATION_JSON));

        assertThrows(AdSenseException.class, () -> client.exchangeAuthorizationCode(
                "client-id", "client-secret", "auth-code", "https://example.com/callback"));
    }

    @Test
    void refreshAccessToken_access_tokenが含まれなければ例外() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON));

        assertThrows(AdSenseException.class,
                () -> client.refreshAccessToken("client-id", "client-secret", "refresh-abc"));
    }

    @Test
    void refreshAccessToken_access_tokenが空文字なら例外() {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"access_token\":\"\"}", MediaType.APPLICATION_JSON));

        assertThrows(AdSenseException.class,
                () -> client.refreshAccessToken("client-id", "client-secret", "refresh-abc"));
    }

    /** 本文の無い200。{@code body(GoogleOAuthTokens.class)} が null を返す経路。 */
    @Test
    void refreshAccessToken_トークン応答に本文が無ければ例外() {
        server.expect(requestTo(TOKEN_URI)).andExpect(method(POST)).andRespond(withSuccess());

        assertThrows(AdSenseException.class,
                () -> client.refreshAccessToken("client-id", "client-secret", "refresh-abc"));
    }

    @Test
    void クライアントIDがnullなら例外() {
        assertThrows(AdSenseException.class,
                () -> client.refreshAccessToken(null, "client-secret", "refresh-abc"));
    }

    @Test
    void クライアントシークレットがnullなら例外() {
        assertThrows(AdSenseException.class, () -> client.refreshAccessToken("client-id", null, "refresh-abc"));
    }

    @Test
    void クライアントシークレットが空文字なら例外() {
        assertThrows(AdSenseException.class, () -> client.refreshAccessToken("client-id", "", "refresh-abc"));
    }

    /** totals.cells が「空の配列」の場合。totals そのものが無い場合とは別の分岐を通る。 */
    @Test
    void fetchReport_totalsのcellsが空配列でも0を返す() {
        expectReport("", "{\"totals\":{\"cells\":[]}}");
        expectReport("&dimensions=DATE", "{}");
        expectReport("&dimensions=PLATFORM_TYPE_NAME", "{}");

        AdSenseReport report = client.fetchReport("access-abc", "pub-123", "LAST_30_DAYS");

        assertEquals("0", report.estimatedEarnings());
        assertEquals(0, report.clicks());
        assertEquals(0, report.impressions());
    }

    /** 本文の無い200。{@code body(JsonNode.class)} が null を返す経路。 */
    @Test
    void fetchReport_応答に本文が無くても0を返す() {
        expectEmptyReport("");
        expectEmptyReport("&dimensions=DATE");
        expectEmptyReport("&dimensions=PLATFORM_TYPE_NAME");

        AdSenseReport report = client.fetchReport("access-abc", "pub-123", "LAST_30_DAYS");

        assertEquals("0", report.estimatedEarnings());
        assertEquals(0, report.dailyDataPoints().size());
        assertEquals(0, report.platformBreakdown().size());
    }

    // ---- accounts.list(issue #1232: パブリッシャーIDの自動発見) ----

    private static final String ACCOUNTS_URI = DATA_API_BASE_URL + "/v2/accounts?pageSize=100";

    @Test
    void listAccounts_resource_nameからaccounts接頭辞を除いた素のパブリッシャーIDと表示名を返す() {
        server.expect(requestTo(ACCOUNTS_URI))
                .andExpect(method(GET))
                .andExpect(header("Authorization", "Bearer access-abc"))
                .andRespond(withSuccess(
                        "{\"accounts\":[{\"name\":\"accounts/pub-1234567890123456\",\"displayName\":\"My Site\"}]}",
                        MediaType.APPLICATION_JSON));

        List<AdSenseAccountSummary> accounts = client.listAccounts("access-abc");

        assertEquals(List.of(new AdSenseAccountSummary("pub-1234567890123456", "My Site")), accounts);
        server.verify();
    }

    @Test
    void listAccounts_接頭辞が無い名前はそのまま返し名前が空の要素は捨てる() {
        server.expect(requestTo(ACCOUNTS_URI))
                .andRespond(withSuccess(
                        "{\"accounts\":[{\"name\":\"pub-1\"},{\"displayName\":\"no name\"},{\"name\":\"\"}]}",
                        MediaType.APPLICATION_JSON));

        assertEquals(List.of(new AdSenseAccountSummary("pub-1", null)), client.listAccounts("t"));
    }

    @Test
    void listAccounts_nextPageTokenがある間は全ページを結合する() {
        server.expect(requestTo(ACCOUNTS_URI))
                .andRespond(withSuccess(
                        "{\"accounts\":[{\"name\":\"accounts/pub-1\",\"displayName\":\"A\"}],\"nextPageToken\":\"tok 2\"}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(ACCOUNTS_URI + "&pageToken=tok+2"))
                .andRespond(withSuccess(
                        "{\"accounts\":[{\"name\":\"accounts/pub-2\",\"displayName\":\"B\"}]}",
                        MediaType.APPLICATION_JSON));

        assertEquals(
                List.of(new AdSenseAccountSummary("pub-1", "A"), new AdSenseAccountSummary("pub-2", "B")),
                client.listAccounts("t"));
        server.verify();
    }

    @Test
    void listAccounts_nextPageTokenが空文字なら次のページを取りに行かない() {
        server.expect(requestTo(ACCOUNTS_URI))
                .andRespond(withSuccess(
                        "{\"accounts\":[{\"name\":\"accounts/pub-1\"}],\"nextPageToken\":\"\"}",
                        MediaType.APPLICATION_JSON));

        assertEquals(List.of(new AdSenseAccountSummary("pub-1", null)), client.listAccounts("t"));
        server.verify();
    }

    @Test
    void listAccounts_アカウントが無い応答は空の一覧() {
        server.expect(requestTo(ACCOUNTS_URI)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertTrue(client.listAccounts("t").isEmpty());
    }

    @Test
    void listAccounts_応答に本文が無くても空の一覧() {
        server.expect(requestTo(ACCOUNTS_URI)).andRespond(withSuccess());

        assertTrue(client.listAccounts("t").isEmpty());
    }

    @Test
    void listAccounts_失敗すると理由の分かるAdSenseExceptionになる() {
        server.expect(requestTo(ACCOUNTS_URI))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("{\"error\":{\"message\":\"secret raw body\"}}")
                        .contentType(MediaType.APPLICATION_JSON));

        AdSenseException e = assertThrows(AdSenseException.class, () -> client.listAccounts("t"));

        assertTrue(e.getMessage().contains("AdSenseアカウント一覧の取得"), e.getMessage());
        assertFalse(e.getMessage().contains("secret raw body"), "生の応答本文を利用者に見せない");
    }

    @Test
    void listAccounts_権限不足の403は理由を含めた例外になる() {
        server.expect(requestTo(ACCOUNTS_URI))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .body("{\"error\":\"forbidden\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        AdSenseException e = assertThrows(AdSenseException.class, () -> client.listAccounts("t"));

        assertTrue(e.getMessage().contains("403"), e.getMessage());
    }

    private String reportUri(String dimensions) {
        return DATA_API_BASE_URL + "/v2/accounts/pub-123/reports:generate?dateRange=LAST_30_DAYS"
                + dimensions + "&metrics=ESTIMATED_EARNINGS&metrics=CLICKS&metrics=IMPRESSIONS";
    }

    private void expectReport(String dimensions, String body) {
        server.expect(requestTo(reportUri(dimensions)))
                .andExpect(method(GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectEmptyReport(String dimensions) {
        server.expect(requestTo(reportUri(dimensions))).andExpect(method(GET)).andRespond(withSuccess());
    }
}
