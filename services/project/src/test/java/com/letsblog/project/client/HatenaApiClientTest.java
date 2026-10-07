package com.letsblog.project.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * はてな OAuth 1.0a のクライアント(issue #1582)。リクエストトークンの取得・認可 URL・アクセストークンへの交換・自分の情報の取得。
 * HMAC-SHA1 の署名は、公開されているテストベクタと、このテスト内で独立に計算した署名の両方で確かめる。
 */
class HatenaApiClientTest {

    private HttpServer server;
    private final AtomicReference<String> method = new AtomicReference<>();
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> requestUrl = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HatenaApiClient client(int status, String response) throws IOException {
        return client(status, response, "application/x-www-form-urlencoded");
    }

    /** 自分の情報(my.json)は実際のはてなと同じく application/json で返す。リクエストトークン・アクセストークンは form。 */
    private HatenaApiClient jsonClient(int status, String response) throws IOException {
        return client(status, response, "application/json");
    }

    private HatenaApiClient client(int status, String response, String responseContentType) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestUrl.set("http://localhost:" + server.getAddress().getPort() + exchange.getRequestURI().getPath());
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", responseContentType);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        String base = "http://localhost:" + server.getAddress().getPort();
        return new HatenaApiClient(RestClient.builder(), base + "/oauth/initiate", base + "/oauth/token",
                "https://www.hatena.example/oauth/authorize", base + "/applications/my.json");
    }

    private static HatenaApiClient unreachable() {
        return new HatenaApiClient(RestClient.builder(), "http://localhost:1/oauth/initiate", "http://localhost:1/oauth/token",
                "https://h.example/a", "http://localhost:1/applications/my.json");
    }

    // ---- 署名 ----

    @Test
    void 署名は公開されているテストベクタと一致する() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("include_entities", "true");
        params.put("oauth_consumer_key", "xvz1evFS4wEEPTGEFPHBog");
        params.put("oauth_nonce", "kYjzVBB8Y0ZFabxSWbWovY3uYSQ2pTgmZeNu2VS4cg");
        params.put("oauth_signature_method", "HMAC-SHA1");
        params.put("oauth_timestamp", "1318622958");
        params.put("oauth_token", "370773112-GmHxMAgYyLbNEtIKZeRNFsMKPR9EyMZeS9weJAEb");
        params.put("oauth_version", "1.0");
        params.put("status", "Hello Ladies + Gentlemen, a signed OAuth request!");

        String signature = HatenaApiClient.signature("POST", "https://api.twitter.com/1.1/statuses/update.json", params,
                "kAcSOqF21Fu85e7zjz7ZN2U4ZRhfV3WpwPAoE3Z7kBw", "LswwdoUaIvS8ltyTt5jkRh4J50vUPVVHtR2YPi5kE");

        assertEquals("hCtSmYh+iHYCEqBWrE7C7hYmtUk=", signature);
    }

    /** このテスト内の独立した検証: Authorization ヘッダを分解し、本文のパラメータと合わせて署名を計算し直す。 */
    private void assertSigned(String consumerSecret, String tokenSecret, Map<String, String> formParams) throws Exception {
        Map<String, String> oauth = parseHeader(auth.get());
        String given = oauth.remove("oauth_signature");
        assertNotNull(given);
        Map<String, String> all = new TreeMap<>(formParams);
        all.putAll(oauth);
        StringBuilder normalized = new StringBuilder();
        for (Map.Entry<String, String> e : all.entrySet()) {
            if (normalized.length() > 0) {
                normalized.append('&');
            }
            normalized.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        String base = method.get() + "&" + enc(requestUrl.get()) + "&" + enc(normalized.toString());
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec((enc(consumerSecret) + "&" + enc(tokenSecret)).getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        String expected = Base64.getEncoder().encodeToString(mac.doFinal(base.getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, given, "署名が一致しない");
        assertEquals("HMAC-SHA1", oauth.get("oauth_signature_method"));
        assertEquals("1.0", oauth.get("oauth_version"));
        assertFalse(oauth.get("oauth_nonce").isBlank());
        assertTrue(oauth.get("oauth_timestamp").matches("\\d{10}"));
    }

    private static Map<String, String> parseHeader(String header) {
        assertTrue(header.startsWith("OAuth "), header);
        Map<String, String> map = new TreeMap<>();
        Matcher m = Pattern.compile("(oauth_[a-z_]+)=\"([^\"]*)\"").matcher(header);
        while (m.find()) {
            map.put(m.group(1), URLDecoder.decode(m.group(2).replace("+", "%2B"), StandardCharsets.UTF_8));
        }
        return map;
    }

    private static Map<String, String> parseForm(String form) {
        Map<String, String> map = new TreeMap<>();
        if (form == null || form.isEmpty()) {
            return map;
        }
        for (String pair : form.split("&")) {
            String[] kv = pair.split("=", 2);
            map.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        return map;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    // ---- リクエストトークン ----

    @Test
    void リクエストトークンをcallbackとscope付きで署名して取得する() throws Exception {
        HatenaApiClient client = client(200, "oauth_token=RT%2B1&oauth_token_secret=RS%3D&oauth_callback_confirmed=true");

        HatenaApiClient.RequestToken token = client.fetchRequestToken("ck", "cs-secret", "https://l/cb?state=7.abc");

        assertEquals("RT+1", token.token());
        assertEquals("RS=", token.secret());
        assertEquals("POST", method.get());
        assertEquals("/oauth/initiate", path.get());
        assertTrue(contentType.get().startsWith("application/x-www-form-urlencoded"));
        Map<String, String> form = parseForm(body.get());
        assertEquals("read_public,write_public", form.get("scope"));
        Map<String, String> oauth = parseHeader(auth.get());
        assertEquals("ck", oauth.get("oauth_consumer_key"));
        assertEquals("https://l/cb?state=7.abc", oauth.get("oauth_callback"));
        assertFalse(oauth.containsKey("oauth_token"));
        assertSigned("cs-secret", "", form);
        assertFalse(auth.get().contains("cs-secret"));
        assertFalse(body.get().contains("cs-secret"));
    }

    @Test
    void リクエストトークンが返らなければ失敗する() throws IOException {
        HatenaApiClient noToken = client(200, "oauth_callback_confirmed=true");
        assertThrows(HatenaApiException.class, () -> noToken.fetchRequestToken("ck", "cs", "https://l/cb"));
        server.stop(0);
        HatenaApiClient noSecret = client(200, "oauth_token=RT");
        assertThrows(HatenaApiException.class, () -> noSecret.fetchRequestToken("ck", "cs", "https://l/cb"));
        server.stop(0);
        HatenaApiClient empty = client(200, "");
        assertThrows(HatenaApiException.class, () -> empty.fetchRequestToken("ck", "cs", "https://l/cb"));
    }

    @Test
    void 拒否されたら理由を含め_秘密は含めない() throws IOException {
        HatenaApiClient client = client(401, "oauth_problem=consumer_key_unknown");

        HatenaApiException e = assertThrows(HatenaApiException.class,
                () -> client.fetchRequestToken("ck", "secret-value", "https://l/cb"));

        assertTrue(e.getMessage().contains("HTTP 401"));
        assertTrue(e.getMessage().contains("consumer_key_unknown"));
        assertFalse(e.getMessage().contains("secret-value"));
    }

    @Test
    void 拒否の本文が空でも長くても失敗として扱い_理由は200文字に切る() throws IOException {
        HatenaApiClient empty = client(500, "");
        assertEquals("はてなのリクエストトークンの取得に失敗しました(HTTP 500)",
                assertThrows(HatenaApiException.class, () -> empty.fetchRequestToken("ck", "cs", "r")).getMessage());
        server.stop(0);
        HatenaApiClient long1 = client(400, "oauth_problem=" + "d".repeat(500));
        HatenaApiException e = assertThrows(HatenaApiException.class, () -> long1.fetchRequestToken("ck", "cs", "r"));
        assertTrue(e.getMessage().contains("d".repeat(200)));
        assertFalse(e.getMessage().contains("d".repeat(201)));
        server.stop(0);
        HatenaApiClient plain = client(502, "Bad Gateway");
        assertTrue(assertThrows(HatenaApiException.class, () -> plain.fetchRequestToken("ck", "cs", "r")).getMessage().contains("HTTP 502"));
    }

    // ---- 認可 URL ----

    @Test
    void 認可URLにリクエストトークンを載せる() {
        HatenaApiClient client = new HatenaApiClient(RestClient.builder(), "https://www.hatena.example/oauth/initiate",
                "https://www.hatena.example/oauth/token", "https://www.hatena.example/oauth/authorize", "https://n.hatena.example/my.json");

        URI uri = URI.create(client.authorizeUrl("RT+1"));

        assertEquals("www.hatena.example", uri.getHost());
        assertEquals("/oauth/authorize", uri.getPath());
        assertEquals("oauth_token=RT%2B1", uri.getRawQuery());
    }

    // ---- アクセストークン ----

    @Test
    void verifierとリクエストトークンの秘密で署名してアクセストークンに交換する() throws Exception {
        HatenaApiClient client = client(200, "oauth_token=AT&oauth_token_secret=ATS&url_name=u&display_name=d");

        HatenaApiClient.AccessToken token = client.fetchAccessToken("ck", "cs-secret", "RT", "RS-secret", "the-verifier");

        assertEquals("AT", token.token());
        assertEquals("ATS", token.secret());
        assertEquals("POST", method.get());
        assertEquals("/oauth/token", path.get());
        Map<String, String> oauth = parseHeader(auth.get());
        assertEquals("RT", oauth.get("oauth_token"));
        assertEquals("the-verifier", oauth.get("oauth_verifier"));
        assertSigned("cs-secret", "RS-secret", parseForm(body.get()));
        assertFalse(auth.get().contains("RS-secret"));
    }

    @Test
    void アクセストークンが返らなければ失敗する() throws IOException {
        HatenaApiClient noToken = client(200, "oauth_token=AT");
        assertThrows(HatenaApiException.class, () -> noToken.fetchAccessToken("ck", "cs", "RT", "RS", "v"));
        server.stop(0);
        HatenaApiClient empty = client(200, "");
        assertThrows(HatenaApiException.class, () -> empty.fetchAccessToken("ck", "cs", "RT", "RS", "v"));
        server.stop(0);
        HatenaApiClient denied = client(401, "oauth_problem=verifier_invalid");
        assertTrue(assertThrows(HatenaApiException.class, () -> denied.fetchAccessToken("ck", "cs", "RT", "RS", "v"))
                .getMessage().contains("verifier_invalid"));
    }

    // ---- 自分の情報 ----

    @Test
    void 自分の情報をアクセストークンで署名して取得し_表示名を返す() throws Exception {
        HatenaApiClient client = jsonClient(200, "{\"url_name\":\"user1\",\"display_name\":\"Let's Blog\",\"profile_image_url\":\"x\"}");

        HatenaApiClient.Profile profile = client.fetchProfile("ck", "cs-secret", "AT", "ATS-secret");

        assertEquals("Let's Blog", profile.name());
        assertEquals("GET", method.get());
        assertEquals("/applications/my.json", path.get());
        assertEquals("AT", parseHeader(auth.get()).get("oauth_token"));
        assertSigned("cs-secret", "ATS-secret", Map.of());
    }

    @Test
    void 表示名が無ければurl_nameを名前にし_どちらも無ければ失敗する() throws IOException {
        HatenaApiClient urlName = jsonClient(200, "{\"url_name\":\"user1\"}");
        assertEquals("user1", urlName.fetchProfile("ck", "cs", "AT", "ATS").name());
        server.stop(0);
        HatenaApiClient blankDisplay = jsonClient(200, "{\"url_name\":\"user1\",\"display_name\":\"\"}");
        assertEquals("user1", blankDisplay.fetchProfile("ck", "cs", "AT", "ATS").name());
        server.stop(0);
        HatenaApiClient none = jsonClient(200, "{}");
        assertThrows(HatenaApiException.class, () -> none.fetchProfile("ck", "cs", "AT", "ATS"));
        server.stop(0);
        HatenaApiClient empty = jsonClient(200, "");
        assertThrows(HatenaApiException.class, () -> empty.fetchProfile("ck", "cs", "AT", "ATS"));
        server.stop(0);
        HatenaApiClient rejected = client(401, "oauth_problem=token_rejected");
        assertTrue(assertThrows(HatenaApiException.class, () -> rejected.fetchProfile("ck", "cs", "AT", "ATS"))
                .getMessage().contains("HTTP 401"));
    }

    // ---- 接続できない ----

    @Test
    void 接続できなければ失敗し_メッセージに秘密を含めない() {
        HatenaApiClient client = unreachable();

        HatenaApiException e1 = assertThrows(HatenaApiException.class, () -> client.fetchRequestToken("ck", "secret-value", "r"));
        HatenaApiException e2 = assertThrows(HatenaApiException.class, () -> client.fetchAccessToken("ck", "secret-value", "RT", "RS", "v"));
        HatenaApiException e3 = assertThrows(HatenaApiException.class, () -> client.fetchProfile("ck", "secret-value", "AT", "ATS"));

        for (HatenaApiException e : new HatenaApiException[] {e1, e2, e3}) {
            assertTrue(e.getMessage().contains("接続できません"));
            assertFalse(e.getMessage().contains("secret-value"));
            assertFalse(String.valueOf(e.getCause()).contains("secret-value"), "原因の例外を保持しない");
        }
    }

    @Test
    void トークンの値は文字列表現に出ない() {
        assertFalse(new HatenaApiClient.RequestToken("RT-VALUE", "RS-VALUE").toString().contains("RT-VALUE"));
        assertFalse(new HatenaApiClient.RequestToken("RT-VALUE", "RS-VALUE").toString().contains("RS-VALUE"));
        assertFalse(new HatenaApiClient.AccessToken("AT-VALUE", "ATS-VALUE").toString().contains("AT-VALUE"));
        assertFalse(new HatenaApiClient.AccessToken("AT-VALUE", "ATS-VALUE").toString().contains("ATS-VALUE"));
    }
}
