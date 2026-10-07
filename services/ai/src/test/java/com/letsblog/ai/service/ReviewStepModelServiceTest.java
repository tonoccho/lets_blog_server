package com.letsblog.ai.service;

import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.ai.LlmConfigProvider;
import com.letsblog.ai.domain.ProjectReviewStepSetting;
import com.letsblog.ai.domain.ReviewStepKey;
import com.letsblog.ai.dto.ReviewStepSettingResponse;
import com.letsblog.ai.dto.ReviewStepSettingsResponse;
import com.letsblog.ai.repository.ProjectReviewStepSettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReviewStepModelServiceの回帰テスト(issue #1211)。多段レビュー(#1210)のステップ別
 * provider/model設定の取得・保存・解決(ステップ設定→プロジェクト既定→グローバル既定)を検証する。
 * プロジェクト既定→グローバル既定のフォールバックはLlmModelServiceが既に実装済みのため、
 * 本サービスはそこへ委譲するだけであることをモックで確認する。
 */
@ExtendWith(MockitoExtension.class)
class ReviewStepModelServiceTest {

    @Mock
    private ProjectReviewStepSettingRepository repository;
    @Mock
    private LlmModelService llmModelService;
    @Mock
    private LlmConfigProvider llmConfigProvider;
    @Mock
    private ProviderModelCatalog providerModelCatalog;

    private ReviewStepModelService service() {
        return new ReviewStepModelService(repository, llmModelService, llmConfigProvider, providerModelCatalog);
    }

    @Test
    void listSettings_5ステップを確定順序で返し未設定はnull() {
        when(repository.findByProjectId(1L)).thenReturn(List.of());
        when(llmConfigProvider.availableModels()).thenReturn(List.of("gpt-4o-mini", "gpt-4o"));

        ReviewStepSettingsResponse response = service().listSettings(1L);

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
        when(repository.findByProjectId(1L)).thenReturn(List.of());
        when(llmConfigProvider.availableModels()).thenReturn(List.of("gpt-4o-mini"));
        when(llmConfigProvider.availableModelsFor(AiProvider.OLLAMA)).thenReturn(List.of("qwen2.5:7b-instruct"));
        when(llmConfigProvider.availableModelsFor(AiProvider.OPENAI)).thenReturn(List.of("gpt-4o-mini", "gpt-4o"));
        when(llmConfigProvider.availableModelsFor(AiProvider.CLAUDE)).thenReturn(List.of("claude-3-5-haiku-20241022"));

        ReviewStepSettingsResponse response = service().listSettings(1L);

        assertEquals(List.of("OLLAMA", "OPENAI", "CLAUDE"), List.copyOf(response.availableModelsByProvider().keySet()));
        assertEquals(List.of("qwen2.5:7b-instruct"), response.availableModelsByProvider().get("OLLAMA"));
        assertEquals(List.of("gpt-4o-mini", "gpt-4o"), response.availableModelsByProvider().get("OPENAI"));
        assertEquals(List.of("claude-3-5-haiku-20241022"), response.availableModelsByProvider().get("CLAUDE"));
        // provider未設定の工程用(システム既定provider)は現行のまま
        assertEquals(List.of("gpt-4o-mini"), response.availableModels());
    }

    @Test
    void listSettings_設定済みステップはその値を返す() {
        ProjectReviewStepSetting factCheck = new ProjectReviewStepSetting(1L, ReviewStepKey.FACT_CHECK);
        factCheck.setLlmProvider("OPENAI");
        factCheck.setLlmModel("gpt-4o");
        when(repository.findByProjectId(1L)).thenReturn(List.of(factCheck));
        when(llmConfigProvider.availableModels()).thenReturn(List.of("gpt-4o"));

        ReviewStepSettingsResponse response = service().listSettings(1L);

        ReviewStepSettingResponse factCheckStep = response.steps().stream()
                .filter(step -> step.stepKey().equals("FACT_CHECK"))
                .findFirst()
                .orElseThrow();
        assertEquals("OPENAI", factCheckStep.provider());
        assertEquals("gpt-4o", factCheckStep.model());

        ReviewStepSettingResponse japaneseStep = response.steps().stream()
                .filter(step -> step.stepKey().equals("JAPANESE"))
                .findFirst()
                .orElseThrow();
        assertNull(japaneseStep.provider());
        assertNull(japaneseStep.model());
    }

    @Test
    void resolveProvider_ステップ上書きがあればそれを返す() {
        ProjectReviewStepSetting style = new ProjectReviewStepSetting(1L, ReviewStepKey.STYLE);
        style.setLlmProvider("CLAUDE");
        when(repository.findByProjectIdAndStepKey(1L, ReviewStepKey.STYLE)).thenReturn(Optional.of(style));

        assertEquals(AiProvider.CLAUDE, service().resolveProvider(1L, ReviewStepKey.STYLE));
        verify(llmModelService, never()).getSelectedProvider(any());
    }

    @Test
    void resolveProvider_ステップ未設定ならプロジェクト既定へ委譲する() {
        when(repository.findByProjectIdAndStepKey(1L, ReviewStepKey.STYLE)).thenReturn(Optional.empty());
        when(llmModelService.getSelectedProvider(1L)).thenReturn(AiProvider.OLLAMA);

        assertEquals(AiProvider.OLLAMA, service().resolveProvider(1L, ReviewStepKey.STYLE));
        verify(llmModelService).getSelectedProvider(1L);
    }

    @Test
    void resolveProvider_行はあるがproviderが未設定ならプロジェクト既定へ委譲する() {
        ProjectReviewStepSetting style = new ProjectReviewStepSetting(1L, ReviewStepKey.STYLE);
        style.setLlmModel("gpt-4o");
        when(repository.findByProjectIdAndStepKey(1L, ReviewStepKey.STYLE)).thenReturn(Optional.of(style));
        when(llmModelService.getSelectedProvider(1L)).thenReturn(AiProvider.OPENAI);

        assertEquals(AiProvider.OPENAI, service().resolveProvider(1L, ReviewStepKey.STYLE));
    }

    @Test
    void resolveModel_ステップ上書きがあればそれを返す() {
        ProjectReviewStepSetting style = new ProjectReviewStepSetting(1L, ReviewStepKey.STYLE);
        style.setLlmModel("gpt-4o");
        when(repository.findByProjectIdAndStepKey(1L, ReviewStepKey.STYLE)).thenReturn(Optional.of(style));

        assertEquals("gpt-4o", service().resolveModel(1L, ReviewStepKey.STYLE));
        verify(llmModelService, never()).getSelectedModel(any(), any());
    }

    @Test
    void resolveModel_ステップ未設定ならプロジェクト既定へ委譲する() {
        when(repository.findByProjectIdAndStepKey(1L, ReviewStepKey.STYLE)).thenReturn(Optional.empty());
        when(llmModelService.getSelectedProvider(1L)).thenReturn(AiProvider.OPENAI);
        when(llmModelService.getSelectedModel(1L, AiProvider.OPENAI)).thenReturn("gpt-4o-mini");

        assertEquals("gpt-4o-mini", service().resolveModel(1L, ReviewStepKey.STYLE));
        verify(llmModelService).getSelectedModel(1L, AiProvider.OPENAI);
    }

    @Test
    void resolveModel_工程のプロバイダーがプロジェクトと異なりモデル未指定ならその工程のプロバイダーのモデルを返す() {
        ProjectReviewStepSetting style = new ProjectReviewStepSetting(1L, ReviewStepKey.STYLE);
        style.setLlmProvider("CLAUDE");
        when(repository.findByProjectIdAndStepKey(1L, ReviewStepKey.STYLE)).thenReturn(Optional.of(style));
        when(llmModelService.getSelectedModel(1L, AiProvider.CLAUDE)).thenReturn("claude-sonnet");

        assertEquals("claude-sonnet", service().resolveModel(1L, ReviewStepKey.STYLE));
        verify(llmModelService, never()).getSelectedModel(any());
    }

    @Test
    void selectSetting_新規保存してレスポンスへ反映する() {
        when(repository.findByProjectIdAndStepKey(1L, ReviewStepKey.JAPANESE)).thenReturn(Optional.empty());
        when(repository.save(any(ProjectReviewStepSetting.class))).thenAnswer(inv -> inv.getArgument(0));
        ProjectReviewStepSetting saved = new ProjectReviewStepSetting(1L, ReviewStepKey.JAPANESE);
        saved.setLlmProvider("OPENAI");
        saved.setLlmModel("gpt-4o");
        when(repository.findByProjectId(1L)).thenReturn(List.of(saved));
        when(llmConfigProvider.availableModels()).thenReturn(List.of("gpt-4o"));

        ReviewStepSettingsResponse response = service().selectSetting(1L, ReviewStepKey.JAPANESE, "OPENAI", "gpt-4o");

        ArgumentCaptor<ProjectReviewStepSetting> captor = ArgumentCaptor.forClass(ProjectReviewStepSetting.class);
        verify(repository).save(captor.capture());
        assertEquals(1L, captor.getValue().getProjectId());
        assertEquals(ReviewStepKey.JAPANESE, captor.getValue().getStepKey());
        assertEquals("OPENAI", captor.getValue().getLlmProvider());
        assertEquals("gpt-4o", captor.getValue().getLlmModel());

        ReviewStepSettingResponse japaneseStep = response.steps().stream()
                .filter(step -> step.stepKey().equals("JAPANESE"))
                .findFirst()
                .orElseThrow();
        assertEquals("OPENAI", japaneseStep.provider());
        assertEquals("gpt-4o", japaneseStep.model());
    }

    @Test
    void selectSetting_既存行があれば更新する() {
        ProjectReviewStepSetting existing = new ProjectReviewStepSetting(1L, ReviewStepKey.STYLE);
        existing.setLlmProvider("OLLAMA");
        existing.setLlmModel("old-model");
        when(repository.findByProjectIdAndStepKey(1L, ReviewStepKey.STYLE)).thenReturn(Optional.of(existing));
        when(repository.save(any(ProjectReviewStepSetting.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findByProjectId(1L)).thenReturn(List.of(existing));
        when(llmConfigProvider.availableModels()).thenReturn(List.of());

        service().selectSetting(1L, ReviewStepKey.STYLE, "CLAUDE", "new-model");

        assertEquals("CLAUDE", existing.getLlmProvider());
        assertEquals("new-model", existing.getLlmModel());
    }

    @Test
    void selectSetting_空文字を保存すると上書きを解除する() {
        ProjectReviewStepSetting existing = new ProjectReviewStepSetting(1L, ReviewStepKey.STYLE);
        existing.setLlmProvider("CLAUDE");
        existing.setLlmModel("gpt-4o");
        when(repository.findByProjectIdAndStepKey(1L, ReviewStepKey.STYLE)).thenReturn(Optional.of(existing));
        when(repository.save(any(ProjectReviewStepSetting.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findByProjectId(1L)).thenReturn(List.of(existing));
        when(llmConfigProvider.availableModels()).thenReturn(List.of());

        service().selectSetting(1L, ReviewStepKey.STYLE, "", "");

        ArgumentCaptor<ProjectReviewStepSetting> captor = ArgumentCaptor.forClass(ProjectReviewStepSetting.class);
        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getLlmProvider());
        assertNull(captor.getValue().getLlmModel());
    }

    @Test
    void selectSetting_modelがnullでも上書きを解除する() {
        ProjectReviewStepSetting existing = new ProjectReviewStepSetting(1L, ReviewStepKey.STYLE);
        existing.setLlmModel("gpt-4o");
        when(repository.findByProjectIdAndStepKey(1L, ReviewStepKey.STYLE)).thenReturn(Optional.of(existing));
        when(repository.save(any(ProjectReviewStepSetting.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repository.findByProjectId(1L)).thenReturn(List.of(existing));
        when(llmConfigProvider.availableModels()).thenReturn(List.of());

        service().selectSetting(1L, ReviewStepKey.STYLE, null, null);

        ArgumentCaptor<ProjectReviewStepSetting> captor = ArgumentCaptor.forClass(ProjectReviewStepSetting.class);
        verify(repository).save(captor.capture());
        assertNull(captor.getValue().getLlmModel());
    }

    @Test
    void resolveModel_行はあるがmodelが空文字ならプロジェクト既定へ委譲する() {
        ProjectReviewStepSetting style = new ProjectReviewStepSetting(1L, ReviewStepKey.STYLE);
        style.setLlmModel("");
        when(repository.findByProjectIdAndStepKey(1L, ReviewStepKey.STYLE)).thenReturn(Optional.of(style));
        when(llmModelService.getSelectedProvider(1L)).thenReturn(AiProvider.OPENAI);
        when(llmModelService.getSelectedModel(1L, AiProvider.OPENAI)).thenReturn("gpt-4o-mini");

        assertEquals("gpt-4o-mini", service().resolveModel(1L, ReviewStepKey.STYLE));
    }

    // ---- issue #1676: 工程別の候補を、プロバイダーから取得した一覧にする

    @Test
    void listSettings_各プロバイダーの候補はプロバイダーへ問い合わせた一覧で_プロジェクトを宣言してから問い合わせる() {
        when(repository.findByProjectId(1L)).thenReturn(List.of());
        when(llmConfigProvider.provider()).thenReturn(AiProvider.OLLAMA);
        when(providerModelCatalog.fetch(eq(AiProvider.OLLAMA), any(Duration.class)))
                .thenReturn(Optional.of(List.of("qwen2.5:7b-instruct", "llama3:8b")));
        when(providerModelCatalog.fetch(eq(AiProvider.OPENAI), any(Duration.class))).thenReturn(Optional.of(List.of("gpt-a", "gpt-b")));
        when(providerModelCatalog.fetch(eq(AiProvider.CLAUDE), any(Duration.class))).thenReturn(Optional.of(List.of("claude-x")));

        ReviewStepSettingsResponse response = service().listSettings(1L);

        assertEquals(List.of("qwen2.5:7b-instruct", "llama3:8b"), response.availableModelsByProvider().get("OLLAMA"));
        assertEquals(List.of("gpt-a", "gpt-b"), response.availableModelsByProvider().get("OPENAI"));
        assertEquals(List.of("claude-x"), response.availableModelsByProvider().get("CLAUDE"));
        assertEquals(List.of(), response.fallbackProviders());
        assertEquals("OLLAMA", response.defaultProvider());
        // provider未設定の工程用は、システム既定プロバイダーの取得結果
        assertEquals(List.of("qwen2.5:7b-instruct", "llama3:8b"), response.availableModels());
        var order = inOrder(llmConfigProvider, providerModelCatalog);
        order.verify(llmConfigProvider).useProject(1L);
        order.verify(providerModelCatalog).fetch(eq(AiProvider.OLLAMA), any(Duration.class));
    }

    @Test
    void listSettings_取得に失敗したプロバイダーはシステム設定の一覧に戻りfallbackProvidersに載る() {
        when(repository.findByProjectId(1L)).thenReturn(List.of());
        when(llmConfigProvider.provider()).thenReturn(AiProvider.OPENAI);
        when(providerModelCatalog.fetch(eq(AiProvider.OLLAMA), any(Duration.class))).thenReturn(Optional.empty());
        when(providerModelCatalog.fetch(eq(AiProvider.OPENAI), any(Duration.class))).thenReturn(Optional.of(List.of("gpt-a")));
        when(providerModelCatalog.fetch(eq(AiProvider.CLAUDE), any(Duration.class))).thenReturn(Optional.empty());
        when(llmConfigProvider.availableModelsFor(AiProvider.OLLAMA)).thenReturn(List.of("qwen2.5:7b-instruct"));
        when(llmConfigProvider.availableModelsFor(AiProvider.CLAUDE)).thenReturn(List.of("claude-default"));

        ReviewStepSettingsResponse response = service().listSettings(1L);

        assertEquals(List.of("qwen2.5:7b-instruct"), response.availableModelsByProvider().get("OLLAMA"));
        assertEquals(List.of("gpt-a"), response.availableModelsByProvider().get("OPENAI"));
        assertEquals(List.of("claude-default"), response.availableModelsByProvider().get("CLAUDE"));
        assertEquals(List.of("OLLAMA", "CLAUDE"), response.fallbackProviders());
        assertEquals(List.of("gpt-a"), response.availableModels());
    }

    @Test
    void listSettings_既定プロバイダーの取得に失敗したときprovider未設定の工程用もシステム設定の一覧でfallbackに載る() {
        when(repository.findByProjectId(1L)).thenReturn(List.of());
        when(llmConfigProvider.provider()).thenReturn(AiProvider.CLAUDE);
        when(providerModelCatalog.fetch(any(), any(Duration.class))).thenReturn(Optional.empty());
        when(llmConfigProvider.availableModelsFor(any())).thenReturn(List.of("claude-default"));

        ReviewStepSettingsResponse response = service().listSettings(1L);

        assertEquals(List.of("claude-default"), response.availableModels());
        assertTrue(response.fallbackProviders().contains("CLAUDE"));
        assertEquals("CLAUDE", response.defaultProvider());
    }

    @Test
    void listSettings_Claudeの候補はProviderModelCatalogが返すAnthropicの一覧になる() {
        ProjectReviewStepSetting style = new ProjectReviewStepSetting(1L, ReviewStepKey.STYLE);
        style.setLlmProvider("CLAUDE");
        when(repository.findByProjectId(1L)).thenReturn(List.of(style));
        when(llmConfigProvider.provider()).thenReturn(AiProvider.OLLAMA);
        when(providerModelCatalog.fetch(eq(AiProvider.OLLAMA), any(Duration.class))).thenReturn(Optional.of(List.of("o")));
        when(providerModelCatalog.fetch(eq(AiProvider.OPENAI), any(Duration.class))).thenReturn(Optional.of(List.of("p")));
        when(providerModelCatalog.fetch(eq(AiProvider.CLAUDE), any(Duration.class)))
                .thenReturn(Optional.of(List.of("claude-sonnet-4", "claude-haiku-4")));

        ReviewStepSettingsResponse response = service().listSettings(1L);

        assertEquals(List.of("claude-sonnet-4", "claude-haiku-4"), response.availableModelsByProvider().get("CLAUDE"));
        assertEquals(List.of(), response.fallbackProviders());
    }

    // ---- issue #1676 レビュー指摘: GET review-steps は全体で3秒以内(要件4)

    /** 渡された待ち時間いっぱいブロックして失敗する(取得できないプロバイダー)。呼び出し回数と待ち時間を記録する。 */
    private List<Duration> stubBlockingCatalog() {
        List<Duration> requested = new CopyOnWriteArrayList<>();
        when(providerModelCatalog.fetch(any(), any(Duration.class))).thenAnswer(invocation -> {
            Duration wait = invocation.getArgument(1);
            requested.add(wait);
            Thread.sleep(Math.min(wait.toMillis(), 5_000));
            return Optional.<List<String>>empty();
        });
        return requested;
    }

    private ReviewStepModelService boundedService(Duration budget) {
        return new ReviewStepModelService(repository, llmModelService, llmConfigProvider, providerModelCatalog, budget);
    }

    @Test
    void listSettings_全プロバイダーがブロックしても全体の所要時間は予算で有界で_取れなかった分は代替一覧になる() {
        when(repository.findByProjectId(1L)).thenReturn(List.of());
        when(llmConfigProvider.provider()).thenReturn(AiProvider.OLLAMA);
        when(llmConfigProvider.availableModelsFor(any())).thenReturn(List.of("fallback-model"));
        List<Duration> requested = stubBlockingCatalog();

        long started = System.nanoTime();
        ReviewStepSettingsResponse response = boundedService(Duration.ofMillis(600)).listSettings(1L);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMillis < 1_500, "所要時間が予算で有界でない: " + elapsedMillis + "ms");
        assertEquals(List.of("OLLAMA", "OPENAI", "CLAUDE"), response.fallbackProviders());
        assertEquals(List.of("fallback-model"), response.availableModelsByProvider().get("CLAUDE"));
        assertEquals(List.of("fallback-model"), response.availableModels());
        requested.forEach(wait -> assertTrue(wait.compareTo(Duration.ofMillis(600)) <= 0, wait.toString()));
    }

    @Test
    void listSettings_予算を使い切った後のプロバイダーには残りのごく僅かな待ち時間しか渡さない() {
        when(repository.findByProjectId(1L)).thenReturn(List.of());
        when(llmConfigProvider.provider()).thenReturn(AiProvider.OLLAMA);
        when(llmConfigProvider.availableModelsFor(any())).thenReturn(List.of("fallback-model"));
        List<Duration> requested = stubBlockingCatalog();

        boundedService(Duration.ofMillis(300)).listSettings(1L);

        assertEquals(3, requested.size(), requested.toString());
        requested.subList(1, 3).forEach(wait -> assertTrue(wait.compareTo(Duration.ofMillis(50)) < 0, wait.toString()));
    }

    @Test
    void selectSetting_も保存後の一覧取得が予算で有界() {
        when(repository.findByProjectIdAndStepKey(1L, ReviewStepKey.JAPANESE)).thenReturn(Optional.empty());
        when(repository.findByProjectId(1L)).thenReturn(List.of());
        when(llmConfigProvider.provider()).thenReturn(AiProvider.OPENAI);
        when(llmConfigProvider.availableModelsFor(any())).thenReturn(List.of("fallback-model"));
        stubBlockingCatalog();

        long started = System.nanoTime();
        ReviewStepSettingsResponse response =
                boundedService(Duration.ofMillis(600)).selectSetting(1L, ReviewStepKey.JAPANESE, "OPENAI", "gpt-4o");
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMillis < 1_500, "所要時間が予算で有界でない: " + elapsedMillis + "ms");
        assertEquals(List.of("OLLAMA", "OPENAI", "CLAUDE"), response.fallbackProviders());
    }

    @Test
    void 既定の予算は応答時間予算の3秒未満である() {
        assertTrue(ReviewStepModelService.FETCH_BUDGET.compareTo(Duration.ofSeconds(3)) < 0);
    }
}
