package com.letsblog.platform.service;

import com.letsblog.platform.dto.ConnectedServiceStatusResponse.Status;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GitLabの到達性判定(issue #1796)。GET &lt;url&gt;/users/sign_in を認証なしで1回だけ(再試行しない)、
 * 3秒のタイムアウトで行い、2xxだけを正常とする。接続先はConnectionDestinationGuardを通さない。
 */
class GitLabReachabilityCheckerTest {

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
                if (responseStatus / 100 == 3) {
                    exchange.getResponseHeaders().add("Location", "/elsewhere");
                }
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

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void 応答が2xxなら正常でusers_sign_inへ認証なしのGETを1回だけ送る() {
        GitLabReachabilityChecker.Result result = new GitLabReachabilityChecker().check(url());

        assertEquals(Status.NORMAL, result.status());
        assertEquals(200, result.httpStatus());
        assertNull(result.errorMessage());
        assertEquals(url() + "/users/sign_in", result.targetUrl());
        assertEquals(1, requests.get());
        assertEquals(List.of("/users/sign_in"), paths);
        assertNull(authHeaders.get(0));
    }

    @Test
    void 末尾スラッシュ付きの接続先でも同じパスへ問い合わせる() {
        GitLabReachabilityChecker.Result result = new GitLabReachabilityChecker().check(url() + "//");

        assertEquals(Status.NORMAL, result.status());
        assertEquals(List.of("/users/sign_in"), paths);
    }

    @Test
    void 応答が2xxなら204でも正常() {
        responseStatus = 204;

        assertEquals(Status.NORMAL, new GitLabReachabilityChecker().check(url()).status());
    }

    @Test
    void 応答が4xxなら異常でHTTPステータスを理由に含める() {
        responseStatus = 404;

        GitLabReachabilityChecker.Result result = new GitLabReachabilityChecker().check(url());

        assertEquals(Status.ERROR, result.status());
        assertEquals(404, result.httpStatus());
        assertTrue(result.errorMessage().contains("GitLab"), result.errorMessage());
        assertTrue(result.errorMessage().contains("404"), result.errorMessage());
    }

    @Test
    void 応答が5xxなら異常でHTTPステータスを理由に含める() {
        responseStatus = 503;

        GitLabReachabilityChecker.Result result = new GitLabReachabilityChecker().check(url());

        assertEquals(Status.ERROR, result.status());
        assertEquals(503, result.httpStatus());
        assertTrue(result.errorMessage().contains("503"), result.errorMessage());
        assertEquals(1, requests.get());
    }

    @Test
    void 応答が3xxなら追従せず異常() {
        responseStatus = 302;

        GitLabReachabilityChecker.Result result = new GitLabReachabilityChecker().check(url());

        assertEquals(Status.ERROR, result.status());
        assertEquals(302, result.httpStatus());
        assertNotNull(result.errorMessage());
        assertEquals(1, requests.get());
    }

    @Test
    void 応答が遅れてタイムアウトしたら異常で理由を返し再試行しない() {
        delayMillis = 1500;
        long startedAt = System.currentTimeMillis();

        GitLabReachabilityChecker.Result result =
                new GitLabReachabilityChecker(Duration.ofMillis(300)).check(url());

        assertEquals(Status.ERROR, result.status());
        assertNull(result.httpStatus());
        assertTrue(result.errorMessage().contains("到達できません"), result.errorMessage());
        assertTrue(System.currentTimeMillis() - startedAt < 1400, "再試行せず1回のタイムアウトで返る");
        assertEquals(1, requests.get());
    }

    @Test
    void 接続できなければ異常で理由を返す() throws IOException {
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }

        GitLabReachabilityChecker.Result result =
                new GitLabReachabilityChecker().check("http://127.0.0.1:" + closedPort);

        assertEquals(Status.ERROR, result.status());
        assertNull(result.httpStatus());
        assertTrue(result.errorMessage().contains("到達できません"), result.errorMessage());
    }

    @Test
    void lbxのホスト名は接続先の検査で禁止されず名前解決の失敗として理由が出る() {
        GitLabReachabilityChecker.Result result =
                new GitLabReachabilityChecker(Duration.ofSeconds(3)).check("http://lbx-gitlab.invalid");

        assertEquals(Status.ERROR, result.status());
        assertTrue(result.errorMessage().contains("到達できません"), result.errorMessage());
        assertFalse(result.errorMessage().contains("禁止"), result.errorMessage());
    }

    @Test
    void loopbackも検査で拒否されない() {
        assertEquals(Status.NORMAL, new GitLabReachabilityChecker().check(url()).status());
    }

    @Test
    void URLとして解釈できない値でも例外にせず異常で理由を返す() {
        GitLabReachabilityChecker.Result result = new GitLabReachabilityChecker().check("ht tp://bad host");

        assertEquals(Status.ERROR, result.status());
        assertNotNull(result.errorMessage());
    }
}
