package com.letsblog.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.repository.GenerationJobRepository;
import com.letsblog.common.net.ConnectionDestinationGuard;
import com.letsblog.common.net.ForbiddenDestinationException;
import com.letsblog.common.net.GuardedTarget;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OllamaPullJobRunner(issue #1675)。実際のHTTPサーバ(JDK HttpServer)をOllamaに見立て、
 * POST /api/pull のストリーミング応答(NDJSON)から、ジョブの進捗・完了・失敗が書き込まれることを確かめる。
 * 失敗はいずれも例外にせず、ジョブをfailedで終わらせる(runningのまま残さない)。
 */
@ExtendWith(MockitoExtension.class)
class OllamaPullJobRunnerTest {

    @Mock
    private GenerationJobRepository repository;
    @Mock
    private ConnectionDestinationGuard guard;

    private HttpServer server;
    private String root;
    private final List<String> requestLines = new CopyOnWriteArrayList<>();
    private final List<String> snapshots = new ArrayList<>();
    private GenerationJob job;
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    @BeforeEach
    void setUp() throws IOException {
        job = new GenerationJob();
        job.setId(11L);
        job.setType(OllamaPullService.JOB_TYPE);
        job.setStatus("running");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        root = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private OllamaPullJobRunner runner() {
        return runner(clock::get);
    }

    private OllamaPullJobRunner runner(LongSupplier millis) {
        return runner(millis, Duration.ofMinutes(5));
    }

    private OllamaPullJobRunner runner(LongSupplier millis, Duration idleTimeout) {
        when(repository.findById(11L)).thenReturn(Optional.of(job));
        when(repository.save(any(GenerationJob.class))).thenAnswer(invocation -> {
            GenerationJob saved = invocation.getArgument(0);
            snapshots.add(saved.getStatus() + "|" + saved.getResultPayload());
            return saved;
        });
        return new OllamaPullJobRunner(repository, new ObjectMapper(), guard, millis, idleTimeout);
    }

    /** /api/pull に、与えた行をNDJSONで返す。 */
    private void respond(int status, String... lines) {
        server.createContext("/api/pull", exchange -> {
            requestLines.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            requestLines.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
    }

    /** 呼ぶたびに1秒進む時計。進捗の書き込み間隔(500ms)を毎回越える。 */
    private OllamaPullJobRunner steppingRunner() {
        AtomicLong now = new AtomicLong();
        return runner(() -> now.addAndGet(1_000));
    }

    /** 進捗を1行流したあと、次の行を送らずに止まる(egressが途中で切れた、Ollamaが固まった)。 */
    private void respondThenStall(CountDownLatch release) {
        server.createContext("/api/pull", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("{\"status\":\"pulling manifest\"}\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
    }

    private String last() {
        return snapshots.get(snapshots.size() - 1);
    }

    @Test
    void 成功まで読むと進捗を反映してdoneで終わり_モデル名をPOSTする() {
        respond(200,
                "{\"status\":\"pulling manifest\"}",
                "{\"status\":\"pulling abc\",\"digest\":\"sha256:abc\",\"total\":1000,\"completed\":250}",
                "{\"status\":\"success\"}");

        steppingRunner().run(11L, root, false, "qwen2.5:7b");

        assertEquals("POST /api/pull", requestLines.get(0));
        assertTrue(requestLines.get(1).contains("\"model\":\"qwen2.5:7b\""), requestLines.get(1));
        assertTrue(requestLines.get(1).contains("\"stream\":true"), requestLines.get(1));
        assertTrue(snapshots.stream().anyMatch(s -> s.startsWith("running|")
                && s.contains("\"percent\":25") && s.contains("\"bytesDone\":250") && s.contains("\"bytesTotal\":1000")), snapshots.toString());
        assertTrue(snapshots.stream().anyMatch(s -> s.startsWith("running|") && s.contains("pulling manifest")), snapshots.toString());
        assertEquals("done|{\"success\":\"true\"}", last());
        verifyNoInteractions(guard);
    }

    @Test
    void ストリームが途中で止まったらアイドルタイムアウトでfailedにし_スレッドを解放する() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        respondThenStall(release);
        OllamaPullJobRunner stalled = runner(clock::get, Duration.ofMillis(300));

        long started = System.nanoTime();
        stalled.run(11L, root, false, "llama3");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        release.countDown();

        assertTrue(elapsedMs < 10_000, "止まったストリームで戻らない: " + elapsedMs + "ms");
        assertTrue(last().startsWith("failed|"), last());
        assertTrue(last().contains("途絶"), last());
    }

    @Test
    void 接続先の末尾のv1と末尾スラッシュは除いたルートに対して呼ぶ() {
        respond(200, "{\"status\":\"success\"}");

        runner().run(11L, root + "/v1/", false, "llama3");

        assertEquals("POST /api/pull", requestLines.get(0));
        assertEquals("done|{\"success\":\"true\"}", last());
    }

    @Test
    void 進捗の書き込みは間隔を空け_最後のdoneは必ず書く() {
        respond(200,
                "{\"status\":\"pulling a\",\"total\":100,\"completed\":10}",
                "{\"status\":\"pulling a\",\"total\":100,\"completed\":20}",
                "{\"status\":\"success\"}");

        runner().run(11L, root, false, "llama3");

        long runningWrites = snapshots.stream().filter(s -> s.startsWith("running|")).count();
        assertEquals(1, runningWrites, snapshots.toString());
        assertEquals("done|{\"success\":\"true\"}", last());
    }

    @Test
    void 間隔が空けば次の進捗も書く() {
        respond(200,
                "{\"status\":\"pulling a\",\"total\":100,\"completed\":10}",
                "{\"status\":\"pulling a\",\"total\":100,\"completed\":20}",
                "{\"status\":\"success\"}");
        steppingRunner().run(11L, root, false, "llama3");

        assertEquals(2, snapshots.stream().filter(s -> s.startsWith("running|")).count(), snapshots.toString());
    }

    @Test
    void totalが無い_0_またはcompletedが無い行は百分率を持たない_読めない行は無視する() {
        respond(200,
                "not json at all",
                "",
                "{\"status\":\"verifying\",\"total\":0,\"completed\":0}",
                "{\"total\":50}",
                "{\"status\":\"success\"}");
        steppingRunner().run(11L, root, false, "llama3");

        assertTrue(snapshots.stream().noneMatch(s -> s.contains("\"percent\":") && !s.contains("\"percent\":null")), snapshots.toString());
        assertTrue(snapshots.stream().anyMatch(s -> s.contains("verifying")), snapshots.toString());
        assertEquals("done|{\"success\":\"true\"}", last());
    }

    @Test
    void ストリーム中のerror行はfailedで終わり理由を残す() {
        respond(200,
                "{\"status\":\"pulling manifest\"}",
                "{\"error\":\"pull model manifest: file does not exist\"}");

        runner().run(11L, root, false, "no-such-model");

        assertTrue(last().startsWith("failed|"), last());
        assertTrue(last().contains("pull model manifest: file does not exist"), last());
        assertFalse(snapshots.stream().anyMatch(s -> s.startsWith("done|")));
    }

    @Test
    void 成功行が来ないままストリームが終わったらfailedにする() {
        respond(200, "{\"status\":\"pulling manifest\"}");

        runner().run(11L, root, false, "llama3");

        assertTrue(last().startsWith("failed|"), last());
        assertTrue(last().contains("完了"), last());
    }

    @Test
    void HTTPエラーはボディのerrorを理由にし_読めなければステータスを理由にする() {
        respond(404, "{\"error\":\"model not found\"}");
        runner().run(11L, root, false, "x");
        assertTrue(last().startsWith("failed|") && last().contains("model not found"), last());
    }

    @Test
    void HTTPエラーのボディがJSONでなければステータスコードを理由にする() {
        respond(500, "boom");
        runner().run(11L, root, false, "x");
        assertTrue(last().startsWith("failed|") && last().contains("500"), last());
    }

    @Test
    void HTTPエラーのボディが空でもステータスコードを理由にする() {
        respond(502);
        runner().run(11L, root, false, "x");
        assertTrue(last().startsWith("failed|") && last().contains("502"), last());
    }

    @Test
    void HTTPエラーのJSONにerrorが無ければステータスコードを理由にする() {
        respond(503, "{\"message\":\"x\"}");
        runner().run(11L, root, false, "x");
        assertTrue(last().startsWith("failed|") && last().contains("503"), last());
    }

    @Test
    void 接続先に届かなければfailedで接続できなかった旨を理由にする() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            closedPort = socket.getLocalPort();
        }

        runner().run(11L, "http://127.0.0.1:" + closedPort + "/v1", false, "llama3");

        assertTrue(last().startsWith("failed|"), last());
        assertTrue(last().contains("接続できません"), last());
    }

    @Test
    void プロジェクトの上書きは宛先検査を通し_検査済みのアドレスへ接続する() {
        respond(200, "{\"status\":\"success\"}");
        when(guard.check("Ollama", "http://override.example:11434")).thenReturn(new GuardedTarget(root, null));

        runner().run(11L, "http://override.example:11434/v1", true, "llama3");

        verify(guard).check("Ollama", "http://override.example:11434");
        assertEquals("POST /api/pull", requestLines.get(0));
        assertEquals("done|{\"success\":\"true\"}", last());
    }

    @Test
    void 宛先検査が拒否したら接続せずfailedにして理由を残す() {
        when(guard.check("Ollama", "http://169.254.169.254")).thenThrow(new ForbiddenDestinationException("禁止された宛先です"));

        runner().run(11L, "http://169.254.169.254/v1", true, "llama3");

        assertTrue(last().startsWith("failed|"), last());
        assertTrue(last().contains("禁止された宛先です"), last());
    }

    @Test
    void ジョブが見つからなければ何も書かず例外にもしない() {
        when(repository.findById(11L)).thenReturn(Optional.empty());
        respond(200, "{\"status\":\"success\"}");

        new OllamaPullJobRunner(repository, new ObjectMapper(), guard, clock::get).run(11L, root, false, "llama3");

        verify(repository, never()).save(any());
    }
}
