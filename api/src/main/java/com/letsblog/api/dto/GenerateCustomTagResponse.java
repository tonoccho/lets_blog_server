package com.letsblog.api.dto;

import com.letsblog.api.domain.CustomTag;

public record GenerateCustomTagResponse(
    Long id,
    String tagName,
    String htmlTemplate,
    String cssContent,
    String description,
    Long projectId
) {
    public static GenerateCustomTagResponse from(CustomTag customTag) {
        return new GenerateCustomTagResponse(
            customTag.getId(),
            customTag.getTagName(),
            customTag.getHtmlTemplate(),
            customTag.getCssContent(),
            customTag.getDescription(),
            customTag.getProjectId()
        );
    }
}
