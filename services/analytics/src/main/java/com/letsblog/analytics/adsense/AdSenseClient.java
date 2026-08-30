package com.letsblog.analytics.adsense;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
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

/**
 * Google OAuth2の認可コード/リフレッシュトークンによるトークン取得と、AdSense Management API
 * (reports:generate)の呼び出しを行う薄いクライアント。GA4のGoogleAnalyticsClientと異なり、
 * AdSenseはサービスアカウント委任に対応していないため3-leggedフローの認可コード/リフレッシュトークンを使う。
 * クライアントID/シークレットはプロジェクトごとに異なるGoogle Cloudプロジェクトを使い分けられるよう
 * プロジェクト単位で保持する(issue #407)ため、呼び出し側(AnalyticsCredentialsService等)が都度渡す。
 */
@Component
public class AdSenseClient {

    private static final String GRANT_TYPE_AUTHORIZATION_CODE = "authorization_code";
    private static final String GRANT_TYPE_REFRESH_TOKEN = "refresh_token";
    private static final List<String> METRICS = List.of("ESTIMATED_EARNINGS", "CLICKS", "IMPRESSIONS");

    private final RestClient client;
    private final String tokenUri;
    private final String dataApiBaseUrl;

    @Autowired
    public AdSenseClient(
            @Value("${app.google-oauth-token-uri}") String tokenUri,
            @Value("${app.adsense-data-api-base-url}") String dataApiBaseUrl) {
        this(RestClient.builder(), tokenUri, dataApiBaseUrl);
    }

    /** テスト専用: MockRestServiceServerを介せるようRestClient.Builderを直接受け取るコンストラクタ。 */
    AdSenseClient(RestClient.Builder builder, String tokenUri, String dataApiBaseUrl) {
        LegacyJacksonRestClientConfig.preferJackson2(builder);
        this.client = builder.build();
        this.tokenUri = tokenUri;
        this.dataApiBaseUrl = dataApiBaseUrl;
    }

    /** OAuth同意画面からの認可コードを、アクセストークン/リフレッシュトークンに交換する。 */
    public GoogleOAuthTokens exchangeAuthorizationCode(String clientId, String clientSecret, String code, String redirectUri) {
        requireClientCredentials(clientId, clientSecret);
        String body = "grant_type=" + GRANT_TYPE_AUTHORIZATION_CODE
                + "&code=" + urlEncode(code)
                + "&redirect_uri=" + urlEncode(redirectUri)
                + "&client_id=" + urlEncode(clientId)
                + "&client_secret=" + urlEncode(clientSecret);
        GoogleOAuthTokens tokens = postForTokens(body);
        if (tokens.refreshToken() == null || tokens.refreshToken().isBlank()) {
            throw new AdSenseException(
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
            throw new AdSenseException("Googleからアクセストークンを取得できませんでした", null);
        }
        return tokens.accessToken();
    }

    private void requireClientCredentials(String clientId, String clientSecret) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new AdSenseException(
                    "このプロジェクトにはGoogle OAuthクライアントID/シークレットが設定されていません"
                            + "(プロジェクト詳細画面のGoogle AdSense設定から設定してください)",
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
                throw new AdSenseException("Googleからのトークンレスポンスが空でした", null);
            }
            return tokens;
        } catch (RestClientResponseException e) {
            throw new AdSenseException(
                    "Google OAuthトークン取得に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** AdSense Management API v2のaccounts.reports.generateを、指定したプリセット期間(dateRange)で呼び出す。 */
    public AdSenseReport fetchReport(String accessToken, String accountId, String dateRange) {
        StringBuilder uri = new StringBuilder(dataApiBaseUrl)
                .append("/v2/accounts/").append(urlEncode(accountId)).append("/reports:generate")
                .append("?dateRange=").append(urlEncode(dateRange));
        METRICS.forEach(metric -> uri.append("&metrics=").append(urlEncode(metric)));
        try {
            JsonNode response = client.get()
                    .uri(uri.toString())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(JsonNode.class);
            AdSenseReport totals = parseReport(response);
            List<AdSenseDailyDataPoint> dailyDataPoints = fetchDailyDataPoints(accessToken, accountId, dateRange);
            List<AdSensePlatformBreakdown> platformBreakdown = fetchPlatformBreakdown(accessToken, accountId, dateRange);
            return new AdSenseReport(
                    totals.estimatedEarnings(), totals.clicks(), totals.impressions(), dailyDataPoints, platformBreakdown);
        } catch (RestClientResponseException e) {
            throw new AdSenseException(
                    "AdSense Management APIの呼び出しに失敗しました: " + e.getStatusCode() + " "
                            + e.getResponseBodyAsString(), e);
        }
    }

    private AdSenseReport parseReport(JsonNode response) {
        if (response == null) {
            return new AdSenseReport("0", 0, 0, List.of(), List.of());
        }
        JsonNode cells = response.path("totals").path("cells");
        if (!cells.isArray() || cells.isEmpty()) {
            return new AdSenseReport("0", 0, 0, List.of(), List.of());
        }
        String estimatedEarnings = cells.path(0).path("value").asText("0");
        long clicks = cells.path(1).path("value").asLong(0);
        long impressions = cells.path(2).path("value").asLong(0);
        return new AdSenseReport(estimatedEarnings, clicks, impressions, List.of(), List.of());
    }

    /** 日次推移グラフ用に、DATEディメンションを指定してreports:generateを呼び出す(issue #426)。 */
    private List<AdSenseDailyDataPoint> fetchDailyDataPoints(String accessToken, String accountId, String dateRange) {
        List<AdSenseDailyDataPoint> points = new ArrayList<>();
        for (JsonNode row : fetchRowsWithDimension(accessToken, accountId, dateRange, "DATE")) {
            JsonNode cells = row.path("cells");
            points.add(new AdSenseDailyDataPoint(
                    cells.path(0).path("value").asText(null),
                    cells.path(1).path("value").asText("0"),
                    cells.path(2).path("value").asLong(0),
                    cells.path(3).path("value").asLong(0)));
        }
        return points;
    }

    /** 収益内訳の円グラフ用に、PLATFORM_TYPE_NAMEディメンションで取得する(issue #426)。 */
    private List<AdSensePlatformBreakdown> fetchPlatformBreakdown(String accessToken, String accountId, String dateRange) {
        List<AdSensePlatformBreakdown> breakdown = new ArrayList<>();
        for (JsonNode row : fetchRowsWithDimension(accessToken, accountId, dateRange, "PLATFORM_TYPE_NAME")) {
            JsonNode cells = row.path("cells");
            breakdown.add(new AdSensePlatformBreakdown(
                    cells.path(0).path("value").asText(null),
                    cells.path(1).path("value").asText("0"),
                    cells.path(2).path("value").asLong(0),
                    cells.path(3).path("value").asLong(0)));
        }
        return breakdown;
    }

    private JsonNode fetchRowsWithDimension(String accessToken, String accountId, String dateRange, String dimension) {
        StringBuilder uri = new StringBuilder(dataApiBaseUrl)
                .append("/v2/accounts/").append(urlEncode(accountId)).append("/reports:generate")
                .append("?dateRange=").append(urlEncode(dateRange))
                .append("&dimensions=").append(urlEncode(dimension));
        METRICS.forEach(metric -> uri.append("&metrics=").append(urlEncode(metric)));
        JsonNode response = client.get()
                .uri(uri.toString())
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(JsonNode.class);
        return response == null ? MissingNode.getInstance() : response.path("rows");
    }
}
