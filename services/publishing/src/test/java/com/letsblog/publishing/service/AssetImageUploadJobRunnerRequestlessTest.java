package com.letsblog.publishing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.common.auth.ServiceTokenClient;
import com.letsblog.common.client.GenerationJobClient;
import com.letsblog.publishing.client.MediaSettingsBridgeClient;
import com.letsblog.publishing.client.OutboundAuthHeaders;
import com.letsblog.publishing.client.ProjectServiceClient;
import com.letsblog.publishing.cms.CmsAdapter;
import com.letsblog.publishing.cms.CmsAdapterFactory;
import com.letsblog.publishing.cms.CmsCredentials;
import com.letsblog.publishing.cms.CmsType;
import com.letsblog.publishing.cms.MediaUploadResult;
import com.letsblog.publishing.cms.ssh.WordPressSshOperations;
import com.letsblog.publishing.domain.Site;
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
import java.util.Optional;
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
 * issue #1764 受け入れ基準1: ランナーを<b>リクエストの無いスレッド</b>で動かし、実物の
 * {@code BulkManagementService}→{@code ProjectService}→{@code ProjectServiceClient}がサービス自身の
 * Client Credentialsで project-service を呼ぶこと、各環境の成否が local, test, production の順で結果に載り、
 * 1環境の失敗も画素数の上限による拒否も {@code FAILED} のログとして載ることを確かめる
 * (WordPress への書き込み = CmsAdapter だけをモックにする)。
 */
@DisplayName("publishing-service: リクエストの無いスレッドでのアセット画像アップロード(issue #1764)")
class AssetImageUploadJobRunnerRequestlessTest {

    private static final String PROJECT_JSON =
            "{\"id\":3,\"localSiteId\":11,\"testSiteId\":12,\"productionSiteId\":13,\"masterEnvironment\":\"test\"}";
    private static final byte[] SMALL_PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1};

    private final ServiceTokenClient serviceTokenClient = mock(ServiceTokenClient.class);
    private final GenerationJobClient generationJobClient = mock(GenerationJobClient.class);
    private final SiteService siteService = mock(SiteService.class);
    private final CmsAdapterFactory cmsAdapterFactory = mock(CmsAdapterFactory.class);
    private final CmsAdapter adapter = mock(CmsAdapter.class);
    private final ImageResizeService imageResizeService = mock(ImageResizeService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<String> seenAuthorization = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private AssetImageUploadJobRunner runner;

    @BeforeEach
    void setUp() throws Exception {
        when(serviceTokenClient.getAccessToken()).thenReturn("svc-token");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenAuthorization.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            byte[] bytes = PROJECT_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();

        ProjectServiceClient client = new ProjectServiceClient(RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                new OutboundAuthHeaders(new MockHttpServletRequest(), serviceTokenClient));
        for (long id = 11; id <= 13; id++) {
            Site site = new Site();
            site.setId(id);
            site.setSiteKey("site-" + id);
            site.setCmsType(CmsType.WORDPRESS);
            when(siteService.getById(id)).thenReturn(Optional.of(site));
            when(siteService.getCredentials("site-" + id)).thenReturn(
                    new CmsCredentials.WordPressCredentials("https://e" + id + ".example", "admin", "SSH"));
        }
        when(cmsAdapterFactory.resolve(CmsType.WORDPRESS)).thenReturn(adapter);
        BulkManagementService bulk = new BulkManagementService(
                mock(WordPressBulkManagementClient.class), mock(BulkUploadStorageService.class), siteService,
                mock(WordPressSshOperations.class), cmsAdapterFactory, new ProjectService(client),
                imageResizeService, mock(MediaSettingsBridgeClient.class));
        runner = new AssetImageUploadJobRunner(bulk, generationJobClient, objectMapper);
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
        }, "asset-image-upload-1");
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }

    private JsonNode donePayload() throws Exception {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(generationJobClient).updateStatus(eq(31L), eq("done"), payload.capture());
        return objectMapper.readTree(payload.getValue());
    }

    @Test
    @DisplayName("全環境の成否が ENVIRONMENT_ORDER 順に載り、1環境が失敗しても他環境の結果は載る。project-service の呼び出しはサービス自身のトークン")
    void uploadOnRequestlessThread() throws Exception {
        when(imageResizeService.resizeToLongEdge(any(), eq("image/png"), eq(1300), eq(true)))
                .thenReturn(new ImageResizeService.ResizeResult(new byte[] {9}, "image/png"));
        when(adapter.uploadMedia(any(), eq("comfyui-5.png"), eq("image/png"), any())).thenAnswer(invocation -> {
            CmsCredentials.WordPressCredentials credentials = invocation.getArgument(0);
            if (credentials.baseUrl().contains("e12")) {
                throw new RuntimeException("接続に失敗しました");
            }
            return new MediaUploadResult("1", credentials.baseUrl() + "/comfyui-5.png");
        });

        onPlainThread(() -> runner.run(31L, 3L, SMALL_PNG, "comfyui-5.png", "image/png", 1300, 5L));

        JsonNode logs = donePayload().get("logs");
        assertEquals(3, logs.size());
        assertEquals(List.of("local", "test", "production"),
                List.of(logs.get(0).get("environment").asText(), logs.get(1).get("environment").asText(),
                        logs.get(2).get("environment").asText()));
        assertEquals("SUCCESS", logs.get(0).get("status").asText());
        assertEquals("FAILED", logs.get(1).get("status").asText());
        assertEquals("接続に失敗しました", logs.get(1).get("errorMessage").asText());
        assertEquals("SUCCESS", logs.get(2).get("status").asText());
        assertTrue(!seenAuthorization.isEmpty());
        assertTrue(seenAuthorization.stream().allMatch("Bearer svc-token"::equals), seenAuthorization.toString());
    }

    @Test
    @DisplayName("画素数の上限による拒否は、どの環境へもアップロードせず環境ごとの FAILED のログとして載る")
    void oversizedImageIsRejectedPerEnvironment() throws Exception {
        when(imageResizeService.resizeToLongEdge(any(), any(), eq(1300), eq(true)))
                .thenThrow(new InvalidImageUploadException("画像の画素数が上限を超えています"));

        onPlainThread(() -> runner.run(31L, 3L, SMALL_PNG, "comfyui-5.png", "image/png", 1300, 5L));

        JsonNode logs = donePayload().get("logs");
        assertEquals(3, logs.size());
        for (JsonNode entry : logs) {
            assertEquals("FAILED", entry.get("status").asText());
            assertTrue(entry.get("errorMessage").asText().contains("画素数"));
        }
        verify(adapter, org.mockito.Mockito.never()).uploadMedia(any(), any(), any(), any());
    }
}
