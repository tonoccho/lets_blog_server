package com.letsblog.api.service;

import com.letsblog.api.domain.Project;
import com.letsblog.api.dto.LlmModelListResponse;
import com.letsblog.api.repository.ProjectRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * プロジェクトごとのLLM利用モデルの一覧・選択を扱う(issue #376)。
 * 外部ホスト型LLM APIにはOllamaのようなローカルインストール/pullの概念がないため、
 * ここでは「どのモデル名を使うか」の選択のみを扱う。
 */
@Service
public class LlmModelService {

    private final ProjectRepository projectRepository;
    private final String globalDefaultModel;
    private final List<String> availableModels;

    public LlmModelService(
            ProjectRepository projectRepository,
            @Value("${app.llm-model}") String globalDefaultModel,
            @Value("${app.llm-available-models}") String availableModelsCsv) {
        this.projectRepository = projectRepository;
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

    private Project getProjectEntity(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));
    }
}
