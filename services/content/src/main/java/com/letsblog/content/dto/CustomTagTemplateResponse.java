package com.letsblog.content.dto;

import com.letsblog.content.domain.CustomTagTemplate;

import java.time.Instant;

public record CustomTagTemplateResponse(
        Long id,
        String templateName,
        String description,
        String category,
        String htmlTemplate,
        String cssContent,
        Integer version,
        Boolean isPublished,
        Long originalTagId,
        Long projectId,
        Long createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public static CustomTagTemplateResponse from(CustomTagTemplate template) {
        return new CustomTagTemplateResponse(
                template.getId(),
                template.getTemplateName(),
                template.getDescription(),
                template.getCategory(),
                template.getHtmlTemplate(),
                template.getCssContent(),
                template.getVersion(),
                template.getIsPublished(),
                template.getOriginalTagId(),
                template.getProjectId(),
                template.getCreatedBy(),
                UtcDateTimes.toInstant(template.getCreatedAt()),
                UtcDateTimes.toInstant(template.getUpdatedAt())
        );
    }
}
