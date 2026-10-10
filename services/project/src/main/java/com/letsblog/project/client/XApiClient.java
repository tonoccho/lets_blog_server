package com.letsblog.project.client;

import com.letsblog.common.client.ExternalCallLoggingInterceptor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.project.config.LegacyJacksonRestClientConfig;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
 * X API(v2)の OAuth 2.0 認可コードフロー(PKCE つきの confidential client)のクライアント(issue #1574)。
 * 認可 URL の組み立て、認可コードのトークン交換、自分のユーザー名の取得だけを行う。受け取ったトークンは
 * 呼び出し側({@code SnsXService})が本番サイトのプラグインへ送るためだけに使い、ここでも保存しない。
 *
 * <p>API のベース URL と認可画面の URL は設定で差し替えられる(受け入れテストの x-stub 向け)。
 */
@Component
public class XApiClient {

    static final String SCOPE = "tweet.read tweet.write users.read offline.access";
    private static final long DEFAULT_EXPIRES_IN = 7200L;

    private final RestClient client;
    private final String authorizeUrl;

    @Autowired
    public XApiClient(
            @Value("${app.x-api-base-url}") String apiBaseUrl,
            @Value("${app.x-authorize-url}") String authorizeUrl) {
        this(RestClient.builder(), apiBaseUrl, authorizeUrl);
    }

    /** テスト専用: RestClient.Builderを直接受け取るコンストラクタ。 */
    XApiClient(RestClient.Builder builder, String apiBaseUrl, String authorizeUrl) {
        LegacyJacksonRestClientConfig.preferJackson2(builder);
        builder.requestInterceptor(new ExternalCallLoggingInterceptor("x-api"));
        this.client = builder.baseUrl(apiBaseUrl).build();
        this.authorizeUrl = authorizeUrl;
    }

    /** X の認可画面の URL。利用者のブラウザをここへ遷移させる。 */
    public String authorizeUrl(String clientId, String redirectUri, String state, String codeChallenge) {
        return UriComponentsBuilder.fromUriString(authorizeUrl)
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", SCOPE)
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .build()
                .encode()
                .toUriString();
    }

    /** 認可コードをアクセストークン・リフレッシュトークンに交換する(Basic 認証で client_id:client_secret を送る)。 */
    public XTokens exchangeCode(
            String clientId, String clientSecret, String code, String redirectUri, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("code_verifier", codeVerifier);
        String basic = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        TokenResponse response;
        try {
            response = client.post()
                    .uri("/2/oauth2/token")
                    .header("Authorization", "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientResponseException e) {
            throw failure("X のトークン交換", e);
        } catch (RestClientException e) {
            throw new XApiException("X に接続できません(トークン交換)", e);
        }
        if (response == null || isBlank(response.accessToken())) {
            throw new XApiException("X からアクセストークンを取得できませんでした");
        }
        if (isBlank(response.refreshToken())) {
            throw new XApiException(
                    "X からリフレッシュトークンを取得できませんでした(X のアプリで offline.access を許可してください)");
        }
        long expiresIn = response.expiresIn() == null ? DEFAULT_EXPIRES_IN : response.expiresIn();
        return new XTokens(response.accessToken(), response.refreshToken(), expiresIn);
    }

    /** 接続したアカウントのユーザー名(@ を除く)。 */
    public String fetchUsername(String accessToken) {
        JsonNode body;
        try {
            body = client.get()
                    .uri("/2/users/me")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw failure("X の自分の情報の取得", e);
        } catch (RestClientException e) {
            throw new XApiException("X に接続できません(自分の情報の取得)", e);
        }
        String username = body == null ? null : body.path("data").path("username").asText(null);
        if (isBlank(username)) {
            throw new XApiException("X からユーザー名を取得できませんでした");
        }
        return username;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 失敗の理由として使う短い説明。X の応答の error / error_description / detail だけで、送った値は含めない。 */
    private static XApiException failure(String what, RestClientResponseException e) {
        StringBuilder message = new StringBuilder(what).append("に失敗しました(HTTP ").append(e.getStatusCode().value());
        try {
            JsonNode body = e.getResponseBodyAs(JsonNode.class);
            for (String key : new String[] {"error_description", "detail", "error", "title"}) {
                String value = body == null ? null : body.path(key).asText(null);
                if (!isBlank(value)) {
                    message.append(": ").append(value.length() > 200 ? value.substring(0, 200) : value);
                    break;
                }
            }
        } catch (RuntimeException ignored) {
            // 本文がJSONでなければ理由は付けない。
        }
        return new XApiException(message.append(")").toString(), e);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("expires_in") Long expiresIn) {
    }

    /** 交換で得たトークン。呼び出し側が本番サイトへ送るためだけに持つ。 */
    public record XTokens(String accessToken, String refreshToken, long expiresIn) {
        /** 誤ってログや例外に出ないよう、値は表示しない。 */
        @Override
        public String toString() {
            return "XTokens[...]";
        }
    }
}
