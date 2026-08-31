package com.letsblog.media.service;

import com.letsblog.media.ai.ImageProvider;
import com.letsblog.media.client.ProjectServiceClient;
import com.letsblog.media.dto.ImageProviderListResponse;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * プロジェクトごとの画像生成AI(ComfyUI/ChatGPT)の一覧・選択を扱う(issue #531)。
 * LlmModelServiceと異なり画像生成にはシステム全体の既定プロバイダー設定がないため、
 * 未選択(null)時はCOMFYUIにフォールバックする(既存の動作を維持するため)。
 *
 * <p>issue #583でlegacy-apiから移設した。データは{@code project_image_settings}
 * ({@link ProjectImageSettingsService}、所有権は{@code lbs_media})が保持する。
 * プロジェクトの存在確認は{@link ProjectServiceClient}経由でproject-serviceへ問い合わせる。
 */
@Service
public class ImageModelService {

    private final ProjectServiceClient projectServiceClient;
    private final ProjectImageSettingsService projectImageSettingsService;

    public ImageModelService(
            ProjectServiceClient projectServiceClient, ProjectImageSettingsService projectImageSettingsService) {
        this.projectServiceClient = projectServiceClient;
        this.projectImageSettingsService = projectImageSettingsService;
    }

    /**
     * プロジェクトの画像生成AI設定一覧を返す。selectedは未上書き時null(「ComfyUIを使用」)を
     * そのまま返す。Web/拡張のUIが空選択肢を表現できるようにするため。
     */
    public ImageProviderListResponse listProvidersForProject(Long projectId) {
        projectServiceClient.requireProjectExists(projectId);
        List<String> availableProviders = Arrays.stream(ImageProvider.values()).map(Enum::name).toList();
        return new ImageProviderListResponse(
                availableProviders, projectImageSettingsService.getImageProvider(projectId));
    }

    /**
     * プロジェクトの選択中画像生成AIを返す。未選択(null/空)またはprojectId未指定
     * (VSCode拡張がプロジェクト未選択のまま呼び出す場合)はCOMFYUIにフォールバックする。
     */
    public ImageProvider getSelectedProvider(Long projectId) {
        if (projectId == null) {
            return ImageProvider.COMFYUI;
        }
        projectServiceClient.requireProjectExists(projectId);
        ImageProvider override = ImageProvider.fromString(projectImageSettingsService.getImageProvider(projectId));
        return override != null ? override : ImageProvider.COMFYUI;
    }

    /** providerが空/nullの場合はプロジェクト単位の上書きを解除する(ComfyUIへ戻す)。 */
    public ImageProviderListResponse selectProvider(Long projectId, String provider) {
        ImageProvider parsed = ImageProvider.fromString(provider);
        projectServiceClient.requireProjectExists(projectId);
        projectImageSettingsService.setImageProvider(projectId, parsed != null ? parsed.name() : null);
        return listProvidersForProject(projectId);
    }
}
