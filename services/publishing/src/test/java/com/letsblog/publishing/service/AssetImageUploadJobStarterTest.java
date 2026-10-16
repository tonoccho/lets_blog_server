package com.letsblog.publishing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.letsblog.common.client.GenerationJobBridgeException;
import com.letsblog.common.client.GenerationJobSummary;
import com.letsblog.publishing.config.AsyncJobConfig;
import com.letsblog.publishing.render.MediaRenderException;
import com.letsblog.publishing.testsupport.AsyncProxies;
import com.letsblog.publishing.testsupport.FakeHttpServer.Response;
import com.letsblog.publishing.testsupport.FakeWordPress;
import com.letsblog.publishing.testsupport.PublishingWorld;
import com.letsblog.publishing.testsupport.PublishingWorld.JobUpdate;
import com.letsblog.publishing.testsupport.PublishingWorld.SiteSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * アセット画像の全環境アップロードジョブの受理側(issue #1764)。{@code BulkInstallJobStarterTest}と同型。
 *
 * <p>issue #1815で書き直し: MediaGeneratedImageClient・MediaSettingsBridgeClient・CurrentActorService・
 * GenerationJobClient・ObjectMapper・AssetImageUploadJobRunner・BulkManagementServiceは実物を使い、外部との通信
 * (media-service・identity-service・project-service・ai-serviceへのHTTP、WordPressへのSSH)だけを差し替える。
 * ランナーの{@code @Async}は、Springの実際のAOPプロキシと本番と同じ{@code AsyncJobConfig}のExecutorで動かす。
 */
@DisplayName("publishing-service: アセット画像アップロードジョブの受理(issue #1764)")
class AssetImageUploadJobStarterTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 1, 2};

    private PublishingWorld world;
    private ThreadPoolTaskExecutor executor;
    private AssetImageUploadJobRunner runner;
    private AssetImageUploadJobStarter starter;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        world.stubActor("Bearer abc", 7L, "user", "u@example.com").asUser("Bearer abc");
        world.bindEnvironments(3L, "test", SiteSpec.ssh(11L, "local-site", "ssh-local", "/var/www/local"), null, null);
        executor = (ThreadPoolTaskExecutor) new AsyncJobConfig().assetImageUploadExecutor();
        runner = AsyncProxies.async(
                new AssetImageUploadJobRunner(world.bulkManagementService, world.generationJobClient, world.mapper),
                "assetImageUploadExecutor", executor);
        starter = starter(world.mapper);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
        world.close();
    }

    private AssetImageUploadJobStarter starter(ObjectMapper mapper) {
        return new AssetImageUploadJobStarter(world.generationJobClient, runner, world.mediaGeneratedImageClient,
                world.mediaSettingsBridgeClient, world.currentActorService, mapper);
    }

    private void generatedImage(long id, byte[] data) {
        world.media.on("GET", "/api/generated-images/" + id + "/file", Response.bytes(200, "image/png", data));
    }

    private List<JobUpdate> awaitTerminal() throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<JobUpdate> updates = world.jobUpdates();
            if (updates.stream().anyMatch(u -> u.status().equals("done") || u.status().equals("failed"))) {
                return updates;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("ジョブの終端の通知が来ませんでした: " + world.jobUpdates());
    }

    private FakeWordPress.Media uploadedMedia() {
        return world.ssh.site("ssh-local", "/var/www/local").media.values().iterator().next();
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("ジョブ種別は安定した非AIの名前")
    void jobTypeIsStable() {
        assertEquals("asset_image_upload", AssetImageUploadJobStarter.JOB_TYPE);
    }

    @Test
    @DisplayName("リクエストスレッドで画像・長辺pxを解決し、利用者のBearerでジョブを作り、取得済みのものをランナーへメモリで渡す")
    void startsJob() throws Exception {
        generatedImage(5L, PNG);

        GenerationJobSummary result = starter.start(3L, 5L);

        assertEquals("asset_image_upload", result.type());
        var created = world.ai.requests("POST", "/api/internal/ai/generation-jobs");
        assertEquals(1, created.size());
        assertEquals("Bearer abc", created.get(0).header("Authorization"));
        JsonNode json = world.mapper.readTree(world.mapper.readTree(created.get(0).body()).get("requestPayload").asText());
        // キューUIが結果の遷移先を決めるのに projectId と generatedImageId を読む。画像本体は載せない。
        assertEquals(3L, json.get("projectId").asLong());
        assertEquals(5L, json.get("generatedImageId").asLong());
        assertEquals(2, json.size());
        // 画像の取得にも利用者のBearerを転送する。
        assertEquals("Bearer abc", world.media.requests("GET", "/api/generated-images/5/file").get(0).header("Authorization"));
        List<JobUpdate> updates = awaitTerminal();
        assertEquals("done", updates.get(updates.size() - 1).status());
        assertTrue(uploadedMedia().url.endsWith("-comfyui-5.png"), uploadedMedia().url);
    }

    @Test
    @DisplayName("受理時に解決した長辺pxでリサイズしてからアップロードされる")
    void resizesToResolvedLongEdge() throws Exception {
        generatedImage(5L, png(2000, 100));
        world.media.json("GET", "/api/internal/media/projects/3/article-image-long-edge-px", "{\"value\":800}");

        starter.start(3L, 5L);

        awaitTerminal();
        BufferedImage uploaded = ImageIO.read(new ByteArrayInputStream(uploadedMedia().bytes));
        assertEquals(800, uploaded.getWidth());
    }

    @Test
    @DisplayName("JPEGの内容ならimage/jpegと拡張子jpgでアップロードされる")
    void jpegIsDetected() throws Exception {
        generatedImage(6L, JPEG);

        starter.start(3L, 6L);

        awaitTerminal();
        assertTrue(uploadedMedia().url.endsWith("-comfyui-6.jpg"), uploadedMedia().url);
    }

    @Test
    @DisplayName("画像が取れなければ、ジョブを作らずランナーも起動せず例外を伝える")
    void imageFetchFailureCreatesNoJob() {
        world.media.status("GET", "/api/generated-images/99/file", 500, "{\"error\":\"down\"}");

        assertThrows(MediaRenderException.class, () -> starter.start(3L, 99L));

        assertTrue(world.ai.requests().isEmpty());
        assertTrue(world.jobUpdates().isEmpty());
    }

    @Test
    @DisplayName("media-serviceが空の画像を返したときも、ジョブを作らず例外を伝える")
    void emptyImageCreatesNoJob() {
        world.media.on("GET", "/api/generated-images/98/file", Response.empty(200));

        assertThrows(MediaRenderException.class, () -> starter.start(3L, 98L));

        assertTrue(world.ai.requests().isEmpty());
    }

    @Test
    @DisplayName("実行枠と待ち行列が満杯なら、ジョブを queue_full の failed にして返す")
    void queueFull() throws Exception {
        generatedImage(5L, PNG);
        CountDownLatch release = new CountDownLatch(1);
        // 実行枠(core 1 / max 2)と待ち行列(5)を、終わらないタスクで埋める。
        for (int i = 0; i < 7; i++) {
            executor.execute(() -> {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        try {
            GenerationJobSummary result = starter.start(3L, 5L);

            assertEquals("failed", result.status());
            JobUpdate failed = world.jobUpdates().get(0);
            assertEquals("failed", failed.status());
            assertEquals(result.id(), failed.id());
            assertEquals("queue_full", failed.result().get("errorType").asText());
            assertFalse(failed.result().get("error").asText().isBlank());
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("ジョブを作れなければ(ai-service 不達)ランナーを起動せず例外を伝える")
    void createFailurePropagates() {
        generatedImage(5L, PNG);
        // 認可されていない(401)応答。サーキットブレーカーに数えられない失敗なので、他のテストへ影響しない。
        world.ai.status("POST", "/api/internal/ai/generation-jobs", 401, "{\"error\":\"unauthorized\"}");

        assertThrows(GenerationJobBridgeException.class, () -> starter.start(3L, 5L));

        assertTrue(world.jobUpdates().isEmpty(), "ランナーは起動しない");
        assertTrue(world.ssh.commands.isEmpty());
    }

    @Test
    @DisplayName("ジョブの要求内容のJSON化に失敗しても、空のJSONで受理を続ける")
    void payloadSerializationFailureFallsBackToEmptyJson() throws Exception {
        generatedImage(5L, PNG);
        // 全ての数値(Long)を書き出せないObjectMapper(実物のObjectMapperに、必ず失敗するシリアライザを登録したもの)。
        ObjectMapper broken = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Long.class, new StdSerializer<>(Long.class) {
            @Override
            public void serialize(Long value, com.fasterxml.jackson.core.JsonGenerator generator,
                    com.fasterxml.jackson.databind.SerializerProvider provider) throws IOException {
                throw new IOException("boom");
            }
        });
        broken.registerModule(module);

        starter(broken).start(3L, 5L);

        var created = world.ai.requests("POST", "/api/internal/ai/generation-jobs").get(0);
        assertEquals("{}", world.mapper.readTree(created.body()).get("requestPayload").asText());
        awaitTerminal();
    }
}
