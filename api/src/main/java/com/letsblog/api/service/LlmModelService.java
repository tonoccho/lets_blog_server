package com.letsblog.api.service;

import com.letsblog.api.ai.AiProvider;
import com.letsblog.api.ai.LlmConfigProvider;
import com.letsblog.api.domain.Project;
import com.letsblog.api.dto.LlmModelListResponse;
import com.letsblog.api.dto.LlmProviderListResponse;
import com.letsblog.api.repository.ProjectRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * プロジェクトごとのLLM利用モデル/AIプロバイダーの一覧・選択を扱う(issue #376/#530)。
 * 外部ホスト型LLM APIにはOllamaのようなローカルインストール/pullの概念がないため、
 * モデルについては「どのモデル名を使うか」の選択のみを扱う。
 */
@Service
public class LlmModelService {

    private final ProjectRepository projectRepository;
    private final LlmConfigProvider llmConfigProvider;
    private final String globalDefaultModel;
    private final List<String> availableModels;

    public LlmModelService(
            ProjectRepository projectRepository,
            LlmConfigProvider llmConfigProvider,
            @Value("${app.llm-model}") String globalDefaultModel,
            @Value("${app.llm-available-models}") String availableModelsCsv) {
        this.projectRepository = projectRepository;
        this.llmConfigProvider = llmConfigProvider;
        this.globalDefaultModel = globalDefaultModel;
        this.availableModels = Arrays.stream(availableModelsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    public LlmModelListResponse listModelsForProject(Long projectId) {
        return new LlmModelListResponse(availableModels, getSelectedModel(projectId));
    }

    /**
     * プロジェクトの選択中モデルを返す。未選択(null)ならグローバルデフォルトにフォールバックする。
     */
    public String getSelectedModel(Long projectId) {
        Project project = getProjectEntity(projectId);
        String selected = project.getLlmModel();
        return selected == null || selected.isBlank() ? globalDefaultModel : selected;
    }

    public LlmModelListResponse selectModel(Long projectId, String modelName) {
        Project project = getProjectEntity(projectId);
        project.setLlmModel(modelName);
        projectRepository.save(project);
        return listModelsForProject(projectId);
    }

    /**
     * プロジェクトのAIプロバイダー設定一覧を返す。selectedは未上書き時null(「グローバル既定を使用」)を
     * そのまま返す(getSelectedProviderのように解決済み値へフォールバックしない)。
     * Web/拡張のUIが「グローバル既定を使用」の空選択肢を表現できるようにするため。
     */
    public LlmProviderListResponse listProvidersForProject(Long projectId) {
        Project project = getProjectEntity(projectId);
        List<String> availableProviders = Arrays.stream(AiProvider.values()).map(Enum::name).toList();
        return new LlmProviderListResponse(availableProviders, project.getLlmProvider());
    }

    /**
     * プロジェクトの選択中AIプロバイダーを返す。未選択(null/空)ならシステム設定の既定プロバイダーに
     * フォールバックする(LlmClient呼び出し時に実際に使うプロバイダーを解決するため)。
     */
    public AiProvider getSelectedProvider(Long projectId) {
        Project project = getProjectEntity(projectId);
        AiProvider override = AiProvider.fromString(project.getLlmProvider());
        return override != null ? override : llmConfigProvider.provider();
    }

    /** providerが空/nullの場合はプロジェクト単位の上書きを解除する(グローバル既定へ戻す)。 */
    public LlmProviderListResponse selectProvider(Long projectId, String provider) {
        AiProvider parsed = AiProvider.fromString(provider);
        Project project = getProjectEntity(projectId);
        project.setLlmProvider(parsed != null ? parsed.name() : null);
        projectRepository.save(project);
        return listProvidersForProject(projectId);
    }

    private Project getProjectEntity(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));
    }
}
