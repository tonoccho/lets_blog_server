package com.letsblog.api.dto;

import com.letsblog.api.domain.Project;

import java.time.LocalDateTime;

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
                project.getCssSelectorPrefix(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
