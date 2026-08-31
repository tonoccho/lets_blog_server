package com.letsblog.media.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.letsblog.media.ai.ComfyUiClient;
import com.letsblog.media.client.GenerationJobClient;
import com.letsblog.media.client.GenerationJobSummary;
import com.letsblog.media.client.ProjectServiceClient;
import com.letsblog.media.dto.ComfyUiCheckpointListResponse;
import com.letsblog.media.dto.GenerationJobResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;

/**
 * プロジェクトごとのComfyUI利用チェックポイントの一覧・選択・インストール・削除を扱う。
 * 実チェックポイントファイルはComfyUIサーバー全体で共有され、プロジェクトは
 * 「どのチェックポイントを使うか」のみを選択する。
 *
 * <p>issue #583でlegacy-apiから移設した。移設前は legacy-api がジョブを作ってから
 * {@code POST /api/comfyui/checkpoints/install} でmedia-serviceへHTTPで依頼していたが、
 * 移設によって<b>同じサービス内の直接呼び出し</b>({@link ModelInstallJobRunner})になり、
 * 内部エンドポイント2本とその呼び出しクライアントが不要になった。
 *
 * <p>{@code generation_jobs}の所有権はai-service(#574)なので、ジョブの作成は
 * {@link GenerationJobClient}経由で委譲する。プロジェクトの存在確認は
 * {@link ProjectServiceClient}経由でproject-serviceへ問い合わせる。
 */
@Service
public class ComfyUiModelService {

    private final ComfyUiClient comfyUiClient;
    private final ProjectServiceClient projectServiceClient;
    private final ProjectImageSettingsService projectImageSettingsService;
    private final GenerationJobClient generationJobClient;
    private final ModelInstallJobRunner modelInstallJobRunner;
    private final ObjectMapper objectMapper;
    private final HttpServletRequest request;
    private final String globalDefaultCheckpoint;

    public ComfyUiModelService(
            ComfyUiClient comfyUiClient,
            ProjectServiceClient projectServiceClient,
            ProjectImageSettingsService projectImageSettingsService,
            GenerationJobClient generationJobClient,
            ModelInstallJobRunner modelInstallJobRunner,
            ObjectMapper objectMapper,
            HttpServletRequest request,
            @Value("${app.comfyui-checkpoint}") String globalDefaultCheckpoint) {
        this.comfyUiClient = comfyUiClient;
        this.projectServiceClient = projectServiceClient;
        this.projectImageSettingsService = projectImageSettingsService;
        this.generationJobClient = generationJobClient;
        this.modelInstallJobRunner = modelInstallJobRunner;
        this.objectMapper = objectMapper;
        this.request = request;
        this.globalDefaultCheckpoint = globalDefaultCheckpoint;
    }

    public ComfyUiCheckpointListResponse listCheckpointsForProject(Long projectId) {
        return new ComfyUiCheckpointListResponse(comfyUiClient.listCheckpoints(), getSelectedCheckpoint(projectId));
    }

    /** プロジェクトの選択中チェックポイント。未選択(null)ならグローバルデフォルトへフォールバックする。 */
    public String getSelectedCheckpoint(Long projectId) {
        projectServiceClient.requireProjectExists(projectId);
        String selected = projectImageSettingsService.getComfyuiCheckpoint(projectId);
        return selected == null || selected.isBlank() ? globalDefaultCheckpoint : selected;
    }

    /**
     * プロジェクト未指定(projectId=null)呼び出しを許容する版。{@code /api/ai/image-options}等、
     * VSCode拡張がプロジェクト未選択のまま呼び出す可能性があるエンドポイント向け。
     */
    public String getSelectedCheckpointOrGlobalDefault(Long projectId) {
        return projectId == null ? globalDefaultCheckpoint : getSelectedCheckpoint(projectId);
    }

    public ComfyUiCheckpointListResponse selectCheckpoint(Long projectId, String checkpointName) {
        projectServiceClient.requireProjectExists(projectId);
        boolean exists = comfyUiClient.listCheckpoints().contains(checkpointName);
        if (!exists) {
            throw new IllegalArgumentException("チェックポイント '" + checkpointName + "' は見つかりません");
        }
        projectImageSettingsService.setComfyuiCheckpoint(projectId, checkpointName);
        return listCheckpointsForProject(projectId);
    }

    public GenerationJobResponse startInstall(String downloadUrl, String fileName) {
        requireHttpUrl(downloadUrl);
        GenerationJobSummary job =
                startJob("comfyui_checkpoint_download", Map.of("url", downloadUrl, "fileName", fileName));
        modelInstallJobRunner.runComfyUiDownload(job.id(), downloadUrl, fileName, bearerToken());
        return toResponse(job);
    }

    public GenerationJobResponse startDelete(String fileName) {
        GenerationJobSummary job = startJob("comfyui_checkpoint_delete", Map.of("fileName", fileName));
        modelInstallJobRunner.runComfyUiDelete(job.id(), fileName, bearerToken());
        return toResponse(job);
    }

    private void requireHttpUrl(String url) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("downloadUrlはhttp(s)のURLを指定してください");
        }
    }

    private String bearerToken() {
        return request.getHeader(HttpHeaders.AUTHORIZATION);
    }

    private GenerationJobSummary startJob(String type, Map<String, String> requestPayload) {
        return generationJobClient.create(type, toJson(requestPayload), bearerToken());
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
