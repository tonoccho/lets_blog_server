package com.letsblog.project.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.project.config.LegacyJacksonRestClientConfig;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * はてなの OAuth 1.0a(リクエストトークンの取得 → 認可画面 → アクセストークンへの交換)と、接続したアカウント名を調べる
 * 自分の情報の取得のクライアント(issue #1582)。署名は HMAC-SHA1(RFC 5849)。受け取ったアクセストークンとその秘密は、
 * 呼び出し側({@code SnsHatenaService})が本番サイトのプラグインへ送るためだけに使い、ここでも保存しない。
 * 記事のブックマークの投稿は WordPress のプラグイン側で行うので、ここには無い。
 *
 * <p>各 URL は設定で差し替えられる(受け入れテストの hatena-stub 向け)。リクエストトークンの取得で要求する scope は
 * {@code read_public,write_public}(自分の情報の取得とブックマークの追加)。
 */
@Component
public class HatenaApiClient {

    static final String SCOPE = "read_public,write_public";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RestClient client;
    private final String requestTokenUrl;
    private final String accessTokenUrl;
    private final String authorizeUrl;
    private final String profileUrl;

    @Autowired
    public HatenaApiClient(
            @Value("${app.hatena-request-token-url}") String requestTokenUrl,
            @Value("${app.hatena-access-token-url}") String accessTokenUrl,
            @Value("${app.hatena-authorize-url}") String authorizeUrl,
            @Value("${app.hatena-profile-url}") String profileUrl) {
        this(RestClient.builder(), requestTokenUrl, accessTokenUrl, authorizeUrl, profileUrl);
    }

    /** テスト専用: RestClient.Builderを直接受け取るコンストラクタ。 */
    HatenaApiClient(RestClient.Builder builder, String requestTokenUrl, String accessTokenUrl, String authorizeUrl,
            String profileUrl) {
        LegacyJacksonRestClientConfig.preferJackson2(builder);
        this.client = builder.build();
        this.requestTokenUrl = requestTokenUrl;
        this.accessTokenUrl = accessTokenUrl;
        this.authorizeUrl = authorizeUrl;
        this.profileUrl = profileUrl;
    }

    /** リクエストトークンを取得する。callbackUrl は認可のあと利用者のブラウザが戻る先(state を含められる)。 */
    public RequestToken fetchRequestToken(String consumerKey, String consumerSecret, String callbackUrl) {
        Map<String, String> form = Map.of("scope", SCOPE);
        Map<String, String> extra = Map.of("oauth_callback", callbackUrl);
        Map<String, String> response = form("リクエストトークンの取得", requestTokenUrl, form,
                authorization("POST", requestTokenUrl, form, consumerKey, consumerSecret, null, "", extra));
        String token = response.get("oauth_token");
        String secret = response.get("oauth_token_secret");
        if (isBlank(token) || isBlank(secret)) {
            throw new HatenaApiException("はてなからリクエストトークンを取得できませんでした");
        }
        return new RequestToken(token, secret);
    }

    /** はてなの認可画面の URL。利用者のブラウザをここへ遷移させる。 */
    public String authorizeUrl(String requestToken) {
        return authorizeUrl + (authorizeUrl.contains("?") ? "&" : "?") + "oauth_token=" + encode(requestToken);
    }

    /** 認可で得た verifier とリクエストトークン(とその秘密)を、アクセストークンとその秘密に交換する。 */
    public AccessToken fetchAccessToken(String consumerKey, String consumerSecret, String requestToken,
            String requestTokenSecret, String verifier) {
        Map<String, String> extra = Map.of("oauth_verifier", verifier);
        Map<String, String> response = form("アクセストークンの取得", accessTokenUrl, Map.of(),
                authorization("POST", accessTokenUrl, Map.of(), consumerKey, consumerSecret, requestToken,
                        requestTokenSecret, extra));
        String token = response.get("oauth_token");
        String secret = response.get("oauth_token_secret");
        if (isBlank(token) || isBlank(secret)) {
            throw new HatenaApiException("はてなからアクセストークンを取得できませんでした");
        }
        return new AccessToken(token, secret);
    }

    /** 接続したアカウントの表示名(display_name、無ければ url_name)。 */
    public Profile fetchProfile(String consumerKey, String consumerSecret, String accessToken,
            String accessTokenSecret) {
        JsonNode body;
        try {
            body = client.get()
                    .uri(profileUrl)
                    .header("Authorization", authorization("GET", profileUrl, Map.of(), consumerKey, consumerSecret,
                            accessToken, accessTokenSecret, Map.of()))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientResponseException e) {
            throw failure("自分の情報の取得", e);
        } catch (RestClientException e) {
            throw new HatenaApiException("はてなに接続できません(自分の情報の取得)");
        }
        String name = text(body, "display_name");
        if (name == null) {
            name = text(body, "url_name");
        }
        if (name == null) {
            throw new HatenaApiException("はてなからアカウント名を取得できませんでした");
        }
        return new Profile(name);
    }

    /** 署名つきの POST(form 本文)を送り、応答の form(oauth_token=...&...)を読む。 */
    private Map<String, String> form(String what, String url, Map<String, String> formParams, String authorization) {
        String text;
        try {
            text = client.post()
                    .uri(url)
                    .header("Authorization", authorization)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(formBody(formParams))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw failure(what, e);
        } catch (RestClientException e) {
            throw new HatenaApiException("はてなに接続できません(" + what + ")");
        }
        return parseForm(text);
    }

    private static String formBody(Map<String, String> params) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (out.length() > 0) {
                out.append('&');
            }
            out.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        return out.toString();
    }

    private static Map<String, String> parseForm(String text) {
        Map<String, String> map = new LinkedHashMap<>();
        if (isBlank(text)) {
            return map;
        }
        for (String pair : text.split("&")) {
            String[] kv = pair.split("=", 2);
            map.put(decode(kv[0]), kv.length > 1 ? decode(kv[1]) : "");
        }
        return map;
    }

    /** OAuth 1.0a の Authorization ヘッダ。秘密(consumer secret・トークンの秘密)は署名の計算にだけ使い、載せない。 */
    private static String authorization(String method, String url, Map<String, String> formParams, String consumerKey,
            String consumerSecret, String token, String tokenSecret, Map<String, String> extra) {
        Map<String, String> oauth = new TreeMap<>(extra);
        oauth.put("oauth_consumer_key", consumerKey);
        oauth.put("oauth_nonce", HexFormat.of().formatHex(randomBytes()));
        oauth.put("oauth_signature_method", "HMAC-SHA1");
        oauth.put("oauth_timestamp", String.valueOf(Instant.now().getEpochSecond()));
        oauth.put("oauth_version", "1.0");
        if (token != null) {
            oauth.put("oauth_token", token);
        }
        Map<String, String> signed = new LinkedHashMap<>(formParams);
        signed.putAll(oauth);
        oauth.put("oauth_signature", signature(method, url, signed, consumerSecret, tokenSecret));
        StringBuilder header = new StringBuilder("OAuth ");
        boolean first = true;
        for (Map.Entry<String, String> e : oauth.entrySet()) {
            if (!first) {
                header.append(", ");
            }
            first = false;
            header.append(e.getKey()).append("=\"").append(encode(e.getValue())).append('"');
        }
        return header.toString();
    }

    /** HMAC-SHA1 の署名(RFC 5849 §3.4)。params は署名の対象すべて(oauth_* と本文・クエリのパラメータ。oauth_signature は除く)。 */
    static String signature(String method, String url, Map<String, String> params, String consumerSecret,
            String tokenSecret) {
        // 名前と値を先にエンコードしてから並べる(RFC 5849 §3.4.1.3.2)。エンコード後の名前が同じなら値の順。
        List<String[]> pairs = new ArrayList<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            pairs.add(new String[] {encode(e.getKey()), encode(e.getValue())});
        }
        pairs.sort((a, b) -> a[0].equals(b[0]) ? a[1].compareTo(b[1]) : a[0].compareTo(b[0]));
        StringBuilder normalized = new StringBuilder();
        for (String[] pair : pairs) {
            if (normalized.length() > 0) {
                normalized.append('&');
            }
            normalized.append(pair[0]).append('=').append(pair[1]);
        }
        String base = method.toUpperCase() + "&" + encode(url) + "&" + encode(normalized.toString());
        String key = encode(consumerSecret) + "&" + encode(tokenSecret == null ? "" : tokenSecret);
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(base.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA1 が使えません", e);
        }
    }

    /** RFC 3986 のパーセントエンコード(unreserved 以外を %XX にする)。 */
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("*", "%2A")
                .replace("%7E", "~");
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static byte[] randomBytes() {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        return raw;
    }

    private static String text(JsonNode body, String field) {
        String value = body == null ? null : body.path(field).asText(null);
        return isBlank(value) ? null : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 失敗の理由として使う短い説明。はてなが返した oauth_problem だけで、送った値は含めない。 */
    private static HatenaApiException failure(String what, RestClientResponseException e) {
        StringBuilder message = new StringBuilder("はてなの").append(what).append("に失敗しました(HTTP ")
                .append(e.getStatusCode().value());
        String reason = parseForm(e.getResponseBodyAsString()).get("oauth_problem");
        if (!isBlank(reason)) {
            message.append(": ").append(reason.length() > 200 ? reason.substring(0, 200) : reason);
        }
        return new HatenaApiException(message.append(")").toString());
    }

    /** リクエストトークンとその秘密。呼び出し側が認可の間だけ持つ。値は誤ってログや例外に出ないよう、表示しない。 */
    public record RequestToken(String token, String secret) {
        @Override
        public String toString() {
            return "RequestToken[...]";
        }
    }

    /** アクセストークンとその秘密。呼び出し側が本番サイトへ送るためだけに持つ。値は表示しない。 */
    public record AccessToken(String token, String secret) {
        @Override
        public String toString() {
            return "AccessToken[...]";
        }
    }

    /** 接続したアカウント。 */
    public record Profile(String name) {
    }
}
