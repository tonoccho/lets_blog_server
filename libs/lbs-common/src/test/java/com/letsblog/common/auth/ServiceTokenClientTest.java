package com.letsblog.common.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ServiceTokenClientの回帰テスト(#567)。KeycloakAdminClientTestと同様、
 * MockRestServiceServerでトークンエンドポイントとの通信を検証する。
 */
class ServiceTokenClientTest {

    private static final String TOKEN_URI = "http://keycloak-test/realms/letsblog/protocol/openid-connect/token";

    private RestClient.Builder builder;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
    }

    private ServiceTokenClient newClient() {
        return new ServiceTokenClient(builder, TOKEN_URI, "letsblog-services", "test-secret");
    }

    private void expectTokenRequest(String accessToken, long expiresInSeconds) {
        server.expect(requestTo(TOKEN_URI))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("grant_type=client_credentials")))
                .andExpect(content().string(containsString("client_id=letsblog-services")))
                .andExpect(content().string(containsString("client_secret=test-secret")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"" + accessToken + "\",\"expires_in\":" + expiresInSeconds + "}",
                        MediaType.APPLICATION_JSON));
    }

    @Test
    void getAccessToken_成功時にアクセストークンを返す() {
        expectTokenRequest("token-1", 60);
        ServiceTokenClient client = newClient();

        String token = client.getAccessToken();

        assertEquals("token-1", token);
        server.verify();
    }

    @Test
    void getAccessToken_有効期限内ならキャッシュを再利用する() {
        expectTokenRequest("token-1", 3600);
        ServiceTokenClient client = newClient();

        String first = client.getAccessToken();
        String second = client.getAccessToken();

        assertEquals("token-1", first);
        assertEquals("token-1", second);
        // トークンエンドポイントへのリクエストは1回だけ積んでいるため、2回目の呼び出しで
        // 再取得していれば server.verify() が失敗する。
        server.verify();
    }

    @Test
    void getAccessToken_期限切れ間近なら再取得する() {
        // MockRestServiceServerは最初のリクエストが送られた後に新しい期待値を追加できないため、
        // 2回分の期待値を先に積んでおく。
        expectTokenRequest("token-1", 0);
        expectTokenRequest("token-2", 60);
        ServiceTokenClient client = newClient();

        String first = client.getAccessToken();
        String second = client.getAccessToken();

        assertEquals("token-1", first);
        assertEquals("token-2", second);
        server.verify();
    }

    @Test
    void getAccessToken_接続不可なら例外になる() {
        server.expect(requestTo(TOKEN_URI))
                .andRespond(request -> {
                    throw new java.io.IOException("Connection refused");
                });
        ServiceTokenClient client = newClient();

        ServiceTokenUnavailableException exception =
                assertThrows(ServiceTokenUnavailableException.class, client::getAccessToken);

        org.hamcrest.MatcherAssert.assertThat(exception.getMessage(), containsString("letsblog-services"));
    }

    @Test
    void getAccessToken_エラー応答なら例外になる() {
        server.expect(requestTo(TOKEN_URI))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("invalid_client"));
        ServiceTokenClient client = newClient();

        assertThrows(ServiceTokenUnavailableException.class, client::getAccessToken);
    }

    @Test
    void getAccessToken_連続失敗でサーキットブレーカーが開き即座に失敗する() {
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        // 失敗しきい値(既定2)に達した後の呼び出しはHTTPリクエストを送らないため、
        // 3回目以降のserver.expect()は積まない。
        ServiceTokenClient client = new ServiceTokenClient(
                builder, TOKEN_URI, "letsblog-services", "test-secret", new CircuitBreaker(2, Duration.ofSeconds(30)));

        assertThrows(ServiceTokenUnavailableException.class, client::getAccessToken);
        assertThrows(ServiceTokenUnavailableException.class, client::getAccessToken);

        ServiceTokenUnavailableException thirdFailure =
                assertThrows(ServiceTokenUnavailableException.class, client::getAccessToken);
        org.hamcrest.MatcherAssert.assertThat(thirdFailure.getMessage(), containsString("サーキットブレーカー"));

        server.verify();
    }

    @Test
    void getAccessToken_クールダウン経過後は半開状態として再試行する() {
        // MockRestServiceServerは最初のリクエストが送られた後に新しい期待値を追加できないため、
        // 2回分(失敗→回復)の期待値を先に積んでおく。
        server.expect(requestTo(TOKEN_URI)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        expectTokenRequest("token-recovered", 60);
        ServiceTokenClient client = new ServiceTokenClient(
                builder, TOKEN_URI, "letsblog-services", "test-secret",
                new CircuitBreaker(1, Duration.ofMillis(1)));

        assertThrows(ServiceTokenUnavailableException.class, client::getAccessToken);

        // クールダウン(1ms)経過を待ってから、半開状態としての再試行が実際にHTTPを呼ぶことを確認する。
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String recovered = client.getAccessToken();

        assertEquals("token-recovered", recovered);
        server.verify();
    }
}
