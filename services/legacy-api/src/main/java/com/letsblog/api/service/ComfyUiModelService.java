package com.letsblog.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.api.ai.ComfyUiClient;
import com.letsblog.api.ai.MediaComfyUiClient;
import com.letsblog.api.client.GenerationJobClient;
import com.letsblog.api.client.GenerationJobSummary;
import com.letsblog.api.client.ProjectServiceClient;
import com.letsblog.api.dto.ComfyUiCheckpointListResponse;
import com.letsblog.api.dto.GenerationJobResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Map;

/**
 * プロジェクトごとのComfyUI利用チェックポイントの一覧・選択・インストール・削除を扱う。
 * 実チェックポイントファイルはComfyUIサーバー全体で共有され、プロジェクトは「どのチェックポイントを使うか」のみを選択する。
 * (Phase14時点では選択状態の保持のみで、実際の画像生成呼び出しへの反映は将来の拡張ポイント)
 * データはprojects god-tableの分割(issue #571)によりproject_image_settings(ProjectImageSettingsService)が保持する。
 *
 * <p>issue #574でgeneration_jobsテーブルの所有権がai-serviceへ移管されたため、ジョブの作成は
 * {@link GenerationJobClient}経由でai-serviceへ委譲する(以前はGenerationJobRepositoryで直接
 * 書き込んでいた)。プロジェクトの存在確認はproject-serviceへ内部ブリッジ({@link ProjectServiceClient})
 * 経由で行う(issue #577 stage3。legacy-apiローカルの{@code ProjectRepository}への直接アクセスを廃止した)。
 */
@Service
public class ComfyUiModelService {

    private final ComfyUiClient comfyUiClient;
    private final MediaComfyUiClient mediaComfyUiClient;
    private final ProjectServiceClient projectServiceClient;
    private final ProjectImageSettingsService projectImageSettingsService;
    private final GenerationJobClient generationJobClient;
    private final ObjectMapper objectMapper;
    private final String globalDefaultCheckpoint;

    public ComfyUiModelService(
            ComfyUiClient comfyUiClient,
            MediaComfyUiClient mediaComfyUiClient,
            ProjectServiceClient projectServiceClient,
            ProjectImageSettingsService projectImageSettingsService,
            GenerationJobClient generationJobClient,
            ObjectMapper objectMapper,
            @Value("${app.comfyui-checkpoint}") String globalDefaultCheckpoint) {
        this.comfyUiClient = comfyUiClient;
        this.mediaComfyUiClient = mediaComfyUiClient;
        this.projectServiceClient = projectServiceClient;
        this.projectImageSettingsService = projectImageSettingsService;
        this.generationJobClient = generationJobClient;
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
        requireProjectExists(projectId);
        String selected = projectImageSettingsService.getComfyuiCheckpoint(projectId);
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
        requireProjectExists(projectId);
        boolean exists = comfyUiClient.listCheckpoints().contains(checkpointName);
        if (!exists) {
            throw new IllegalArgumentException("チェックポイント '" + checkpointName + "' は見つかりません");
        }
        projectImageSettingsService.setComfyuiCheckpoint(projectId, checkpointName);
        return listCheckpointsForProject(projectId);
    }

    public GenerationJobResponse startInstall(String downloadUrl, String fileName) {
        requireHttpUrl(downloadUrl);
        GenerationJobSummary job = startJob("comfyui_checkpoint_download", Map.of("url", downloadUrl, "fileName", fileName));
        mediaComfyUiClient.startInstall(job.id(), downloadUrl, fileName);
        return toResponse(job);
    }

    public GenerationJobResponse startDelete(String fileName) {
        GenerationJobSummary job = startJob("comfyui_checkpoint_delete", Map.of("fileName", fileName));
        mediaComfyUiClient.startDelete(job.id(), fileName);
        return toResponse(job);
    }

    private void requireHttpUrl(String url) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("downloadUrlはhttp(s)のURLを指定してください");
        }
    }

    private void requireProjectExists(Long projectId) {
        projectServiceClient.getProject(projectId);
    }

    private GenerationJobSummary startJob(String type, Map<String, String> requestPayload) {
        return generationJobClient.create(type, toJson(requestPayload));
    }

    private GenerationJobResponse toResponse(GenerationJobSummary job) {
        return new GenerationJobResponse(job.id(), job.type(), job.status(), job.createdAt(), job.updatedAt());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
