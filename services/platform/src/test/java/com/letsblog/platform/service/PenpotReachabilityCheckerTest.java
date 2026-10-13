package com.letsblog.platform.service;

import com.letsblog.common.net.ConnectionDestinationGuard;
import com.letsblog.common.net.GuardedTarget;
import com.letsblog.platform.dto.ConnectedServiceStatusResponse.Status;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部Penpotの到達性判定(issue #1774)。接続先はConnectionDestinationGuardで検査したアドレスへ固定し、
 * 認証なしの軽量なGETを1回だけ(再試行しない)、短いタイムアウトで行う。実際のHTTPサーバー(loopback)で確かめる。
 */
class PenpotReachabilityCheckerTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final List<String> paths = new ArrayList<>();
    private final List<String> authHeaders = new ArrayList<>();
    private volatile int responseStatus = 200;
    private volatile long delayMillis = 0;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            paths.add(exchange.getRequestURI().getPath());
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                Thread.sleep(delayMillis);
                exchange.sendResponseHeaders(responseStatus, -1);
            } catch (InterruptedException | IOException e) {
                // クライアントがタイムアウトで切断済み
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String loopbackUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static ConnectionDestinationGuard guardResolvingTo(String address) {
        return new ConnectionDestinationGuard(
                h -> new InetAddress[] {InetAddress.getByName(address)}, List::of, () -> false);
    }

    /** ガードの検査結果は無視してloopbackのテスト用サーバーへ繋ぐ(ガード自体は別のテストで確かめる)。 */
    private PenpotReachabilityChecker checkerToLoopback(Duration timeout, List<String> pinned) {
        return new PenpotReachabilityChecker(guardResolvingTo("192.168.1.50"), timeout, target -> {
            pinned.add(target.baseUrl());
            return RestClient.builder().baseUrl(loopbackUrl()).build();
        });
    }

    @Test
    void 到達できればNORMALで認証情報なしのGETを1回だけ送る() {
        List<String> pinned = new ArrayList<>();

        PenpotReachabilityChecker.Result result =
                checkerToLoopback(Duration.ofSeconds(3), pinned).check("http://penpot.lan:9001");

        assertEquals(Status.NORMAL, result.status());
        assertEquals(200, result.httpStatus());
        assertNull(result.errorMessage());
        assertEquals("http://penpot.lan:9001/", result.targetUrl());
        assertEquals(1, requests.get());
        assertEquals(List.of("/"), paths);
        assertNull(authHeaders.get(0));
        // 検査したアドレスへ固定したURLで接続する
        assertEquals(List.of("http://192.168.1.50:9001/"), pinned);
    }

    @Test
    void 末尾スラッシュ付きの接続先でも同じ判定になる() {
        PenpotReachabilityChecker.Result result = checkerToLoopback(Duration.ofSeconds(3), new ArrayList<>())
                .check("http://penpot.lan:9001/");

        assertEquals(Status.NORMAL, result.status());
        assertEquals("http://penpot.lan:9001/", result.targetUrl());
    }

    @Test
    void 応答4xxもも到達できているのでNORMAL() {
        responseStatus = 404;

        PenpotReachabilityChecker.Result result =
                checkerToLoopback(Duration.ofSeconds(3), new ArrayList<>()).check("http://penpot.lan:9001");

        assertEquals(Status.NORMAL, result.status());
        assertEquals(404, result.httpStatus());
    }

    @Test
    void 応答5xxは動いているが異常なのでWARNING() {
        responseStatus = 503;

        PenpotReachabilityChecker.Result result =
                checkerToLoopback(Duration.ofSeconds(3), new ArrayList<>()).check("http://penpot.lan:9001");

        assertEquals(Status.WARNING, result.status());
        assertEquals(503, result.httpStatus());
        assertNotNull(result.errorMessage());
        assertEquals(1, requests.get());
    }

    @Test
    void 応答が遅れてタイムアウトしたらERRORで理由を返し再試行しない() {
        delayMillis = 1500;
        long startedAt = System.currentTimeMillis();

        Duration timeout = Duration.ofMillis(300);
        PenpotReachabilityChecker checker = new PenpotReachabilityChecker(
                guardResolvingTo("192.168.1.50"), timeout,
                target -> PenpotReachabilityChecker.pinnedClient(new GuardedTarget(loopbackUrl() + "/", null), timeout));

        PenpotReachabilityChecker.Result result = checker.check("http://penpot.lan:9001");

        assertEquals(Status.ERROR, result.status());
        assertNull(result.httpStatus());
        assertTrue(result.errorMessage().contains("Penpot"), result.errorMessage());
        assertEquals("http://penpot.lan:9001/", result.targetUrl());
        assertTrue(System.currentTimeMillis() - startedAt < 1400, "再試行せず1回のタイムアウトで返る");
        assertEquals(1, requests.get());
    }

    @Test
    void 接続できなければERRORで理由を返す() throws IOException {
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        PenpotReachabilityChecker checker = new PenpotReachabilityChecker(
                guardResolvingTo("192.168.1.50"), Duration.ofSeconds(3),
                target -> RestClient.builder().baseUrl("http://127.0.0.1:" + closedPort).build());

        PenpotReachabilityChecker.Result result = checker.check("http://penpot.lan:9001");

        assertEquals(Status.ERROR, result.status());
        assertNull(result.httpStatus());
        assertTrue(result.errorMessage().contains("到達できません"), result.errorMessage());
    }

    @Test
    void ガードに拒否された接続先は接続を試みずERRORで理由を返す() {
        PenpotReachabilityChecker checker = new PenpotReachabilityChecker(
                guardResolvingTo("127.0.0.1"), Duration.ofSeconds(3), target -> {
                    throw new AssertionError("拒否された接続先へは接続しない");
                });

        PenpotReachabilityChecker.Result result = checker.check("http://penpot.lan:9001");

        assertEquals(Status.ERROR, result.status());
        assertNull(result.httpStatus());
        assertTrue(result.errorMessage().contains("禁止された宛先"), result.errorMessage());
        assertEquals(0, requests.get());
    }

    @Test
    void 名前解決できない接続先はERRORで理由を返す() {
        PenpotReachabilityChecker checker = new PenpotReachabilityChecker(
                new ConnectionDestinationGuard(h -> {
                    throw new UnknownHostException(h);
                }, List::of, () -> false),
                Duration.ofSeconds(3), target -> {
                    throw new AssertionError("名前解決できない接続先へは接続しない");
                });

        PenpotReachabilityChecker.Result result = checker.check("http://nowhere.invalid:9001");

        assertEquals(Status.ERROR, result.status());
        assertTrue(result.errorMessage().contains("名前解決できない"), result.errorMessage());
    }

    @Test
    void 既定のクライアントは検査したアドレスへ固定して接続しタイムアウトを守る() {
        RestClient client = PenpotReachabilityChecker.pinnedClient(
                new GuardedTarget(loopbackUrl() + "/", null), Duration.ofSeconds(3));

        assertEquals(200, client.get().uri("/").retrieve().toBodilessEntity().getStatusCode().value());

        delayMillis = 1500;
        RestClient slow = PenpotReachabilityChecker.pinnedClient(
                new GuardedTarget(loopbackUrl() + "/", null), Duration.ofMillis(300));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.web.client.RestClientException.class,
                () -> slow.get().uri("/").retrieve().toBodilessEntity());
    }

    @Test
    void 本番の既定コンストラクタで組み立てられ禁止アドレスは拒否する() {
        PenpotReachabilityChecker checker = new PenpotReachabilityChecker();

        PenpotReachabilityChecker.Result result = checker.check("http://127.0.0.1:9001");

        assertEquals(Status.ERROR, result.status());
    }
}
