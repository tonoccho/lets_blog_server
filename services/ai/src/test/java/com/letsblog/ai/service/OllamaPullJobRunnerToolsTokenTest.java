package com.letsblog.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.repository.GenerationJobRepository;
import com.letsblog.common.auth.ServiceTokenUnavailableException;
import com.letsblog.common.auth.ToolsTokenProvider;
import com.letsblog.common.net.ConnectionDestinationGuard;
import com.letsblog.ai.testsupport.FakeHttpServer;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** モデルのpull(JDKのHttpClientを直接使う経路)でextensionsのOllamaへ付けるトークン(#1804)。 */
class OllamaPullJobRunnerToolsTokenTest {

    private final GenerationJobRepository repository = mock(GenerationJobRepository.class);
    /** 宛先検査(実物。ループバックは必ず拒否する)。名前解決は実物のDNS、インタフェース列挙とコンテナ判定は固定(issue #1823)。 */
    private final ConnectionDestinationGuard guard =
            new ConnectionDestinationGuard(java.net.InetAddress::getAllByName, List::of, () -> false);
    private FakeHttpServer lan;
    private final GenerationJob job = new GenerationJob();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String root;

    @BeforeEach
    void setUp() throws Exception {
        job.setId(11L);
        job.setType(OllamaPullService.JOB_TYPE);
        job.setStatus("running");
        when(repository.findById(11L)).thenReturn(Optional.of(job));
        when(repository.save(any(GenerationJob.class))).thenAnswer(invocation -> invocation.getArgument(0));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/pull", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            authorizations.add(header == null ? "(none)" : header);
            byte[] body = "{\"status\":\"success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        root = "http://127.0.0.1:" + server.getAddress().getPort();
        // 宛先検査を通る(ループバックでない)アドレスで待ち受けるサーバー。プロジェクトの上書き先に見立てる。
        lan = FakeHttpServer.onAllInterfaces();
        lan.on("POST", "/api/pull", request -> {
            String header = request.header("Authorization");
            authorizations.add(header == null ? "(none)" : header);
            return FakeHttpServer.Response.bytes(200, "application/x-ndjson", "{\"status\":\"success\"}".getBytes(StandardCharsets.UTF_8));
        });
    }

    @AfterEach
    void tearDown() {
        lan.close();
        server.stop(0);
    }

    private OllamaPullJobRunner runner(ToolsTokenProvider tools) {
        return new OllamaPullJobRunner(repository, new ObjectMapper(), guard, System::currentTimeMillis,
                Duration.ofMinutes(5), tools);
    }

    @Test
    @DisplayName("システム設定の接続先へのpullに、専用クライアントのトークンが付く")
    void システム設定の接続先にはトークンが付く() {
        runner(ToolsTokenProvider.fromSupplier(() -> "tok-1")).run(11L, root, false, "llama3");

        assertThat(authorizations).containsExactly("Bearer tok-1");
        assertThat(job.getStatus()).isEqualTo("done");
    }

    @Test
    @DisplayName("プロジェクト単位の上書き先へのpullには付かない")
    void 上書き先には付かない() {
        runner(ToolsTokenProvider.fromSupplier(() -> "tok-1")).run(11L, lan.lanBaseUrl(), true, "llama3");

        assertThat(authorizations).containsExactly("(none)");
        assertThat(job.getStatus()).isEqualTo("done");
        assertThat(lan.requests("POST", "/api/pull")).hasSize(1);
    }

    @Test
    @DisplayName("secret未設定(無効)なら付かない")
    void 無効なら付かない() {
        runner(ToolsTokenProvider.disabled()).run(11L, root, false, "llama3");

        assertThat(authorizations).containsExactly("(none)");
        assertThat(job.getStatus()).isEqualTo("done");
    }

    @Test
    @DisplayName("トークンが取れないときは、送らずにジョブをfailedにして理由を残す")
    void 取れないときはジョブが失敗する() {
        runner(ToolsTokenProvider.fromSupplier(() -> {
            throw new ServiceTokenUnavailableException("サービストークンの取得に失敗しました(client_id=letsblog-tools-client): 401");
        })).run(11L, root, false, "llama3");

        assertThat(authorizations).isEmpty();
        assertThat(job.getStatus()).isEqualTo("failed");
        assertThat(job.getResultPayload()).contains("KEYCLOAK_TOOLS_CLIENT_SECRET").contains("401");
    }
}
