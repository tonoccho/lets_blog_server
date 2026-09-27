package com.letsblog.analytics.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.letsblog.analytics.adsense.GoogleOAuthTokens;
import com.letsblog.analytics.client.GoogleApiFailureMessage;
import com.letsblog.analytics.config.LegacyJacksonRestClientConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ユーザーOAuth(3-legged、AdSenseと同じ方式)で取得したトークンにより、GA4 Admin API(accountSummaries.list)と
 * GA4 Data API(runReport)を呼び出す薄いクライアント(issue #1231でサービスアカウントJWT Bearerグラントから移行)。
 * 要求するスコープは{@code analytics.readonly}のみで、GA4側の設定を変更するAPIは呼ばない。
 * クライアントID/シークレット/リフレッシュトークンの復号・productionSiteの判定等は呼び出し側
 * (ProjectAnalyticsSettingsService / GoogleAnalyticsReportService)が行い、このクラス自体は
 * 資格情報の出どころを知らない(BraveSearchClientと同じ方針)。
 */
@Component
public class GoogleAnalyticsClient {

    private static final String GRANT_TYPE_AUTHORIZATION_CODE = "authorization_code";
    private static final String GRANT_TYPE_REFRESH_TOKEN = "refresh_token";
    private static final String ACCOUNT_SUMMARIES_PAGE_SIZE = "200";
    private static final String PROPERTY_PREFIX = "properties/";

    private final RestClient client;
    private final String dataApiBaseUrl;
    private final String adminApiBaseUrl;
    private final String tokenUri;

    @Autowired
    public GoogleAnalyticsClient(
            @Value("${app.google-analytics-data-api-base-url}") String dataApiBaseUrl,
            @Value("${app.google-analytics-admin-api-base-url}") String adminApiBaseUrl,
            @Value("${app.google-analytics-oauth-token-uri}") String tokenUri) {
        this(RestClient.builder(), dataApiBaseUrl, adminApiBaseUrl, tokenUri);
    }

    /** テスト専用: MockRestServiceServerを介せるようRestClient.Builderを直接受け取るコンストラクタ。 */
    GoogleAnalyticsClient(RestClient.Builder builder, String dataApiBaseUrl, String adminApiBaseUrl, String tokenUri) {
        LegacyJacksonRestClientConfig.preferJackson2(builder);
        this.client = builder.build();
        this.dataApiBaseUrl = dataApiBaseUrl;
        this.adminApiBaseUrl = adminApiBaseUrl;
        this.tokenUri = tokenUri;
    }

    /** OAuth同意画面からの認可コードを、アクセストークン/リフレッシュトークンに交換する。 */
    public GoogleOAuthTokens exchangeAuthorizationCode(
            String clientId, String clientSecret, String code, String redirectUri) {
        requireClientCredentials(clientId, clientSecret);
        String body = "grant_type=" + GRANT_TYPE_AUTHORIZATION_CODE
                + "&code=" + urlEncode(code)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&client_id=" + urlEncode(clientId)
                + "&client_secret=" + urlEncode(clientSecret);
        GoogleOAuthTokens tokens = postForTokens(body);
        if (tokens.refreshToken() == null || tokens.refreshToken().isBlank()) {
            throw new GoogleAnalyticsException(
                    "Googleからリフレッシュトークンを取得できませんでした"
                            + "(既に同意済みの場合、Googleアカウントの連携済みアプリから一度解除してから再度連携してください)",
                    null);
        }
        return tokens;
    }

    public String refreshAccessToken(String clientId, String clientSecret, String refreshToken) {
        requireClientCredentials(clientId, clientSecret);
        String body = "grant_type=" + GRANT_TYPE_REFRESH_TOKEN
                + "&refresh_token=" + urlEncode(refreshToken)
                + "&client_id=" + urlEncode(clientId)
                + "&client_secret=" + urlEncode(clientSecret);
        GoogleOAuthTokens tokens = postForTokens(body);
        if (tokens.accessToken() == null || tokens.accessToken().isBlank()) {
            throw new GoogleAnalyticsException("Googleからアクセストークンを取得できませんでした", null);
        }
        return tokens.accessToken();
    }

    private void requireClientCredentials(String clientId, String clientSecret) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new GoogleAnalyticsException(
                    "このプロジェクトにはGoogle OAuthクライアントID/シークレットが設定されていません"
                            + "(Google Analytics設定から設定してください)",
                    null);
        }
    }

    private GoogleOAuthTokens postForTokens(String formBody) {
        try {
            GoogleOAuthTokens tokens = client.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formBody)
                    .retrieve()
                    .body(GoogleOAuthTokens.class);
            if (tokens == null) {
                throw new GoogleAnalyticsException("Googleからのトークンレスポンスが空でした", null);
            }
            return tokens;
        } catch (RestClientResponseException e) {
            throw new GoogleAnalyticsException(
                    GoogleApiFailureMessage.of("Google OAuth認証", e.getStatusCode(), e.getResponseBodyAsString()), e);
        }
    }

    /**
     * 連携したGoogleアカウントがアクセスできるGA4プロパティを、Admin APIのaccountSummaries.listで
     * 全ページ(nextPageToken)取得して結合する。
     */
    public List<GoogleAnalyticsPropertySummary> listProperties(String accessToken) {
        List<GoogleAnalyticsPropertySummary> properties = new ArrayList<>();
        String pageToken = null;
        do {
            String uri = adminApiBaseUrl + "/v1beta/accountSummaries?pageSize=" + ACCOUNT_SUMMARIES_PAGE_SIZE
                    + (pageToken == null ? "" : "&pageToken=" + urlEncode(pageToken));
            JsonNode response;
            try {
                response = client.get()
                        .uri(uri)
                        .header("Authorization", "Bearer " + accessToken)
                        .retrieve()
                        .body(JsonNode.class);
            } catch (RestClientResponseException e) {
                throw new GoogleAnalyticsException(
                        GoogleApiFailureMessage.of(
                                "Google Analytics Admin APIの呼び出し", e.getStatusCode(), e.getResponseBodyAsString()),
                        e);
            }
            if (response == null) {
                break;
            }
            for (JsonNode account : response.path("accountSummaries")) {
                String accountName = account.path("displayName").asText(null);
                for (JsonNode property : account.path("propertySummaries")) {
                    String name = property.path("property").asText("");
                    String propertyId = name.startsWith(PROPERTY_PREFIX) ? name.substring(PROPERTY_PREFIX.length()) : name;
                    properties.add(new GoogleAnalyticsPropertySummary(
                            propertyId, property.path("displayName").asText(null), accountName));
                }
            }
            pageToken = response.path("nextPageToken").asText(null);
        } while (pageToken != null && !pageToken.isBlank());
        return properties;
    }

    public GoogleAnalyticsReport fetchReport(String accessToken, String propertyId, int periodDays) {
        GoogleAnalyticsReport totals = runReport(accessToken, propertyId, periodDays);
        List<GoogleAnalyticsDailyDataPoint> dailyDataPoints = fetchDailyDataPoints(accessToken, propertyId, periodDays);
        List<GoogleAnalyticsChannelBreakdown> channelBreakdown = fetchChannelBreakdown(accessToken, propertyId, periodDays);
        return new GoogleAnalyticsReport(
                totals.sessions(), totals.activeUsers(), totals.pageViews(), dailyDataPoints, channelBreakdown);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private GoogleAnalyticsReport runReport(String accessToken, String propertyId, int periodDays) {
        Map<String, Object> body = Map.of(
                "dateRanges", List.of(Map.of("startDate", periodDays + "daysAgo", "endDate", "today")),
                "metrics", List.of(
                        Map.of("name", "sessions"),
                        Map.of("name", "activeUsers"),
                        Map.of("name", "screenPageViews")));
        try {
            JsonNode response = client.post()
                    .uri(dataApiBaseUrl + "/v1beta/properties/" + propertyId + ":runReport")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return parseReport(response);
        } catch (RestClientResponseException e) {
            throw new GoogleAnalyticsException(
                    GoogleApiFailureMessage.of(
                            "Google Analytics Data APIの呼び出し", e.getStatusCode(), e.getResponseBodyAsString()),
                    e);
        }
    }

    private GoogleAnalyticsReport parseReport(JsonNode response) {
        if (response == null) {
            return new GoogleAnalyticsReport(0, 0, 0, List.of(), List.of());
        }
        JsonNode rows = response.path("rows");
        if (!rows.isArray() || rows.isEmpty()) {
            return new GoogleAnalyticsReport(0, 0, 0, List.of(), List.of());
        }
        JsonNode values = rows.get(0).path("metricValues");
        return new GoogleAnalyticsReport(
                values.path(0).path("value").asLong(0),
                values.path(1).path("value").asLong(0),
                values.path(2).path("value").asLong(0),
                List.of(),
                List.of());
    }

    /** 日次推移グラフ用に、dateディメンションを指定してrunReportを呼び出す(issue #426)。 */
    private List<GoogleAnalyticsDailyDataPoint> fetchDailyDataPoints(String accessToken, String propertyId, int periodDays) {
        JsonNode response = runReportWithDimension(accessToken, propertyId, periodDays, "date");
        List<GoogleAnalyticsDailyDataPoint> points = new ArrayList<>();
        for (JsonNode row : response.path("rows")) {
            JsonNode values = row.path("metricValues");
            points.add(new GoogleAnalyticsDailyDataPoint(
                    formatGaDate(row.path("dimensionValues").path(0).path("value").asText(null)),
                    values.path(0).path("value").asLong(0),
                    values.path(1).path("value").asLong(0),
                    values.path(2).path("value").asLong(0)));
        }
        return points;
    }

    /** トラフィックソース別内訳の円グラフ用に、sessionDefaultChannelGroupディメンションで取得する(issue #426)。 */
    private List<GoogleAnalyticsChannelBreakdown> fetchChannelBreakdown(String accessToken, String propertyId, int periodDays) {
        JsonNode response = runReportWithDimension(accessToken, propertyId, periodDays, "sessionDefaultChannelGroup");
        List<GoogleAnalyticsChannelBreakdown> breakdown = new ArrayList<>();
        for (JsonNode row : response.path("rows")) {
            JsonNode values = row.path("metricValues");
            breakdown.add(new GoogleAnalyticsChannelBreakdown(
                    row.path("dimensionValues").path(0).path("value").asText(null),
                    values.path(0).path("value").asLong(0),
                    values.path(1).path("value").asLong(0),
                    values.path(2).path("value").asLong(0)));
        }
        return breakdown;
    }

    private JsonNode runReportWithDimension(String accessToken, String propertyId, int periodDays, String dimension) {
        Map<String, Object> body = Map.of(
                "dateRanges", List.of(Map.of("startDate", periodDays + "daysAgo", "endDate", "today")),
                "dimensions", List.of(Map.of("name", dimension)),
                "metrics", List.of(
                        Map.of("name", "sessions"),
                        Map.of("name", "activeUsers"),
                        Map.of("name", "screenPageViews")));
        try {
            JsonNode response = client.post()
                    .uri(dataApiBaseUrl + "/v1beta/properties/" + propertyId + ":runReport")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + accessToken)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            return response == null ? MissingNode.getInstance() : response;
        } catch (RestClientResponseException e) {
            throw new GoogleAnalyticsException(
                    GoogleApiFailureMessage.of(
                            "Google Analytics Data APIの呼び出し", e.getStatusCode(), e.getResponseBodyAsString()),
                    e);
        }
    }

    /** GA4のdateディメンションはデフォルトで"yyyyMMdd"形式のため、表示用に"yyyy-MM-dd"へ変換する。 */
    private static String formatGaDate(String raw) {
        if (raw == null || raw.length() != 8) {
            return raw;
        }
        return raw.substring(0, 4) + "-" + raw.substring(4, 6) + "-" + raw.substring(6, 8);
    }
}
