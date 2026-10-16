package com.letsblog.publishing.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.letsblog.publishing.domain.BulkOperationType;
import com.letsblog.publishing.testsupport.FakeHttpServer.Response;
import com.letsblog.publishing.testsupport.PublishingWorld;
import com.letsblog.publishing.testsupport.PublishingWorld.JobUpdate;
import com.letsblog.publishing.testsupport.PublishingWorld.SiteSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全環境インストールジョブの実行本体(issue #1702)。{@code @Async}はSpringプロキシ経由でしか効かないため、
 * メソッドを同期的に呼んで検証する(project-serviceの ProjectEnvironmentSyncJobRunnerTest と同じ)。
 *
 * <p>issue #1815で書き直し: BulkManagementService・BulkUploadStorageService・GenerationJobClient・ProjectService等は
 * 実物を使い、外部との通信(project-service・provision-agent・ai-serviceへのHTTP)だけを{@code FakeHttpServer}で
 * 差し替える。ジョブの状態通知は、ai-serviceが実際に受けたPATCHで確かめる。
 */
@DisplayName("publishing-service: 全環境インストールジョブランナー(issue #1702)")
class BulkInstallJobRunnerTest {

    private PublishingWorld world;
    private BulkInstallJobRunner runner;

    @BeforeEach
    void setUp() {
        world = new PublishingWorld();
        runner = new BulkInstallJobRunner(world.bulkManagementService, world.bulkUploadStorageService,
                world.generationJobClient, world.mapper);
        world.agent.json("POST", "/bulk-management", "{\"status\":\"success\"}");
        world.agent.json("POST", "/bulk-management/upload", "{\"status\":\"success\"}");
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    private void bind(SiteSpec local, SiteSpec test, SiteSpec production) {
        world.bindEnvironments(3L, "test", local, test, production);
    }

    private JobUpdate terminal(String status) {
        List<JobUpdate> matched = world.jobUpdates().stream().filter(u -> u.status().equals(status)).toList();
        assertEquals(1, matched.size(), status + " の通知: " + world.jobUpdates());
        assertEquals(21L, matched.get(0).id());
        return matched.get(0);
    }

    private boolean notified(String status) {
        return world.jobUpdates().stream().anyMatch(u -> u.status().equals(status));
    }

    @Test
    @DisplayName("slug: installing を running で通知してから、環境ごとの結果(logs)を載せて done にする")
    void slugSuccess() {
        bind(SiteSpec.managed(11L, "local-site"), SiteSpec.managed(12L, "test-site"), null);
        world.agent.on("POST", "/bulk-management", request -> request.bodyText().contains("test-site")
                ? Response.json(502, "{\"error\":\"wp-cli: 失敗\"}") : null);

        runner.runSlugInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, "akismet", 5L);

        List<JobUpdate> updates = world.jobUpdates();
        assertEquals(List.of("running", "done"), updates.stream().map(JobUpdate::status).toList());
        assertEquals("installing", updates.get(0).result().get("phase").asText());
        JsonNode result = updates.get(1).result();
        assertEquals("done", result.get("phase").asText());
        assertEquals(3L, result.get("projectId").asLong());
        JsonNode logs = result.get("logs");
        assertEquals(2, logs.size());
        // 環境の順(ENVIRONMENT_ORDER)を保つ。環境ごとの失敗は FAILED の log で、ジョブは done。
        assertEquals("local", logs.get(0).get("environment").asText());
        assertEquals("SUCCESS", logs.get(0).get("status").asText());
        assertEquals("test", logs.get(1).get("environment").asText());
        assertEquals("FAILED", logs.get(1).get("status").asText());
        assertTrue(logs.get(1).get("errorMessage").asText().contains("provision-agent"));
        // BulkOperationLogResponse と同じ形
        assertEquals("PLUGIN_INSTALL", logs.get(0).get("operationType").asText());
        assertEquals("SLUG", logs.get(0).get("sourceType").asText());
        assertEquals("akismet", logs.get(0).get("value").asText());
        assertTrue(logs.get(0).get("createdAt").asText().endsWith("Z") || logs.get(0).get("createdAt").asText().contains("T"),
                logs.get(0).get("createdAt").asText());
        assertFalse(notified("failed"));
    }

    @Test
    @DisplayName("zip: 保存先から読み、保存済みのバイト列を各環境へ適用して done にする")
    void zipSuccess() throws Exception {
        bind(SiteSpec.managed(11L, "local-site"), null, null);
        BulkUploadStorageService.StoredZip stored =
                world.bulkUploadStorageService.store(3L, new byte[] {1, 2, 3}, "my-plugin.zip");

        runner.runZipInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, stored, 5L);

        JsonNode result = terminal("done").result();
        assertEquals("ZIP", result.get("logs").get(0).get("sourceType").asText());
        assertEquals("my-plugin.zip", result.get("logs").get(0).get("originalFilename").asText());
        assertEquals(1, world.agent.requests("POST", "/bulk-management/upload").size());
    }

    @Test
    @DisplayName("zip: 保存済みファイルが読めなければ unexpected_error の failed")
    void zipLoadFailure() {
        bind(SiteSpec.managed(11L, "local-site"), null, null);
        BulkUploadStorageService.StoredZip missing =
                new BulkUploadStorageService.StoredZip("3/missing.zip", "abc", "my-plugin.zip");

        runner.runZipInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, missing, 5L);

        JsonNode result = terminal("failed").result();
        assertEquals("unexpected_error", result.get("errorType").asText());
        assertTrue(result.get("error").asText().contains("保存済みファイル"));
    }

    @Test
    @DisplayName("プロジェクトが無い(ProjectNotFoundException)は project_not_found の failed")
    void projectNotFound() {
        // project-serviceにプロジェクト3は登録されていない(404)。
        runner.runSlugInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, "akismet", 5L);

        assertEquals("project_not_found", terminal("failed").result().get("errorType").asText());
        assertFalse(notified("done"));
    }

    @Test
    @DisplayName("入力の不正(IllegalArgumentException)は invalid_request の failed")
    void invalidRequest() {
        runner.runSlugInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, " ", 5L);

        assertEquals("invalid_request", terminal("failed").result().get("errorType").asText());
    }

    @Test
    @DisplayName("想定外の例外は unexpected_error の failed")
    void unexpected() {
        world.project.status("GET", "/api/internal/project/projects/3", 500, "{\"error\":\"down\"}");

        runner.runSlugInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, "akismet", 5L);

        JsonNode result = terminal("failed").result();
        assertEquals("unexpected_error", result.get("errorType").asText());
        assertFalse(result.get("error").asText().isBlank());
    }

    @Test
    @DisplayName("例外にメッセージが無くても、読める文言(例外のクラス名)になる")
    void failurePayloadWithoutMessage() {
        var payload = BulkInstallJobRunner.failurePayload(new IllegalStateException());

        assertEquals("IllegalStateException", payload.get("error"));
        assertEquals("unexpected_error", payload.get("errorType"));
    }

    @Test
    @DisplayName("ログの optional な項目(null)も、そのまま null として載る")
    void nullFieldsStayNull() {
        bind(SiteSpec.managed(11L, "local-site"), null, null);
        world.agent.json("POST", "/bulk-management", "{\"status\":\"skipped\"}");

        runner.runSlugInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, "akismet", 5L);

        JsonNode entry = terminal("done").result().get("logs").get(0);
        assertEquals("SKIPPED", entry.get("status").asText());
        assertTrue(entry.get("errorMessage").isNull());
        assertTrue(entry.get("stackTrace").isNull());
    }

    /** ジョブ1件の完了行(issue #1775)だけを集める。 */
    private static List<ILoggingEvent> completionLines(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(BulkInstallJobRunner.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .filter(e -> e.getFormattedMessage().startsWith("job completed:"))
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("完了行: 成功(done)は INFO で kind / job_id / duration_ms / outcome を持つ1行")
    void completionLineOnSuccess() {
        bind(SiteSpec.managed(11L, "local-site"), null, null);

        List<ILoggingEvent> lines = completionLines(
                () -> runner.runSlugInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, "akismet", 5L));

        assertEquals(1, lines.size());
        assertEquals(Level.INFO, lines.get(0).getLevel());
        assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=bulk_install job_id=21 duration_ms=\\d+ outcome=success"));
    }

    @Test
    @DisplayName("完了行: 例外でジョブが failed になる場合は WARN で outcome に例外クラス名")
    void completionLineOnFailure() {
        world.project.status("GET", "/api/internal/project/projects/3", 500, "{\"error\":\"down\"}");

        List<ILoggingEvent> lines = completionLines(
                () -> runner.runSlugInstall(21L, 3L, BulkOperationType.PLUGIN_INSTALL, "akismet", 5L));

        assertEquals(1, lines.size());
        assertEquals(Level.WARN, lines.get(0).getLevel());
        assertTrue(lines.get(0).getFormattedMessage()
                .matches("job completed: kind=bulk_install job_id=21 duration_ms=\\d+ outcome=IllegalStateException"));
    }
}
