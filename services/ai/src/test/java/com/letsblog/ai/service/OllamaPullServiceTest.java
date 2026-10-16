package com.letsblog.ai.service;

import com.letsblog.ai.domain.GenerationJob;
import com.letsblog.ai.dto.PullOllamaModelResponse;
import com.letsblog.ai.testsupport.AiWorld;
import com.letsblog.ai.testsupport.FakeHttpServer;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OllamaPullService(issue #1675)。検証・実効接続先の解決・二重開始の拒否・ジョブ作成と実行の起動。
 *
 * <p>issue #1823: ProjectConnectionService・OllamaPullJobRunner・CurrentActorService・ConnectionDestinationGuardは実物で、
 * platform-service・identity-service・Ollama(POST /api/pull)はHTTPフェイク、DBはメモリ上のリポジトリで差し替える。
 * 実行はテストでは同期(@Asyncはプロキシ越しにしか効かない)なので、起動したpullの結果がジョブの最終状態に現れる。
 */
class OllamaPullServiceTest {

    private static final String CONFIG_PATH = "/api/internal/platform/ai-connections-config";
    private static final String SUCCESS = "{\"status\":\"success\"}";

    private AiWorld world;
    private OllamaPullService service;

    @BeforeEach
    void setUp() {
        world = new AiWorld().actor(5L, "user");
        OllamaPullJobRunner runner = new OllamaPullJobRunner(
                world.generationJobRepository, world.objectMapper, world.destinationGuard, System::currentTimeMillis);
        service = new OllamaPullService(
                world.projectConnectionService, world.generationJobRepository, world.currentActorService, runner,
                world.objectMapper);
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    /** platform-serviceが返すシステム設定のOllama接続先。 */
    private void systemOllama(String baseUrl) {
        world.http.json("GET", CONFIG_PATH, "{\"ollama\":{\"baseUrl\":" + (baseUrl == null ? "null" : "\"" + baseUrl + "\"")
                + ",\"source\":\"DATABASE\",\"configured\":true},\"comfyui\":{\"baseUrl\":\"http://c\",\"source\":\"DATABASE\","
                + "\"configured\":true}}");
    }

    private static void pullSucceeds(FakeHttpServer server) {
        server.on("POST", "/api/pull", FakeHttpServer.Response.bytes(200, "application/x-ndjson", SUCCESS.getBytes()));
    }

    private GenerationJob running(long id, String payload) {
        GenerationJob job = new GenerationJob();
        job.setId(id);
        job.setType(OllamaPullService.JOB_TYPE);
        job.setStatus("running");
        job.setRequestPayload(payload);
        return world.generationJobRepository.save(job);
    }

    @Test
    void start_プロジェクトの上書きがあればそれでジョブを作って実行を起動する() {
        systemOllama(world.http.baseUrl() + "/v1");
        pullSucceeds(world.lan());
        world.projectAiSettingsService.setConnectionUrls(7L, world.lan().lanBaseUrl() + "/v1", null);

        PullOllamaModelResponse response = service.start(7L, "  qwen2.5:7b-instruct ");

        GenerationJob saved = world.jobRows.saved.get(0);
        assertEquals(new PullOllamaModelResponse(saved.getId(), false), response);
        assertEquals(OllamaPullService.JOB_TYPE, saved.getType());
        assertEquals(5L, saved.getOwnerUserId());
        assertTrue(saved.getRequestPayload().contains("\"model\":\"qwen2.5:7b-instruct\""));
        assertTrue(saved.getRequestPayload().contains("\"projectId\":7"));
        // 起動したpullは、プロジェクトの上書き先(検査を通ったアドレス)へ向かい、システム設定の接続先へは向かわない。
        List<FakeHttpServer.Request> pulls = world.lan().requests("POST", "/api/pull");
        assertEquals(1, pulls.size());
        assertTrue(pulls.get(0).bodyText().contains("\"model\":\"qwen2.5:7b-instruct\""), pulls.get(0).bodyText());
        assertEquals(0, world.http.requests("POST", "/api/pull").size());
        assertEquals("done", saved.getStatus());
    }

    @Test
    void start_システム設定の接続先ならプロジェクト上書きではないものとして起動する() {
        systemOllama(world.http.baseUrl() + "/v1");
        pullSucceeds(world.http);

        PullOllamaModelResponse response = service.start(7L, "llama3");

        assertFalse(response.alreadyRunning());
        // プロジェクト上書きではないので宛先検査を通さない(ループバックのシステム設定の接続先へそのまま届く)。
        assertEquals(1, world.http.requests("POST", "/api/pull").size());
        assertEquals("done", world.jobRows.rows.get(response.jobId()).getStatus());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "   ", "a b", "foo;rm -rf", "../etc", ":tag", "model:", "a:b:c", "-leading", "モデル", "x\ny", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void start_空や不正な文字のモデル名は拒否し何も作らない(String model) {
        assertThrows(InvalidOllamaModelNameException.class, () -> service.start(7L, model));

        assertEquals(0, world.http.requests().size());
        assertTrue(world.jobRows.saved.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"llama3", "qwen2.5:7b-instruct", "hf.co/bartowski/Llama-3.2-3B-GGUF:Q4_K_M", "e2e-pull_1:latest"})
    void start_Ollamaのモデル名の形式は受け付ける(String model) {
        systemOllama(world.http.baseUrl());
        pullSucceeds(world.http);

        service.start(7L, model);

        assertEquals(1, world.http.requests("POST", "/api/pull").size());
        assertTrue(world.http.requests("POST", "/api/pull").get(0).bodyText().contains("\"model\":\"" + model + "\""));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void start_Ollamaの接続先が無ければ409相当で何も作らない(String baseUrl) {
        systemOllama(baseUrl);

        assertThrows(IllegalStateException.class, () -> service.start(7L, "llama3"));

        assertTrue(world.jobRows.saved.isEmpty());
        assertEquals(0, world.http.requests("POST", "/api/pull").size());
    }

    @Test
    void start_同じプロジェクトの同じモデルが実行中なら新しく始めず実行中のジョブを返す() {
        systemOllama(world.http.baseUrl());
        pullSucceeds(world.http);
        running(20L, "{\"projectId\":8,\"model\":\"llama3\"}");
        running(21L, "{\"projectId\":7,\"model\":\"other\"}");
        running(22L, "not json");
        running(23L, null);
        running(24L, "{\"projectId\":7,\"model\":\"llama3\"}");

        PullOllamaModelResponse response = service.start(7L, "llama3");

        assertEquals(new PullOllamaModelResponse(24L, true), response);
        assertTrue(response.alreadyRunning());
        assertEquals(5, world.jobRows.saved.size()); // 新しいジョブは保存していない。
        assertEquals(0, world.http.requests("POST", "/api/pull").size());
    }

    @Test
    void start_別プロジェクトや別モデルや読めないペイロードの実行中ジョブは二重開始とみなさない() {
        systemOllama(world.http.baseUrl());
        pullSucceeds(world.http);
        running(20L, "{\"projectId\":8,\"model\":\"llama3\"}");
        running(21L, "{\"projectId\":7,\"model\":\"other\"}");
        running(22L, "not json");
        running(23L, null);

        PullOllamaModelResponse response = service.start(7L, "llama3");

        assertFalse(response.alreadyRunning());
        assertTrue(response.jobId() != 20L && response.jobId() != 21L && response.jobId() != 22L && response.jobId() != 23L);
        assertEquals(1, world.http.requests("POST", "/api/pull").size());
    }
}
