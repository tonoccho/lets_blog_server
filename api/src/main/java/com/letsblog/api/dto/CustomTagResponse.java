package com.letsblog.api.dto;

import com.letsblog.api.domain.CustomTag;

import java.time.LocalDateTime;

public record CustomTagResponse(
        Long id,
        String tagName,
        String htmlTemplate,
        String description,
        String cssContent,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static CustomTagResponse from(CustomTag tag) {
        return new CustomTagResponse(
                tag.getId(),
                tag.getTagName(),
                tag.getHtmlTemplate(),
                tag.getDescription(),
                tag.getCssContent(),
                tag.getCreatedAt(),
                tag.getUpdatedAt());
    }
}
