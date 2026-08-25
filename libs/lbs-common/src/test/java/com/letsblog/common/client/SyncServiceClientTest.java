package com.letsblog.common.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.letsblog.common.web.CorrelationIdFilter;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * SyncServiceClientの単体テスト(issue #581、C12)。受入基準「サーキットブレーカの動作がテストで
 * 検証されている」「下流サービス停止時に、呼び出し元がハングせず明確なエラーを返す」を、
 * 実際のHTTPサーバー(JDK標準の{@link HttpServer}/{@link ServerSocket})を使って検証する。
 */
class SyncServiceClientTest {

    private HttpServer httpServer;
    private ServerSocket rawServerSocket;

    @AfterEach
    void tearDown() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (rawServerSocket != null) {
            try {
                rawServerSocket.close();
            } catch (IOException ignored) {
                // テスト終了処理なので無視してよい
            }
        }
    }

    private SyncServiceClient.Builder freshBuilder(String baseUrl) {
        // 各テストで専用のCircuitBreaker/Retryレジストリを使い、テスト間で状態が漏れないようにする。
        return SyncServiceClient.builder(RestClient.builder(), "test-service", baseUrl)
                .circuitBreakerRegistry(CircuitBreakerRegistry.ofDefaults())
                .retryRegistry(RetryRegistry.ofDefaults());
    }

    @Test
    void 正常応答をそのまま返す() throws IOException {
        httpServer = startHttpServer(exchange -> respond(exchange, 200, "{\"value\":\"ok\"}"));
        SyncServiceClient client = freshBuilder(baseUrl(httpServer)).profile(SyncCallProfile.SHORT).build();

        Value result = client.get("/api/value", new Object[0], Value.class, h -> { });

        assertEquals("ok", result.value());
    }

    @Test
    void 呼び出し元スレッドのMDCにある相関IDを下流へヘッダとして転送する() throws IOException {
        AtomicReference<String> receivedHeader = new AtomicReference<>();
        httpServer = startHttpServer(exchange -> {
            receivedHeader.set(exchange.getRequestHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER));
            respond(exchange, 200, "{\"value\":\"ok\"}");
        });
        SyncServiceClient client = freshBuilder(baseUrl(httpServer)).profile(SyncCallProfile.SHORT).build();

        MDC.put(CorrelationIdFilter.MDC_KEY, "test-correlation-id");
        try {
            client.get("/api/value", new Object[0], Value.class, h -> { });
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }

        assertEquals("test-correlation-id", receivedHeader.get());
    }

    @Test
    void MDCに相関IDが無ければヘッダを付与しない() throws IOException {
        AtomicReference<String> receivedHeader = new AtomicReference<>();
        httpServer = startHttpServer(exchange -> {
            receivedHeader.set(exchange.getRequestHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER));
            respond(exchange, 200, "{\"value\":\"ok\"}");
        });
        SyncServiceClient client = freshBuilder(baseUrl(httpServer)).profile(SyncCallProfile.SHORT).build();

        client.get("/api/value", new Object[0], Value.class, h -> { });

        assertNull(receivedHeader.get());
    }

    @Test
    void クライアントエラーは4xxとして分類されリトライされない() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        httpServer = startHttpServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 400, "{\"error\":\"bad request\"}");
        });
        SyncServiceClient client = freshBuilder(baseUrl(httpServer)).profile(SyncCallProfile.SHORT).build();

        SyncServiceClientErrorException e = assertThrows(
                SyncServiceClientErrorException.class, () -> client.get("/api/value", new Object[0], Value.class, h -> { }));

        assertEquals(400, e.statusCode());
        assertEquals(1, requests.get(), "4xxはリトライ対象外なので1回しか呼ばれない");
    }

    @Test
    void サーバーエラーの連続でサーキットブレーカーがOPENになり以降は即座に失敗する() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        httpServer = startHttpServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 500, "{\"error\":\"boom\"}");
        });
        CircuitBreakerConfig smallWindow = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofMinutes(5))
                .recordExceptions(SyncServiceServerErrorException.class)
                .build();
        SyncServiceClient client = freshBuilder(baseUrl(httpServer))
                .profile(SyncCallProfile.SHORT)
                .circuitBreakerConfig(smallWindow)
                .retryConfig(RetryConfig.custom().maxAttempts(1).build())
                .build();

        for (int i = 0; i < 4; i++) {
            assertThrows(
                    SyncServiceServerErrorException.class, () -> client.get("/api/value", new Object[0], Value.class, h -> { }));
        }
        int requestsBeforeOpen = requests.get();
        assertEquals(4, requestsBeforeOpen);

        // 5回目以降はサーキットブレーカーがOPENのため、実際のHTTP呼び出しを行わずに即座に失敗する。
        assertThrows(
                SyncServiceCircuitOpenException.class, () -> client.get("/api/value", new Object[0], Value.class, h -> { }));
        assertThrows(
                SyncServiceCircuitOpenException.class, () -> client.get("/api/value", new Object[0], Value.class, h -> { }));

        assertEquals(requestsBeforeOpen, requests.get(), "OPEN中は下流サーバーへ到達しない");
    }

    @Test
    void クライアントエラーはサーキットブレーカーの失敗としてカウントされない() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        httpServer = startHttpServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 404, "{\"error\":\"not found\"}");
        });
        CircuitBreakerConfig smallWindow = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofMinutes(5))
                .recordExceptions(SyncServiceServerErrorException.class)
                .build();
        SyncServiceClient client =
                freshBuilder(baseUrl(httpServer)).profile(SyncCallProfile.SHORT).circuitBreakerConfig(smallWindow).build();

        for (int i = 0; i < 10; i++) {
            assertThrows(
                    SyncServiceClientErrorException.class, () -> client.get("/api/value", new Object[0], Value.class, h -> { }));
        }

        assertEquals(10, requests.get(), "4xxはサーキットブレーカーをOPENにしないので毎回下流へ到達する");
    }

    @Test
    void 応答が遅い下流に対してタイムアウトで打ち切りハングしない() throws IOException {
        httpServer = startHttpServer(exchange -> {
            try {
                Thread.sleep(8000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"value\":\"too-late\"}");
        });
        SyncServiceClient client = freshBuilder(baseUrl(httpServer))
                .profile(SyncCallProfile.SHORT) // 読み取りタイムアウト5秒
                .retryConfig(RetryConfig.custom().maxAttempts(1).build())
                .build();

        long start = System.nanoTime();
        assertThrows(
                SyncServiceTimeoutException.class, () -> client.get("/api/value", new Object[0], Value.class, h -> { }));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertTrue(
                elapsedMillis < 8000,
                "下流が8秒応答しなくても、設定した読み取りタイムアウト(5秒、リトライ無効)で打ち切られ、"
                        + "8秒待たされることはないはず。実際: " + elapsedMillis + "ms");
    }

    @Test
    void 接続不能な下流には即座に通信断として失敗する() throws IOException {
        rawServerSocket = new ServerSocket(0);
        int port = rawServerSocket.getLocalPort();
        rawServerSocket.close(); // 誰も listen していないポートへ接続させる(connection refused)
        SyncServiceClient client = freshBuilder("http://localhost:" + port)
                .profile(SyncCallProfile.SHORT)
                .retryConfig(RetryConfig.custom().maxAttempts(1).build())
                .build();

        assertThrows(
                SyncServiceUnavailableException.class, () -> client.get("/api/value", new Object[0], Value.class, h -> { }));
    }

    @Test
    void GETは一時的な通信断からリトライして成功する() throws IOException {
        rawServerSocket = new ServerSocket(0);
        int port = rawServerSocket.getLocalPort();
        AtomicInteger attempts = new AtomicInteger();
        Thread serverThread = new Thread(() -> acceptAndRespondAfterFailures(rawServerSocket, attempts, 2));
        serverThread.setDaemon(true);
        serverThread.start();

        SyncServiceClient client = SyncServiceClient
                .builder(RestClient.builder(), "flaky-service", "http://localhost:" + port)
                .circuitBreakerRegistry(CircuitBreakerRegistry.ofDefaults())
                .retryRegistry(RetryRegistry.ofDefaults())
                .profile(SyncCallProfile.SHORT)
                .build();

        Value result = client.get("/api/value", new Object[0], Value.class, h -> { });

        assertEquals("ok", result.value());
        assertEquals(3, attempts.get(), "2回失敗した後の3回目(既定maxAttempts=3)で成功する");
    }

    @Test
    void POSTは応答が無くてもリトライせず1回で諦める() throws IOException {
        // GETのリトライ確認(接続断からの復帰)とは異なるシナリオを使う: JDKのHttpClientは
        // 「リクエストのバイト列が1つも送信できていないまま接続が切れた」場合、非冪等なメソッドでも
        // トランスポート層で自動的に再接続を試みることがある(RestClient/resilience4jの外側の挙動)。
        // 「リクエストは送信できたがサーバーが応答しない(タイムアウト)」ケースであれば、この
        // トランスポート層の自動再接続は起こらないため、応用層(resilience4j Retry)がPOSTを
        // リトライしていないことを確実に検証できる。
        AtomicInteger requests = new AtomicInteger();
        httpServer = startHttpServer(exchange -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(8000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"value\":\"too-late\"}");
        });
        SyncServiceClient client = freshBuilder(baseUrl(httpServer)).profile(SyncCallProfile.SHORT).build();

        long start = System.nanoTime();
        assertThrows(SyncServiceTimeoutException.class,
                () -> client.post("/api/value", new Object[0], java.util.Map.of("a", "b"), Value.class, h -> { }));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertEquals(1, requests.get(), "POSTは非冪等なのでタイムアウトしても再試行しない");
        assertTrue(elapsedMillis < 8000, "リトライしていれば8秒を大きく超えるはず。実際: " + elapsedMillis + "ms");
    }

    /** 最初のfailuresCount回は接続を即座に切断し、それ以降は有効なHTTPレスポンスを返す簡易サーバー。 */
    private void acceptAndRespondAfterFailures(ServerSocket serverSocket, AtomicInteger attempts, int failuresCount) {
        try {
            while (!serverSocket.isClosed()) {
                try (Socket socket = serverSocket.accept()) {
                    int n = attempts.incrementAndGet();
                    if (n <= failuresCount) {
                        // リクエストを読まずに即座に切断(RST/EOF)し、クライアント側でIOExceptionを起こさせる。
                        socket.setSoLinger(true, 0);
                        continue;
                    }
                    // 最小限のHTTPリクエストを読み飛ばしてから、有効なレスポンスを返す。
                    consumeRequest(socket);
                    writeSimpleJsonResponse(socket, "{\"value\":\"ok\"}");
                }
            }
        } catch (IOException e) {
            // サーバースレッド終了(テストのtearDownでソケットをcloseした場合等)。テスト失敗はassertionで検出される。
        }
    }

    private void consumeRequest(Socket socket) throws IOException {
        java.io.InputStream in = socket.getInputStream();
        byte[] buffer = new byte[4096];
        // ヘッダー終端(\r\n\r\n)まで読む簡易実装。リクエストボディの有無はこのテストでは考慮しない。
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, 1);
            if (read == -1) {
                break;
            }
            total += read;
            if (total >= 4 && endsWithHeaderTerminator(buffer, total)) {
                break;
            }
        }
    }

    private boolean endsWithHeaderTerminator(byte[] buffer, int length) {
        return buffer[length - 4] == '\r' && buffer[length - 3] == '\n'
                && buffer[length - 2] == '\r' && buffer[length - 1] == '\n';
    }

    private void writeSimpleJsonResponse(Socket socket, String jsonBody) throws IOException {
        byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private HttpServer startHttpServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private String baseUrl(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private record Value(String value) {
    }
}
