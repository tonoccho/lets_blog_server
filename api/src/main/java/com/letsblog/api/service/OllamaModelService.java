package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.ai.OllamaClient;
import com.letsblog.api.ai.OllamaModelInfo;
import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.domain.Project;
import com.letsblog.api.dto.GenerationJobResponse;
import com.letsblog.api.dto.OllamaModelListResponse;
import com.letsblog.api.repository.GenerationJobRepository;
import com.letsblog.api.repository.ProjectRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * プロジェクトごとのOllama利用モデルの一覧・選択・インストール・削除を扱う。
 * インストール済みモデル自体はOllamaサーバー全体で共有され、プロジェクトは「どのモデルを使うか」のみを選択する。
 */
@Service
public class OllamaModelService {

    private final OllamaClient ollamaClient;
    private final ModelInstallJobRunner modelInstallJobRunner;
    private final ProjectRepository projectRepository;
    private final GenerationJobRepository generationJobRepository;
    private final ObjectMapper objectMapper;
    private final String globalDefaultModel;

    public OllamaModelService(
            OllamaClient ollamaClient,
            ModelInstallJobRunner modelInstallJobRunner,
            ProjectRepository projectRepository,
            GenerationJobRepository generationJobRepository,
            ObjectMapper objectMapper,
            @Value("${app.ollama-model}") String globalDefaultModel) {
        this.ollamaClient = ollamaClient;
        this.modelInstallJobRunner = modelInstallJobRunner;
        this.projectRepository = projectRepository;
        this.generationJobRepository = generationJobRepository;
        this.objectMapper = objectMapper;
        this.globalDefaultModel = globalDefaultModel;
    }

    public OllamaModelListResponse listModelsForProject(Long projectId) {
        List<OllamaModelInfo> models = ollamaClient.listModels();
        return new OllamaModelListResponse(models, getSelectedModel(projectId));
    }

    /**
     * プロジェクトの選択中モデルを返す。未選択(null)ならグローバルデフォルトにフォールバックする。
     */
    public String getSelectedModel(Long projectId) {
        Project project = getProjectEntity(projectId);
        String selected = project.getOllamaModel();
        return selected == null || selected.isBlank() ? globalDefaultModel : selected;
    }

    public OllamaModelListResponse selectModel(Long projectId, String modelName) {
        Project project = getProjectEntity(projectId);
        boolean exists = ollamaClient.listModels().stream().anyMatch(m -> m.name().equals(modelName));
        if (!exists) {
            throw new IllegalArgumentException("モデル '" + modelName + "' はインストールされていません");
        }
        project.setOllamaModel(modelName);
        projectRepository.save(project);
        return listModelsForProject(projectId);
    }

    public GenerationJobResponse startPull(String modelName) {
        GenerationJob job = startJob("ollama_pull", Map.of("modelName", modelName));
        modelInstallJobRunner.runOllamaPull(job.getId(), modelName);
        return toResponse(job);
    }

    public GenerationJobResponse startDelete(String modelName) {
        GenerationJob job = startJob("ollama_delete", Map.of("modelName", modelName));
        modelInstallJobRunner.runOllamaDelete(job.getId(), modelName);
        return toResponse(job);
    }

    private Project getProjectEntity(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));
    }

    private GenerationJob startJob(String type, Map<String, String> requestPayload) {
        GenerationJob job = new GenerationJob();
        job.setType(type);
        job.setStatus("running");
        job.setRequestPayload(toJson(requestPayload));
        return generationJobRepository.save(job);
    }

    private GenerationJobResponse toResponse(GenerationJob job) {
        return new GenerationJobResponse(job.getId(), job.getType(), job.getStatus(), job.getCreatedAt(), job.getUpdatedAt());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
