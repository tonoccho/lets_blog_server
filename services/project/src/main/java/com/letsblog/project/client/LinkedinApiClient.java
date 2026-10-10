package com.letsblog.project.client;

import com.letsblog.common.client.ExternalCallLoggingInterceptor;
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
 * LinkedIn の OAuth 2.0 認可コードフロー(認可 URL → アクセストークン)と、投稿者を特定する userinfo(OpenID Connect)の
 * クライアント(issue #1581)。受け取ったトークンは呼び出し側({@code SnsLinkedinService})が本番サイトのプラグインへ
 * 送るためだけに使い、ここでも保存しない。LinkedIn のアクセストークンは60日で切れ、Share on LinkedIn のアプリには
 * refresh token が出ないので、更新はしない(切れたら利用者が再接続する)。
 *
 * <p>API のベース URL・トークン交換の URL・認可画面の URL は設定で差し替えられる(受け入れテストの linkedin-stub 向け)。
 */
@Component
public class LinkedinApiClient {

    static final String SCOPE = "openid profile w_member_social";
    private static final long DEFAULT_EXPIRES_IN = 5_184_000L;

    private final RestClient client;
    private final String apiBaseUrl;
    private final String tokenUrl;
    private final String authorizeUrl;

    @Autowired
    public LinkedinApiClient(
            @Value("${app.linkedin-api-base-url}") String apiBaseUrl,
            @Value("${app.linkedin-token-url}") String tokenUrl,
            @Value("${app.linkedin-authorize-url}") String authorizeUrl) {
        this(RestClient.builder(), apiBaseUrl, tokenUrl, authorizeUrl);
    }

    /** テスト専用: RestClient.Builderを直接受け取るコンストラクタ。 */
    LinkedinApiClient(RestClient.Builder builder, String apiBaseUrl, String tokenUrl, String authorizeUrl) {
        LegacyJacksonRestClientConfig.preferJackson2(builder);
        builder.requestInterceptor(new ExternalCallLoggingInterceptor("linkedin-api"));
        this.client = builder.build();
        this.apiBaseUrl = apiBaseUrl;
        this.tokenUrl = tokenUrl;
        this.authorizeUrl = authorizeUrl;
    }

    /** LinkedIn の認可画面の URL。利用者のブラウザをここへ遷移させる。 */
    public String authorizeUrl(String clientId, String redirectUri, String state) {
        return UriComponentsBuilder.fromUriString(authorizeUrl)
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", SCOPE)
                .queryParam("state", state)
                .build()
                .encode()
                .toUriString();
    }

    /** 認可コードをアクセストークン(約60日)に交換する。 */
    public AccessToken exchangeCode(String clientId, String clientSecret, String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        TokenResponse response;
        try {
            response = client.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(TokenResponse.class);
        } catch (RestClientResponseException e) {
            throw failure("LinkedIn のトークン交換", e);
        } catch (RestClientException e) {
            throw new LinkedinApiException("LinkedIn に接続できません(トークン交換)");
        }
        if (response == null || isBlank(response.accessToken())) {
            throw new LinkedinApiException("LinkedIn からアクセストークンを取得できませんでした");
        }
        long expiresIn = response.expiresIn() == null ? DEFAULT_EXPIRES_IN : response.expiresIn();
        return new AccessToken(response.accessToken(), expiresIn);
    }

    /** 接続したメンバーの sub(投稿者 {@code urn:li:person:<sub>})と表示名。 */
    public Profile fetchProfile(String accessToken) {
        JsonNode body;
        try {
            body = client.get()
                    .uri(apiBaseUrl + "/v2/userinfo")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw failure("LinkedIn の自分の情報の取得", e);
        } catch (RestClientException e) {
            throw new LinkedinApiException("LinkedIn に接続できません(自分の情報の取得)");
        }
        String sub = text(body, "sub");
        if (sub == null) {
            throw new LinkedinApiException("LinkedIn からメンバーIDを取得できませんでした");
        }
        return new Profile(sub, displayName(body, sub));
    }

    /** name、無ければ姓名、それも無ければ sub を表示名にする。 */
    private static String displayName(JsonNode body, String sub) {
        String name = text(body, "name");
        if (name != null) {
            return name;
        }
        String given = text(body, "given_name");
        String family = text(body, "family_name");
        if (given != null && family != null) {
            return given + " " + family;
        }
        if (given != null) {
            return given;
        }
        return family != null ? family : sub;
    }

    private static String text(JsonNode body, String field) {
        String value = body == null ? null : body.path(field).asText(null);
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 失敗の理由として使う短い説明。LinkedIn の応答の理由だけで、送った値は含めない。 */
    private static LinkedinApiException failure(String what, RestClientResponseException e) {
        StringBuilder message = new StringBuilder(what).append("に失敗しました(HTTP ").append(e.getStatusCode().value());
        try {
            JsonNode body = e.getResponseBodyAs(JsonNode.class);
            String reason = null;
            if (body != null) {
                for (String field : new String[] {"error_description", "message", "error"}) {
                    reason = text(body, field);
                    if (reason != null) {
                        break;
                    }
                }
            }
            if (reason != null) {
                message.append(": ").append(reason.length() > 200 ? reason.substring(0, 200) : reason);
            }
        } catch (RuntimeException ignored) {
            // 本文がJSONでなければ理由は付けない。
        }
        return new LinkedinApiException(message.append(")").toString());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") Long expiresIn) {
    }

    /** アクセストークン。呼び出し側が本番サイトへ送るためだけに持つ。値は誤ってログや例外に出ないよう、表示しない。 */
    public record AccessToken(String accessToken, long expiresIn) {
        @Override
        public String toString() {
            return "AccessToken[...]";
        }
    }

    /** 接続したメンバー。 */
    public record Profile(String sub, String name) {
    }
}
