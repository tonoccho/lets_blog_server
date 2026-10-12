package com.letsblog.media.service;

import com.letsblog.media.ai.ComfyUiClient;
import com.letsblog.media.client.ProjectServiceClient;
import com.letsblog.media.dto.ComfyUiCheckpointListResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * プロジェクトごとのComfyUI利用チェックポイントの一覧と選択を扱う。
 * 実チェックポイントファイルはComfyUIサーバー(GPU PCなど外部)にあり、プロジェクトは
 * 「どのチェックポイントを使うか」のみを選択する。チェックポイントの導入・削除は
 * ComfyUIを動かしているPC側で行い、ここには無い(issue #1746)。
 *
 * <p>issue #583でlegacy-apiから移設した。プロジェクトの存在確認は
 * {@link ProjectServiceClient}経由でproject-serviceへ問い合わせる。
 */
@Service
public class ComfyUiModelService {

    private final ComfyUiClient comfyUiClient;
    private final ProjectServiceClient projectServiceClient;
    private final ProjectImageSettingsService projectImageSettingsService;
    private final String globalDefaultCheckpoint;

    public ComfyUiModelService(
            ComfyUiClient comfyUiClient,
            ProjectServiceClient projectServiceClient,
            ProjectImageSettingsService projectImageSettingsService,
            @Value("${app.comfyui-checkpoint}") String globalDefaultCheckpoint) {
        this.comfyUiClient = comfyUiClient;
        this.projectServiceClient = projectServiceClient;
        this.projectImageSettingsService = projectImageSettingsService;
        this.globalDefaultCheckpoint = globalDefaultCheckpoint;
    }

    public ComfyUiCheckpointListResponse listCheckpointsForProject(Long projectId) {
        return new ComfyUiCheckpointListResponse(comfyUiClient.listCheckpoints(projectId), getSelectedCheckpoint(projectId));
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
        boolean exists = comfyUiClient.listCheckpoints(projectId).contains(checkpointName);
        if (!exists) {
            throw new IllegalArgumentException("チェックポイント '" + checkpointName + "' は見つかりません");
        }
        projectImageSettingsService.setComfyuiCheckpoint(projectId, checkpointName);
        return listCheckpointsForProject(projectId);
    }
}
