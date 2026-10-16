package com.letsblog.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.RestClient;

/**
 * SyncServiceClientの分岐の穴埋め(#1831)。HTTPのサーバー側は実際のJDK {@link HttpServer}、
 * 通信路の失敗は{@link RestClient}のインターセプタが投げる{@link IOException}で再現する(通信層だけを差し替える)。
 */
class SyncServiceClientBranchTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (server != null) {
            server.stop(0);
        }
    }

    private String start(int status, String body, AtomicReference<String> correlationHeader) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (correlationHeader != null) {
                correlationHeader.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static SyncServiceClient client(RestClient.Builder restClient, String baseUrl) {
        return SyncServiceClient.builder(restClient, "branch-service", baseUrl)
                .circuitBreakerRegistry(CircuitBreakerRegistry.ofDefaults())
                .retryRegistry(RetryRegistry.ofDefaults())
                .build();
    }

    private static RestClient.Builder failingWith(IOException failure) {
        return RestClient.builder().requestInterceptor((request, body, execution) -> {
            throw failure;
        });
    }

    @Test
    void headersがnullでも呼び出せる() throws IOException {
        String baseUrl = start(200, "ok", null);

        String result = client(RestClient.builder(), baseUrl).get("/x", new Object[0], String.class, null);

        assertThat(result).isEqualTo("ok");
    }

    @Test
    void 空白だけの相関IDは転送しない() throws IOException {
        AtomicReference<String> received = new AtomicReference<>();
        String baseUrl = start(200, "ok", received);
        MDC.put("correlationId", "   ");

        client(RestClient.builder(), baseUrl).get("/x", new Object[0], String.class, h -> { });

        assertThat(received.get()).isNull();
    }

    @Test
    void 本文が空の4xxは例外メッセージにステータスの説明を使う() throws IOException {
        String baseUrl = start(404, "", null);

        assertThatThrownBy(() -> client(RestClient.builder(), baseUrl).get("/x", new Object[0], String.class, h -> { }))
                .isInstanceOf(SyncServiceClientErrorException.class)
                .hasMessageContaining("404");
    }

    @Test
    void 本文つきの4xxは本文を例外メッセージに使う() throws IOException {
        String baseUrl = start(404, "no such thing", null);

        assertThatThrownBy(() -> client(RestClient.builder(), baseUrl).get("/x", new Object[0], String.class, h -> { }))
                .isInstanceOf(SyncServiceClientErrorException.class)
                .hasMessageContaining("no such thing");
    }

    @Test
    void SocketTimeoutExceptionはタイムアウトとして扱う() {
        assertThatThrownBy(() -> client(failingWith(new SocketTimeoutException("read")), "http://127.0.0.1:1")
                .get("/x", new Object[0], String.class, h -> { }))
                .isInstanceOf(SyncServiceTimeoutException.class);
    }

    @Test
    void HttpTimeoutExceptionはタイムアウトとして扱う() {
        assertThatThrownBy(() -> client(failingWith(new HttpTimeoutException("t")), "http://127.0.0.1:1")
                .get("/x", new Object[0], String.class, h -> { }))
                .isInstanceOf(SyncServiceTimeoutException.class);
    }

    @Test
    void クラス名にTimeoutを含む例外もタイムアウトとして扱う() {
        assertThatThrownBy(() -> client(failingWith(new SlowTimeoutFailure()), "http://127.0.0.1:1")
                .get("/x", new Object[0], String.class, h -> { }))
                .isInstanceOf(SyncServiceTimeoutException.class);
    }

    @Test
    void タイムアウトではないIOExceptionは到達不能として扱う() {
        assertThatThrownBy(() -> client(failingWith(new IOException("reset")), "http://127.0.0.1:1")
                .get("/x", new Object[0], String.class, h -> { }))
                .isInstanceOf(SyncServiceUnavailableException.class);
    }

    private static final class SlowTimeoutFailure extends IOException {
        SlowTimeoutFailure() {
            super("slow");
        }
    }
}
