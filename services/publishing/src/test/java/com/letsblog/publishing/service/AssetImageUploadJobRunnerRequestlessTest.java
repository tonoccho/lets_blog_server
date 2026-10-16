package com.letsblog.publishing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.publishing.testsupport.FakeWordPress;
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
 * issue #1764 受け入れ基準1: ランナーを<b>リクエストの無いスレッド</b>で動かし、実物の
 * {@code BulkManagementService}→{@code ProjectService}→{@code ProjectServiceClient}がサービス自身の
 * Client Credentialsで project-service を呼ぶこと、各環境の成否が local, test, production の順で結果に載り、
 * 1環境の失敗も画素数の上限による拒否も {@code FAILED} のログとして載ることを確かめる。
 *
 * <p>issue #1815で書き直し: ServiceTokenClient・ImageResizeService・CmsAdapterFactory・WordPressAdapter等は実物を使い、
 * 外部との通信(トークン発行・project-service・ai-serviceへのHTTP、WordPressへのSSH)だけを差し替える。
 */
@DisplayName("publishing-service: リクエストの無いスレッドでのアセット画像アップロード(issue #1764)")
class AssetImageUploadJobRunnerRequestlessTest {

    private static final byte[] SMALL_PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1};

    private PublishingWorld world;
    private AssetImageUploadJobRunner runner;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        world.bindEnvironments(3L, "test", SiteSpec.ssh(11L, "site-11", "ssh-11", "/var/www/11"),
                SiteSpec.ssh(12L, "site-12", "ssh-12", "/var/www/12"),
                SiteSpec.ssh(13L, "site-13", "ssh-13", "/var/www/13"));
        runner = new AssetImageUploadJobRunner(world.bulkManagementService, world.generationJobClient, world.mapper);
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
        }, "asset-image-upload-1");
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
    @DisplayName("全環境の成否が ENVIRONMENT_ORDER 順に載り、1環境が失敗しても他環境の結果は載る。project-service の呼び出しはサービス自身のトークン")
    void uploadOnRequestlessThread() throws Exception {
        world.ssh.rejectHost("ssh-12");

        onPlainThread(() -> runner.run(31L, 3L, SMALL_PNG, "comfyui-5.png", "image/png", 1300, 5L));

        JsonNode logs = donePayload().get("logs");
        assertEquals(3, logs.size());
        assertEquals(List.of("local", "test", "production"),
                List.of(logs.get(0).get("environment").asText(), logs.get(1).get("environment").asText(),
                        logs.get(2).get("environment").asText()));
        assertEquals("SUCCESS", logs.get(0).get("status").asText());
        assertEquals("FAILED", logs.get(1).get("status").asText());
        assertTrue(logs.get(1).get("errorMessage").asText().contains("ssh-12"));
        assertEquals("SUCCESS", logs.get(2).get("status").asText());
        assertEquals(1, world.ssh.site("ssh-11", "/var/www/11").media.size());
        assertEquals(1, world.ssh.site("ssh-13", "/var/www/13").media.size());
        assertTrue(!projectServiceAuthorization().isEmpty());
        assertTrue(projectServiceAuthorization().stream().allMatch("Bearer svc-token"::equals),
                projectServiceAuthorization().toString());
    }

    @Test
    @DisplayName("画素数の上限による拒否は、どの環境へもアップロードせず環境ごとの FAILED のログとして載る")
    void oversizedImageIsRejectedPerEnvironment() throws Exception {
        onPlainThread(() -> runner.run(31L, 3L, OversizedImageFixtures.pngHeaderOnly(30000, 30000), "comfyui-5.png",
                "image/png", 1300, 5L));

        JsonNode logs = donePayload().get("logs");
        assertEquals(3, logs.size());
        for (JsonNode entry : logs) {
            assertEquals("FAILED", entry.get("status").asText());
            assertTrue(entry.get("errorMessage").asText().contains("画素数"));
        }
        assertTrue(world.ssh.commands.isEmpty(), "どの環境へもアップロードしない");
    }

    @Test
    @DisplayName("アップロードされた画像は、WordPressのメディアとして各環境に残る")
    void uploadedMediaRemainsOnEachEnvironment() throws Exception {
        onPlainThread(() -> runner.run(31L, 3L, SMALL_PNG, "comfyui-5.png", "image/png", 1300, 5L));

        for (String host : List.of("ssh-11", "ssh-12", "ssh-13")) {
            FakeWordPress.Site site = world.ssh.site(host, "/var/www/" + host.substring(4));
            assertEquals(1, site.media.size(), host);
            assertTrue(site.media.values().iterator().next().url.endsWith("-comfyui-5.png"));
        }
    }
}
