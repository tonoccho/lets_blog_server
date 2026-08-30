package com.letsblog.project.dto;

import com.letsblog.project.domain.Project;
import java.time.LocalDateTime;

/**
 * issue #577 stage2。project-serviceのProjectResponseは、他サービスが所有する設定
 * (project_ai_settings/project_image_settings/analytics_credentials/project_content_settings、
 * issue #571のC2で分割済み)を含まない、project-service自身が所有するフィールドのみを返す。
 * legacy-api版のProjectResponse(imageSettings/cssSelectorPrefixを内部ブリッジ経由でまとめて返す)とは
 * 異なり、それらはWeb側が各サービスから個別に取得する想定(#577 PR説明の既知の制限を参照)。
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
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ProjectResponse from(
            Project project, SiteResponse localSite, SiteResponse testSite, SiteResponse productionSite) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getSlug(),
                localSite,
                testSite,
                productionSite,
                project.getMasterEnvironment(),
                project.getGithubRepository(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
