package com.letsblog.ai.service;

import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.domain.ProjectReviewStepSetting;
import com.letsblog.ai.domain.ReviewStepKey;
import com.letsblog.ai.dto.ReviewStepSettingResponse;
import com.letsblog.ai.dto.ReviewStepSettingsResponse;
import com.letsblog.ai.testsupport.AiWorld;
import com.letsblog.ai.testsupport.FakeHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReviewStepModelServiceの回帰テスト(issue #1211)。多段レビュー(#1210)のステップ別
 * provider/model設定の取得・保存・解決(ステップ設定→プロジェクト既定→グローバル既定)を検証する。
 * プロジェクト既定→グローバル既定のフォールバックはLlmModelServiceが既に実装済みのため、
 * 本サービスはそこへ委譲するだけであることを確認する。
 *
 * <p>issue #1823: LlmModelService・RemoteLlmConfigProvider・ProviderModelCatalog・ProjectAiSettingsServiceは実物で、
 * platform-serviceと各プロバイダーのモデル一覧APIはHTTPフェイク、DBはメモリ上のリポジトリで差し替える。
 */
class ReviewStepModelServiceTest {

    private AiWorld world;
    private ReviewStepModelService service;
    private String root;

    @BeforeEach
    void setUp() {
        world = new AiWorld().admin();
        service = world.reviewStepModelService;
        root = world.http.baseUrl();
    }

    @AfterEach
    void tearDown() {
        world.close();
    }

    /** platform-serviceのシステム設定: 3プロバイダーぶんの既定モデルと選択肢。 */
    private void systemConfigs(AiProvider defaultProvider, List<String> ollama, List<String> openai, List<String> claude) {
        world.llmConfig(AiProvider.OLLAMA, root + "/v1", "", ollama.isEmpty() ? "" : ollama.get(0), ollama,
                defaultProvider == AiProvider.OLLAMA);
        world.llmConfig(AiProvider.OPENAI, null, "", openai.isEmpty() ? "" : openai.get(0), openai,
                defaultProvider == AiProvider.OPENAI);
        world.llmConfig(AiProvider.CLAUDE, null, "", claude.isEmpty() ? "" : claude.get(0), claude,
                defaultProvider == AiProvider.CLAUDE);
    }

    private void projectKey(AiProvider provider, String key) {
        byte[] encrypted = world.credentialCipher.encrypt(key);
        if (provider == AiProvider.OPENAI) {
            world.projectAiSettingsService.setOpenAiApiKeyEncrypted(1L, encrypted);
        } else {
            world.projectAiSettingsService.setClaudeApiKeyEncrypted(1L, encrypted);
        }
    }

    private ProjectReviewStepSetting stepRow(ReviewStepKey key, String provider, String model) {
        ProjectReviewStepSetting row = new ProjectReviewStepSetting(1L, key);
        row.setLlmProvider(provider);
        row.setLlmModel(model);
        return world.reviewStepSettingRepository.save(row);
    }

    private static ReviewStepSettingResponse step(ReviewStepSettingsResponse response, String key) {
        return response.steps().stream().filter(step -> step.stepKey().equals(key)).findFirst().orElseThrow();
    }

    /** どのプロバイダーの一覧取得も、渡された待ち時間いっぱいブロックして失敗する(取得できないプロバイダー)。 */
    private void blockingProviders() {
        FakeHttpServer.Response empty = FakeHttpServer.Response.ok("{\"models\":[],\"data\":[]}");
        world.http.on("GET", "/api/tags", request -> sleepThen(empty));
        world.http.on("GET", "/cloud/v1/models", request -> sleepThen(empty));
    }

    private static FakeHttpServer.Response sleepThen(FakeHttpServer.Response response) {
        try {
            Thread.sleep(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return response;
    }

    @Test
    void listSettings_5ステップを確定順序で返し未設定はnull() {
        systemConfigs(AiProvider.OPENAI, List.of("qwen"), List.of("gpt-4o-mini", "gpt-4o"), List.of("claude"));

        ReviewStepSettingsResponse response = service.listSettings(1L);

        assertEquals(5, response.steps().size());
        assertEquals(
                List.of("JAPANESE", "PROOFREADING", "FACT_CHECK", "READER_PERSPECTIVE", "STYLE"),
                response.steps().stream().map(ReviewStepSettingResponse::stepKey).toList());
        response.steps().forEach(step -> {
            assertNull(step.provider());
            assertNull(step.model());
        });
        assertEquals(List.of("OLLAMA", "OPENAI", "CLAUDE"), response.availableProviders());
        assertEquals(List.of("gpt-4o-mini", "gpt-4o"), response.availableModels());
    }

    @Test
    void listSettings_providerごとの候補を返しOLLAMAにOpenAIのモデルを含めない() {
        systemConfigs(AiProvider.OPENAI, List.of("qwen2.5:7b-instruct"), List.of("gpt-4o-mini", "gpt-4o"),
                List.of("claude-3-5-haiku-20241022"));

        ReviewStepSettingsResponse response = service.listSettings(1L);

        assertEquals(List.of("OLLAMA", "OPENAI", "CLAUDE"), List.copyOf(response.availableModelsByProvider().keySet()));
        assertEquals(List.of("qwen2.5:7b-instruct"), response.availableModelsByProvider().get("OLLAMA"));
        assertEquals(List.of("gpt-4o-mini", "gpt-4o"), response.availableModelsByProvider().get("OPENAI"));
        assertEquals(List.of("claude-3-5-haiku-20241022"), response.availableModelsByProvider().get("CLAUDE"));
        // provider未設定の工程用(システム既定provider)は現行のまま
        assertEquals(List.of("gpt-4o-mini", "gpt-4o"), response.availableModels());
    }

    @Test
    void listSettings_設定済みステップはその値を返す() {
        systemConfigs(AiProvider.OPENAI, List.of("qwen"), List.of("gpt-4o"), List.of("claude"));
        stepRow(ReviewStepKey.FACT_CHECK, "OPENAI", "gpt-4o");

        ReviewStepSettingsResponse response = service.listSettings(1L);

        assertEquals("OPENAI", step(response, "FACT_CHECK").provider());
        assertEquals("gpt-4o", step(response, "FACT_CHECK").model());
        assertNull(step(response, "JAPANESE").provider());
        assertNull(step(response, "JAPANESE").model());
    }

    @Test
    void resolveProvider_ステップ上書きがあればそれを返す() {
        world.projectAiSettingsService.setLlmProvider(1L, "OLLAMA");
        stepRow(ReviewStepKey.STYLE, "CLAUDE", null);

        assertEquals(AiProvider.CLAUDE, service.resolveProvider(1L, ReviewStepKey.STYLE));
    }

    @Test
    void resolveProvider_ステップ未設定ならプロジェクト既定へ委譲する() {
        world.projectAiSettingsService.setLlmProvider(1L, "OLLAMA");

        assertEquals(AiProvider.OLLAMA, service.resolveProvider(1L, ReviewStepKey.STYLE));
    }

    @Test
    void resolveProvider_行はあるがproviderが未設定ならプロジェクト既定へ委譲する() {
        world.projectAiSettingsService.setLlmProvider(1L, "OPENAI");
        stepRow(ReviewStepKey.STYLE, null, "gpt-4o");

        assertEquals(AiProvider.OPENAI, service.resolveProvider(1L, ReviewStepKey.STYLE));
    }

    @Test
    void resolveProvider_プロジェクトにも無ければシステム既定プロバイダーを返す() {
        systemConfigs(AiProvider.CLAUDE, List.of("o"), List.of("p"), List.of("c"));

        assertEquals(AiProvider.CLAUDE, service.resolveProvider(1L, ReviewStepKey.STYLE));
    }

    @Test
    void resolveModel_ステップ上書きがあればそれを返す() {
        stepRow(ReviewStepKey.STYLE, null, "gpt-4o");

        assertEquals("gpt-4o", service.resolveModel(1L, ReviewStepKey.STYLE));
        // プロジェクト既定やシステム設定は引かない。
        assertEquals(0, world.http.requests().size());
    }

    @Test
    void resolveModel_ステップ未設定ならプロジェクト既定へ委譲する() {
        world.projectAiSettingsService.setLlmProvider(1L, "OPENAI");
        world.projectAiSettingsService.setLlmModel(1L, AiProvider.OPENAI, "gpt-4o-mini");

        assertEquals("gpt-4o-mini", service.resolveModel(1L, ReviewStepKey.STYLE));
    }

    @Test
    void resolveModel_工程のプロバイダーがプロジェクトと異なりモデル未指定ならその工程のプロバイダーのモデルを返す() {
        world.projectAiSettingsService.setLlmProvider(1L, "OPENAI");
        world.projectAiSettingsService.setLlmModel(1L, AiProvider.OPENAI, "gpt-4o");
        world.projectAiSettingsService.setLlmModel(1L, AiProvider.CLAUDE, "claude-sonnet");
        stepRow(ReviewStepKey.STYLE, "CLAUDE", null);

        assertEquals("claude-sonnet", service.resolveModel(1L, ReviewStepKey.STYLE));
    }

    @Test
    void resolveModel_行はあるがmodelが空文字ならプロジェクト既定へ委譲する() {
        world.projectAiSettingsService.setLlmProvider(1L, "OPENAI");
        world.projectAiSettingsService.setLlmModel(1L, AiProvider.OPENAI, "gpt-4o-mini");
        stepRow(ReviewStepKey.STYLE, null, "");

        assertEquals("gpt-4o-mini", service.resolveModel(1L, ReviewStepKey.STYLE));
    }

    @Test
    void selectSetting_新規保存してレスポンスへ反映する() {
        systemConfigs(AiProvider.OPENAI, List.of("o"), List.of("gpt-4o"), List.of("c"));

        ReviewStepSettingsResponse response = service.selectSetting(1L, ReviewStepKey.JAPANESE, "OPENAI", "gpt-4o");

        ProjectReviewStepSetting saved = world.reviewStepRows.saved.get(0);
        assertEquals(1L, saved.getProjectId());
        assertEquals(ReviewStepKey.JAPANESE, saved.getStepKey());
        assertEquals("OPENAI", saved.getLlmProvider());
        assertEquals("gpt-4o", saved.getLlmModel());
        assertEquals("OPENAI", step(response, "JAPANESE").provider());
        assertEquals("gpt-4o", step(response, "JAPANESE").model());
    }

    @Test
    void selectSetting_既存行があれば更新する() {
        systemConfigs(AiProvider.OPENAI, List.of("o"), List.of("p"), List.of("c"));
        ProjectReviewStepSetting existing = stepRow(ReviewStepKey.STYLE, "OLLAMA", "old-model");

        service.selectSetting(1L, ReviewStepKey.STYLE, "CLAUDE", "new-model");

        assertEquals("CLAUDE", existing.getLlmProvider());
        assertEquals("new-model", existing.getLlmModel());
        assertEquals(1, world.reviewStepRows.rows.size());
    }

    @Test
    void selectSetting_空文字を保存すると上書きを解除する() {
        systemConfigs(AiProvider.OPENAI, List.of("o"), List.of("p"), List.of("c"));
        ProjectReviewStepSetting existing = stepRow(ReviewStepKey.STYLE, "CLAUDE", "gpt-4o");

        service.selectSetting(1L, ReviewStepKey.STYLE, "", "");

        assertNull(existing.getLlmProvider());
        assertNull(existing.getLlmModel());
    }

    @Test
    void selectSetting_modelがnullでも上書きを解除する() {
        systemConfigs(AiProvider.OPENAI, List.of("o"), List.of("p"), List.of("c"));
        ProjectReviewStepSetting existing = stepRow(ReviewStepKey.STYLE, null, "gpt-4o");

        service.selectSetting(1L, ReviewStepKey.STYLE, null, null);

        assertNull(existing.getLlmModel());
    }

    @Test
    void selectSetting_不明なproviderは400相当の例外にして保存しない() {
        InvalidReviewInputException e = assertThrows(InvalidReviewInputException.class,
                () -> service.selectSetting(1L, ReviewStepKey.STYLE, "NO_SUCH", "m"));

        assertTrue(e.getMessage().contains("NO_SUCH"), e.getMessage());
        assertTrue(world.reviewStepRows.saved.isEmpty());
    }

    // ---- issue #1676: 工程別の候補を、プロバイダーから取得した一覧にする

    @Test
    void listSettings_各プロバイダーの候補はプロバイダーへ問い合わせた一覧で_プロジェクトを宣言してから問い合わせる() {
        systemConfigs(AiProvider.OLLAMA, List.of("fallback"), List.of("fallback"), List.of("fallback"));
        // OpenAI/Claudeはプロジェクトのキーで問い合わせる。対象プロジェクトの宣言が先でなければキーを引けず取得できない。
        projectKey(AiProvider.OPENAI, "sk-openai");
        projectKey(AiProvider.CLAUDE, "sk-ant");
        world.http.json("GET", "/api/tags", "{\"models\":[{\"name\":\"qwen2.5:7b-instruct\"},{\"name\":\"llama3:8b\"}]}");
        world.http.on("GET", "/cloud/v1/models", request -> request.header("x-api-key") != null
                ? FakeHttpServer.Response.ok("{\"data\":[{\"id\":\"claude-x\"}]}")
                : FakeHttpServer.Response.ok("{\"data\":[{\"id\":\"gpt-a\"},{\"id\":\"gpt-b\"}]}"));

        ReviewStepSettingsResponse response = service.listSettings(1L);

        assertEquals(List.of("qwen2.5:7b-instruct", "llama3:8b"), response.availableModelsByProvider().get("OLLAMA"));
        assertEquals(List.of("gpt-a", "gpt-b"), response.availableModelsByProvider().get("OPENAI"));
        assertEquals(List.of("claude-x"), response.availableModelsByProvider().get("CLAUDE"));
        assertEquals(List.of(), response.fallbackProviders());
        assertEquals("OLLAMA", response.defaultProvider());
        // provider未設定の工程用は、システム既定プロバイダーの取得結果
        assertEquals(List.of("qwen2.5:7b-instruct", "llama3:8b"), response.availableModels());
    }

    @Test
    void listSettings_取得に失敗したプロバイダーはシステム設定の一覧に戻りfallbackProvidersに載る() {
        systemConfigs(AiProvider.OPENAI, List.of("qwen2.5:7b-instruct"), List.of("gpt-default"), List.of("claude-default"));
        projectKey(AiProvider.OPENAI, "sk-openai");
        // Ollamaは接続先が応答しない(404)、Claudeはプロジェクトにキーが無い。
        world.http.on("GET", "/cloud/v1/models", FakeHttpServer.Response.ok("{\"data\":[{\"id\":\"gpt-a\"}]}"));

        ReviewStepSettingsResponse response = service.listSettings(1L);

        assertEquals(List.of("qwen2.5:7b-instruct"), response.availableModelsByProvider().get("OLLAMA"));
        assertEquals(List.of("gpt-a"), response.availableModelsByProvider().get("OPENAI"));
        assertEquals(List.of("claude-default"), response.availableModelsByProvider().get("CLAUDE"));
        assertEquals(List.of("OLLAMA", "CLAUDE"), response.fallbackProviders());
        assertEquals(List.of("gpt-a"), response.availableModels());
    }

    @Test
    void listSettings_既定プロバイダーの取得に失敗したときprovider未設定の工程用もシステム設定の一覧でfallbackに載る() {
        systemConfigs(AiProvider.CLAUDE, List.of("o"), List.of("p"), List.of("claude-default"));

        ReviewStepSettingsResponse response = service.listSettings(1L);

        assertEquals(List.of("claude-default"), response.availableModels());
        assertTrue(response.fallbackProviders().contains("CLAUDE"));
        assertEquals("CLAUDE", response.defaultProvider());
    }

    @Test
    void listSettings_Claudeの候補はAnthropicの一覧になる() {
        systemConfigs(AiProvider.OLLAMA, List.of("o"), List.of("p"), List.of("fallback"));
        projectKey(AiProvider.CLAUDE, "sk-ant");
        stepRow(ReviewStepKey.STYLE, "CLAUDE", null);
        world.http.json("GET", "/api/tags", "{\"models\":[{\"name\":\"o\"}]}");
        world.http.on("GET", "/cloud/v1/models", request -> request.header("x-api-key") != null
                ? FakeHttpServer.Response.ok("{\"data\":[{\"id\":\"claude-sonnet-4\"},{\"id\":\"claude-haiku-4\"}]}")
                : FakeHttpServer.Response.json(401, "{}"));

        ReviewStepSettingsResponse response = service.listSettings(1L);

        assertEquals(List.of("claude-sonnet-4", "claude-haiku-4"), response.availableModelsByProvider().get("CLAUDE"));
        assertEquals(List.of("OPENAI"), response.fallbackProviders());
        assertEquals("CLAUDE", step(response, "STYLE").provider());
    }

    @Test
    void listSettings_システム既定プロバイダーが解決できなければ既定の候補はシステム設定の一覧になる() {
        // 既定プロバイダーが不明な値だとfromStringが例外を投げるので、provider()がnullになる経路(空)を使う。
        world.llmConfig(AiProvider.OLLAMA, root + "/v1", "", "m", List.of("m"), false);
        world.llmConfig(AiProvider.OPENAI, null, "", "p", List.of("p"), false);
        world.llmConfig(AiProvider.CLAUDE, null, "", "c", List.of("c"), false);
        world.http.on("GET", "/api/internal/platform/llm-config", request -> request.query() == null
                ? FakeHttpServer.Response.ok(
                        "{\"provider\":\"\",\"baseUrl\":null,\"apiKey\":\"\",\"defaultModel\":\"d\","
                                + "\"availableModels\":[\"d1\",\"d2\"],\"requestTimeoutSeconds\":10}")
                : null);

        ReviewStepSettingsResponse response = service.listSettings(1L);

        assertNull(response.defaultProvider());
        assertEquals(List.of("d1", "d2"), response.availableModels());
    }

    // ---- issue #1676 レビュー指摘: GET review-steps は全体で3秒以内(要件4)

    @Test
    void listSettings_全プロバイダーがブロックしても全体の所要時間は予算で有界で_取れなかった分は代替一覧になる() {
        systemConfigs(AiProvider.OLLAMA, List.of("fallback-model"), List.of("fallback-model"), List.of("fallback-model"));
        projectKey(AiProvider.OPENAI, "sk-openai");
        projectKey(AiProvider.CLAUDE, "sk-ant");
        blockingProviders();
        ReviewStepModelService bounded = new ReviewStepModelService(
                world.reviewStepSettingRepository, world.llmModelService, world.llmConfigProvider,
                world.providerModelCatalog, Duration.ofMillis(600));

        long started = System.nanoTime();
        ReviewStepSettingsResponse response = bounded.listSettings(1L);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMillis < 1_500, "所要時間が予算で有界でない: " + elapsedMillis + "ms");
        assertEquals(List.of("OLLAMA", "OPENAI", "CLAUDE"), response.fallbackProviders());
        assertEquals(List.of("fallback-model"), response.availableModelsByProvider().get("CLAUDE"));
        assertEquals(List.of("fallback-model"), response.availableModels());
    }

    @Test
    void listSettings_予算を使い切った後のプロバイダーには残りのごく僅かな待ち時間しか渡さない() {
        systemConfigs(AiProvider.OLLAMA, List.of("fallback-model"), List.of("fallback-model"), List.of("fallback-model"));
        projectKey(AiProvider.OPENAI, "sk-openai");
        projectKey(AiProvider.CLAUDE, "sk-ant");
        blockingProviders();
        ReviewStepModelService bounded = new ReviewStepModelService(
                world.reviewStepSettingRepository, world.llmModelService, world.llmConfigProvider,
                world.providerModelCatalog, Duration.ofMillis(300));

        long started = System.nanoTime();
        bounded.listSettings(1L);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        // 先頭のプロバイダーが予算(300ms)を使い切る。後ろ2つに予算の残りが渡されるなら合計は予算の近くで終わる。
        // 各プロバイダーへ予算全体(や5秒)を渡してしまうと、3つ合わせて予算の数倍かかる。
        assertTrue(elapsedMillis < 1_200, "後続のプロバイダーが予算を超えて待った: " + elapsedMillis + "ms");
    }

    @Test
    void selectSetting_も保存後の一覧取得が予算で有界() {
        systemConfigs(AiProvider.OPENAI, List.of("fallback-model"), List.of("fallback-model"), List.of("fallback-model"));
        projectKey(AiProvider.OPENAI, "sk-openai");
        projectKey(AiProvider.CLAUDE, "sk-ant");
        blockingProviders();
        ReviewStepModelService bounded = new ReviewStepModelService(
                world.reviewStepSettingRepository, world.llmModelService, world.llmConfigProvider,
                world.providerModelCatalog, Duration.ofMillis(600));

        long started = System.nanoTime();
        ReviewStepSettingsResponse response = bounded.selectSetting(1L, ReviewStepKey.JAPANESE, "OPENAI", "gpt-4o");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMillis < 1_500, "所要時間が予算で有界でない: " + elapsedMillis + "ms");
        assertEquals(List.of("OLLAMA", "OPENAI", "CLAUDE"), response.fallbackProviders());
    }

    @Test
    void 既定の予算は応答時間予算の3秒未満である() {
        assertTrue(ReviewStepModelService.FETCH_BUDGET.compareTo(Duration.ofSeconds(3)) < 0);
    }
}
