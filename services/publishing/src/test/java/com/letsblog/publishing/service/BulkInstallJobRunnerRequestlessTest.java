package com.letsblog.publishing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.publishing.client.MediaSettingsBridgeClient;
import com.letsblog.publishing.client.OutboundAuthHeaders;
import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.ssh.WordPressSshOperations;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.provisioning.WordPressBulkManagementClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * issue #1702 受け入れ基準1: ランナーを<b>リクエストの無いスレッド</b>で動かし、実物の
 * {@code BulkManagementService}→{@code ProjectService}/{@code SiteService}→{@code ProjectServiceClient}が
 * サービス自身のClient Credentialsで project-service を呼ぶこと、各環境の成否が local, test, production の順で
 * 結果に載り、1環境が失敗しても他環境の結果が載ることを確かめる(WordPress への書き込みだけをモックにする)。
 */
@DisplayName("publishing-service: リクエストの無いスレッドでの全環境インストール(issue #1702)")
class BulkInstallJobRunnerRequestlessTest {

    private static final String PROJECT_JSON =
            "{\"id\":3,\"localSiteId\":11,\"testSiteId\":12,\"productionSiteId\":13,\"masterEnvironment\":\"test\"}";

    private final ServiceTokenClient serviceTokenClient = mock(ServiceTokenClient.class);
    private final GenerationJobClient generationJobClient = mock(GenerationJobClient.class);
    private final WordPressBulkManagementClient wordPress = mock(WordPressBulkManagementClient.class);
    private final BulkUploadStorageService storage = mock(BulkUploadStorageService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> seenAuthorization = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private BulkInstallJobRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        when(serviceTokenClient.getAccessToken()).thenReturn("svc-token");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenAuthorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            String path = exchange.getRequestURI().getPath();
            String body = path.contains("/sites/")
                    ? "{\"id\":" + path.substring(path.lastIndexOf('/') + 1) + ",\"siteKey\":\"k\",\"name\":\"n\","
                            + "\"baseUrl\":\"https://e.example\",\"cmsType\":\"WORDPRESS\","
                            + "\"managedWordpress\":true,\"wpSlug\":\"slug-" + path.substring(path.lastIndexOf('/') + 1) + "\"}"
                    : PROJECT_JSON;
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        ProjectServiceClient client = new ProjectServiceClient(RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                new OutboundAuthHeaders(new MockHttpServletRequest(), serviceTokenClient));
        BulkManagementService bulk = new BulkManagementService(
                wordPress, storage, new SiteService(client), mock(WordPressSshOperations.class),
                mock(CmsAdapterFactory.class), new ProjectService(client),
                mock(ImageResizeService.class), mock(MediaSettingsBridgeClient.class));
        runner = new BulkInstallJobRunner(bulk, storage, generationJobClient, objectMapper);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        RequestContextHolder.resetRequestAttributes();
    }

    private void onPlainThread(Runnable body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            RequestContextHolder.resetRequestAttributes();
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "bulk-install-1");
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private JsonNode donePayload() throws Exception {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(21L), eq("done"), payload.capture());
        return objectMapper.readTree(payload.getValue());
    }

    @Test
    @DisplayName("slug: 全環境の成否が ENVIRONMENT_ORDER 順に載り、1環境が失敗しても他環境の結果は載る。呼び出しはサービス自身のトークン")
    void slugInstallOnRequestlessThread() throws Exception {
        when(wordPress.apply(any())).thenAnswer(invocation -> {
            WordPressBulkManagementClient.BulkApplyCommand command = invocation.getArgument(0);
            return "slug-12".equals(command.slug())
                    ? new WordPressBulkManagementClient.BulkApplyResult("FAILED", "wp-cli: not found", "trace")
                    : new WordPressBulkManagementClient.BulkApplyResult("SUCCESS", null, null);
        });

        onPlainThread(() -> runner.runSlugInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, "no-such-plugin", 5L));

        JsonNode logs = donePayload().get("logs");
        assertEquals(3, logs.size());
        assertEquals(List.of("local", "test", "production"),
                List.of(logs.get(0).get("environment").asText(), logs.get(1).get("environment").asText(),
                        logs.get(2).get("environment").asText()));
        assertEquals("SUCCESS", logs.get(0).get("status").asText());
        assertEquals("FAILED", logs.get(1).get("status").asText());
        assertEquals("SUCCESS", logs.get(2).get("status").asText());
        assertTrue(seenAuthorization.size() >= 4, "プロジェクト1回+サイト3回を引く: " + seenAuthorization);
        assertTrue(seenAuthorization.stream().allMatch("Bearer svc-token"::equals), seenAuthorization.toString());
    }

    @Test
    @DisplayName("zip: 保存済みのバイト列を各環境へ適用し、成否が ENVIRONMENT_ORDER 順に載る。呼び出しはサービス自身のトークン")
    void zipInstallOnRequestlessThread() throws Exception {
        byte[] bytes = {1, 2, 3};
        when(storage.load("3/abc.zip")).thenReturn(bytes);
        when(wordPress.applyZip(any(), eq("plugin_install"), eq(bytes), eq("my-plugin.zip")))
                .thenReturn(new WordPressBulkManagementClient.BulkApplyResult("SUCCESS", null, null));
        BulkUploadStorageService.StoredZip stored =
                new BulkUploadStorageService.StoredZip("3/abc.zip", "abc", "my-plugin.zip");

        onPlainThread(() -> runner.runZipInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, stored, 5L));

        JsonNode logs = donePayload().get("logs");
        assertEquals(3, logs.size());
        assertEquals("local", logs.get(0).get("environment").asText());
        assertEquals("production", logs.get(2).get("environment").asText());
        assertEquals("ZIP", logs.get(0).get("sourceType").asText());
        assertTrue(seenAuthorization.stream().allMatch("Bearer svc-token"::equals), seenAuthorization.toString());
    }
}
