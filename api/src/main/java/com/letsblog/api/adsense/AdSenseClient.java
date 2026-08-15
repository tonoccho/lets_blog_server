package com.letsblog.api.adsense;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.api.config.LegacyJacksonRestClientConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Google OAuth2の認可コード/リフレッシュトークンによるトークン取得と、AdSense Management API
 * (reports:generate)の呼び出しを行う薄いクライアント。GA4のGoogleAnalyticsClientと異なり、
 * AdSenseはサービスアカウント委任に対応していないため3-leggedフローの認可コード/リフレッシュトークンを使う。
 * クライアントID/シークレットはアプリ全体で1つ(Google Cloud Consoleに1回登録)なのでプロジェクト単位ではなく
 * このクライアントが直接保持する(プロジェクト単位のリフレッシュトークンは呼び出し側が渡す)。
 */
@Component
public class AdSenseClient {

    private static final String GRANT_TYPE_AUTHORIZATION_CODE = "authorization_code";
    private static final String GRANT_TYPE_REFRESH_TOKEN = "refresh_token";
    private static final List<String> METRICS = List.of("ESTIMATED_EARNINGS", "CLICKS", "IMPRESSIONS");

    private final RestClient client;
    private final String tokenUri;
    private final String dataApiBaseUrl;
    private final String clientId;
    private final String clientSecret;

    @Autowired
    public AdSenseClient(
            @Value("${app.google-oauth-token-uri}") String tokenUri,
            @Value("${app.adsense-data-api-base-url}") String dataApiBaseUrl,
            @Value("${app.google-oauth-client-id:}") String clientId,
            @Value("${app.google-oauth-client-secret:}") String clientSecret) {
        this(RestClient.builder(), tokenUri, dataApiBaseUrl, clientId, clientSecret);
    }

    /** テスト専用: MockRestServiceServerを介せるようRestClient.Builderを直接受け取るコンストラクタ。 */
    AdSenseClient(RestClient.Builder builder, String tokenUri, String dataApiBaseUrl,
            String clientId, String clientSecret) {
        LegacyJacksonRestClientConfig.preferJackson2(builder);
        this.client = builder.build();
        this.tokenUri = tokenUri;
        this.dataApiBaseUrl = dataApiBaseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /** OAuth同意画面からの認可コードを、アクセストークン/リフレッシュトークンに交換する。 */
    public GoogleOAuthTokens exchangeAuthorizationCode(String code, String redirectUri) {
        requireClientCredentials();
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

    public String refreshAccessToken(String refreshToken) {
        requireClientCredentials();
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

    private void requireClientCredentials() {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            throw new AdSenseException(
                    "Google OAuthクライアントID/シークレットが設定されていません"
                            + "(GOOGLE_OAUTH_CLIENT_ID/GOOGLE_OAUTH_CLIENT_SECRET)",
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
            return parseReport(response);
        } catch (RestClientResponseException e) {
            throw new AdSenseException(
                    "AdSense Management APIの呼び出しに失敗しました: " + e.getStatusCode() + " "
                            + e.getResponseBodyAsString(), e);
        }
    }

    private AdSenseReport parseReport(JsonNode response) {
        if (response == null) {
            return new AdSenseReport("0", 0, 0);
        }
        JsonNode cells = response.path("totals").path("cells");
        if (!cells.isArray() || cells.isEmpty()) {
            return new AdSenseReport("0", 0, 0);
        }
        String estimatedEarnings = cells.path(0).path("value").asText("0");
        long clicks = cells.path(1).path("value").asLong(0);
        long impressions = cells.path(2).path("value").asLong(0);
        return new AdSenseReport(estimatedEarnings, clicks, impressions);
    }
}
