package com.letsblog.project.client;

import com.letsblog.common.client.ExternalCallLoggingInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.project.config.LegacyJacksonRestClientConfig;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Facebook(Graph API)の OAuth 認可コードフロー(認可 URL → ユーザートークン → 長期ユーザートークン → 管理しているページ)の
 * クライアント(issue #1580)。個人アカウントには投稿しないので、長期ユーザートークンで {@code /me/accounts} を呼び、
 * ページごとのトークン(長期ユーザートークンから得たものは期限が無い)を受け取る。受け取ったトークンは呼び出し側
 * ({@code SnsFacebookService})が本番サイトのプラグインへ送るためだけに使い、ここでも保存しない。
 *
 * <p>API のベース URL と認可画面の URL は設定で差し替えられる(受け入れテストの facebook-stub 向け)。
 */
@Component
public class FacebookApiClient {

    /** ページへ投稿するための権限。pages_manage_posts は本番(Advanced Access)ではアプリ審査が要る。 */
    static final String SCOPE = "pages_show_list,pages_manage_posts,pages_read_engagement";

    private final RestClient client;
    private final String authorizeUrl;

    @Autowired
    public FacebookApiClient(
            @Value("${app.facebook-api-base-url}") String apiBaseUrl,
            @Value("${app.facebook-authorize-url}") String authorizeUrl) {
        this(RestClient.builder(), apiBaseUrl, authorizeUrl);
    }

    /** テスト専用: RestClient.Builderを直接受け取るコンストラクタ。 */
    FacebookApiClient(RestClient.Builder builder, String apiBaseUrl, String authorizeUrl) {
        LegacyJacksonRestClientConfig.preferJackson2(builder);
        builder.requestInterceptor(new ExternalCallLoggingInterceptor("facebook-api"));
        this.client = builder.baseUrl(apiBaseUrl).build();
        this.authorizeUrl = authorizeUrl;
    }

    /** Facebook の認可画面の URL。利用者のブラウザをここへ遷移させる。 */
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

    /** 認可コードをユーザートークン(短期)に交換する。 */
    public String exchangeCode(String clientId, String clientSecret, String code, String redirectUri) {
        JsonNode body = getToken("Facebook のトークン交換", "トークン交換",
                "client_id", clientId, "client_secret", clientSecret, "code", code, "redirect_uri", redirectUri);
        String token = text(body, "access_token");
        if (token == null) {
            throw new FacebookApiException("Facebook からアクセストークンを取得できませんでした");
        }
        return token;
    }

    /** 短期のユーザートークンを長期(約60日)に交換する。ページのトークンは、この長期トークンで取得したものが無期限になる。 */
    public String exchangeLongLived(String clientId, String clientSecret, String shortLivedToken) {
        JsonNode body = getToken("Facebook の長期トークンへの交換", "長期トークンへの交換",
                "grant_type", "fb_exchange_token", "client_id", clientId, "client_secret", clientSecret,
                "fb_exchange_token", shortLivedToken);
        String token = text(body, "access_token");
        if (token == null) {
            throw new FacebookApiException("Facebook から長期トークンを取得できませんでした");
        }
        return token;
    }

    /** 利用者が管理しているページ(ID・名前・ページのトークン)。トークンを持たないページは投稿先にできないので除く。 */
    public List<Page> listPages(String userToken) {
        JsonNode body;
        try {
            body = client.get()
                    .uri(uriBuilder -> uriBuilder.path("/me/accounts").queryParam("fields", "id,name,access_token").build())
                    .header("Authorization", "Bearer " + userToken)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw failure("Facebook のページ一覧の取得", e);
        } catch (RestClientException e) {
            throw new FacebookApiException("Facebook に接続できません(ページ一覧の取得)");
        }
        List<Page> pages = new ArrayList<>();
        JsonNode data = body == null ? null : body.path("data");
        if (data != null && data.isArray()) {
            for (JsonNode item : data) {
                String id = text(item, "id");
                String name = text(item, "name");
                String token = text(item, "access_token");
                if (id != null && token != null) {
                    pages.add(new Page(id, name == null ? id : name, token));
                }
            }
        }
        return pages;
    }

    private JsonNode getToken(String failedWhat, String connectWhat, String... queryPairs) {
        try {
            return client.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/oauth/access_token");
                        for (int i = 0; i < queryPairs.length; i += 2) {
                            uriBuilder.queryParam(queryPairs[i], queryPairs[i + 1]);
                        }
                        return uriBuilder.build();
                    })
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw failure(failedWhat, e);
        } catch (RestClientException e) {
            throw new FacebookApiException("Facebook に接続できません(" + connectWhat + ")");
        }
    }

    private static String text(JsonNode body, String field) {
        String value = body == null ? null : body.path(field).asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    /** 失敗の理由として使う短い説明。Facebook の応答の error.message だけで、送った値は含めない。 */
    private static FacebookApiException failure(String what, RestClientResponseException e) {
        StringBuilder message = new StringBuilder(what).append("に失敗しました(HTTP ").append(e.getStatusCode().value());
        try {
            JsonNode body = e.getResponseBodyAs(JsonNode.class);
            String reason = body == null ? null : body.path("error").path("message").asText(null);
            if (reason != null && !reason.isBlank()) {
                message.append(": ").append(reason.length() > 200 ? reason.substring(0, 200) : reason);
            }
        } catch (RuntimeException ignored) {
            // 本文がJSONでなければ理由は付けない。
        }
        return new FacebookApiException(message.append(")").toString());
    }

    /** 管理しているページ。ページのトークンを持つので、値は表示しない。 */
    public record Page(String id, String name, String accessToken) {
        @Override
        public String toString() {
            return "Page[id=" + id + ", name=" + name + "]";
        }
    }
}
