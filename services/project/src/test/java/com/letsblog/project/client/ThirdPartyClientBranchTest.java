package com.letsblog.project.client;

import com.letsblog.project.testsupport.FakeHttpServer;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部SNS(はてな・LinkedIn)のクライアントの、他のテストが通さない分岐(issue #1818)。
 * クライアントは実物で、HTTPの向こう(SNSのAPI)だけをフェイクHTTPサーバーに差し替える。
 */
class ThirdPartyClientBranchTest {

    private final FakeHttpServer http = new FakeHttpServer();

    @AfterEach
    void close() {
        http.close();
    }

    private HatenaApiClient hatena(String authorizeUrl) {
        String base = http.baseUrl();
        return new HatenaApiClient(base + "/oauth/initiate", base + "/oauth/token", authorizeUrl, base + "/profile");
    }

    @Test
    void hatena_認可画面のURLが既にクエリを持っていれば_を足す() {
        assertEquals("https://hatena.example/authorize?x=1&oauth_token=RT", hatena("https://hatena.example/authorize?x=1").authorizeUrl("RT"));
        assertEquals("https://hatena.example/authorize?oauth_token=RT", hatena("https://hatena.example/authorize").authorizeUrl("RT"));
    }

    @Test
    void hatena_署名はトークンの秘密があるときと無いときで別の値になる() {
        Map<String, String> params = Map.of("oauth_nonce", "n", "oauth_timestamp", "1", "scope", "read_public", "a b", "c&d");

        String withoutTokenSecret = HatenaApiClient.signature("POST", "https://x.example/a", params, "cs", null);
        String withTokenSecret = HatenaApiClient.signature("POST", "https://x.example/a", params, "cs", "ts");

        assertNotEquals(withoutTokenSecret, withTokenSecret);
        assertEquals(withoutTokenSecret, HatenaApiClient.signature("post", "https://x.example/a", params, "cs", null),
                "メソッドの大文字小文字は署名に影響しない");
    }

    @Test
    void linkedin_失敗の本文にエラーの項目が1つも無ければ_理由を付けずステータスだけを示す() {
        String base = http.baseUrl();
        LinkedinApiClient client = new LinkedinApiClient(base, base + "/oauth/v2/accessToken", "https://linkedin.example/authorize");
        http.status("POST", "/oauth/v2/accessToken", 400, "{\"unrelated\":\"x\"}");

        LinkedinApiException e = assertThrows(LinkedinApiException.class,
                () -> client.exchangeCode("cid", "csecret", "code", "https://l/cb"));

        assertTrue(e.getMessage().endsWith("(HTTP 400)"), e.getMessage());
    }

    @Test
    void linkedin_失敗の本文にmessageがあればそれを理由にする() {
        String base = http.baseUrl();
        LinkedinApiClient client = new LinkedinApiClient(base, base + "/oauth/v2/accessToken", "https://linkedin.example/authorize");
        http.status("POST", "/oauth/v2/accessToken", 400, "{\"message\":\"bad code\"}");

        LinkedinApiException e = assertThrows(LinkedinApiException.class,
                () -> client.exchangeCode("cid", "csecret", "code", "https://l/cb"));

        assertTrue(e.getMessage().contains(": bad code"), e.getMessage());
    }
}
