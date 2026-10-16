package com.letsblog.common.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * extensionsのツール(Ollama・ComfyUI・PlantUML・ポータル)を呼ぶときに付けるトークンの供給元(#1804)。
 * secretが無ければ付けない(従来どおり)、あるのに取れなければ理由の分かる例外にする(黙って外さない)。
 */
class ToolsTokenProviderTest {

    private static ServiceTokenClient clientReturning(String token, AtomicInteger calls) {
        return new ServiceTokenClient(RestClient.builder(), "http://keycloak/token", "letsblog-tools-client", "s") {
            @Override
            public synchronized String getAccessToken() {
                calls.incrementAndGet();
                return token;
            }
        };
    }

    private static ServiceTokenClient clientFailing(RuntimeException failure) {
        return new ServiceTokenClient(RestClient.builder(), "http://keycloak/token", "letsblog-tools-client", "s") {
            @Override
            public synchronized String getAccessToken() {
                throw failure;
            }
        };
    }

    @Test
    @DisplayName("無効(secret未設定)ではAuthorizationを返さず、ヘッダーも付けない")
    void 無効ではヘッダーを付けない() {
        ToolsTokenProvider provider = ToolsTokenProvider.disabled();

        assertThat(provider.enabled()).isFalse();
        assertThat(provider.authorization()).isEmpty();
        HttpHeaders headers = new HttpHeaders();
        provider.apply(headers);
        assertThat(headers.containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();
    }

    @Test
    @DisplayName("クライアントがnullなら無効として扱う")
    void nullは無効() {
        assertThat(ToolsTokenProvider.of(null).enabled()).isFalse();
    }

    @Test
    @DisplayName("有効ならBearerを返し、ヘッダーへ付ける")
    void 有効ならBearerを付ける() {
        AtomicInteger calls = new AtomicInteger();
        ToolsTokenProvider provider = ToolsTokenProvider.of(clientReturning("tok-1", calls));

        assertThat(provider.enabled()).isTrue();
        assertThat(provider.authorization()).contains("Bearer tok-1");
        HttpHeaders headers = new HttpHeaders();
        provider.apply(headers);
        assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer tok-1");
    }

    @Test
    @DisplayName("取得に失敗したら、原因を含む専用の例外にする(ヘッダー無しで続けない)")
    void 取得失敗は専用の例外() {
        ServiceTokenUnavailableException cause = new ServiceTokenUnavailableException("サービストークンの取得に失敗しました(client_id=letsblog-tools-client): 401");
        ToolsTokenProvider provider = ToolsTokenProvider.of(clientFailing(cause));

        assertThatThrownBy(provider::authorization)
                .isInstanceOf(ToolsTokenUnavailableException.class)
                .hasMessageContaining("KEYCLOAK_TOOLS_CLIENT_SECRET")
                .hasMessageContaining("client_id=letsblog-tools-client")
                .hasCause(cause);
        assertThatThrownBy(() -> provider.apply(new HttpHeaders()))
                .isInstanceOf(ToolsTokenUnavailableException.class);
    }

    @Test
    @DisplayName("トークンの取得で想定外の例外が出ても、専用の例外にして理由を残す")
    void 想定外の例外も専用の例外() {
        ToolsTokenProvider provider = ToolsTokenProvider.of(clientFailing(new IllegalStateException("boom")));

        assertThatThrownBy(provider::authorization)
                .isInstanceOf(ToolsTokenUnavailableException.class)
                .hasMessageContaining("boom");
    }

    @Test
    @DisplayName("供給元(Supplier)から作ったプロバイダーも同じ振る舞いをする(呼び出し側のテスト用)")
    void 供給元から作れる() {
        assertThat(ToolsTokenProvider.fromSupplier(() -> "tok-s").authorization()).contains("Bearer tok-s");
        assertThatThrownBy(() -> ToolsTokenProvider.fromSupplier(() -> {
            throw new ServiceTokenUnavailableException("down");
        }).authorization()).isInstanceOf(ToolsTokenUnavailableException.class).hasMessageContaining("down");
    }

    private static final class Recorded {
        HttpHeaders headers;
        boolean executed;
    }

    private static Recorded intercept(ClientHttpRequestInterceptor interceptor) throws IOException {
        Recorded recorded = new Recorded();
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://tools/x"));
        ClientHttpRequestExecution execution = (req, body) -> {
            recorded.headers = req.getHeaders();
            recorded.executed = true;
            return new MockClientHttpResponse(new byte[0], 200);
        };
        try (ClientHttpResponse ignored = interceptor.intercept(new HttpRequestWrapper(request), new byte[0], execution)) {
            return recorded;
        }
    }

    @Test
    @DisplayName("インターセプターは、有効なら各リクエストにBearerを付ける")
    void インターセプターは有効ならBearerを付ける() throws IOException {
        Recorded recorded = intercept(ToolsTokenProvider.of(clientReturning("tok-2", new AtomicInteger())).interceptor());

        assertThat(recorded.executed).isTrue();
        assertThat(recorded.headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer tok-2");
    }

    @Test
    @DisplayName("インターセプターは、無効ならヘッダーを付けずに送る")
    void インターセプターは無効なら素通し() throws IOException {
        Recorded recorded = intercept(ToolsTokenProvider.disabled().interceptor());

        assertThat(recorded.executed).isTrue();
        assertThat(recorded.headers.containsHeader(HttpHeaders.AUTHORIZATION)).isFalse();
    }

    @Test
    @DisplayName("インターセプターは、取得に失敗したらリクエストを送らずに例外にする")
    void インターセプターは失敗したら送らない() {
        ToolsTokenProvider provider = ToolsTokenProvider.of(clientFailing(new ServiceTokenUnavailableException("down")));

        assertThatThrownBy(() -> intercept(provider.interceptor()))
                .isInstanceOf(ToolsTokenUnavailableException.class);
    }
}
