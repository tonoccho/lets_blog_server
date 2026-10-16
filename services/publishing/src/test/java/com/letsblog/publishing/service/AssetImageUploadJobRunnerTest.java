package com.letsblog.publishing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.letsblog.publishing.testsupport.PublishingWorld;
import com.letsblog.publishing.testsupport.PublishingWorld.JobUpdate;
import com.letsblog.publishing.testsupport.PublishingWorld.SiteSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * アセット画像アップロードジョブの実行本体(issue #1764)。{@code @Async}はSpringプロキシ経由でしか効かないため、
 * メソッドを同期的に呼んで検証する。
 *
 * <p>issue #1815で書き直し: BulkManagementService・GenerationJobClient・ObjectMapperは実物を使い、外部との通信
 * (project-service・ai-serviceへのHTTP、WordPressへのSSH)だけを差し替える。環境ごとの結果は、
 * WordPress(SSH)のフェイクとai-serviceが実際に受けたジョブ通知で確かめる。
 */
@DisplayName("publishing-service: アセット画像アップロードジョブランナー(issue #1764)")
class AssetImageUploadJobRunnerTest {

    private static final byte[] DATA = {1, 2, 3};

    private PublishingWorld world;
    private AssetImageUploadJobRunner runner;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        runner = new AssetImageUploadJobRunner(world.bulkManagementService, world.generationJobClient, world.mapper);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private JobUpdate terminal(String status) {
        List<JobUpdate> matched = world.jobUpdates().stream().filter(u -> u.status().equals(status)).toList();
        assertEquals(1, matched.size(), status + " の通知: " + world.jobUpdates());
        assertEquals(31L, matched.get(0).id());
        return matched.get(0);
    }

    @Test
    @DisplayName("解決済みの長辺pxでアップロードし、環境ごとの結果(logs)を ENVIRONMENT_ORDER 順に載せて done にする。環境の失敗はFAILEDのログでジョブはdone")
    void success() {
        world.bindEnvironments(3L, "test", SiteSpec.ssh(11L, "local-site", "ssh-local", "/var/www/local"),
                SiteSpec.ssh(12L, "test-site", "ssh-test", "/var/www/test"),
                SiteSpec.ssh(13L, "production-site", "ssh-production", "/var/www/production"));
        world.ssh.rejectHost("ssh-test");

        runner.run(31L, 3L, DATA, "comfyui-5.png", "image/png", 1300, 7L);

        List<JobUpdate> updates = world.jobUpdates();
        assertEquals(List.of("running", "done"), updates.stream().map(JobUpdate::status).toList());
        assertEquals("uploading", updates.get(0).result().get("phase").asText());
        JsonNode result = updates.get(1).result();
        assertEquals("done", result.get("phase").asText());
        assertEquals(3L, result.get("projectId").asLong());
        JsonNode logs = result.get("logs");
        assertEquals(3, logs.size());
        assertEquals("local", logs.get(0).get("environment").asText());
        assertEquals("SUCCESS", logs.get(0).get("status").asText());
        assertEquals("test", logs.get(1).get("environment").asText());
        assertEquals("FAILED", logs.get(1).get("status").asText());
        assertTrue(logs.get(1).get("errorMessage").asText().contains("ssh-test"));
        assertEquals("production", logs.get(2).get("environment").asText());
        assertEquals("MEDIA_UPLOAD", logs.get(0).get("operationType").asText());
    }

    @Test
    @DisplayName("プロジェクトが無ければ project_not_found の failed にする")
    void projectNotFound() {
        // project-serviceにプロジェクト3は登録されていない(404)。
        runner.run(31L, 3L, DATA, "comfyui-5.png", "image/png", 1300, 7L);

        assertEquals("project_not_found", terminal("failed").result().get("errorType").asText());
        assertFalse(world.jobUpdates().stream().anyMatch(u -> u.status().equals("done")));
    }

    @Test
    @DisplayName("想定外の例外は unexpected_error の failed にし、理由を載せる")
    void unexpectedFailure() {
        world.project.status("GET", "/api/internal/project/projects/3", 500, "{\"error\":\"boom\"}");

        runner.run(31L, 3L, DATA, "comfyui-5.png", "image/png", 1300, 7L);

        JsonNode json = terminal("failed").result();
        assertEquals("unexpected_error", json.get("errorType").asText());
        assertTrue(json.get("error").asText().contains("boom"));
    }

    @Test
    @DisplayName("紐付けられたサイトが登録されていない環境しか無ければ、logsは空のまま done にする")
    void environmentsWithoutRegisteredSitesAreSkipped() {
        world.bindProject(11L, 3L, 11L, null, null, "test");

        runner.run(31L, 3L, DATA, "comfyui-5.png", "image/png", 1300, 7L);

        JsonNode logs = terminal("done").result().get("logs");
        assertEquals(0, logs.size(), "サイトが登録されていない環境はスキップされる");
    }

    @Test
    @DisplayName("JSON化に失敗しても空のJSONで通知を続ける")
    void serializationFailureFallsBack() {
        // どの文字列も書き出せないObjectMapper(実物のObjectMapperに、必ず失敗するシリアライザを登録したもの)。
        ObjectMapper broken = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(String.class, new StdSerializer<>(String.class) {
            @Override
            public void serialize(String value, com.fasterxml.jackson.core.JsonGenerator generator,
                    com.fasterxml.jackson.databind.SerializerProvider provider) throws java.io.IOException {
                throw new java.io.IOException("boom");
            }
        });
        broken.registerModule(module);
        AssetImageUploadJobRunner brokenRunner =
                new AssetImageUploadJobRunner(world.bulkManagementService, world.generationJobClient, broken);
        world.bindEnvironments(3L, "test", SiteSpec.ssh(11L, "local-site", "ssh-local", "/var/www/local"), null,
                null);

        brokenRunner.run(31L, 3L, DATA, "comfyui-5.png", "image/png", 1300, 7L);

        assertEquals(List.of("running", "done"), world.jobUpdates().stream().map(JobUpdate::status).toList());
        assertTrue(world.ai.requests("PATCH", ".*").stream().allMatch(r -> r.bodyText().contains("\"resultPayload\":\"{}\"")),
                world.ai.requests("PATCH", ".*").stream().map(r -> r.bodyText()).toList().toString());
    }
}
