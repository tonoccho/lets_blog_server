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

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
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

    private ReviewStepModelService service() {
        return new ReviewStepModelService(repository, llmModelService, llmConfigProvider);
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
        verify(llmModelService, never()).getSelectedModel(any());
    }

    @Test
    void resolveModel_ステップ未設定ならプロジェクト既定へ委譲する() {
        when(repository.findByProjectIdAndStepKey(1L, ReviewStepKey.STYLE)).thenReturn(Optional.empty());
        when(llmModelService.getSelectedModel(1L)).thenReturn("gpt-4o-mini");

        assertEquals("gpt-4o-mini", service().resolveModel(1L, ReviewStepKey.STYLE));
        verify(llmModelService).getSelectedModel(1L);
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
        when(llmModelService.getSelectedModel(1L)).thenReturn("gpt-4o-mini");

        assertEquals("gpt-4o-mini", service().resolveModel(1L, ReviewStepKey.STYLE));
    }
}
