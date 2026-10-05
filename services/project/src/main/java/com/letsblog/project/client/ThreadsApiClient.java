package com.letsblog.project.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.project.config.LegacyJacksonRestClientConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Threads API の OAuth 認可コードフロー(認可 URL → 短期トークン → 長期トークン)のクライアント(issue #1579)。
 * 受け取ったトークンは呼び出し側({@code SnsThreadsService})が本番サイトのプラグインへ送るためだけに使い、
 * ここでも保存しない。Threads の長期トークン(約60日)は、プラグインがアプリの秘密なしで期限前に更新する。
 *
 * <p>API のベース URL と認可画面の URL は設定で差し替えられる(受け入れテストの threads-stub 向け)。
 */
@Component
public class ThreadsApiClient {

    static final String SCOPE = "threads_basic,threads_content_publish";
    private static final long DEFAULT_LONG_LIVED_EXPIRES_IN = 5_184_000L;

    private final RestClient client;
    private final String authorizeUrl;

    @Autowired
    public ThreadsApiClient(
            @Value("${app.threads-api-base-url}") String apiBaseUrl,
            @Value("${app.threads-authorize-url}") String authorizeUrl) {
        this(RestClient.builder(), apiBaseUrl, authorizeUrl);
    }

    /** テスト専用: RestClient.Builderを直接受け取るコンストラクタ。 */
    ThreadsApiClient(RestClient.Builder builder, String apiBaseUrl, String authorizeUrl) {
        LegacyJacksonRestClientConfig.preferJackson2(builder);
        this.client = builder.baseUrl(apiBaseUrl).build();
        this.authorizeUrl = authorizeUrl;
    }

    /** Threads の認可画面の URL。利用者のブラウザをここへ遷移させる。 */
    public String authorizeUrl(String clientId, String redirectUri, String state) {
        return UriComponentsBuilder.fromUriString(authorizeUrl)
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", SCOPE)
                .queryParam("response_type", "code")
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();
    }

    /** 認可コードを短期トークン(約1時間)とユーザーIDに交換する。 */
    public ShortLivedToken exchangeCode(String clientId, String clientSecret, String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("grant_type", "authorization_code");
        form.add("redirect_uri", redirectUri);
        form.add("code", code);
        JsonNode body;
        try {
            body = client.post()
                    .uri("/oauth/access_token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw failure("Threads のトークン交換", e);
        } catch (RestClientException e) {
            throw new ThreadsApiException("Threads に接続できません(トークン交換)");
        }
        String accessToken = text(body, "access_token");
        String userId = text(body, "user_id");
        if (accessToken == null) {
            throw new ThreadsApiException("Threads からアクセストークンを取得できませんでした");
        }
        if (userId == null) {
            throw new ThreadsApiException("Threads からユーザーIDを取得できませんでした");
        }
        return new ShortLivedToken(accessToken, userId);
    }

    /** 短期トークンを長期トークン(約60日)に交換する。 */
    public LongLivedToken exchangeLongLived(String clientSecret, String shortLivedToken) {
        LongLivedResponse response;
        try {
            response = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/access_token")
                            .queryParam("grant_type", "th_exchange_token")
                            .queryParam("client_secret", clientSecret)
                            .queryParam("access_token", shortLivedToken)
                            .build())
                    .retrieve()
                    .body(LongLivedResponse.class);
        } catch (RestClientResponseException e) {
            throw failure("Threads の長期トークンへの交換", e);
        } catch (RestClientException e) {
            throw new ThreadsApiException("Threads に接続できません(長期トークンへの交換)");
        }
        if (response == null || isBlank(response.accessToken())) {
            throw new ThreadsApiException("Threads から長期トークンを取得できませんでした");
        }
        long expiresIn = response.expiresIn() == null ? DEFAULT_LONG_LIVED_EXPIRES_IN : response.expiresIn();
        return new LongLivedToken(response.accessToken(), expiresIn);
    }

    /** 接続したアカウントのユーザー名。 */
    public String fetchUsername(String accessToken) {
        JsonNode body;
        try {
            body = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/v1.0/me").queryParam("fields", "id,username").build())
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw failure("Threads の自分の情報の取得", e);
        } catch (RestClientException e) {
            throw new ThreadsApiException("Threads に接続できません(自分の情報の取得)");
        }
        String username = text(body, "username");
        if (username == null) {
            throw new ThreadsApiException("Threads からユーザー名を取得できませんでした");
        }
        return username;
    }

    private static String text(JsonNode body, String field) {
        String value = body == null ? null : body.path(field).asText(null);
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 失敗の理由として使う短い説明。Threads の応答の error.message だけで、送った値は含めない。 */
    private static ThreadsApiException failure(String what, RestClientResponseException e) {
        StringBuilder message = new StringBuilder(what).append("に失敗しました(HTTP ").append(e.getStatusCode().value());
        try {
            JsonNode body = e.getResponseBodyAs(JsonNode.class);
            String reason = body == null ? null : body.path("error").path("message").asText(null);
            if (!isBlank(reason)) {
                message.append(": ").append(reason.length() > 200 ? reason.substring(0, 200) : reason);
            }
        } catch (RuntimeException ignored) {
            // 本文がJSONでなければ理由は付けない。
        }
        return new ThreadsApiException(message.append(")").toString());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record LongLivedResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresIn) {
    }

    /** 認可コードの交換で得た短期トークン。長期トークン化にだけ使う。 */
    public record ShortLivedToken(String accessToken, String userId) {
        /** 誤ってログや例外に出ないよう、値は表示しない。 */
        @Override
        public String toString() {
            return "ShortLivedToken[...]";
        }
    }

    /** 長期トークン。呼び出し側が本番サイトへ送るためだけに持つ。 */
    public record LongLivedToken(String accessToken, long expiresIn) {
        @Override
        public String toString() {
            return "LongLivedToken[...]";
        }
    }
}
