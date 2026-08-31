package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.ai.ComfyUiClient;
import com.letsblog.api.domain.GenerationJob;
import com.letsblog.api.domain.Project;
import com.letsblog.api.dto.ComfyUiCheckpointListResponse;
import com.letsblog.api.dto.GenerationJobResponse;
import com.letsblog.api.repository.GenerationJobRepository;
import com.letsblog.api.repository.ProjectRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Map;

/**
 * プロジェクトごとのComfyUI利用チェックポイントの一覧・選択・インストール・削除を扱う。
 * 実チェックポイントファイルはComfyUIサーバー全体で共有され、プロジェクトは「どのチェックポイントを使うか」のみを選択する。
 * (Phase14時点では選択状態の保持のみで、実際の画像生成呼び出しへの反映は将来の拡張ポイント)
 */
@Service
public class ComfyUiModelService {

    private final ComfyUiClient comfyUiClient;
    private final ModelInstallJobRunner modelInstallJobRunner;
    private final ProjectRepository projectRepository;
    private final GenerationJobRepository generationJobRepository;
    private final ObjectMapper objectMapper;
    private final String globalDefaultCheckpoint;

    public ComfyUiModelService(
            ComfyUiClient comfyUiClient,
            ModelInstallJobRunner modelInstallJobRunner,
            ProjectRepository projectRepository,
            GenerationJobRepository generationJobRepository,
            ObjectMapper objectMapper,
            @Value("${app.comfyui-checkpoint}") String globalDefaultCheckpoint) {
        this.comfyUiClient = comfyUiClient;
        this.modelInstallJobRunner = modelInstallJobRunner;
        this.projectRepository = projectRepository;
        this.generationJobRepository = generationJobRepository;
        this.objectMapper = objectMapper;
        this.globalDefaultCheckpoint = globalDefaultCheckpoint;
    }

    public ComfyUiCheckpointListResponse listCheckpointsForProject(Long projectId) {
        return new ComfyUiCheckpointListResponse(comfyUiClient.listCheckpoints(), getSelectedCheckpoint(projectId));
    }

    /**
     * プロジェクトの選択中チェックポイントを返す。未選択(null)ならグローバルデフォルトにフォールバックする。
     */
    public String getSelectedCheckpoint(Long projectId) {
        Project project = getProjectEntity(projectId);
        String selected = project.getComfyuiCheckpoint();
        return selected == null || selected.isBlank() ? globalDefaultCheckpoint : selected;
    }

    /**
     * プロジェクト未指定(projectId=null)呼び出しを許容する版。/api/ai/image-options等、
     * VSCode拡張がプロジェクト未選択のまま呼び出す可能性があるエンドポイント向け。
     */
    public String getSelectedCheckpointOrGlobalDefault(Long projectId) {
        return projectId == null ? globalDefaultCheckpoint : getSelectedCheckpoint(projectId);
    }

    public ComfyUiCheckpointListResponse selectCheckpoint(Long projectId, String checkpointName) {
        Project project = getProjectEntity(projectId);
        boolean exists = comfyUiClient.listCheckpoints().contains(checkpointName);
        if (!exists) {
            throw new IllegalArgumentException("チェックポイント '" + checkpointName + "' は見つかりません");
        }
        project.setComfyuiCheckpoint(checkpointName);
        projectRepository.save(project);
        return listCheckpointsForProject(projectId);
    }

    public GenerationJobResponse startInstall(String downloadUrl, String fileName) {
        requireHttpUrl(downloadUrl);
        GenerationJob job = startJob("comfyui_checkpoint_download", Map.of("url", downloadUrl, "fileName", fileName));
        modelInstallJobRunner.runComfyUiDownload(job.getId(), downloadUrl, fileName);
        return toResponse(job);
    }

    public GenerationJobResponse startDelete(String fileName) {
        GenerationJob job = startJob("comfyui_checkpoint_delete", Map.of("fileName", fileName));
        modelInstallJobRunner.runComfyUiDelete(job.getId(), fileName);
        return toResponse(job);
    }

    private void requireHttpUrl(String url) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("downloadUrlはhttp(s)のURLを指定してください");
        }
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
