package com.letsblog.media.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.media.client.CmsBridgeClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #1249: ガベージコレクションの削除ループは、ジョブ起動時のユーザーBearerトークンではなく
 * media-service自身のClient Credentialsトークン({@link ServiceTokenClient})で
 * publishing-serviceを呼ぶ。実際のCmsBridgeClientを実HTTPサーバーに向けて配線を固定する
 * ({@code SyncServiceClient}は独自のrequestFactoryを持つためMockRestServiceServerは使えない)。
 */
class MediaGarbageCollectionJobRunnerServiceTokenTest {

    private HttpServer server;
    private final Map<String, String> authByMediaId = new ConcurrentHashMap<>();
    private final List<String> requestedMediaIds = new CopyOnWriteArrayList<>();
    private volatile List<String> unauthorizedMediaIds = List.of();

    private GenerationJobClient generationJobClient;
    private AuditLogService auditLogService;
    private MediaGarbageCollectionJobRunner runner;
    private ListAppender<ILoggingEvent> logs;
    private Logger runnerLogger;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/publishing/projects/1/media/", this::respondDelete);
        server.start();

        ServiceTokenClient serviceTokenClient = Mockito.mock(ServiceTokenClient.class);
        when(serviceTokenClient.getAccessToken()).thenReturn("service-token-1249");
        CmsBridgeClient cmsBridgeClient = new CmsBridgeClient(
                RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(), serviceTokenClient);

        generationJobClient = Mockito.mock(GenerationJobClient.class);
        auditLogService = Mockito.mock(AuditLogService.class);
        runner = new MediaGarbageCollectionJobRunner(
                cmsBridgeClient, generationJobClient, auditLogService, new ObjectMapper());

        runnerLogger = (Logger) LoggerFactory.getLogger(MediaGarbageCollectionJobRunner.class);
        logs = new ListAppender<>();
        logs.start();
        runnerLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        runnerLogger.detachAppender(logs);
        server.stop(0);
    }

    private void respondDelete(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String mediaId = path.substring(path.lastIndexOf('/') + 1);
        requestedMediaIds.add(mediaId);
        authByMediaId.put(mediaId, String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        boolean unauthorized = unauthorizedMediaIds.contains(mediaId);
        byte[] body = unauthorized ? "{\"error\":\"expired\"}".getBytes() : new byte[0];
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(unauthorized ? 401 : 204, unauthorized ? body.length : -1);
        if (unauthorized) {
            exchange.getResponseBody().write(body);
        }
        exchange.close();
    }

    @Test
    void 削除リクエストのAuthorizationはServiceTokenClientのトークンである() {
        runner.runDelete(123L, 1L, "local", List.of("10", "20"), 9L, "keycloak-sub-1");

        assertThat(authByMediaId)
                .containsEntry("10", "Bearer service-token-1249")
                .containsEntry("20", "Bearer service-token-1249");
        verify(generationJobClient).updateStatus(eq(123L), eq("done"), any());
    }

    @Test
    void 一部のメディアが401でも残りの削除を続け_ステータス付きで記録し_終端状態を通知する() {
        unauthorizedMediaIds = List.of("20");

        runner.runDelete(123L, 1L, "local", List.of("10", "20", "30"), 9L, "keycloak-sub-1");

        assertThat(requestedMediaIds).containsExactly("10", "20", "30");

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(123L), eq("done"), payload.capture());
        assertThat(payload.getValue())
                .contains("\"deletedCount\":2")
                .contains("\"failedCount\":1")
                .contains("401");

        ArgumentCaptor<String> auditPayload = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).log(eq(9L), eq("keycloak-sub-1"), eq(AuditLogService.ACTION_MEDIA_GARBAGE_COLLECTED),
                eq("PROJECT"), eq(1L), auditPayload.capture(), eq(null), eq(null));
        assertThat(auditPayload.getValue()).contains("\"20\":").contains("401");

        List<ILoggingEvent> warns = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage()).contains("123").contains("20").contains("401");
    }

    @Test
    void 全件が401ならfailedで終端通知する() {
        unauthorizedMediaIds = List.of("10", "20");

        runner.runDelete(123L, 1L, "local", List.of("10", "20"), 9L, "keycloak-sub-1");

        assertThat(requestedMediaIds).containsExactly("10", "20");
        verify(generationJobClient).updateStatus(eq(123L), eq("failed"), any());
    }
}
