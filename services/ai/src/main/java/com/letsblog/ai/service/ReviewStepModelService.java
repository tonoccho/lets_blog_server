package com.letsblog.ai.service;

import com.letsblog.ai.ai.AiProvider;
import com.letsblog.ai.ai.LlmConfigProvider;
import com.letsblog.ai.domain.ProjectReviewStepSetting;
import com.letsblog.ai.domain.ReviewStepKey;
import com.letsblog.ai.dto.ReviewStepSettingResponse;
import com.letsblog.ai.dto.ReviewStepSettingsResponse;
import com.letsblog.ai.repository.ProjectReviewStepSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * プロジェクト × レビューステップ(issue #1210)ごとのAI(LLM)プロバイダー/モデル設定の
 * 取得・保存・解決を扱う(issue #1211)。
 *
 * <p>解決順序(ステップ設定 → プロジェクト既定 → グローバル既定)のうち「プロジェクト既定 →
 * グローバル既定」の部分は{@link LlmModelService#getSelectedProvider}/
 * {@link LlmModelService#getSelectedModel}が既に実装しているため、本サービスはステップ設定が
 * 無い場合にそこへ委譲するだけで済む。
 */
@Service
public class ReviewStepModelService {

    private final ProjectReviewStepSettingRepository repository;
    private final LlmModelService llmModelService;
    private final LlmConfigProvider llmConfigProvider;

    public ReviewStepModelService(
            ProjectReviewStepSettingRepository repository,
            LlmModelService llmModelService,
            LlmConfigProvider llmConfigProvider) {
        this.repository = repository;
        this.llmModelService = llmModelService;
        this.llmConfigProvider = llmConfigProvider;
    }

    /**
     * 5ステップぶんの選択値(未設定はnull)と選択可能なprovider/model一覧を返す。未設定を
     * 解決済み値へ埋めない(LlmModelService#listProvidersForProjectと同じ契約)。
     */
    @Transactional(readOnly = true)
    public ReviewStepSettingsResponse listSettings(Long projectId) {
        Map<ReviewStepKey, ProjectReviewStepSetting> byStepKey = repository.findByProjectId(projectId).stream()
                .collect(Collectors.toMap(ProjectReviewStepSetting::getStepKey, Function.identity()));

        var steps = Arrays.stream(ReviewStepKey.values())
                .map(stepKey -> {
                    ProjectReviewStepSetting setting = byStepKey.get(stepKey);
                    return new ReviewStepSettingResponse(
                            stepKey.name(),
                            setting != null ? setting.getLlmProvider() : null,
                            setting != null ? setting.getLlmModel() : null);
                })
                .toList();

        var availableProviders = Arrays.stream(AiProvider.values()).map(Enum::name).toList();
        return new ReviewStepSettingsResponse(steps, availableProviders, llmConfigProvider.availableModels());
    }

    /** provider/modelが空/nullの場合は、そのステップの上書きを解除する。 */
    @Transactional
    public ReviewStepSettingsResponse selectSetting(Long projectId, ReviewStepKey stepKey, String provider, String model) {
        ProjectReviewStepSetting setting = repository.findByProjectIdAndStepKey(projectId, stepKey)
                .orElseGet(() -> new ProjectReviewStepSetting(projectId, stepKey));

        AiProvider parsedProvider = AiProvider.fromString(provider);
        setting.setLlmProvider(parsedProvider != null ? parsedProvider.name() : null);
        setting.setLlmModel(model == null || model.isBlank() ? null : model);
        repository.save(setting);

        return listSettings(projectId);
    }

    /**
     * 呼び出し時に実際に使うプロバイダーを解決する(ステップ設定 → プロジェクト既定 →
     * グローバル既定)。
     */
    @Transactional(readOnly = true)
    public AiProvider resolveProvider(Long projectId, ReviewStepKey stepKey) {
        return repository.findByProjectIdAndStepKey(projectId, stepKey)
                .map(ProjectReviewStepSetting::getLlmProvider)
                .map(AiProvider::fromString)
                .orElseGet(() -> llmModelService.getSelectedProvider(projectId));
    }

    /** 呼び出し時に実際に使うモデルを解決する(ステップ設定 → プロジェクト既定 → グローバル既定)。 */
    @Transactional(readOnly = true)
    public String resolveModel(Long projectId, ReviewStepKey stepKey) {
        return repository.findByProjectIdAndStepKey(projectId, stepKey)
                .map(ProjectReviewStepSetting::getLlmModel)
                .filter(m -> !m.isBlank())
                .orElseGet(() -> llmModelService.getSelectedModel(projectId));
    }
}
