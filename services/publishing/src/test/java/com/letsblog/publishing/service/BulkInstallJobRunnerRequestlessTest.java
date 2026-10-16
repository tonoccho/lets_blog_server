package com.letsblog.publishing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.testsupport.FakeHttpServer.Response;
import com.letsblog.publishing.testsupport.PublishingWorld;
import com.letsblog.publishing.testsupport.PublishingWorld.SiteSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * issue #1702 受け入れ基準1: ランナーを<b>リクエストの無いスレッド</b>で動かし、実物の
 * {@code BulkManagementService}→{@code ProjectService}/{@code SiteService}→{@code ProjectServiceClient}が
 * サービス自身のClient Credentialsで project-service を呼ぶこと、各環境の成否が local, test, production の順で
 * 結果に載り、1環境が失敗しても他環境の結果が載ることを確かめる。
 *
 * <p>issue #1815で書き直し: ServiceTokenClient・WordPressBulkManagementClient・GenerationJobClient・
 * BulkUploadStorageService等は実物を使い、外部との通信(トークン発行・project-service・provision-agent・
 * ai-serviceへのHTTP)だけを{@code FakeHttpServer}で差し替える。
 */
@DisplayName("publishing-service: リクエストの無いスレッドでの全環境インストール(issue #1702)")
class BulkInstallJobRunnerRequestlessTest {

    private PublishingWorld world;
    private BulkInstallJobRunner runner;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        world.bindEnvironments(3L, "test", SiteSpec.managed(11L, "slug-11"), SiteSpec.managed(12L, "slug-12"),
                SiteSpec.managed(13L, "slug-13"));
        runner = new BulkInstallJobRunner(world.bulkManagementService, world.bulkUploadStorageService,
                world.generationJobClient, world.mapper);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        world.close();
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

    private JsonNode donePayload() {
        return world.jobUpdates().stream().filter(u -> u.status().equals("done")).findFirst().orElseThrow().result();
    }

    private List<String> projectServiceAuthorization() {
        return world.project.requests().stream().map(r -> String.valueOf(r.header("Authorization"))).toList();
    }

    @Test
    @DisplayName("slug: 全環境の成否が ENVIRONMENT_ORDER 順に載り、1環境が失敗しても他環境の結果は載る。呼び出しはサービス自身のトークン")
    void slugInstallOnRequestlessThread() throws Exception {
        world.agent.on("POST", "/bulk-management", request -> request.bodyText().contains("slug-12")
                ? Response.json(502, "{\"error\":\"wp-cli: not found\"}") : Response.ok("{\"status\":\"success\"}"));

        onPlainThread(() -> runner.runSlugInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, "no-such-plugin", 5L));

        JsonNode logs = donePayload().get("logs");
        assertEquals(3, logs.size());
        assertEquals(List.of("local", "test", "production"),
                List.of(logs.get(0).get("environment").asText(), logs.get(1).get("environment").asText(),
                        logs.get(2).get("environment").asText()));
        assertEquals("SUCCESS", logs.get(0).get("status").asText());
        assertEquals("FAILED", logs.get(1).get("status").asText());
        assertEquals("SUCCESS", logs.get(2).get("status").asText());
        assertTrue(projectServiceAuthorization().size() >= 4,
                "プロジェクト1回+サイト3回を引く: " + projectServiceAuthorization());
        assertTrue(projectServiceAuthorization().stream().allMatch("Bearer svc-token"::equals),
                projectServiceAuthorization().toString());
    }

    @Test
    @DisplayName("zip: 保存済みのバイト列を各環境へ適用し、成否が ENVIRONMENT_ORDER 順に載る。呼び出しはサービス自身のトークン")
    void zipInstallOnRequestlessThread() throws Exception {
        world.agent.json("POST", "/bulk-management/upload", "{\"status\":\"success\"}");
        BulkUploadStorageService.StoredZip stored =
                world.bulkUploadStorageService.store(3L, new byte[] {1, 2, 3}, "my-plugin.zip");

        onPlainThread(() -> runner.runZipInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, stored, 5L));

        JsonNode logs = donePayload().get("logs");
        assertEquals(3, logs.size());
        assertEquals("local", logs.get(0).get("environment").asText());
        assertEquals("production", logs.get(2).get("environment").asText());
        assertEquals("ZIP", logs.get(0).get("sourceType").asText());
        assertEquals(3, world.agent.requests("POST", "/bulk-management/upload").size());
        assertTrue(projectServiceAuthorization().stream().allMatch("Bearer svc-token"::equals),
                projectServiceAuthorization().toString());
    }
}
