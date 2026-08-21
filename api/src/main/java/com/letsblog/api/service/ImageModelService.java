package com.letsblog.api.service;

import com.letsblog.api.ai.ImageProvider;
import com.letsblog.api.domain.Project;
import com.letsblog.api.dto.ImageProviderListResponse;
import com.letsblog.api.repository.ProjectRepository;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * プロジェクトごとの画像生成AI(ComfyUI/ChatGPT)の一覧・選択を扱う(issue #531)。
 * LlmModelServiceと異なり画像生成にはシステム全体の既定プロバイダー設定がないため、
 * 未選択(null)時はCOMFYUIにフォールバックする(既存の動作を維持するため)。
 */
@Service
public class ImageModelService {

    private final ProjectRepository projectRepository;

    public ImageModelService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    /**
     * プロジェクトの画像生成AI設定一覧を返す。selectedは未上書き時null(「ComfyUIを使用」)をそのまま返す。
     * Web/拡張のUIが空選択肢を表現できるようにするため。
     */
    public ImageProviderListResponse listProvidersForProject(Long projectId) {
        Project project = getProjectEntity(projectId);
        List<String> availableProviders = Arrays.stream(ImageProvider.values()).map(Enum::name).toList();
        return new ImageProviderListResponse(availableProviders, project.getImageProvider());
    }

    /**
     * プロジェクトの選択中画像生成AIを返す。未選択(null/空)またはprojectId未指定(VSCode拡張がプロジェクト
     * 未選択のまま呼び出す場合)はCOMFYUIにフォールバックする(既存の動作を維持するため)。
     */
    public ImageProvider getSelectedProvider(Long projectId) {
        if (projectId == null) {
            return ImageProvider.COMFYUI;
        }
        Project project = getProjectEntity(projectId);
        ImageProvider override = ImageProvider.fromString(project.getImageProvider());
        return override != null ? override : ImageProvider.COMFYUI;
    }

    /** providerが空/nullの場合はプロジェクト単位の上書きを解除する(ComfyUIへ戻す)。 */
    public ImageProviderListResponse selectProvider(Long projectId, String provider) {
        ImageProvider parsed = ImageProvider.fromString(provider);
        Project project = getProjectEntity(projectId);
        project.setImageProvider(parsed != null ? parsed.name() : null);
        projectRepository.save(project);
        return listProvidersForProject(projectId);
    }

    private Project getProjectEntity(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException("id " + projectId + " のプロジェクトは登録されていません"));
    }
}
