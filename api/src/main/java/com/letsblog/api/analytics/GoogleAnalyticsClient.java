package com.letsblog.api.analytics;

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
import java.util.Map;

/**
 * サービスアカウント認証(JWT Bearerグラント)でGoogle OAuth2トークンを取得し、
 * GA4 Data API(runReport)を呼び出す薄いクライアント。認証情報の復号・productionSiteの判定等は
 * 呼び出し側(GoogleAnalyticsReportService)が行い、このクラス自体は鍵の出どころを知らない
 * (BraveSearchClientと同じ方針)。
 */
@Component
public class GoogleAnalyticsClient {

    private static final String SCOPE = "https://www.googleapis.com/auth/analytics.readonly";
    private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer";

    private final RestClient client;
    private final String dataApiBaseUrl;
    private final String defaultTokenUri;
    private final GoogleServiceAccountJwtSigner jwtSigner;

    @Autowired
    public GoogleAnalyticsClient(
            @Value("${app.google-analytics-data-api-base-url}") String dataApiBaseUrl,
            @Value("${app.google-analytics-oauth-token-uri}") String defaultTokenUri,
            GoogleServiceAccountJwtSigner jwtSigner) {
        this(RestClient.builder(), dataApiBaseUrl, defaultTokenUri, jwtSigner);
    }

    /** テスト専用: MockRestServiceServerを介せるようRestClient.Builderを直接受け取るコンストラクタ。 */
    GoogleAnalyticsClient(RestClient.Builder builder, String dataApiBaseUrl, String defaultTokenUri,
            GoogleServiceAccountJwtSigner jwtSigner) {
        LegacyJacksonRestClientConfig.preferJackson2(builder);
        this.client = builder.build();
        this.dataApiBaseUrl = dataApiBaseUrl;
        this.defaultTokenUri = defaultTokenUri;
        this.jwtSigner = jwtSigner;
    }

    public GoogleAnalyticsReport fetchReport(GoogleServiceAccountKey key, String propertyId, int periodDays) {
        String accessToken = fetchAccessToken(key);
        return runReport(accessToken, propertyId, periodDays);
    }

    private String fetchAccessToken(GoogleServiceAccountKey key) {
        String tokenUri = (key.tokenUri() == null || key.tokenUri().isBlank()) ? defaultTokenUri : key.tokenUri();
        String jwt = jwtSigner.sign(key, SCOPE, tokenUri);
        try {
            JsonNode response = client.post()
                    .uri(tokenUri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body("grant_type=" + URLEncoder.encode(GRANT_TYPE, StandardCharsets.UTF_8) + "&assertion=" + jwt)
                    .retrieve()
                    .body(JsonNode.class);
            String accessToken = response == null ? null : response.path("access_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new GoogleAnalyticsException("Googleからアクセストークンを取得できませんでした", null);
            }
            return accessToken;
        } catch (RestClientResponseException e) {
            throw new GoogleAnalyticsException(
                    "Google OAuth認証に失敗しました: " + e.getStatusCode() + " " + e.getResponseBodyAsString(), e);
        }
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
                    "Google Analytics Data APIの呼び出しに失敗しました: " + e.getStatusCode() + " "
                            + e.getResponseBodyAsString(), e);
        }
    }

    private GoogleAnalyticsReport parseReport(JsonNode response) {
        if (response == null) {
            return new GoogleAnalyticsReport(0, 0, 0);
        }
        JsonNode rows = response.path("rows");
        if (!rows.isArray() || rows.isEmpty()) {
            return new GoogleAnalyticsReport(0, 0, 0);
        }
        JsonNode values = rows.get(0).path("metricValues");
        return new GoogleAnalyticsReport(
                values.path(0).path("value").asLong(0),
                values.path(1).path("value").asLong(0),
                values.path(2).path("value").asLong(0));
    }
}
