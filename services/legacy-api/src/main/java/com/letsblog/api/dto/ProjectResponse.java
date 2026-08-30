package com.letsblog.api.dto;

import com.letsblog.api.domain.Project;
import com.letsblog.api.domain.ProjectImageSettings;

import java.time.LocalDateTime;

/**
 * projectsの縮小と、project_image_settings(media)/project_content_settings(content)への
 * 設定分割後も、Webの画面には従来どおり1つのレスポンスとしてまとめて返す(issue #571)。
 * project_content_settingsの所有権はcontent-serviceへ移った(issue #576)ため、cssSelectorPrefixは
 * ProjectContentSettingsエンティティではなく、内部ブリッジ(ContentServiceClient)経由で取得した
 * 文字列をそのまま受け取る。
 */
public record ProjectResponse(
        Long id,
        String name,
        String slug,
        SiteResponse localSite,
        SiteResponse testSite,
        SiteResponse productionSite,
        String masterEnvironment,
        String githubRepository,
        String cssSelectorPrefix,
        String defaultNegativePrompt,
        String defaultQualityPrompt,
        Integer defaultGeneratedImageWidth,
        Integer defaultGeneratedImageHeight,
        Integer defaultArticleImageLongEdgePx,
        Boolean blockSexualContent,
        Boolean blockViolentContent,
        Boolean blockDiscriminatoryContent,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ProjectResponse from(
            Project project, SiteResponse localSite, SiteResponse testSite, SiteResponse productionSite,
            ProjectImageSettings imageSettings, String cssSelectorPrefix) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getSlug(),
                localSite,
                testSite,
                productionSite,
                project.getMasterEnvironment(),
                project.getGithubRepository(),
                cssSelectorPrefix,
                imageSettings == null ? null : imageSettings.getDefaultNegativePrompt(),
                imageSettings == null ? null : imageSettings.getDefaultQualityPrompt(),
                imageSettings == null ? null : imageSettings.getDefaultGeneratedImageWidth(),
                imageSettings == null ? null : imageSettings.getDefaultGeneratedImageHeight(),
                imageSettings == null ? null : imageSettings.getDefaultArticleImageLongEdgePx(),
                imageSettings == null ? null : imageSettings.getBlockSexualContent(),
                imageSettings == null ? null : imageSettings.getBlockViolentContent(),
                imageSettings == null ? null : imageSettings.getBlockDiscriminatoryContent(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
