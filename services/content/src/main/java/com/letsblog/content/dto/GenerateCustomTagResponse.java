package com.letsblog.content.dto;

import com.letsblog.content.domain.CustomTag;

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
